#!/usr/bin/env python3
"""Build the TrashTalk survey network from OpenStreetMap.

TrashTalk treats a neighbourhood the way a bike-share system treats its dock
graph: the unit of data is not a point but a *segment* of street between two
intersections. A segment can be surveyed, adopted, cleaned and scored, which
makes the map a field of edges rather than a scatter of pins.

Phase 0 replaced the synthesised grid with real geometry. Edges come from OSM
via osmnx, simplified so each runs intersection to intersection, and every
segment carries its OSM way ids and both end nodes. That last part is the point
of the exercise: map matching returns way ids, so this is the join between a
recorded walk and the segment it belongs to.

By default nothing on this network is observed. Every segment comes out
unsurveyed -- no litter index, no history, no composition -- because a
simulated index is indistinguishable on screen from a measured one, and a map
that colours a block nobody has walked is lying to the person reading it.

--synthetic-litter brings back the fitted model for demos and screenshots. It
is a model, not an observation, and anything published from it has to say so.

Usage:
    python tools/generate_street_network.py
    python tools/generate_street_network.py --synthetic-litter   # demos only
"""

import hashlib
import json
import math
import argparse
import random
from pathlib import Path

import osmnx as ox

SEED = 20250214
DAYS = 14

# --------------------------------------------------------------- study area
#
# "Terrell Heights" has no boundary relation in OSM -- Nominatim returns no
# result for it, and "Terrell Hills" is a different, incorporated city to the
# south-east. So the pilot area is pinned to an explicit bounding box, which
# also keeps every run byte-identical rather than drifting with OSM's
# administrative edits.
NEIGHBORHOOD = "Terrell Heights"
CITY, STATE, CITY_ID = "San Antonio", "TX", "san-antonio-tx"

# (west, south, east, north)
#
# Covers Alamo Heights as well as Terrell Heights. The original box was the
# 2 km pilot and nothing else, so the app opened on a map that was empty the
# moment you stood one street outside it.
#
# Widening is safe for data already collected: segment_id hashes the OSM node
# pair at each end of a block, not anything about this box, so a block keeps
# its id and its history when the area around it grows.
BBOX = (-98.4880, 29.4650, -98.4460, 29.5100)

# Exactly the classes a volunteer can walk and survey. Anchored with ^...$
# because osmnx passes this to Overpass as a regex -- an unanchored
# "secondary" also matches "secondary_link", pulling in ramps.
HIGHWAY_CLASSES = (
    "residential",
    "tertiary",
    "secondary",
    "primary",
    "unclassified",
    "living_street",
)
CUSTOM_FILTER = '["highway"~"^({})$"]'.format("|".join(HIGHWAY_CLASSES))

# OSM highway tag -> the app's StreetClass. Drives render weight and the
# baseline litter load an arterial carries over a cul-de-sac.
STREET_CLASS = {
    "primary": "arterial",
    "secondary": "arterial",
    "tertiary": "collector",
    "unclassified": "collector",
    "residential": "residential",
    "living_street": "residential",
}

# Trip generators. Litter does not fall uniformly; it concentrates where people
# transfer between modes and where packaged food is sold. Still hand-placed:
# pulling these from OSM is a worthwhile follow-up, but it would change the
# litter model's inputs, which is a separate change from changing its geometry.
POIS = [
    ("poi_bus_1",  "Austin Hwy @ Larchmont (bus stop)", "transit",    29.49640, -98.46180),
    ("poi_bus_2",  "Austin Hwy @ Rexford (bus stop)",   "transit",    29.49700, -98.45420),
    ("poi_bus_3",  "Nacogdoches @ Eventide (bus stop)", "transit",    29.50610, -98.45980),
    ("poi_store",  "Corner Market & Gas",               "commercial", 29.49720, -98.46120),
    ("poi_food",   "Taqueria row",                      "commercial", 29.49690, -98.45660),
    ("poi_strip",  "Terrell Plaza strip mall",          "commercial", 29.50010, -98.45300),
    ("poi_school", "Wilshire Elementary",               "school",     29.50420, -98.46280),
    ("poi_park",   "Terrell Heights Park",              "park",       29.50760, -98.45640),
    ("poi_laundry", "Coin laundry",                     "commercial", 29.50180, -98.46450),
    ("poi_church", "Neighborhood church lot",           "civic",      29.50530, -98.45120),
]

M_PER_DEG_LAT = 110574.0


def m_per_deg_lng(lat):
    return 111320.0 * math.cos(math.radians(lat))


