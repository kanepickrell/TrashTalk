# TrashTalk

A gamified street-litter survey for Android, plus the open dataset and the
public site that come out of it.

The premise: litter is the visible end of a linear materials economy, and the
street is the last place anyone can still stop it. TrashTalk turns the walk you
already take into territory you hold — claim a block, defend it from your
neighbours, climb the leaderboard — and every pickup logged along the way is
also a measurement, at street-segment resolution, released openly.

**Live site:** https://trashtalk-app.vercel.app

---

## What is in this repository

```
app/            Android app (Kotlin, Jetpack Compose, Firebase)
web/            Marketing + open-data site (Next.js, deployed on Vercel)
brand/paths/    The four path mascot badges (SVG)
tools/          Data and asset generators (Python)
docs/           Firebase setup, Firestore rules
```

---

## Running the app

Open the repository root in Android Studio and press Run. That is the whole
procedure — there is no Node step, no API key, and no Firebase project
required to get a working build.

Requirements: JDK 17, Android SDK 36. The Gradle wrapper handles the rest.

From the command line:

```bash
./gradlew :app:installDebug
```

### Two backends, chosen automatically

| | Without `google-services.json` | With it |
|---|---|---|
| Backend | On-device demo store | Cloud Firestore |
| Data | Seeded neighbourhood, 32 collectors, 4 crews, 2 weeks of history | Live, shared, multi-device |
| Auth | Local identity | Firebase anonymous auth |

The demo backend is not a stub: it runs the same scoring, badge, streak and
territory logic as live mode and persists your progress to disk. Drop a real
`google-services.json` into `app/` and the same build switches to Firestore
with no code change — see [`docs/FIREBASE_SETUP.md`](docs/FIREBASE_SETUP.md).

---

## The map

Three layers: a pale raster basemap for street context, a data layer, and
markers.

- **Litter density** bins the network into 110 m hexagons, classified into five
  quantile classes. A surface answers "where is the problem" much faster than a
  few hundred thin strokes.
- **Territory** keeps the segments, coloured by the crew holding each block.
  Adoption is a property *of a block*, so binning it would blur the thing being
  asked about.
- **7-day change** is signed, so it uses a diverging scale around a neutral
  grey. **Cleanup activity** is magnitude, so it uses a one-hue ramp.

Basemap tiles come from Esri's World Light Gray Canvas (no API key) and are a
progressive enhancement: the bundled street geometry always draws underneath,
so the map works with no network at all — which matters, because volunteers
survey exactly the places with the worst coverage.

Performance note: projection and path construction are cached and rebuilt only
when the camera or data changes; a pan slides the cached geometry with a canvas
translate. The ambient pulse lives on a separate overlay canvas so animating it
does not repaint the hex surface underneath.

---

## The game

Seven narrative levels run first, for everyone. They argue a single case —
litter is systemic, "garbage" is a ~70-year-old human invention, biodegradable
and recycling mostly do not work, and the way out is to stop producing waste.
Each level ends in a short check, so a player who reaches the path choice has
demonstrably read the argument.

Then four paths, drawn as nature's cleanup crew:

| Path | Mascot | Role |
|---|---|---|
| Scientist | Mycelium Mushroom | Finds the pattern; surveys unwalked blocks |
| Eco-warrior | Dung Beetle | Holds the line; defends adopted segments |
| Advocate | Great-tailed Grackle | Turns evidence into local demand |
| Seeker | Forager Ant | Covers ground relentlessly; streaks and records |

Twenty badges, five per path, five repeatable tiers each. Five banked tiers
raise a path level, and there is no maximum.

The pairing is the mechanism. **M1:** Seekers and Eco-warriors compete, so they
generate records and volume. **M2:** Scientists and Advocates collaborate, so
evidence becomes recruitment and pressure. M2 fuels M1; M1 feeds M2 better data.

### Scoring

Points follow **harm, not mass**: a plastic bag outscores a newspaper because
the bag ends up as particles in a food web. Dirtier segments pay a difficulty
multiplier, and the first survey of an unwalked segment pays a flat coverage
bonus — the only counterweight to everybody re-walking the same photogenic
block.

---

## The data

Every pickup is an observation. The Litter Impact Assessment aggregates them
into something a council or a researcher can act on: coverage and confidence
first, then density per 100 m, composition by material, a daily trend, and a
distance-decay analysis that identifies which land uses actually generate the
litter.

Regenerate the bundled survey network and the published dataset:

```bash
python tools/generate_street_network.py   # app asset
python tools/build_web_assets.py          # site visuals + CSV/GeoJSON downloads
python tools/make_launcher_icons.py       # launcher icons from the brand mark
```

> **On the pilot geometry.** The bundled network for Terrell Heights is
> synthesised, not field-surveyed: representative street geometry with a
> litter-accumulation model fitted per segment. It is there so the app is
> explorable and the pipeline is demonstrable. Replace it with an Overpass
> export of real ways before publishing any assessment as fact — the generator
> is written so only its first step has to change.

---

## The site

```bash
cd web
npm install
npm run dev        # http://localhost:3000
npx vercel deploy --prod
```

The hero is not stock photography; it is the actual survey network rendered to
SVG with the same palette as the app, generated by `tools/build_web_assets.py`.
A site about making data available should be built out of the data.

---

## Design notes

Colour is encoded by the job it does, and every palette was validated against
the surfaces it actually renders on rather than eyeballed:

- **Magnitude** gets a sequential ramp — one hue, monotone lightness.
- **Polarity** (7-day change) gets a diverging pair around a neutral grey.
- **Identity** (crews, materials) gets the categorical set in fixed slot order,
  never cycled, folded into "Other" past eight.
- **State** (hazard bands) gets reserved status colours, always with a label
  and a marker so meaning never rests on hue alone.

The hex ramp is the one deliberate exception: its lightest class sits below the
2:1 contrast floor against the pale basemap. That is a considered trade for the
classed-choropleth look, and it ships with three non-colour reliefs — a dark
outline on every cell, numeric ranges in the legend, and a tap readout giving
the exact count.
