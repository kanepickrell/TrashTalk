#!/usr/bin/env python3
"""Build the marketing site's visuals and open-data downloads from real data.

The site could have used stock photography of volunteers holding bin bags. It
uses the actual survey network instead, rendered to SVG with the same visual
grammar as the app: a near-black ground, segments glowing by litter density,
crew territory washed in behind. Two reasons that is the better choice -- the
hero image is then literally the product rather than an illustration of it, and
a site about making data available should be built out of the data.

Two modes, decided by whether the network carries observations. Until
somebody has walked a block there is nothing measured to publish, so the
heroes fall back to the network itself, the downloads carry geometry alone,
and stats.json says `hasObservations: false` with its observation figures
zeroed. Publishing a simulated litter index as an open dataset was the
previous behaviour and it was indefensible: the numbers were a model fitted
to real streets, indistinguishable on the page from measurement, under a
CC BY 4.0 licence and with no caveat anywhere on the site.

Outputs, all into web/public:
  hero-density.svg      litter density, or the bare network when unsurveyed
  hero-territory.svg    crew territory, or the bare network when unsurveyed
  data/*.csv/.geojson   the downloadable dataset
  stats.json            headline figures the pages read at build time
"""

import csv
import json
import math
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NETWORK = ROOT / "app/src/main/assets/terrell_heights.json"
WEB_PUBLIC = ROOT / "web/public"
DATA_DIR = WEB_PUBLIC / "data"

# The validated app palette, reused so the site and the product agree.
LITTER_RAMP = ["#3A2410", "#6B3A0E", "#A85A12", "#E08A22", "#FFC14D", "#FFE6B0"]
CREW_COLORS = ["#3987E5", "#D95926", "#199E70", "#C98500"]
CREWS = [
    ("team_austin", "Austin Hwy Alliance"),
    ("team_night", "Nacogdoches Night Crew"),
    ("team_trashers", "Terrell Heights Trashers"),
    ("team_wilshire", "Wilshire Walkers"),
]
VOID = "#07090C"
BASEMAP = "#17212C"

WIDTH, HEIGHT = 1600, 1000
PAD = 0.04


# Every row's evidence level.
#
# The column exists now so the schema does not change under researchers later,
# and every row says "unverified" because that is true: photo evidence and
# peer review are not built yet, so nothing here has been checked by anybody.
# Publishing the column full of a value we had not earned would be worse than
# not publishing it.
TRUST_LEVEL = "unverified"

TRUST_NOTE = (
    "trust_level is 'unverified' for every row in this release. Evidence "
    "capture and peer review are not implemented yet, so no observation has "
    "been corroborated. Filter on this column once 'partial' and 'verified' "
    "appear."
)

def lerp_hex(a, b, t):
    ar, ag, ab = int(a[1:3], 16), int(a[3:5], 16), int(a[5:7], 16)
    br, bg, bb = int(b[1:3], 16), int(b[3:5], 16), int(b[5:7], 16)
    return "#%02x%02x%02x" % (
        round(ar + (br - ar) * t),
        round(ag + (bg - ag) * t),
        round(ab + (bb - ab) * t),
    )


def sample(ramp, t):
    t = max(0.0, min(1.0, t))
    scaled = t * (len(ramp) - 1)
    i = min(int(scaled), len(ramp) - 2)
    return lerp_hex(ramp[i], ramp[i + 1], scaled - i)


def mercator_y(lat):
    s = math.sin(math.radians(lat))
    return 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)


def mercator_x(lng):
    return (lng + 180.0) / 360.0


def project(segments):
    """Fit the network to the SVG canvas in Web Mercator."""
    xs, ys = [], []
    for s in segments:
        for lat, lng in s["coords"]:
            xs.append(mercator_x(lng))
            ys.append(mercator_y(lat))
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    sx = WIDTH * (1 - 2 * PAD) / (maxx - minx)
    sy = HEIGHT * (1 - 2 * PAD) / (maxy - miny)
    scale = min(sx, sy)
    ox = (WIDTH - (maxx - minx) * scale) / 2
    oy = (HEIGHT - (maxy - miny) * scale) / 2

    def to_px(lat, lng):
        return (
            (mercator_x(lng) - minx) * scale + ox,
            (mercator_y(lat) - miny) * scale + oy,
        )

    return to_px


