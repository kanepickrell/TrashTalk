# Data and asset generators

Run from the repository root.

| Script | Produces |
|---|---|
| `generate_street_network.py` | `app/src/main/assets/terrell_heights.json` — the survey network, from OSM |
| `build_web_assets.py` | site visuals and the published CSV/GeoJSON downloads |
| `make_launcher_icons.py` | launcher icons from the brand mark |

## Street network

Requires `osmnx` (`pip install osmnx`). Pulls the walkable street classes for
the pilot bounding box, simplifies edges intersection-to-intersection, and
keeps the OSM way ids and end nodes on every segment so map matching can join a
recorded walk back to a segment.

"Terrell Heights" has no boundary relation in OSM, so the area is a pinned
bounding box rather than `graph_from_place`. That also keeps runs reproducible.

## Basemap

The offline vector basemap is extracted from the Protomaps daily planet build.
`tools/bin/` is gitignored; download the CLI first:

```bash
# https://github.com/protomaps/go-pmtiles/releases  (Windows x86_64 zip)
curl -sL -o pmtiles.zip https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Windows_x86_64.zip
```

Find the latest build key, then extract just the neighbourhood:

```bash
curl -s https://build-metadata.protomaps.dev/builds.json | tail   # newest key
./tools/bin/pmtiles.exe extract \
  "https://build.protomaps.com/<YYYYMMDD>.pmtiles" \
  "app/src/main/assets/terrell_heights.pmtiles" \
  --bbox=-98.4660,29.4950,-98.4460,29.5100 \
  --maxzoom=15
```

Extraction uses HTTP range requests: ~1 MB is transferred out of a ~139 GB
planet file. `--maxzoom` is capped at 15 because that is the planet build's
own maximum; MapLibre overzooms past it, which is normal for vector tiles.
