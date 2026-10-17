# Data and asset generators

Run from the repository root.

| Script | Produces |
|---|---|
| `generate_street_network.py` | `app/src/main/assets/terrell_heights.json` — the survey network, from OSM |
| `build_web_assets.py` | site visuals and the published CSV/GeoJSON downloads |
| `make_launcher_icons.py` | launcher icons from the brand mark |
| `build_basemap.ps1` | the whole offline basemap package in `assets/map/` |
| `build_basemap_style.mjs` | just `assets/map/style.json` (called by the above) |

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
  "app/src/main/assets/map/terrell_heights.pmtiles" \
  --bbox=-98.4660,29.4950,-98.4460,29.5100 \
  --maxzoom=15
```

Extraction uses HTTP range requests: ~1 MB is transferred out of a ~139 GB
planet file. `--maxzoom` is capped at 15 because that is the planet build's
own maximum; MapLibre overzooms past it, which is normal for vector tiles.

### The basemap package

The archive is only the tiles. The style that paints them, the glyphs that draw
labels and the sprites that draw shields are built by:

```powershell
pwsh tools/build_basemap.ps1
```

which writes into `app/src/main/assets/map/` and stages copies into
`web/public/map/`:

| | |
|---|---|
| `style.json` | 69 layers, generated from `@protomaps/basemaps` |
| `fonts/<stack>/<range>.pbf` | Noto Sans Regular, Medium, Italic -- Latin ranges only |
| `sprites/basemap.{json,png}` | road shields, one-way arrows, town spots (@1x and @2x) |

Outputs are committed, so an ordinary build never runs this. Re-run it when the
Protomaps version moves or the flavour changes.

The style is generated rather than hand-written because it is ~70 layers of
zoom-dependent road casings and label collision rules. We take that geometry
wholesale and substitute only colour -- see the `FLAVOR` block in
`build_basemap_style.mjs`, which derives from Protomaps' `grayscale` and fails
the build if a future version adds a colour key we have not considered.

Note the package version tracks the *tile* schema, not the plan: our archive is
v4 (`version 4.15.2` in its metadata), which `@protomaps/basemaps` 5.x targets.

Two things the style depends on that are easy to break:

- `{archive}` in the source URL is a placeholder the app substitutes at
  runtime. The archive is range-read, so it cannot be served from `asset://` --
  `Basemap.kt` copies it out of the APK first. Fonts and sprites are whole-file
  reads and load from `asset://` directly.
- The app marks `pmtiles` as `noCompress`. `Basemap.kt` relies on that to size
  the asset without reading it, and will throw if it is ever dropped.

Nothing in the app's package references the network; its style is asserted free
of `http(s)` URLs at generation time.

The generator emits the style twice -- once for the app (`asset://` glyphs and
sprites, `{archive}` substituted at runtime) and once for the site (everything
served from `/map`). One flavour, one layer list, two sets of URLs: if the
public map painted a block a different colour from the one in a volunteer's
hand, the data would stop being one dataset and start being two claims.
