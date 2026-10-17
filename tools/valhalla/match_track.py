"""Map-matches a recorded track and resolves it to TrashTalk segments.

This is the join Phase 4.2 depends on, written once here so the scoring
function can port it rather than invent it, and so it can be checked against a
running Valhalla before anybody builds on top of it.

The naive version does not work. Valhalla returns an OSM way id per matched
edge, and the plan assumed that identifies a segment. It does not: osmnx splits
ways at intersections, so our 317 segments carry only 148 distinct way ids and
67 of those ways span several segments -- one spans twelve. "Rockhill Drive" is
a single way across a dozen blocks.

So the join is two steps:

  1. The way id narrows the candidates, usually to one or two segments.
  2. The matched edge's own geometry picks between them by proximity.

Step 2 is what makes it correct. Step 1 is what makes it fast and what stops
proximity alone from snapping to a parallel street across a car park.

    python match_track.py --track track.json [--url http://localhost:8002]

`track.json` is a list of {lat, lng, at} points, which is what the walk
recorder stores.
"""

import argparse
import json
import math
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
NETWORK = REPO / "app/src/main/assets/terrell_heights.json"

EARTH_RADIUS_M = 6_371_000.0


def haversine(a, b):
    lat1, lng1 = a
    lat2, lng2 = b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def point_to_segment_m(p, a, b):
    """Metres from p to the line segment ab, in a local flat approximation."""
    lat0 = math.radians(p[0])
    mx = 111_320.0 * math.cos(lat0)
    my = 110_540.0

    px, py = p[1] * mx, p[0] * my
    ax, ay = a[1] * mx, a[0] * my
    bx, by = b[1] * mx, b[0] * my

    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def distance_to_polyline_m(p, poly):
    return min(
        point_to_segment_m(p, poly[i], poly[i + 1]) for i in range(len(poly) - 1)
    ) if len(poly) > 1 else haversine(p, poly[0])


def decode_polyline6(encoded):
    """Valhalla encodes shapes at six decimal places, not Google's five."""
    coords, index, lat, lng = [], 0, 0, 0
    while index < len(encoded):
        for first in (True, False):
            shift, result = 0, 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else (result >> 1)
            if first:
                lat += delta
            else:
                lng += delta
        coords.append((lat / 1e6, lng / 1e6))
    return coords


def load_segments():
    data = json.loads(NETWORK.read_text(encoding="utf-8"))
    segments = []
    for s in data["segments"]:
        segments.append(
            {
                "id": s["id"],
                "name": s["name"],
                "way_ids": set(s.get("osmWayIds") or []),
                "coords": [(lat, lng) for lat, lng in s["coords"]],
            }
        )
    return segments


def trace_attributes(url, track):
    body = {
        "shape": [{"lat": p["lat"], "lon": p["lng"]} for p in track],
        "costing": "pedestrian",
        # map_snap, not edge_walk: a recorded walk has GPS noise and will not
        # lie exactly on the graph. edge_walk assumes it already does.
        "shape_match": "map_snap",
        "filters": {
            "attributes": [
                "edge.way_id",
                "edge.names",
                "edge.begin_shape_index",
                "edge.end_shape_index",
                "shape",
            ],
            "action": "include",
        },
    }
    req = urllib.request.Request(
        f"{url}/trace_attributes",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def resolve(matched, segments):
    """Maps each matched edge to a TrashTalk segment, or to None."""
    shape = decode_polyline6(matched.get("shape", ""))
    by_way = {}
    for seg in segments:
        for w in seg["way_ids"]:
            by_way.setdefault(w, []).append(seg)

    results = []
    for edge in matched.get("edges", []):
        way_id = edge.get("way_id")
        lo = edge.get("begin_shape_index", 0)
        hi = edge.get("end_shape_index", lo)
        piece = shape[lo : hi + 1] or shape[lo : lo + 1]
        if not piece:
            continue

        # Step 1: the way id narrows it. Fall back to the whole network only
        # when the way is one we do not carry, which happens at the edges of
        # the pilot area.
        candidates = by_way.get(way_id) or segments

        # Step 2: geometry decides, scored across the whole matched piece
        # rather than at its midpoint.
        #
        # A midpoint is one sample and it is not enough. Consecutive blocks of
        # the same street are collinear and share an endpoint, so a midpoint
        # that drifts a few metres lands just as near the neighbouring block --
        # tested against the twelve blocks of Haskin Drive, a midpoint picked
        # the wrong one three times. Averaging over the piece uses the length
        # of the match, which is exactly the information that distinguishes
        # one block from the next.
        def score(seg):
            return sum(distance_to_polyline_m(pt, seg["coords"]) for pt in piece) / len(piece)

        best = min(candidates, key=score)
        dist = score(best)

        results.append(
            {
                "way_id": way_id,
                "name": (edge.get("names") or [None])[0],
                "segment_id": best["id"] if dist <= 40 else None,
                "segment_name": best["name"] if dist <= 40 else None,
                "metres_off": round(dist, 1),
                "narrowed_by_way_id": way_id in by_way,
                "candidates": len(candidates),
            }
        )
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8002")
    ap.add_argument("--track", required=True, help="JSON list of {lat,lng,at}")
    args = ap.parse_args()

    track = json.loads(Path(args.track).read_text(encoding="utf-8"))
    if len(track) < 2:
        sys.exit("a track needs at least two points")

    segments = load_segments()
    print(f"network: {len(segments)} segments")
    print(f"track:   {len(track)} points")

    try:
        matched = trace_attributes(args.url, track)
    except Exception as exc:  # noqa: BLE001 - the message is the whole point
        sys.exit(
            f"\nValhalla at {args.url} did not answer: {exc}\n"
            "Start it with:  docker compose up -d valhalla"
        )

    rows = resolve(matched, segments)
    if not rows:
        sys.exit("Valhalla matched nothing. Is the track inside the built bbox?")

    placed = [r for r in rows if r["segment_id"]]
    narrowed = [r for r in rows if r["narrowed_by_way_id"]]

    print(f"\nmatched edges:        {len(rows)}")
    print(f"  resolved to segment:{len(placed):4d}  ({100*len(placed)/len(rows):.0f}%)")
    print(f"  way id was known:   {len(narrowed):4d}  ({100*len(narrowed)/len(rows):.0f}%)")
    if placed:
        worst = max(placed, key=lambda r: r["metres_off"])
        print(f"  worst offset:       {worst['metres_off']} m on {worst['segment_name']}")

    print("\nedge -> segment")
    for r in rows[:25]:
        flag = "" if r["segment_id"] else "   UNPLACED"
        print(
            f"  way {r['way_id']:<12} {str(r['name'])[:22]:<22} "
            f"-> {str(r['segment_name'])[:26]:<26} "
            f"{r['metres_off']:>6} m  (from {r['candidates']} candidates){flag}"
        )


if __name__ == "__main__":
    main()
