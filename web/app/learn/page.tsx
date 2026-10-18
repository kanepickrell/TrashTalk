import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "The seven levels",
  description:
    "TrashTalk's seven-level case: why street litter matters, how garbage " +
    "became a human invention, why biodegradable and recycling mostly do not " +
    "work, and what would actually fix it.",
};

type Level = {
  n: number;
  title: string;
  question: string;
  standfirst: string;
  body: string[];
  stats?: { value: string; caption: string; source: string }[];
};

const LEVELS: Level[] = [
  {
    n: 1,
    title: "Why street litter is not a small problem",
    question: "Why is street litter such a problem?",
    standfirst:
      "A wrapper on a kerb is not where the story ends. It is where it starts, " +
      "and the street is the last place anyone can still stop it.",
    body: [
      "Storm drains are not filters — they are a delivery system. What sits on a kerb travels through a drain, into a creek, down a river and out to sea. Roughly 8 million tonnes of plastic and other trash reach the ocean every year, and the majority of marine debris begins on land as ordinary mismanaged litter.",
      "A banana peel is consumed by a crowd of hungry microbes. A plastic bag has no such predator. It photodegrades: sunlight breaks it into smaller and smaller fragments, and those fragments are deadly when swallowed. They also carry pollutants, including PCBs and endocrine disrupters. Worse, they look like food — drifting film resembles jellyfish and zooplankton, and animals eat it on purpose.",
      "The story does not end with a dead jellyfish. After damaging the food web, that material can come back to the table: a UNEP and WHO review identified more than 800 man-made chemicals, bisphenol A among them, in products and materials people encounter daily.",
    ],
    stats: [
      { value: "8M tonnes", caption: "plastic and trash entering the ocean each year", source: "UNEP" },
      { value: "~60%", caption: "of ocean pollution originating as human litter", source: "UNEP" },
      { value: "800+", caption: "man-made chemicals found in everyday products", source: "UNEP / WHO" },
    ],
  },
  {
    n: 2,
    title: "How we got here",
    question: "How did we get here?",
    standfirst:
      "Nobody decided to build a disposable society. It was assembled out of a " +
      "thousand small conveniences, each reasonable on its own.",
    body: [
      "Single-use packaging solved real problems: it kept food safe for longer, cut shipping weight, lowered prices and freed people from washing up. Convenience and affordability are not stupid things to want. The cost was simply moved somewhere nobody was looking — into the future, and onto the kerb.",
      "Somewhere along the way the package stopped being how goods travelled and became part of what was sold: brighter, lighter, more layers, more laminates. Each improvement made the package better at marketing and worse at ever being anything again.",
      "Once disposability is assumed, litter stops being an anomaly and becomes a running cost — absorbed by city budgets, volunteers, and the places furthest from anyone with influence. Litter is the predictable output of a system, and it lands unevenly.",
    ],
  },
  {
    n: 3,
    title: "What garbage actually is",
    question: "What is garbage?",
    standfirst:
      "Garbage does not exist in nature. It is a human invention, and a " +
      "surprisingly recent one.",
    body: [
      "Waste in a functioning ecosystem is food, shelter or soil for something else. A fallen tree is an apartment block. Nothing accumulates, because nothing is produced that nothing else can use.",
      "Human-made materials severed that harmony by arriving faster than anything evolved to eat them. In nature all outputs are useful — a wisdom worth echoing, but not in the innocent ignorance that leads unwitting creatures to eat inedible trash.",
      "Most accounts put the modern concept of garbage in the 1950s, with single-use packaging and cheap polymers. Before that, materials were too valuable to throw away. Garbage is about seventy years old, which means it is young enough to end.",
    ],
    stats: [
      { value: "Zero", caption: "waste produced by a closed natural system", source: "Ecology" },
      { value: "~70 yrs", caption: "age of garbage as a mass phenomenon", source: "Historical" },
    ],
  },
  {
    n: 4,
    title: "The alternatives, honestly assessed",
    question: "Are there alternatives?",
    standfirst:
      "Biodegradable, compostable, recyclable. Three words doing an enormous " +
      "amount of reassuring and not much else.",
    body: [
      "Everything biodegrades eventually; the label omits the two facts that matter — how long, and under what conditions. A plastic certified biodegradable in a 58 °C industrial vessel behaves like ordinary plastic in a gutter.",
      "Compostable packaging is a real improvement where industrial composting exists, accepts it, and the item actually gets there. Absent that chain it contaminates recycling streams, confuses people acting in good faith, and still litters. The infrastructure is the product; the cup is just a cup.",
      "Recycling genuinely works for a few materials — aluminium and glass can cycle almost indefinitely, and that is worth defending. Plastics mostly downcycle: each pass shortens the polymer chains, so a bottle becomes fibre, then filler, then waste. Recycling is a backstop that was sold as an absolution.",
      "Which leaves the only reliable move: not generating the material in the first place. Every alternative above makes disposal less bad. The one intervention that always works is the item that was never made.",
    ],
    stats: [
      { value: "Infinite", caption: "practical recycling passes for aluminium and glass", source: "Materials science" },
      { value: "Downcycle", caption: "what most plastic recycling actually does", source: "Materials science" },
    ],
  },
  {
    n: 5,
    title: "A plan for the future",
    question: "What would actually fix this?",
    standfirst:
      "To outsmart the garbage crisis we have to eliminate the idea of waste — " +
      "not improve how we bury it.",
    body: [
      "Imagine our needs shifted just slightly: we look for products not only for their contents but for the experience of owning them. Packaging designed for quality and longevity, that ages beautifully instead of becoming embarrassing the moment the contents are gone. A jar worth keeping is not packaging; it is an object.",
      "Instead of seeing waste purely as a negative — a useless by-product we spend money to burn or bury — we can see it as an inherently valuable combination of materials that can be processed into objects with specific purposes. Nothing changes until the balance sheet agrees that a discarded material is an asset.",
      "The key is to see our outputs not as problems but as assets; waste not as the end of a linear process but as a stage in a circular life cycle. Everything else — deposit schemes, refill systems, producer responsibility, repair rights — follows from that one shift.",
    ],
  },
  {
    n: 6,
    title: "How we get there",
    question: "How does a neighbourhood actually change?",
    standfirst:
      "We can have cleaner communities today. The constraint is not technology. " +
      "It is demand.",
    body: [
      "Every day we vote with our dollars for a society of convenience and single-use goods. That is not an accusation, it is leverage: the mechanism runs in reverse. Unless local businesses know we want sustainable products, they cannot change their model — and most will not gamble a thin margin on a preference nobody has stated out loud.",
      "Ask for it where it is heard: at the counter, in the review, at the neighbourhood association, in the email to the council member. Reduce the single-use packaging in your own life so the ask is credible. A request from one customer is noise; the same request from forty is a roadmap.",
      "This is where the map earns its keep. “There is litter around here” is an opinion. A Litter Impact Assessment reads, for example, “this corridor averages 31 pieces per 100 metres, 64% single-use plastic, concentrated within 150 metres of three retail frontages” — figures illustrative, and very hard to argue with once they are yours. Scientists produce it; Advocates deliver it.",
    ],
  },
  {
    n: 7,
    title: "Join the movement",
    question: "Where do you fit?",
    standfirst:
      "You have the argument. Now pick the role you will actually play in it — " +
      "and keep levelling, indefinitely.",
    body: [
      "Four paths — Scientist, Eco-warrior, Advocate, Seeker — each with five badges, each badge with five repeatable tiers. Bank five tiers and your path level goes up. There is no maximum.",
      "The paths are an engine, not a costume. Seekers and Eco-warriors engage each other by nature: both compete on output, so together they generate records, rivalry and volume. Scientists and Advocates work with each other by nature: one produces evidence, the other turns it into recruitment and pressure.",
      "The goal: virtually eliminate street litter in Terrell Heights, produce geospatial maps and Litter Impact Assessments that governments, companies and neighbourhoods can act on, give clean-up crews a way to recruit people who have never heard the phrase “circular economy”, and give researchers a dataset on how waste actually lands across a city. Then do it in the next neighbourhood.",
    ],
  },
];

