# Valhalla

One deployment unblocks two things: map matching for server-side scoring
(Phase 4.2) and real pedestrian routing for the planner (7.2).

Runs on the OptiPlex for development, moves to Cloud Run when the scoring
function needs it in production.

## Running it

```bash
cd tools/valhalla

docker compose run --rm clip       # once: downloads Texas, cuts out San Antonio
docker compose up -d valhalla      # builds tiles on first run, then serves
docker compose logs -f valhalla    # watch the first build
```

The first run downloads ~700 MB and builds tiles; budget twenty minutes or so.
After that `./data` holds the tiles and startup is seconds. `./data` is
gitignored — it is a build artefact, not source.

Check it is up:

```bash
curl -s localhost:8002/status | head
```

## Why it clips first

The Geofabrik Texas extract is 692 MB. Building a routing graph for the whole
state to serve one neighbourhood is a long build and several gigabytes of tiles
nobody queries, so `clip` cuts a San Antonio metro box
(`-98.90,29.20 .. -98.20,29.75`) and Valhalla builds from that.

That box is deliberately far larger than the 2 km pilot area. A graph that
stopped at the pilot boundary could not route a walk that stepped outside it,
and map matching needs the roads on both sides of a junction to work out which
one somebody actually took.

## The segment join, and why it is not a lookup

`match_track.py` is the part Phase 4.2 depends on. Run it against a recorded
track:

```bash
python match_track.py --track track.json
```

The plan assumed Valhalla's per-edge OSM way id identifies one of our segments.
It does not. osmnx splits ways at intersections, so the 317 segments carry only
**148 distinct way ids, and 67 of those ways span several segments — one spans
twelve.** "Haskin Drive" is a single OSM way across a dozen blocks, so a way id
narrows the answer to twelve candidates, not one.

The join is therefore two steps:

1. The way id narrows the candidates — usually to one or two.
2. The matched edge's own geometry chooses between them.

Step 2 is scored across the whole matched piece rather than at its midpoint.
That matters more than it sounds: consecutive blocks of one street are
collinear and share an endpoint, so a midpoint that drifts a few metres lands
just as near the next block along. Measured on the twelve blocks of Haskin
Drive, midpoint-only put three of twelve on the wrong block; averaging over the
piece gets all twelve.

A match more than 40 m from any candidate resolves to nothing. Attributing it
to whichever block happened to be nearest would put litter on streets nobody
walked, which is worse than recording that it could not be placed.

## Testing the join without Valhalla

```bash
python test_resolve.py
```

Builds synthetic matches from the real network with GPS-like noise and checks
the resolver puts them back. It covers the polyline6 decoder, distinct streets,
the worst shared-way case, and that an off-network track stays unplaced — so
the join can be trusted before the tile build has even finished.

## Taking it to production

Cloud Run, with the tiles baked into the image rather than built at start:
a cold start that builds a routing graph is a cold start measured in minutes.
Build the tile tar once, push it with the image, and set `use_tiles_ignore_pbf`.
