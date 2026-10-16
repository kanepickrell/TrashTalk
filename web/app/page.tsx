import stats from "@/public/stats.json";

const MODES = [
  {
    name: "Survey",
    accent: "var(--crew-1)",
    body:
      "Walk a block and record what is there. Surveys are how the map gets " +
      "built, and the blocks nobody has walked are worth the most.",
  },
  {
    name: "Collect",
    accent: "var(--crew-2)",
    body:
      "Pick it up and log it. Collections are how the map gets better, and " +
      "how a segment stays clean enough to hold.",
  },
];

const FEATURES = [
  {
    title: "Block battles",
    body:
      "Take a segment somebody else is holding and it becomes head-to-head. " +
      "Keep it under the moderate threshold for a week and it is yours.",
  },
  {
    title: "Season standings",
    body:
      "Ground held converts to season points for you and your crew. The " +
      "longer you hold a block, the more it is worth.",
  },
  {
    title: "Works without signal",
    body:
      "The map and the survey form work offline, because the blocks most " +
      "worth walking are often the ones with the worst coverage.",
  },
  {
    title: "Capture territory",
    body:
      "Adopt a segment and it is painted in your crew's colour. Hold adjacent " +
      "blocks and your territory joins into one contiguous patch.",
  },
  {
    title: "Play together",
    body:
      "Go solo on the neighbourhood map, or claim ground for a crew. Four " +
      "paths mean your contribution counts even if you never top a board.",
  },
  {
    title: "Track your impact",
    body:
      "Pieces, estimated mass, composition by material, streaks and coverage. " +
      "Your numbers, and the neighbourhood's, in the same units.",
  },
];

const PATHS = [
  {
    name: "Scientist",
    mascot: "Mycelium Mushroom",
    art: "/paths/scientist.svg",
    accent: "var(--crew-1)",
    tagline: "Find the pattern first",
    body:
      "Survey the blocks nobody walks. Guess where litter concentrates, then " +
      "prove it with counts. Your evidence is what turns a complaint into a case.",
    badges: ["Insight", "Curiosity", "Exploration", "Litter analysis", "Calibration"],
  },
  {
    name: "Eco-warrior",
    mascot: "Dung Beetle",
    art: "/paths/warrior.svg",
    accent: "var(--crew-2)",
    tagline: "Hold the line",
    body:
      "Out-collect everyone, then keep the gains. Chase records, defend the " +
      "segments you have adopted, set the pace your crew measures itself against.",
    badges: ["City leader", "Team leader", "Protect the flag", "Weekend warrior", "Raise the bar"],
  },
  {
    name: "Advocate",
    mascot: "Great-tailed Grackle",
    art: "/paths/advocate.svg",
    accent: "var(--crew-3)",
    tagline: "Turn evidence into demand",
    body:
      "Carry the story outward. Recruit, teach, share the numbers, and make " +
      "local businesses hear that single-use packaging costs them customers.",
    badges: ["Sharing", "Recruiting", "Education", "Motivation", "Inspiration"],
  },
  {
    name: "Seeker",
    mascot: "Forager Ant",
    art: "/paths/seeker.svg",
    accent: "var(--crew-4)",
    tagline: "Go further, every day",
    body:
      "Cover ground relentlessly. Streaks, distance, personal records — you are " +
      "the reason coverage reaches blocks the crowd never visits.",
    badges: ["Long distance", "Personal record", "Streak", "Longest streak", "Diligence"],
  },
];

const FAQ = [
  {
    q: "How much does TrashTalk cost?",
    a:
      "Free, with no subscription. The dataset it produces is free too — CC BY " +
      "4.0, no sign-up, no API key. The project exists to make this data " +
      "available, so putting it behind a paywall would defeat the point.",
  },
  {
    q: "I am not sure I care about litter that much. Should I try it?",
    a:
      "Yes. TrashTalk turns a walk into a game: claim a block, hold it against " +
      "your neighbours, watch your territory grow on the map. You will learn " +
      "where the litter in your own neighbourhood actually comes from, which " +
      "is more interesting than it sounds.",
  },
  {
    q: "Do I need to join a crew or commit to anything?",
    a:
      "No. Pick up one wrapper, log it, and you are in. Crews, streaks and " +
      "territory are there for people who want the competition; the dataset " +
      "benefits either way.",
  },
  {
    q: "Why does a plastic bag score more points than a newspaper?",
    a:
      "Points follow harm rather than mass. A bag photodegrades into particles " +
      "that carry pollutants and that animals mistake for food; newsprint " +
      "biodegrades. An aluminium can — genuinely recyclable — scores lowest. " +
      "The scoring is an argument about what actually matters.",
  },
  {
    q: "How is holding territory different from just cleaning up?",
    a:
      "Anyone can clear a block once. Holding it means it stays under the " +
      "moderate threshold for a full week, verified by whoever walks it next. " +
      "That is a much harder thing to do, so it is what the game rewards.",
  },
  {
    q: "Can I run TrashTalk in my own neighbourhood?",
    a:
      "That is the intent. The survey network is a GeoJSON file the app loads; " +
      "swap in an Overpass export of your streets and the whole system follows.",
  },
  {
    q: "What happens to my location data?",
    a:
      "A pickup records the segment you were on and the time. You can plant " +
      "flags and adopt segments anonymously, and the published dataset contains " +
      "segments and counts, never individual tracks.",
  },
];