def meters(a, b):
    """Planar distance in metres between two (lat, lng) pairs."""
    dlat = (a[0] - b[0]) * M_PER_DEG_LAT
    dlng = (a[1] - b[1]) * m_per_deg_lng((a[0] + b[0]) / 2)
    return math.hypot(dlat, dlng)


def point_to_polyline_m(pt, poly):
    best = float("inf")
    for i in range(len(poly) - 1):
        ax, ay = poly[i]
        bx, by = poly[i + 1]
        scale = m_per_deg_lng(ax)
        axm, aym = ax * M_PER_DEG_LAT, ay * scale
        bxm, bym = bx * M_PER_DEG_LAT, by * scale
        pxm, pym = pt[0] * M_PER_DEG_LAT, pt[1] * scale
        vx, vy = bxm - axm, bym - aym
        wx, wy = pxm - axm, pym - aym
        L2 = vx * vx + vy * vy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
        cx, cy = axm + t * vx, aym + t * vy
        best = min(best, math.hypot(pxm - cx, pym - cy))
    return best


def decay(dist_m, scale_m):
    """Smooth distance decay in [0,1]."""
    return math.exp(-(dist_m / scale_m) ** 2)


# ------------------------------------------------------------------- fetch

def segment_id(u, v, key):
    """A stable id from the undirected edge identity.

    Sorting the node pair makes it direction-independent, and the key
    distinguishes parallel edges between the same two intersections (divided
    roads, service loops). Hashing keeps it short and opaque, so nothing
    downstream is tempted to parse meaning out of it.
    """
    raw = "{}-{}-{}".format(min(u, v), max(u, v), key)
    return "s_" + hashlib.sha1(raw.encode()).hexdigest()[:12]


def as_list(value):
    """OSM tags are a single value or a list of values after simplification."""
    if value is None:
        return []
    return list(value) if isinstance(value, list) else [value]


def first_tag(value, default=None):
    items = as_list(value)
    return items[0] if items else default


def fetch_segments():
    """Pull the walkable street network and flatten it to segments."""
    ox.settings.requests_timeout = 180
    ox.settings.use_cache = True

    graph = ox.graph_from_bbox(
        bbox=BBOX,
        custom_filter=CUSTOM_FILTER,
        simplify=True,          # edges run intersection to intersection
        retain_all=False,       # drop disconnected fragments
        truncate_by_edge=True,  # keep edges that cross the bbox edge
    )
    # A street is the same street in both directions: surveying the north side
    # is surveying the block, and keeping both directions would score every
    # one-way pair twice.
    graph = ox.convert.to_undirected(graph)

    nodes = graph.nodes
    segments = []
    for u, v, key, data in graph.edges(keys=True, data=True):
        geom = data.get("geometry")
        if geom is not None:
            coords = [(lat, lng) for lng, lat in geom.coords]
        else:
            coords = [
                (nodes[u]["y"], nodes[u]["x"]),
                (nodes[v]["y"], nodes[v]["x"]),
            ]
        if len(coords) < 2:
            continue

        highway = first_tag(data.get("highway"), "residential")
        name = first_tag(data.get("name"))

        segments.append({
            "id": segment_id(u, v, key),
            "name": name,  # disambiguated once every segment is known
            "cls": STREET_CLASS.get(highway, "residential"),
            "highway": highway,
            "coords": [[round(lat, 6), round(lng, 6)] for lat, lng in coords],
            # Every OSM way this edge was built from. Simplification merges
            # consecutive ways, so this is a list, and map matching needs all
            # of them to resolve a match back to one segment.
            "osmWayIds": [int(w) for w in as_list(data.get("osmid"))],
            "osmU": int(u),
            "osmV": int(v),
            "osmKey": int(key),
            # osmnx measures length along the real geometry, in metres.
            "lengthM": round(float(data.get("length", 0.0)), 1),
        })

    return name_segments(segments)


def name_segments(segments):
    """Give every segment a human label, unique enough to pick from a list."""
    def base_label(seg):
        return seg["name"] or "Unnamed {}".format(seg["highway"].replace("_", " "))

    counts = {}
    for seg in segments:
        label = base_label(seg)
        counts[label] = counts.get(label, 0) + 1

    # Number the segments of a multi-segment street from one end, so the
    # ordinals read in geographic order rather than hash order.
    seen = {}
    for seg in sorted(segments, key=lambda s: (base_label(s), s["coords"][0])):
        label = base_label(seg)
        if counts[label] > 1:
            seen[label] = seen.get(label, 0) + 1
            seg["name"] = "{} ({})".format(label, seen[label])
        else:
            seg["name"] = label
    return segments


