// Generates the offline basemap style for the Android app.
//
// Writes app/src/main/assets/map/style.json. Run it through
// tools/build_basemap.ps1, which also fetches the fonts and sprites the style
// references -- a style without those is a map with no labels and no shields.
//
// Why generate rather than hand-write: the Protomaps basemap is ~70 layers of
// zoom-dependent road casings and label collisions that nobody should maintain
// by hand. We take their layer geometry wholesale and substitute only colour.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { layers, namedFlavor } from "@protomaps/basemaps";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, "../app/src/main/assets/map/style.json");

// The style names the archive with a token rather than a URL. The archive is
// range-read, so it cannot be served from `asset://` -- the app copies it out
// of the APK at first launch and substitutes the real path here. See
// BasemapAssets.kt.
const ARCHIVE_TOKEN = "{archive}";

const SOURCE = "protomaps";

/**
 * The TrashTalk flavour.
 *
 * Derived from Protomaps' `grayscale`, which is the right starting point for a
 * data map: it is deliberately desaturated, so the whole colour budget stays
 * available for the thing the map is actually about -- the litter density ramp
 * and the path colours drawn on top. A basemap that spends colour on parks and
 * motorways leaves the data competing with its own backdrop.
 *
 * Two changes from stock grayscale:
 *
 *  - Much lighter, and warmed to the app's MapGround (#F3F2EE). Stock grayscale
 *    sits around #a3a3a3, which would swallow the lightest class of the hex
 *    ramp entirely.
 *  - Roads are white on a warm casing, the Felt idiom the design target uses:
 *    streets read as channels cut out of the ground rather than lines drawn on
 *    it, which keeps them legible under a translucent density surface.
 *
 * Water is the single permitted tint, kept to low chroma. It is the one feature
 * where a hue carries meaning that no reader has to be taught.
 */
const GROUND = "#F3F2EE"; // TT.MapGround
const INK = "#2E3440"; // TT.MapInk
const ROAD = "#FFFFFF";
const CASING = "#E2DED5";

const FLAVOR = {
  background: GROUND,
  earth: GROUND,

  // Landuse: present but barely. A reader should be able to tell a park from a
  // block without the park ever pulling the eye off the data.
  park_a: "#E6EADF",
  park_b: "#E6EADF",
  wood_a: "#E3E8DC",
  wood_b: "#E3E8DC",
  scrub_a: "#E8EBE2",
  scrub_b: "#E8EBE2",
  zoo: "#E8EBE2",
  hospital: "#EFEAE8",
  school: "#EDEAE2",
  industrial: "#EAE8E2",
  military: "#EBE9E3",
  pedestrian: "#ECEAE4",
  aerodrome: "#EAE9E4",
  runway: "#FFFFFF",
  glacier: "#F0EDE4",
  sand: "#F0EDE4",
  beach: "#F0EDE4",
  buildings: "#E7E3DA",
  pier: CASING,

  water: "#D7E3EA",

  // Roads. Every class gets the same white fill; width and casing carry the
  // hierarchy, which is how the Felt reference reads at a glance.
  other: ROAD,
  minor_service: ROAD,
  minor_a: ROAD,
  minor_b: ROAD,
  link: ROAD,
  major: ROAD,
  highway: ROAD,
  railway: "#E0DCD3",

  minor_service_casing: CASING,
  minor_casing: CASING,
  link_casing: CASING,
  major_casing_early: CASING,
  major_casing_late: CASING,
  highway_casing_early: CASING,
  highway_casing_late: CASING,

  // Tunnels read as the road's ghost: same geometry, dropped contrast.
  tunnel_other: "#EFECE4",
  tunnel_minor: "#EFECE4",
  tunnel_link: "#EFECE4",
  tunnel_major: "#EFECE4",
  tunnel_highway: "#EFECE4",
  tunnel_other_casing: "#E4E0D7",
  tunnel_minor_casing: "#E4E0D7",
  tunnel_link_casing: "#E4E0D7",
  tunnel_major_casing: "#E4E0D7",
  tunnel_highway_casing: "#E4E0D7",

  bridges_other: ROAD,
  bridges_minor: ROAD,
  bridges_link: ROAD,
  bridges_major: ROAD,
  bridges_highway: ROAD,
  bridges_other_casing: CASING,
  bridges_minor_casing: CASING,
  bridges_link_casing: CASING,
  bridges_major_casing: CASING,
  bridges_highway_casing: CASING,

  boundaries: "#B0AAA0",

  // Labels. Street names sit above the data surface, so they get white halos
  // and enough contrast to survive whatever colour lands underneath them.
  roads_label_minor: "#7B8290",
  roads_label_minor_halo: "#FFFFFF",
  roads_label_major: "#5A6372",
  roads_label_major_halo: "#FFFFFF",
  address_label: "#9AA0A8",
  address_label_halo: "#FFFFFF",
  ocean_label: "#8FA3B3",
  subplace_label: "#6B7382",
  subplace_label_halo: GROUND,
  city_label: INK,
  city_label_halo: GROUND,
  state_label: "#8A9099",
  state_label_halo: GROUND,
  country_label: "#7A818C",
};

// A key we neither override nor want to inherit silently would render at stock
// grayscale -- a mid-grey blot on a near-white map, and easy to miss in review.
// Fail the build instead, so a Protomaps upgrade that adds a key is a decision
// rather than an accident.
const stock = namedFlavor("grayscale");
const missing = Object.keys(stock).filter((k) => !(k in FLAVOR));
const unknown = Object.keys(FLAVOR).filter((k) => !(k in stock));
if (missing.length || unknown.length) {
  if (missing.length) console.error(`Flavor keys not overridden: ${missing.join(", ")}`);
  if (unknown.length) console.error(`Flavor keys that no longer exist: ${unknown.join(", ")}`);
  process.exit(1);
}

const style = {
  version: 8,
  name: "TrashTalk basemap",
  // Fonts and sprites are whole-file reads, so unlike the archive they load
  // straight from the APK.
  glyphs: "asset://map/fonts/{fontstack}/{range}.pbf",
  sprite: "asset://map/sprites/basemap",
  sources: {
    [SOURCE]: {
      type: "vector",
      url: `pmtiles://${ARCHIVE_TOKEN}`,
      attribution: "© Protomaps © OpenStreetMap",
    },
  },
  layers: layers(SOURCE, FLAVOR, { lang: "en" }),
};

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(style, null, 1));

const symbolLayers = style.layers.filter((l) => l.type === "symbol").length;
console.log(`style.json: ${style.layers.length} layers (${symbolLayers} symbol), -> ${OUT}`);