function StoreButtons() {
  return (
    <div className="btn-row">
      <span className="btn btn-primary store" aria-disabled="true">
        <b>Android</b>
        <small>In field testing</small>
      </span>
      <span className="btn btn-ghost store" aria-disabled="true">
        <b>iOS</b>
        <small>Planned</small>
      </span>
    </div>
  );
}

export default function Home() {
  return (
    <main>
      {/* ------------------------------------------------------------ hero */}
      <header className="hero">
        <div className="wrap">
          <div className="hero-copy">
            <p className="eyebrow">Terrell Heights, San Antonio · pilot neighbourhood</p>
            <h1>
              Claim your blocks.
              <br />
              <em>Prove it with data.</em>
            </h1>
            <p className="lede">
              Turn the walk you already take into healthy competition. Claim
              streets, defend them from your neighbours, and climb the
              leaderboard — while every pickup you log becomes open research
              data on where litter actually lands.
            </p>
            <div className="btn-row" style={{ marginTop: 26 }}>
              <a className="btn btn-primary" href="#game">
                See how it works
              </a>
              <a className="btn btn-ghost" href="/data">
                Explore the dataset
              </a>
            </div>
          </div>

          <figure className="hero-map" style={{ margin: 0 }}>
            <img
              src="/hero-density.svg"
              alt={`Litter density across ${stats.segments} street segments in ${stats.neighborhood}, with arterials and commercial frontages glowing brightest`}
            />
            <figcaption className="map-caption">
              Live litter density across {stats.segments} surveyed segments.
              Brighter means more on the ground right now.
            </figcaption>
          </figure>

          <div className="stats">
            <div className="stat">
              <b className="tnum">{stats.segments}</b>
              <span>street segments mapped</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.networkKm} km</b>
              <span>of network surveyed</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.pieces.toLocaleString()}</b>
              <span>pieces currently on the ground</span>
            </div>
            <div className="stat">
              <b className="tnum" style={{ color: "var(--serious)" }}>
                {stats.singleUsePct}%
              </b>
              <span>of it single-use plastic</span>
            </div>
          </div>
        </div>
      </header>

      {/* ------------------------------------------------- two ways to play */}
      <section id="modes">
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Two ways to play</p>
            <h2>Survey or collect, play your way.</h2>
            <p className="body">
              One game, two ways to play it. Surveying and collecting each have
              their own board, so every contest is fair — the person mapping
              unwalked streets competes with mappers, the person hauling bags
              competes with haulers.
            </p>
          </div>
          <div className="grid-2">
            {MODES.map((m) => (
              <article
                className="path-card"
                key={m.name}
                style={{ ["--accent" as string]: m.accent }}
              >
                <h3>{m.name}</h3>
                <p style={{ color: "var(--ink-2)", marginTop: 8 }}>{m.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- compete */}
      <section id="compete" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">The standings</p>
            <h2>Compete for your neighbourhood.</h2>
          </div>
          <div className="stats" style={{ marginTop: 0 }}>
            <div className="stat">
              <b className="tnum">{stats.heldSegments}</b>
              <span>blocks currently held</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.crews.length}</b>
              <span>crews in play</span>
            </div>
            <div className="stat">
              <b className="tnum">{stats.unwalkedSegments}</b>
              <span>blocks still unclaimed</span>
            </div>
            <div className="stat">
              <b className="tnum">20</b>
              <span>badges, five tiers each</span>
            </div>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------- the game */}
      <section id="game" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">How the game works</p>
            <h2>Three steps, and the third is the hard one.</h2>
          </div>

          <div className="grid-3">
            <article className="card">
              <span className="step-n">1</span>
              <h3>Walk a block — every metre counts</h3>
              <p>
                The map is a field of street segments, not a scatter of pins.
                Dashed ones have never been surveyed by anybody —{" "}
                {stats.unwalkedSegments} of them right now — and walking one
                first pays a coverage bonus.
              </p>
            </article>
            <article className="card">
              <span className="step-n">2</span>
              <h3>Your route becomes your territory</h3>
              <p>
                Log what you picked up and the segment is claimed in your
                crew&rsquo;s colour. Link adjacent blocks to grow one contiguous
                patch of ground you control.
              </p>
            </article>
            <article className="card">
              <span className="step-n">3</span>
              <h3>Defend your turf, steal theirs</h3>
              <p>
                Territory is never safe. Let a block slip back to moderate and
                your run resets — and somebody else can take it. Every claim
                moves the leaderboard, from your street to the whole city.
              </p>
            </article>
          </div>
        </div>
      </section>

      {/* -------------------------------------------------------- territory */}
      <section id="territory" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="grid-2">
            <div>
              <p className="eyebrow">Territory</p>
              <h2 style={{ marginBlock: "10px 16px" }}>
                {stats.heldSegments} blocks are currently held
              </h2>
              <p className="body">
                Every adopted segment is painted in its crew&rsquo;s colour.
                Hold adjacent blocks and your territory joins into a contiguous
                patch; let one slip and somebody else takes it. The map is a
                standings table you can walk through.
              </p>
              <p className="body" style={{ marginTop: 14 }}>
                The remaining {stats.unwalkedSegments} segments are still dark.
                Nobody has walked them, so nobody knows what is there — which is
                exactly the gap the Scientist path exists to close.
              </p>
              <div className="crew-list">
                {stats.crews.map((c) => (
                  <span className="crew" key={c.name}>
                    <i className="dot" style={{ background: c.color }} />
                    {c.name}
                  </span>
                ))}
              </div>
            </div>
            <figure className="territory-map" style={{ margin: 0 }}>
              <img
                src="/hero-territory.svg"
                alt="The same street network coloured by which neighbourhood crew currently holds each block"
              />
            </figure>
          </div>
        </div>
      </section>

      {/* -------------------------------------------------------- features */}
      <section id="features" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Master the game</p>
            <h2>Battles, seasons and the blocks in between.</h2>
          </div>
          <div className="grid-3">
            {FEATURES.map((f) => (
              <article className="card" key={f.title}>
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------ paths */}
      <section id="paths" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Four paths · twenty badges · no level cap</p>
            <h2>Pick the role you will actually play.</h2>
            <p className="body">
              Nature&rsquo;s cleanup crew: a fungus that decomposes, a beetle
              that recycles, a bird that broadcasts, an ant that forages. Each
              path is an organism that already does this job — which is the
              whole argument of level three, wearing a face.
            </p>
            <p className="body" style={{ marginTop: 14 }}>
              After the seven learning levels you choose one. Each has five
              badges, each badge five repeatable tiers, and five banked tiers
              raise your level. There is no maximum.
            </p>
          </div>

          <div className="grid-paths">
            {PATHS.map((p) => (
              <article
                className="path-card"
                key={p.name}
                style={{ ["--accent" as string]: p.accent }}
              >
                <div className="path-head">
                  <img className="path-art" src={p.art} alt="" width={96} height={96} />
                  <div>
                    <p className="eyebrow">{p.tagline}</p>
                    <h3>{p.name}</h3>
                    <p className="small" style={{ marginTop: 2 }}>{p.mascot}</p>
                  </div>
                </div>
                <p style={{ color: "var(--ink-2)", fontSize: "0.97rem", marginTop: 12 }}>
                  {p.body}
                </p>
                <div className="badge-row">
                  {p.badges.map((b) => (
                    <span className="badge-pill" key={b}>
                      {b}
                    </span>
                  ))}
                </div>
              </article>
            ))}
          </div>

          <div style={{ marginTop: 22 }} className="grid-2">
            <div className="mech">
              <span className="mech-tag">M1</span>
              <div>
                <strong>Seekers + Eco-warriors compete.</strong>{" "}
                <span className="small">
                  Both chase output, so between them they generate records,
                  rivalry and sheer volume.
                </span>
              </div>
            </div>
            <div className="mech">
              <span className="mech-tag">M2</span>
              <div>
                <strong>Scientists + Advocates collaborate.</strong>{" "}
                <span className="small">
                  One produces the evidence, the other turns it into recruitment
                  and pressure. M2 fuels M1; M1 feeds M2 better data.
                </span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ----------------------------------------------------------- learn */}
      <section id="learn" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Education comes first</p>
            <h2>Seven levels before you pick a side.</h2>
            <p className="body">
              TrashTalk opens with an argument, not a tutorial. Litter is the
              visible end of a linear materials economy — and &ldquo;garbage&rdquo;
              is a human invention about seventy years old, which means it is
              young enough to end. You cannot skip it, because a player who has
              not read it does not know what they are choosing.
            </p>
          </div>

          <div className="grid-3">
            <article className="card">
              <p className="eyebrow">Levels 1–3</p>
              <h3 style={{ marginTop: 8 }}>Why it matters</h3>
              <p>
                Roughly 8 million tonnes of plastic reach the ocean each year,
                most of it beginning as ordinary land litter. A bag does not rot;
                it photodegrades into particles that carry pollutants and that
                animals eat on purpose.
              </p>
            </article>
            <article className="card">
              <p className="eyebrow">Level 4</p>
              <h3 style={{ marginTop: 8 }}>The alternatives, honestly</h3>
              <p>
                Biodegradable, compostable, recyclable — three words doing a lot
                of reassuring. Aluminium and glass genuinely cycle; most plastics
                downcycle until they are waste.
              </p>
            </article>
            <article className="card">
              <p className="eyebrow">Levels 5–7</p>
              <h3 style={{ marginTop: 8 }}>What would fix it</h3>
              <p>
                Stop treating waste as the end of a line and start treating it as
                a stage in a cycle. Then create the local demand that lets
                businesses change — which takes evidence.
              </p>
            </article>
          </div>

          <div className="btn-row" style={{ marginTop: 24 }}>
            <a className="btn btn-ghost" href="/learn">
              Read all seven levels
            </a>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------------- research */}
      <section id="research" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="grid-2">
            <div>
              <p className="eyebrow">For researchers and cities</p>
              <h2 style={{ marginBlock: "10px 16px" }}>
                Street-level litter data, openly licensed
              </h2>
              <p className="body">
                Litter research is usually starved of spatial resolution:
                surveys are expensive, infrequent, and reported as
                neighbourhood averages. A gamified survey network produces the
                opposite — repeated observations at segment level, with
                composition by material and a daily time series.
              </p>
              <p className="body" style={{ marginTop: 14 }}>
                Every release carries its coverage and confidence up front. A
                confident number over {stats.coveragePct}% of the network is a
                more useful input than a precise-looking number over a tenth of
                it, and the reader is entitled to know which one they have.
              </p>
              <div className="btn-row" style={{ marginTop: 22 }}>
                <a className="btn btn-primary" href="/data">
                  Open data portal
                </a>
                <a className="btn btn-ghost" href="/data/terrell-heights-segments.csv">
                  Download CSV
                </a>
              </div>
            </div>

            <div className="card">
              <p className="eyebrow">What the pilot measured</p>
              <h3 style={{ marginBlock: "8px 16px" }}>Composition by count</h3>
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
              <p className="small" style={{ marginTop: 16 }}>
                Shares by piece count, weighted by each segment&rsquo;s estimated
                load. {stats.singleUsePct}% of all pieces are single-use plastic.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- faq */}
      <section id="faq" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="section-head">
            <p className="eyebrow">Questions</p>
            <h2>Common questions.</h2>
          </div>
          <div>
            {FAQ.map((f) => (
              <details className="faq" key={f.q}>
                <summary>{f.q}</summary>
                <p>{f.a}</p>
              </details>
            ))}
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- cta */}
      <section id="get" style={{ paddingTop: 0 }}>
        <div className="wrap">
          <div className="cta">
            <p className="eyebrow">Start with one block</p>
            <h2 style={{ marginBlock: "12px 16px", maxWidth: "20ch" }}>
              Your street is already on the map.
            </h2>
            <p className="body">
              {stats.unwalkedSegments} segments in {stats.neighborhood} have
              never been surveyed by anyone. Walking one of them is the single
              most useful thing a new player can do — for the leaderboard and
              for the dataset.
            </p>
            <div style={{ marginTop: 26 }}>
              <StoreButtons />
            </div>
            <div className="btn-row" style={{ marginTop: 14 }}>
              <a className="btn btn-ghost" href="/data">
                Explore the data
              </a>
              <a className="btn btn-ghost" href="/learn">
                Start the seven levels
              </a>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