def polyline(seg, to_px):
    pts = [to_px(lat, lng) for lat, lng in seg["coords"]]
    return " ".join("%s%.1f,%.1f" % ("M" if i == 0 else "L", x, y)
                    for i, (x, y) in enumerate(pts))


def stroke_weight(seg):
    return {"arterial": 3.6, "collector": 2.6}.get(seg["cls"], 1.9)


def svg_header():
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" '
        'width="%d" height="%d" role="img" ' % (WIDTH, HEIGHT, WIDTH, HEIGHT)
    )


def has_observations(meta, segments):
    """Whether anything here was actually measured.

    The network ships unsurveyed now -- real OSM geometry, no litter model --
    so everything downstream has two modes. Publishing zeros in the shape of
    measurements would be its own kind of lie: a reader cannot tell "nobody
    has walked this block" from "somebody walked it and found nothing", and
    those are opposite claims.
    """
    if meta.get("litterData") == "none":
        return False
    return any(s.get("surveyCount", 0) > 0 for s in segments)


def render_network(segments, to_px):
    """The survey network itself, drawn by street class.

    What the site has to show when there are no observations: the real thing
    the project is built on, which is a neighbourhood resolved into blocks
    that can each be adopted and surveyed. Geometry is the honest half of this
    dataset and it is worth looking at on its own.
    """
    out = [
        svg_header(),
        'aria-label="The TrashTalk survey network: every street segment that '
        'can be walked and surveyed">',
        '<rect width="100%%" height="100%%" fill="%s"/>' % VOID,
    ]
    # Arterials last so the structure of the neighbourhood reads at a glance.
    order = {"residential": 0, "collector": 1, "arterial": 2}
    shade = {"residential": "#24323F", "collector": "#3C5668", "arterial": "#5F8296"}
    for s in sorted(segments, key=lambda s: order.get(s["cls"], 0)):
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (polyline(s, to_px), shade.get(s["cls"], BASEMAP), stroke_weight(s))
        )
    out.append("</svg>")
    return "\n".join(out)


def render_density(segments, to_px):
    out = [
        svg_header(),
        'aria-label="Litter density across the Terrell Heights survey network">',
        '<rect width="100%%" height="100%%" fill="%s"/>' % VOID,
    ]
    # Basemap first, so unlit streets still read as a grid.
    for s in segments:
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (polyline(s, to_px), BASEMAP, stroke_weight(s))
        )
    # Then the glow, brightest segments last so they sit on top.
    for s in sorted(segments, key=lambda s: s["litterIndex"]):
        idx = s["litterIndex"]
        if idx < 0.03:
            continue
        color = sample(LITTER_RAMP, idx)
        w = stroke_weight(s)
        bloom = idx * idx
        d = polyline(s, to_px)
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" opacity="%.3f" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (d, color, w * (2.4 + 2.2 * bloom), 0.07 * bloom)
        )
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" opacity="%.3f" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (d, color, w * 1.6, 0.30 * idx)
        )
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" opacity="%.3f" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (d, color, w, 0.25 + 0.75 * idx)
        )
    out.append("</svg>")
    return "".join(out)


def render_territory(segments, to_px, claims):
    out = [
        svg_header(),
        'aria-label="Street segments held by each neighbourhood crew">',
        '<rect width="100%%" height="100%%" fill="%s"/>' % VOID,
    ]
    for s in segments:
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" '
            'stroke-linecap="round" stroke-linejoin="round"/>'
            % (polyline(s, to_px), BASEMAP, stroke_weight(s))
        )
    for s in segments:
        crew = claims.get(s["id"])
        if crew is None:
            continue
        color = CREW_COLORS[crew]
        w = stroke_weight(s)
        d = polyline(s, to_px)
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" opacity="0.13" '
            'stroke-linecap="round" stroke-linejoin="round"/>' % (d, color, w * 7)
        )
        out.append(
            '<path d="%s" stroke="%s" stroke-width="%.1f" fill="none" opacity="0.9" '
            'stroke-linecap="round" stroke-linejoin="round"/>' % (d, color, w)
        )
    out.append("</svg>")
    return "".join(out)


