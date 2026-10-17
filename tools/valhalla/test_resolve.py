"""Tests the segment join without needing Valhalla running.

The join is the part of Phase 4.2 most likely to be quietly wrong, because
being wrong looks like "a pickup was credited to the next block along" rather
than like an error. These build synthetic matches from the real network, with
GPS-like noise, and check the resolver puts them back where they came from.

    python test_resolve.py
"""

import random
import sys

import match_track as mt


def encode6(coords):
    out, plat, plng = [], 0, 0
    for lat, lng in coords:
        ilat, ilng = int(round(lat * 1e6)), int(round(lng * 1e6))
        for delta in (ilat - plat, ilng - plng):
            v = ~(delta << 1) if delta < 0 else (delta << 1)
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plng = ilat, ilng
    return "".join(out)


def jitter(coords, metres, rng):
    """GPS noise, roughly. A degree of latitude is about 110.5 km."""
    d = metres / 110_540.0
    return [(lat + rng.uniform(-d, d), lng + rng.uniform(-d, d)) for lat, lng in coords]


def synthetic(segments, rng, noise_m):
    """One matched edge per segment, built from that segment's own geometry."""
    shape, edges = [], []
    for seg in segments:
        start = len(shape)
        shape.extend(jitter(seg["coords"], noise_m, rng))
        edges.append(
            {
                "way_id": sorted(seg["way_ids"])[0],
                "names": [seg["name"]],
                "begin_shape_index": start,
                "end_shape_index": len(shape) - 1,
            }
        )
    return {"shape": encode6(shape), "edges": edges}


def test_polyline_roundtrip(net):
    coords = net[0]["coords"]
    decoded = mt.decode_polyline6(encode6(coords))
    worst = max(mt.haversine(a, b) for a, b in zip(decoded, coords))
    assert worst < 0.5, f"polyline6 decode drifted {worst:.2f} m"
    return f"polyline6 round-trip within {worst:.3f} m"


def test_distinct_streets(net):
    """Different streets, which the way id alone already separates."""
    rng = random.Random(7)
    picked = [s for s in net if len(s["coords"]) >= 2][:12]
    rows = mt.resolve(synthetic(picked, rng, noise_m=8), net)
    hits = sum(r["segment_id"] == s["id"] for r, s in zip(rows, picked))
    assert hits == len(picked), f"only {hits}/{len(picked)} resolved"
    return f"{hits}/{len(picked)} distinct segments resolved under 8 m noise"


def test_shared_way(net):
    """The hard case: one OSM way carrying many consecutive blocks.

    A way-id-only lookup cannot do this at all, and a midpoint-only match got
    three of twelve wrong, always onto the adjacent block.
    """
    counts = {}
    for seg in net:
        for w in seg["way_ids"]:
            counts.setdefault(w, []).append(seg)
    way, sharing = max(counts.items(), key=lambda kv: len(kv[1]))
    assert len(sharing) >= 5, "expected a way spanning several segments"

    rng = random.Random(11)
    rows = mt.resolve(synthetic(sharing, rng, noise_m=6), net)
    hits = sum(r["segment_id"] == s["id"] for r, s in zip(rows, sharing))
    assert hits == len(sharing), f"only {hits}/{len(sharing)} on way {way}"
    return f"{hits}/{len(sharing)} blocks of one {len(sharing)}-segment way resolved"


def test_off_network_is_unplaced(net):
    """A track nowhere near the network must resolve to nothing.

    Attributing a pickup to whichever block happened to be nearest would put
    litter on streets nobody walked, which is worse than recording that it
    could not be placed.
    """
    far = [(29.60, -98.60), (29.601, -98.601)]
    fake = {
        "shape": encode6(far),
        "edges": [
            {"way_id": 999_999_999, "names": ["Nowhere"],
             "begin_shape_index": 0, "end_shape_index": 1}
        ],
    }
    rows = mt.resolve(fake, net)
    assert rows[0]["segment_id"] is None, "a far-off track was placed on a segment"
    return f"a track {rows[0]['metres_off']:.0f} m off-network stayed unplaced"


def main():
    net = mt.load_segments()
    print(f"network: {len(net)} segments\n")

    failures = 0
    for test in (
        test_polyline_roundtrip,
        test_distinct_streets,
        test_shared_way,
        test_off_network_is_unplaced,
    ):
        try:
            print(f"  PASS  {test(net)}")
        except AssertionError as exc:
            print(f"  FAIL  {test.__name__}: {exc}")
            failures += 1

    print()
    if failures:
        sys.exit(f"{failures} failed")
    print("all good -- the join is sound before Valhalla is even running")


if __name__ == "__main__":
    main()
