import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://trashtalk.vercel.app"),
  title: {
    default: "TrashTalk — claim your blocks",
    template: "%s — TrashTalk",
  },
  description:
    "TrashTalk turns neighbourhood litter cleanup into territory you hold. " +
    "Survey a block, log what you pick up, keep it clean — and generate open " +
    "street-level litter data that researchers and cities can actually use.",
  openGraph: {
    title: "TrashTalk — claim your blocks",
    description:
      "A gamified street-litter survey. Hold territory, climb the leaderboard, " +
      "and build an open dataset on where litter actually lands.",
    type: "website",
    images: ["/hero-density.svg"],
  },
  icons: { icon: "/mark.png", apple: "/mark.png" },
};

function Nav() {
  return (
    <nav className="nav">
      <div className="wrap nav-inner">
        <a className="brand" href="/">
          <img src="/mark.png" alt="" width={30} height={30} />
          TrashTalk
        </a>
        <div className="nav-links">
          <a href="/#game">The game</a>
          <a href="/#paths">Paths</a>
          <a href="/learn">Learn</a>
          <a href="/data">Open data</a>
          <a className="btn btn-primary" href="/#get">
            Get the app
          </a>
        </div>
      </div>
    </nav>
  );
}

function Footer() {
  return (
    <footer>
      <div className="wrap">
        <div className="foot-grid">
          <div>
            <h4>TrashTalk</h4>
            <a href="/#game">How the game works</a>
            <a href="/#territory">Territory</a>
            <a href="/#paths">The four paths</a>
            <a href="/#get">Get the app</a>
          </div>
          <div>
            <h4>Learn</h4>
            <a href="/learn">The seven levels</a>
            <a href="/learn#level-4">Recycling, honestly</a>
            <a href="/learn#level-5">The circular case</a>
          </div>
          <div>
            <h4>Research</h4>
            <a href="/data">Open dataset</a>
            <a href="/data#schema">Schema</a>
            <a href="/data#method">Method &amp; limits</a>
            <a href="/data/terrell-heights-segments.csv">Download CSV</a>
          </div>
          <div>
            <h4>Project</h4>
            <a href="/data#method">Methodology</a>
            <a href="mailto:hello@trashtalk.app">Contact</a>
          </div>
        </div>

        <p className="note">
          TrashTalk is an independent neighbourhood project. The pilot dataset
          covers Terrell Heights, San Antonio, Texas. Survey geometry in this
          release is representative rather than field-surveyed — see the method
          notes before citing any figure. Data published under CC BY 4.0.
        </p>
      </div>
    </footer>
  );
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <Nav />
        {children}
        <Footer />
      </body>
    </html>
  );
}