def assign_claims(segments):
    """Mirror the app's rule: crews hold the blocks they actually work."""
    rng = random.Random(20250214)
    ranked = sorted(segments, key=lambda s: -s["cleanups"])
    claims = {}
    for i, s in enumerate(ranked[:46]):
        if s["cleanups"] < 2:
            continue
        claims[s["id"]] = rng.randrange(len(CREW_COLORS))
    return claims


def write_downloads(meta, segments, pois, surveyed):
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    csv_path = DATA_DIR / "terrell-heights-segments.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if not surveyed:
            # Geometry only. An observation column full of zeros reads as "we
            # looked and there was nothing", which is not what no data means.
            w.writerow([
                "segment_id", "street_name", "street_class", "length_m",
                "osm_way_ids", "mid_lat", "mid_lng",
            ])
            for s in segments:
                mid = s["coords"][len(s["coords"]) // 2]
                w.writerow([
                    s["id"], s["name"], s["cls"], round(s["lengthM"], 1),
                    " ".join(str(w_) for w_ in s.get("osmWayIds") or []),
                    mid[0], mid[1],
                ])
        else:
            w.writerow([
                "segment_id", "street_name", "street_class", "length_m",
                "litter_index", "pieces_estimated", "pieces_per_100m",
                "accumulation_rate", "surveys", "cleanups", "trust_level",
                "mid_lat", "mid_lng",
            ])
            for s in segments:
                mid = s["coords"][len(s["coords"]) // 2]
                per100 = (s["estimatedPieces"] / s["lengthM"] * 100) if s["lengthM"] else 0
                w.writerow([
                    s["id"], s["name"], s["cls"], round(s["lengthM"], 1),
                    s["litterIndex"], s["estimatedPieces"], round(per100, 2),
                    s["propensity"], s["surveyCount"], s["cleanups"], TRUST_LEVEL,
                    mid[0], mid[1],
                ])

    geo = {
        "type": "FeatureCollection",
        "metadata": dict(
            {
                "neighborhood": meta["neighborhood"],
                "city": meta["city"],
                "license": "CC BY 4.0",
                "note": meta["note"],
                "contains_observations": bool(surveyed),
            },
            **({"trust_levels": TRUST_NOTE} if surveyed else {}),
        ),
        "features": [
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[lng, lat] for lat, lng in s["coords"]],
                },
                "properties": dict(
                    {
                        "id": s["id"],
                        "name": s["name"],
                        "street_class": s["cls"],
                        "length_m": s["lengthM"],
                        "osm_way_ids": s.get("osmWayIds") or [],
                    },
                    **(
                        {
                            "litter_index": s["litterIndex"],
                            "pieces_estimated": s["estimatedPieces"],
                            "accumulation_rate": s["propensity"],
                            "surveys": s["surveyCount"],
                            "cleanups": s["cleanups"],
                            "trust_level": TRUST_LEVEL,
                            "composition": s["composition"],
                            "history_daily_index": s["history"],
                        }
                        if surveyed
                        else {}
                    ),
                ),
            }
            for s in segments
        ],
    }
    (DATA_DIR / "terrell-heights-segments.geojson").write_text(
        json.dumps(geo), encoding="utf-8")

    (DATA_DIR / "terrell-heights-generators.geojson").write_text(
        json.dumps({
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [p["lng"], p["lat"]]},
                    "properties": {"id": p["id"], "name": p["name"], "kind": p["kind"]},
                }
                for p in pois
            ],
        }),
        encoding="utf-8",
    )
    return csv_path


def composition_totals(segments):
    totals = {}
    for s in segments:
        weight = s["estimatedPieces"]
        for k, v in s["composition"].items():
            totals[k] = totals.get(k, 0) + v * weight
    total = sum(totals.values()) or 1
    return {k: v / total for k, v in sorted(totals.items(), key=lambda kv: -kv[1])}


SINGLE_USE = {"PLASTIC_BAG", "FILM_WRAPPER", "FOAM", "FOOD_PACKAGING", "PLASTIC_BOTTLE"}
LABELS = {
    "PLASTIC_BOTTLE": "Plastic bottles", "FILM_WRAPPER": "Film & wrappers",
    "CIGARETTE": "Cigarette butts", "FOOD_PACKAGING": "Food packaging",
    "ALUMINUM_CAN": "Aluminium cans", "PLASTIC_BAG": "Plastic bags",
    "FOAM": "Foam", "GLASS": "Glass", "PAPER": "Paper", "OTHER": "Other",
}


