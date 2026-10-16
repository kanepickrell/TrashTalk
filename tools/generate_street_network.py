#!/usr/bin/env python3
"""Generate the bundled survey network for TrashTalk.

TrashTalk treats a neighbourhood the way Citi Bike treats its dock graph: the
unit of data is not a point but a *segment* of street between two
intersections. A segment can be surveyed, adopted, cleaned and scored, which
makes the map a field of edges rather than a scatter of pins.

This script synthesises a plausible network for Terrell Heights, San Antonio TX
(the first TrashTalk target neighbourhood) together with 14 days of observation
history so the map time scrubber has something to animate. It is
deterministic: same seed, same network, so the demo backend and the committed
asset never disagree.

To move to real geometry, replace the grid construction with an Overpass
export of highway=residential/tertiary/secondary ways clipped to the
neighbourhood boundary and keep the rest of the pipeline intact.
"""

import json
import math
import random
from pathlib import Path

SEED = 20250214
random.seed(SEED)

DAYS = 14

# Neighbourhood envelope.
LAT0, LAT_STEP, N_ROWS = 29.4950, 0.00180, 9
LNG0, LNG_STEP, N_COLS = -98.46600, 0.00205, 11

EW_NAMES = [
    "Burr Rd", "Terrell Rd", "Castano Ave", "Abiso Ave", "Northridge Dr",
    "Greenridge Dr", "Eventide Dr", "Sunset Rd", "Loma Vista Dr",
]
NS_NAMES = [
    "Albany St", "Patterson Ave", "Larchmont Dr", "Vanderbilt St",
    "Chevy Chase Dr", "Rexford Dr", "Ridgehaven Pl", "Walsh Rd",
    "Glen Arbor St", "Rosedale Ct", "Harrigan Way",
]

# Trip generators. Litter does not fall uniformly; it concentrates where people
# transfer between modes and where packaged food is sold.
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

