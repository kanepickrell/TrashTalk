import type { Metadata } from "next";
import stats from "@/lib/stats";
import BasemapMap from "@/components/BasemapMap";

export const metadata: Metadata = {
  title: "Open data",
  description:
    "Street-segment litter data from the TrashTalk pilot in Terrell Heights, " +
    "San Antonio: CSV and GeoJSON, CC BY 4.0, with composition by material and " +
    "a daily index history.",
};

const FIELDS = [
  ["segment_id", "string", "Stable identifier for the street segment"],
  ["street_name", "string", "Street and block, e.g. “Burr Rd 400 block”"],
  ["street_class", "enum", "arterial · collector · residential"],
  ["length_m", "number", "Segment length in metres"],
  ["litter_index", "0–1", "Current litter density, 0 clear to 1 dumping-scale"],
  ["pieces_estimated", "integer", "Estimated pieces currently on the segment"],
  ["pieces_per_100m", "number", "Density normalised for comparability"],
  ["accumulation_rate", "0–1", "How fast the segment refills once cleared"],
  ["surveys", "integer", "Times the segment has been walked and logged"],
  ["cleanups", "integer", "Recorded collection events on the segment"],
  ["trust_level", "enum", "unverified · partial · verified — evidence behind the row"],
  ["composition", "object", "Share of pieces by material (GeoJSON only)"],
  ["history_daily_index", "array", `Daily litter index, ${stats.historyDays} days (GeoJSON only)`],
];

// Which of those a reader will actually find in the file they download. The
// schema is the shape the dataset takes once blocks have been surveyed; the
// current release stops after osm_way_ids, and saying so here is cheaper
// than letting somebody write a parser against columns that are not there.
const PRESENT_NOW = new Set([
  "segment_id", "street_name", "street_class", "length_m", "osm_way_ids",
]);

const DOWNLOADS = [
  {
    name: "terrell-heights-segments.csv",
    desc: "One row per street segment. Opens in anything.",
    meta: `CSV · ${stats.segments} rows`,
  },
  {
    name: "terrell-heights-segments.geojson",
    desc: stats.hasObservations
      ? "LineString geometry with full properties, composition and daily history."
      : "LineString geometry with segment identifiers. No observations yet.",
    meta: "GeoJSON · EPSG:4326",
  },
  {
    name: "terrell-heights-generators.geojson",
    desc: "Litter generators: transit stops, retail frontages, schools, parks.",
    meta: "GeoJSON · 10 points",
  },
];