def main():
    raw = json.loads(NETWORK.read_text(encoding="utf-8"))
    meta, segments, pois = raw["meta"], raw["segments"], raw["pois"]
    WEB_PUBLIC.mkdir(parents=True, exist_ok=True)

    surveyed_mode = has_observations(meta, segments)
    to_px = project(segments)

    if surveyed_mode:
        claims = assign_claims(segments)
        (WEB_PUBLIC / "hero-density.svg").write_text(
            render_density(segments, to_px), encoding="utf-8")
        (WEB_PUBLIC / "hero-territory.svg").write_text(
            render_territory(segments, to_px, claims), encoding="utf-8")
    else:
        # Both heroes become the network itself. The density hero had nothing
        # to colour by, and the territory hero was drawing crews that do not
        # exist -- assign_claims invents them with random.choice. A marketing
        # image is still a claim about the data.
        claims = {}
        network_svg = render_network(segments, to_px)
        (WEB_PUBLIC / "hero-density.svg").write_text(network_svg, encoding="utf-8")
        (WEB_PUBLIC / "hero-territory.svg").write_text(network_svg, encoding="utf-8")

    write_downloads(meta, segments, pois, surveyed_mode)

    network_m = sum(s["lengthM"] for s in segments)
    surveyed = sum(1 for s in segments if s.get("surveyCount", 0) > 0)

    stats = {
        "neighborhood": meta["neighborhood"],
        "city": "%s, %s" % (meta["city"], meta["state"]),
        "segments": len(segments),
        "surveyedSegments": surveyed,
        "coveragePct": round(100 * surveyed / len(segments)) if segments else 0,
        "unwalkedSegments": len(segments) - surveyed,
        "networkKm": round(network_m / 1000, 1),
        "generators": len(pois),
        "hasObservations": surveyed_mode,
        "license": "CC BY 4.0",
        "note": meta["note"],
    }

    if not surveyed_mode:
        # The observation fields stay present and zeroed rather than being
        # dropped. The site reads them in a dozen places, and a missing key
        # would be a build error where a zero plus `hasObservations` is a
        # state the pages can actually render: nobody has walked this yet.
        stats.update({
            "pieces": 0,
            "piecesPer100m": 0,
            "singleUsePct": 0,
            "historyDays": 0,
            "crews": [],
            "heldSegments": 0,
            "composition": [],
            "hotspots": [],
        })
    else:
        comp = composition_totals(segments)
        total_pieces = sum(s["estimatedPieces"] for s in segments)
        stats.update({
            "pieces": total_pieces,
            "piecesPer100m": round(total_pieces / network_m * 100, 1),
            "singleUsePct": round(
                100 * sum(v for k, v in comp.items() if k in SINGLE_USE)),
            "historyDays": meta.get("historyDays", 0),
            "crews": [
                {"name": n, "color": CREW_COLORS[i]}
                for i, (_, n) in enumerate(CREWS)
            ],
            "heldSegments": len(claims),
            "composition": [
                {"label": LABELS.get(k, k), "share": round(v, 4), "key": k}
                for k, v in list(comp.items())[:7]
            ],
            "hotspots": [
                {
                    "name": s["name"],
                    "piecesPer100m": round(s["estimatedPieces"] / s["lengthM"] * 100, 1),
                    "index": s["litterIndex"],
                }
                for s in sorted(
                    segments,
                    key=lambda s: -(s["estimatedPieces"] / s["lengthM"] if s["lengthM"] else 0),
                )[:6]
            ],
        })

    (WEB_PUBLIC / "stats.json").write_text(json.dumps(stats, indent=2), encoding="utf-8")

    print("wrote hero svgs and data/ (csv + 2 geojson)")
    if surveyed_mode:
        print("segments=%d coverage=%d%% pieces=%d"
              % (len(segments), stats["coveragePct"], stats["pieces"]))
    else:
        print("segments=%d  no observations -- geometry only, no litter figures"
              % len(segments))


if __name__ == "__main__":
    main()