export default function Learn() {
  return (
    <main>
      <section style={{ paddingBottom: 40 }}>
        <div className="wrap">
          <p className="eyebrow">The seven levels</p>
          <h1 style={{ marginBlock: "14px 20px", maxWidth: "16ch" }}>
            The case, in order
          </h1>
          <p className="lede">
            These seven levels open the app, and they run before anyone picks a
            path. They argue a single case: litter is the visible end of a
            linear materials economy, the usual escapes mostly do not work, and
            the way out is to stop producing waste rather than to manage it
            better.
          </p>
        </div>
      </section>

      <div className="wrap">
        <div className="prose" style={{ paddingBottom: 80 }}>
          {LEVELS.map((l) => (
            <article key={l.n} id={`level-${l.n}`}>
              <h2>
                <span className="level-no">Level {l.n}.</span> {l.title}
              </h2>
              <p style={{ color: "var(--teal)", marginTop: 12 }}>{l.standfirst}</p>
              {l.body.map((para, i) => (
                <p key={i}>{para}</p>
              ))}

              {l.stats && (
                <div className="stats" style={{ marginTop: 24 }}>
                  {l.stats.map((s) => (
                    <div className="stat" key={s.value}>
                      <b style={{ color: "var(--amber)", fontSize: "1.4rem" }}>
                        {s.value}
                      </b>
                      <span>{s.caption}</span>
                      <span className="source">{s.source}</span>
                    </div>
                  ))}
                </div>
              )}
            </article>
          ))}

          <h2>Then you choose</h2>
          <p>
            Level seven ends with a choice of path, and the paths never cap.
            Five banked badge tiers raise your level, the badges repeat, and the
            neighbourhood keeps generating new ground to cover.
          </p>
          <p style={{ marginTop: 20 }}>
            <a className="btn btn-primary" href="/data">
              See what the surveys produced
            </a>
          </p>

          <p className="small" style={{ marginTop: 40 }}>
            Figures are attributed inline. Where a number is a projection or an
            estimate it says so — an advocate who overstates the case loses the
            argument the first time somebody checks.
          </p>
        </div>
      </div>
    </main>
  );
}