# --------------------------------------------------------- the litter model

def propensity(seg, arterial_polys, jitter=True):
    """How fast this segment accumulates litter, in [0,1].

    Drivers, in rough order of real-world effect size: arterial traffic,
    nearby packaged-food retail, transit waiting areas, and school/park foot
    traffic. Residential interiors accumulate slowly.
    """
    mid = seg["coords"][len(seg["coords"]) // 2]
    score = 0.08

    if arterial_polys:
        art = min(point_to_polyline_m(mid, poly) for poly in arterial_polys)
        score += 0.42 * decay(art, 260.0)

    if seg["cls"] == "arterial":
        score += 0.22
    elif seg["cls"] == "collector":
        score += 0.09

    for _, _, kind, plat, plng in POIS:
        d = meters(mid, (plat, plng))
        if kind == "commercial":
            score += 0.30 * decay(d, 180.0)
        elif kind == "transit":
            score += 0.26 * decay(d, 150.0)
        elif kind in ("school", "park"):
            score += 0.16 * decay(d, 200.0)
        else:
            score += 0.07 * decay(d, 160.0)

    if jitter:
        score += random.uniform(-0.05, 0.05)
    return max(0.0, min(1.0, score))


CATEGORIES = ["PLASTIC_BOTTLE", "PLASTIC_BAG", "FILM_WRAPPER", "CIGARETTE",
              "ALUMINUM_CAN", "GLASS", "PAPER", "FOAM", "FOOD_PACKAGING", "OTHER"]


def composition(seg, prop):
    """Composition shifts with context: butts at transit, film near retail."""
    mid = seg["coords"][len(seg["coords"]) // 2]
    commercial = max(decay(meters(mid, (p[3], p[4])), 200.0)
                     for p in POIS if p[2] in ("commercial", "transit"))
    w = {
        "PLASTIC_BOTTLE": 1.0 + 1.6 * prop,
        "PLASTIC_BAG": 0.7 + 0.9 * prop,
        "FILM_WRAPPER": 0.8 + 2.2 * commercial,
        "CIGARETTE": 0.5 + 3.0 * commercial,
        "ALUMINUM_CAN": 0.8 + 1.1 * prop,
        "GLASS": 0.3 + 0.7 * prop,
        "PAPER": 0.9 + 0.4 * (1 - prop),
        "FOAM": 0.4 + 1.3 * commercial,
        "FOOD_PACKAGING": 0.6 + 2.4 * commercial,
        "OTHER": 0.5,
    }
    total = sum(w.values())
    return {k: round(v / total, 4) for k, v in w.items()}


def unsurveyed(segs):
    """The real network with nothing observed on it yet.

    This is the default, and it is the honest starting state: a block nobody
    has walked has no litter index, no history and no composition, and the map
    should say so by showing nothing rather than by showing a plausible colour.

    Propensity stays, because it is not an observation. It is a prior derived
    from the street's own attributes -- class, arterial proximity, nearby POIs
    -- and the planner needs something to rank an unwalked block by. The random
    jitter the simulator added to it does not stay.
    """
    arterial_polys = [
        [tuple(c) for c in s["coords"]] for s in segs if s["cls"] == "arterial"
    ]
    for s in segs:
        s["propensity"] = round(propensity(s, arterial_polys, jitter=False), 4)
        s["litterIndex"] = 0.0
        s["history"] = []
        s["cleanups"] = 0
        s["estimatedPieces"] = 0
        s["composition"] = {}
        s["surveyCount"] = 0
    return segs


def simulate(segs):
    """Run DAYS of accumulation and cleanup to produce a history series.

    Opt-in via --synthetic-litter, and only ever for demos and screenshots.
    What comes out is a model fitted to real geometry, not measurement, and it
    is indistinguishable on screen from the real thing -- which is exactly why
    it is not the default.
    """
    arterial_polys = [
        [tuple(c) for c in s["coords"]] for s in segs if s["cls"] == "arterial"
    ]
    for s in segs:
        s["propensity"] = round(propensity(s, arterial_polys), 4)

    state = {s["id"]: s["propensity"] * random.uniform(0.35, 0.95) for s in segs}
    series = {s["id"]: [] for s in segs}
    cleanups = {s["id"]: 0 for s in segs}

    # Volunteer effort is not uniform either: it follows the few segments people
    # actually walk, which is exactly the bias TrashTalk exists to correct.
    attention = {s["id"]: random.random() ** 4.0 for s in segs}

    for _ in range(DAYS):
        for s in segs:
            sid = s["id"]
            p = s["propensity"]
            # Relaxation toward the segment's own carrying capacity. Using the
            # propensity as the ceiling (rather than 1.0) is what keeps a quiet
            # residential block quiet: left alone it settles at its own
            # equilibrium instead of creeping toward a saturated maximum.
            state[sid] += 0.18 * (p - state[sid]) + random.uniform(-0.012, 0.020)
            if random.random() < 0.012 + 0.46 * attention[sid]:
                state[sid] *= random.uniform(0.18, 0.55)   # a pickup happened
                cleanups[sid] += 1
            state[sid] = max(0.0, min(1.0, state[sid]))
            series[sid].append(round(state[sid], 4))

    for s in segs:
        sid = s["id"]
        idx = series[sid][-1]
        s["litterIndex"] = idx
        s["history"] = series[sid]
        s["cleanups"] = cleanups[sid]
        s["estimatedPieces"] = int(round(idx * s["lengthM"] * 0.11))
        s["composition"] = composition(s, s["propensity"])
        # Most of a neighbourhood has never been walked by anybody. That gap is
        # the Scientist path's whole reason to exist, so the seed data should
        # show it rather than pretend to full coverage.
        s["surveyCount"] = cleanups[sid] + (1 if random.random() < 0.18 else 0)
    return segs


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--synthetic-litter",
        action="store_true",
        help="fill in a simulated litter index and history (demos only)",
    )
    args = ap.parse_args()

    random.seed(SEED)

    print("fetching OSM network for {} ...".format(NEIGHBORHOOD))
    segs = fetch_segments()
    print("  {} segments from OSM".format(len(segs)))

    segs = simulate(segs) if args.synthetic_litter else unsurveyed(segs)

    lats = [c[0] for s in segs for c in s["coords"]]
    lngs = [c[1] for s in segs for c in s["coords"]]

    out = {
        "meta": {
            "neighborhood": NEIGHBORHOOD,
            "city": CITY,
            "state": STATE,
            "cityId": CITY_ID,
            "seed": SEED,
            "historyDays": DAYS,
            "segmentCount": len(segs),
            "source": "OpenStreetMap via osmnx {}".format(ox.__version__),
            "osmFilter": CUSTOM_FILTER,
            "bbox": {"west": BBOX[0], "south": BBOX[1], "east": BBOX[2], "north": BBOX[3]},
            "bounds": {
                "minLat": min(lats), "maxLat": max(lats),
                "minLng": min(lngs), "maxLng": max(lngs),
            },
            "litterData": "synthetic" if args.synthetic_litter else "none",
            "note": (
                "Street geometry is real, from OpenStreetMap (ODbL). The litter "
                "index, history and composition are a synthetic model fitted to "
                "that geometry, not survey observations -- do not publish them "
                "as measurements."
                if args.synthetic_litter else
                "Street geometry is real, from OpenStreetMap (ODbL). No litter "
                "observations: every segment starts unsurveyed, and anything the "
                "app shows on this network was measured by somebody walking it."
            ),
        },
        "pois": [{"id": p[0], "name": p[1], "kind": p[2], "lat": p[3], "lng": p[4]}
                 for p in POIS],
        "segments": segs,
    }

    dest = Path(__file__).resolve().parent.parent / "app/src/main/assets/terrell_heights.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")

    classes = {}
    for s in segs:
        classes[s["cls"]] = classes.get(s["cls"], 0) + 1
    multi = sum(1 for s in segs if len(s["osmWayIds"]) > 1)

    print("wrote {} ({:.1f} KiB)".format(dest, dest.stat().st_size / 1024))
    print("  classes        : {}".format(classes))
    print("  network length : {:.2f} km".format(sum(s["lengthM"] for s in segs) / 1000))
    print("  multi-way edges: {}".format(multi))
    if args.synthetic_litter:
        idxs = sorted(s["litterIndex"] for s in segs)
        print("  litterIndex    : min {:.3f} median {:.3f} max {:.3f}".format(
            idxs[0], idxs[len(idxs) // 2], idxs[-1]))
        print("  unsurveyed     : {} of {}".format(
            sum(1 for s in segs if s["surveyCount"] == 0), len(segs)))
    else:
        print("  litter data    : none -- every segment unsurveyed")


if __name__ == "__main__":
    main()