export default function Data() {
  return (
    <main>
      <section style={{ paddingBottom: 30 }}>
        <div className="wrap">
          <p className="eyebrow">Open data · {stats.license}</p>
          <h1 style={{ marginBlock: "14px 20px", maxWidth: "18ch" }}>
            Litter, at street-segment resolution
          </h1>
          <p className="lede">
            Litter research is usually starved of spatial resolution: surveys
            are expensive, infrequent, and reported as neighbourhood averages. A
            gamified survey network produces the opposite — repeated
            observations per segment, composition by material, and a daily
            series.
            {stats.hasObservations
              ? ` This is the ${stats.neighborhood} pilot, published in full.`
              : " No observations have been collected yet. What is published" +
                " below is the survey network itself: every block that can be" +
                " walked, with the identifiers the observations will carry."}
          </p>

          {!stats.hasObservations && (
            <div
              className="card"
              style={{ marginBlock: "18px 4px", borderColor: "var(--amber)" }}
            >
              <p className="eyebrow" style={{ color: "var(--amber)" }}>
                No survey data yet
              </p>
              <p style={{ marginTop: 8 }}>
                This release contains street geometry and nothing else. Earlier
                versions of this page published a <b>simulated</b> litter index
                — a model fitted to these streets — in the same tables and
                under the same licence as measurement. It has been withdrawn.
                Figures below that count litter read zero because nobody has
                surveyed these blocks yet, not because they are clean.
              </p>
            </div>
          )}

          <div className="stats">
            <div className="stat">
              <b className="tnum">{stats.segments}</b>
              <span>segments</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.coveragePct}%</b>
              <span>surveyed at least once</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.networkKm} km</b>
              <span>of street network</span>
            </div>
            <div className="stat">
              <b className="tnum">
                {stats.hasObservations ? stats.piecesPer100m : "—"}
              </b>
              <span>mean pieces per 100 m</span>
            </div>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- the map */}
      <section style={{ paddingTop: 0 }} id="map">
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">The pilot area</p>
            <h2>The same map the volunteers use</h2>
            <p className="body">
              Street geometry, basemap and styling are the files that ship
              inside the app, served here from this site rather than from a
              tile vendor. No API key, no quota, and nobody watching who reads
              it.
            </p>
          </div>
          <BasemapMap
            className="map-figure"
            segmentsUrl="/data/terrell-heights-segments.geojson"
            label="Street map of the Terrell Heights pilot area, coloured by litter density"
          />
          <p className="caption">
            Every segment in the download, drawn from that same file — click one
            for its figures. Lighter is cleaner. Basemap &copy; Protomaps &copy;
            OpenStreetMap. Pilot area: {stats.neighborhood}.
          </p>
        </div>
      </section>

      {/* --------------------------------------------------------- downloads */}
      <section style={{ paddingTop: 30 }} id="download">
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Download</p>
            <h2>No sign-up, no API key</h2>
            <p className="body">
              Released under Creative Commons Attribution 4.0. Cite as:
              TrashTalk {stats.neighborhood} litter survey, {stats.city}.
            </p>
          </div>
          <div className="stack">
            {DOWNLOADS.map((d) => (
              <a className="dl" href={`/data/${d.name}`} key={d.name} download>
                <span>
                  <span className="dl-name">{d.name}</span>
                  <br />
                  <span className="dl-meta">{d.desc}</span>
                </span>
                <span className="dl-meta" style={{ whiteSpace: "nowrap" }}>
                  {d.meta} ↓
                </span>
              </a>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------ schema */}
      <section style={{ paddingTop: 0 }} id="schema">
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Schema</p>
            <h2>What is in a row</h2>
          </div>
          <div className="scroll-x">
            <table className="data">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Type</th>
                  <th style={{ textAlign: "left" }}>Meaning</th>
                </tr>
              </thead>
              <tbody>
                {FIELDS.map((f) => (
                  <tr key={f[0]}>
                    <td style={{ fontFamily: "ui-monospace, monospace", fontSize: "0.85rem" }}>
                      {f[0]}
                    </td>
                    <td className="dl-meta">{f[1]}</td>
                    <td style={{ textAlign: "left", color: "var(--ink-2)" }}>
                      {f[2]}
                      {!stats.hasObservations && !PRESENT_NOW.has(f[0]) && (
                        <span className="dl-meta"> · not in this release</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* ---------------------------------------------------------- findings */}
      <section style={{ paddingTop: 0, display: stats.hasObservations ? undefined : "none" }}>
        <div className="wrap">
          <div className="grid-2" style={{ alignItems: "start" }}>
            <div className="card">
              <p className="eyebrow">Priority segments</p>
              <h3 style={{ marginBlock: "8px 14px" }}>Highest density</h3>
              <table className="data">
                <thead>
                  <tr>
                    <th>Segment</th>
                    <th>per 100 m</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.hotspots.map((h) => (
                    <tr key={h.name}>
                      <td style={{ color: "var(--ink-2)" }}>{h.name}</td>
                      <td className="tnum">{h.piecesPer100m}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="small" style={{ marginTop: 14 }}>
                Density rather than raw counts, because a cul-de-sac and an
                arterial are not comparable on totals.
              </p>
            </div>

            <div className="card">
              <p className="eyebrow">Composition</p>
              <h3 style={{ marginBlock: "8px 14px" }}>By piece count</h3>
              {stats.composition.map((c, i) => (
                <div className="comp-row" key={c.key}>
                  <div>
                    <div style={{ fontSize: "0.92rem", marginBottom: 6 }}>{c.label}</div>
                    <div className="bar">
                      <i
                        style={{
                          width: `${Math.max(2, c.share * 100)}%`,
                          background: [
                            "var(--crew-1)",
                            "var(--crew-2)",
                            "var(--crew-3)",
                            "var(--crew-4)",
                            "#d55181",
                            "#008300",
                            "#9085e9",
                          ][i % 7],
                        }}
                      />
                    </div>
                  </div>
                  <div className="tnum" style={{ textAlign: "right", color: "var(--ink-2)" }}>
                    {(c.share * 100).toFixed(1)}%
                  </div>
                </div>
              ))}
              <p className="small" style={{ marginTop: 14 }}>
                {stats.hasObservations
                  ? `${stats.singleUsePct}% of all pieces are single-use plastic.`
                  : "Composition will appear here once blocks have been surveyed."}
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------ method */}
      <section style={{ paddingTop: 0 }} id="method">
        <div className="wrap">
          <div className="prose">
            <p className="eyebrow">Method and limits</p>
            <h2 style={{ marginTop: 10 }}>Read this before citing anything</h2>

            <h3>Coverage is reported, never hidden</h3>
            <p>
              {stats.surveyedSegments} of {stats.segments} segments (
              {stats.coveragePct}%) have been surveyed at least once;{" "}
              {stats.unwalkedSegments} have never been walked. Hotspot ranking
              within the surveyed set is reliable. Neighbourhood totals should
              be read as lower bounds, because the unwalked segments contribute
              nothing to them.
            </p>

            <h3>Volunteer effort is biased, and the data shows it</h3>
            <p>
              People walk convenient, visible, already-popular blocks. We do not
              correct that bias away — we publish it, and the game is designed to
              counteract it by paying a bonus for the first survey of an unwalked
              segment. Treat survey count per segment as an exposure variable.
            </p>

            <h3>Mass is an estimate, not a weighing</h3>
            <p>
              Mass is derived from observed composition and per-material average
              weights rather than a scale, so it carries roughly a two-fold
              uncertainty band. Counts and composition are the primary
              measurements; mass is a convenience.
            </p>

            <h3>Geometry in this release</h3>
            <p>{stats.note}</p>

            <h3>Reuse and attribution</h3>
            <p>
              Licensed {stats.license}. You may redistribute and adapt the data,
              including commercially, with attribution. If you publish something
              built on it we would like to hear about it — it helps make the case
              for opening the next neighbourhood.
            </p>

            <p style={{ marginTop: 26 }}>
              <a className="btn btn-ghost" href="mailto:hello@trashtalk.app">
                Get in touch about a research use
              </a>
            </p>
          </div>
        </div>
      </section>
    </main>
  );
}