# Two arterials slice the grid. Arterials carry the most traffic and, with it,
# the most roadside litter.
ARTERIALS = [
    ("art_austin", "Austin Hwy", [(29.49420, -98.46680), (29.49560, -98.46040),
                                  (29.49760, -98.45400), (29.49980, -98.44820)]),
    ("art_nacog",  "Nacogdoches Rd", [(29.50980, -98.46620), (29.50700, -98.46000),
                                      (29.50400, -98.45420), (29.50080, -98.44860)]),
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
        # Work in metres so the projection is not skewed by latitude.
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


def jitter(v, amt):
    return v + random.uniform(-amt, amt)


def row_lat(i):
    return LAT0 + i * LAT_STEP + (0.00012 * math.sin(i * 1.7))


def col_lng(j):
    return LNG0 + j * LNG_STEP + (0.00013 * math.cos(j * 2.1))


def curve(a, b, bend):
    """Insert a midpoint so segments read as streets rather than rulers."""
    mid_lat = (a[0] + b[0]) / 2 + bend * (b[1] - a[1]) * 0.08
    mid_lng = (a[1] + b[1]) / 2 + bend * (b[0] - a[0]) * 0.08
    return [a, (round(mid_lat, 6), round(mid_lng, 6)), b]


def build_segments():
    segs = []

    def add(sid, name, cls, coords):
        segs.append({"id": sid, "name": name, "cls": cls,
                     "coords": [[round(c[0], 6), round(c[1], 6)] for c in coords]})

    # East-west streets, split at every intersection.
    for i in range(N_ROWS):
        lat = row_lat(i)
        name = EW_NAMES[i]
        cls = "collector" if i in (1, 6) else "residential"
        for j in range(N_COLS - 1):
            a = (jitter(lat, 0.00006), col_lng(j))
            b = (jitter(lat, 0.00006), col_lng(j + 1))
            add("s_ew_%d_%d" % (i, j), "%s %d block" % (name, 100 + j * 100), cls,
                curve(a, b, random.uniform(-1, 1)))

    # North-south streets.
    for j in range(N_COLS):
        lng = col_lng(j)
        name = NS_NAMES[j]
        cls = "collector" if j in (2, 7) else "residential"
        for i in range(N_ROWS - 1):
            a = (row_lat(i), jitter(lng, 0.00006))
            b = (row_lat(i + 1), jitter(lng, 0.00006))
            add("s_ns_%d_%d" % (j, i), "%s %d block" % (name, 100 + i * 100), cls,
                curve(a, b, random.uniform(-1, 1)))

    # Arterials, split into runs between their shape points.
    for aid, name, pts in ARTERIALS:
        for k in range(len(pts) - 1):
            add("%s_%d" % (aid, k), "%s segment %d" % (name, k + 1), "arterial",
                curve(pts[k], pts[k + 1], random.uniform(-0.4, 0.4)))
    return segs


def propensity(seg):
    """How fast this segment accumulates litter, in [0,1].

    Drivers, in rough order of real-world effect size: arterial traffic,
    nearby packaged-food retail, transit waiting areas, and school/park foot
    traffic. Residential interiors accumulate slowly.
    """
    mid = seg["coords"][len(seg["coords"]) // 2]
    score = 0.08

    art = min(point_to_polyline_m(mid, [tuple(p) for p in pts])
              for _, _, pts in ARTERIALS)
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

    score += random.uniform(-0.05, 0.05)
    return max(0.0, min(1.0, score))


# Composition shifts with context: cigarette butts cluster at transit stops,
# food packaging near retail, paper and yard debris inside subdivisions.
CATEGORIES = ["PLASTIC_BOTTLE", "PLASTIC_BAG", "FILM_WRAPPER", "CIGARETTE",
              "ALUMINUM_CAN", "GLASS", "PAPER", "FOAM", "FOOD_PACKAGING", "OTHER"]


def composition(seg, prop):
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


def simulate(segs):
    """Run DAYS of accumulation and cleanup to produce a history series."""
    for s in segs:
        s["propensity"] = round(propensity(s), 4)

    # Start each segment part-way up its equilibrium so day 0 is not empty.
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
        length = sum(meters(s["coords"][i], s["coords"][i + 1])
                     for i in range(len(s["coords"]) - 1))
        idx = series[sid][-1]
        s["lengthM"] = round(length, 1)
        s["litterIndex"] = idx
        s["history"] = series[sid]
        s["cleanups"] = cleanups[sid]
        # Observed pieces scale with both density and how much street there is.
        s["estimatedPieces"] = int(round(idx * length * 0.11))
        s["composition"] = composition(s, s["propensity"])
        # Segments nobody has walked are the Scientist path raw material.
        # Most of a neighbourhood has never been walked by anybody. That gap
        # is the Scientist path's whole reason to exist, so the seed data
        # should show it rather than pretend to full coverage.
        s["surveyCount"] = cleanups[sid] + (1 if random.random() < 0.18 else 0)
    return segs


def main():
    segs = simulate(build_segments())
    out = {
        "meta": {
            "neighborhood": "Terrell Heights",
            "city": "San Antonio",
            "state": "TX",
            "cityId": "san-antonio-tx",
            "seed": SEED,
            "historyDays": DAYS,
            "segmentCount": len(segs),
            "bounds": {
                "minLat": min(c[0] for s in segs for c in s["coords"]),
                "maxLat": max(c[0] for s in segs for c in s["coords"]),
                "minLng": min(c[1] for s in segs for c in s["coords"]),
                "maxLng": max(c[1] for s in segs for c in s["coords"]),
            },
            "note": "Synthetic survey network generated by tools/generate_street_network.py. "
                    "Geometry is representative, not surveyed; replace with an Overpass "
                    "export before publishing real Litter Impact Assessments.",
        },
        "pois": [{"id": p[0], "name": p[1], "kind": p[2], "lat": p[3], "lng": p[4]}
                 for p in POIS],
        "segments": segs,
    }
    dest = Path(__file__).resolve().parent.parent / "app/src/main/assets/terrell_heights.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")

    idxs = sorted(s["litterIndex"] for s in segs)
    print("wrote %s (%.1f KiB)" % (dest, dest.stat().st_size / 1024))
    print("segments=%d pieces=%d" % (len(segs), sum(s["estimatedPieces"] for s in segs)))
    print("litterIndex min=%.3f median=%.3f max=%.3f" % (idxs[0], idxs[len(idxs) // 2], idxs[-1]))
    for s in sorted(segs, key=lambda s: -s["litterIndex"])[:5]:
        print("  hotspot %-34s idx=%.3f pieces=%d" % (s["name"], s["litterIndex"], s["estimatedPieces"]))


if __name__ == "__main__":
    main()
