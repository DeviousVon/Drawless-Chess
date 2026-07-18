import type { Metadata } from "next";
import {
  BETA_DOWNLOAD_URL,
  BETA_GROUP_URL,
  SiteFooter,
  SiteHeader,
  SOURCE_URL,
} from "./site-chrome";

export const metadata: Metadata = {
  title: "Every game has a winner",
  description:
    "Offline chess with decisive no-draw rules, seven illustrated opponents, and no ads or tracking.",
  alternates: { canonical: "/" },
};

const opponents = [
  { id: "learner", name: "Mira", level: "Learner" },
  { id: "casual", name: "Theo", level: "Casual" },
  { id: "challenger", name: "Rhea", level: "Challenger" },
  { id: "club", name: "Mateo", level: "Club" },
  { id: "expert", name: "Yuna", level: "Expert" },
  { id: "master", name: "Amara", level: "Master" },
  { id: "grandmaster", name: "Lucian", level: "Grandmaster" },
];

const themes = [
  { name: "Imperial Marble", className: "theme-marble" },
  { name: "Desert Sandstone", className: "theme-sandstone" },
  { name: "Glacier Slate", className: "theme-glacier" },
  { name: "Verdigris Copper", className: "theme-verdigris" },
  { name: "Amethyst Geode", className: "theme-geode" },
];

export default function Home() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <section className="hero section-shell" aria-labelledby="hero-title">
          <div className="hero-copy">
            <img
              className="hero-artwork"
              src="/media/hero-kings-1200.webp"
              srcSet="/media/hero-kings-640.webp 640w, /media/hero-kings-1200.webp 1200w"
              sizes="(max-width: 760px) calc(100vw - 28px), 560px"
              width="1200"
              height="630"
              alt=""
              loading="eager"
              fetchPriority="high"
              decoding="async"
            />
            <p className="eyebrow">Offline chess · Decisive by design</p>
            <h1 id="hero-title">Every game has a winner.</h1>
            <p className="hero-lede">
              Familiar chess, reworked to replace routine draws with decisive
              results. Play seven illustrated opponents completely offline—no
              account, ads, or tracking.
            </p>
            <div className="button-row">
              <a className="button button-primary" href="#beta">
                Join the beta
              </a>
              <a className="button button-secondary" href="#rules">
                How Drawless works
              </a>
            </div>
            <div className="trust-row" aria-label="Product highlights">
              <span>Android</span>
              <span>Single player</span>
              <span>Completely offline</span>
            </div>
            <p className="release-note">
              <span className="status-dot" aria-hidden="true" /> Closed Android
              beta now open
            </p>
          </div>

          <div className="hero-visual" aria-label="Drawless Chess gameplay preview">
            <div className="board-glow" aria-hidden="true" />
            <div className="phone-frame">
              <picture>
                <source
                  type="image/webp"
                  srcSet="/media/gameplay-360.webp 360w, /media/gameplay-720.webp 720w"
                  sizes="(max-width: 700px) 72vw, 360px"
                />
                <img
                  src="/media/gameplay-720.webp"
                  width="720"
                  height="1280"
                  alt="Drawless Chess game against Theo on the Imperial Marble board."
                  loading="lazy"
                  decoding="async"
                />
              </picture>
            </div>
            <div className="visual-tag visual-tag-top">Actual app screen</div>
            <div className="visual-tag visual-tag-bottom">
              <span aria-hidden="true">♟</span> Material decides after 50 moves
            </div>
          </div>
        </section>

        <section className="proof-strip" aria-label="Drawless Chess promise">
          <div className="section-shell proof-strip-inner">
            <span>Checkmate still wins</span>
            <span>Five board themes</span>
            <span>Seven opponent levels</span>
            <span>Games save locally</span>
          </div>
        </section>

        <section className="section section-shell beta-section" id="beta" aria-labelledby="beta-title">
          <div className="beta-panel">
            <div className="beta-copy">
              <p className="eyebrow">Closed Android beta</p>
              <h2 id="beta-title">Play it before launch.</h2>
              <p>
                Help shape Drawless Chess while the public release is in
                preparation. Beta access takes two quick steps.
              </p>
              <span className="beta-badge">Temporary beta access</span>
            </div>
            <div className="beta-flow">
              <ol className="beta-steps">
                <li className="beta-step">
                  <span className="beta-step-number" aria-hidden="true">01</span>
                  <h3>Join the testing group</h3>
                  <p>
                    Sign in with the Google account you use in the Play Store,
                    then join the Drawless Chess Testers group.
                  </p>
                  <a
                    className="button button-primary"
                    href={BETA_GROUP_URL}
                    target="_blank"
                    rel="noreferrer"
                    aria-label="Join the testing group (opens in a new tab)"
                  >
                    Join the testing group <span aria-hidden="true">↗</span>
                  </a>
                </li>
                <li className="beta-step">
                  <span className="beta-step-number" aria-hidden="true">02</span>
                  <h3>Install from Google Play</h3>
                  <p>
                    After joining, open the private Play listing with that same
                    account and install the beta.
                  </p>
                  <a
                    className="button button-gold"
                    href={BETA_DOWNLOAD_URL}
                    target="_blank"
                    rel="noreferrer"
                    aria-label="Download the beta from Google Play (opens in a new tab)"
                  >
                    Download the beta <span aria-hidden="true">↗</span>
                  </a>
                </li>
              </ol>
              <p className="beta-account-note">
                Use the same Google account for both steps. Play access may take
                a few minutes to appear after joining.
              </p>
            </div>
          </div>
        </section>

        <section className="section section-shell" id="rules" aria-labelledby="rules-title">
          <div className="section-heading">
            <p className="eyebrow">The Drawless rules</p>
            <h2 id="rules-title">Familiar chess. Decisive endings.</h2>
            <p>
              The board and pieces are familiar. Drawless rules change what
              happens when ordinary chess would stop without a winner.
            </p>
          </div>
          <div className="rule-grid">
            <article className="rule-card">
              <span className="rule-number" aria-hidden="true">01</span>
              <h3>Stalemate loses</h3>
              <p>Under the default rule, a player with no legal move loses.</p>
            </article>
            <article className="rule-card">
              <span className="rule-number" aria-hidden="true">02</span>
              <h3>Third repetition loses</h3>
              <p>
                Cause the same position a third time and you lose—unless every
                legal move repeats.
              </p>
            </article>
            <article className="rule-card">
              <span className="rule-number" aria-hidden="true">03</span>
              <h3>Dead positions decide</h3>
              <p>
                When checkmate becomes impossible, the selected dead-position
                rule awards the game.
              </p>
            </article>
            <article className="rule-card">
              <span className="rule-number" aria-hidden="true">04</span>
              <h3>Bare king loses</h3>
              <p>A player left with only a king loses immediately.</p>
            </article>
          </div>
          <p className="rules-footnote">
            After 50 moves without a pawn move or capture, material points decide the winner.
          </p>
        </section>

        <section className="section section-muted" id="features" aria-labelledby="features-title">
          <div className="section-shell split-heading">
            <div>
              <p className="eyebrow">Built for real games</p>
              <h2 id="features-title">Start quickly. Stay focused.</h2>
            </div>
            <p>
              Quick Play gets you to the board in one tap. Custom games let you
              choose your side, opponent, rules, and clock.
            </p>
          </div>
          <div className="section-shell feature-grid">
            <article>
              <span className="feature-mark" aria-hidden="true">A</span>
              <h3>Play your way</h3>
              <p>Untimed, 3-, 5-, or 10-minute games, plus a 15+10 control.</p>
            </article>
            <article>
              <span className="feature-mark" aria-hidden="true">B</span>
              <h3>See the whole game</h3>
              <p>Legal moves, captures, material, history, and optional threats.</p>
            </article>
            <article>
              <span className="feature-mark" aria-hidden="true">C</span>
              <h3>Keep your momentum</h3>
              <p>Resume, hints, undo, rematches, local records, and streaks.</p>
            </article>
          </div>
        </section>

        <section className="section section-shell opponents-section" id="opponents" aria-labelledby="opponents-title">
          <div className="split-heading">
            <div>
              <p className="eyebrow">Seven opponents</p>
              <h2 id="opponents-title">From Learner to Grandmaster.</h2>
            </div>
            <p>
              Each illustrated opponent has a distinct playing strength and
              personality. Level names are descriptive—not Elo claims.
            </p>
          </div>
          <ul className="opponent-grid" aria-label="Drawless Chess opponents">
            {opponents.map((opponent) => (
              <li key={opponent.id} className="opponent-card">
                <img
                  src={`/media/opponents/${opponent.id}-256.webp`}
                  width="256"
                  height="256"
                  loading="lazy"
                  decoding="async"
                  alt=""
                />
                <div>
                  <strong>{opponent.name}</strong>
                  <span>{opponent.level}</span>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="section themes-section" id="themes" aria-labelledby="themes-title">
          <div className="section-shell themes-layout">
            <div className="themes-copy">
              <p className="eyebrow">Five themes</p>
              <h2 id="themes-title">Make the board yours.</h2>
              <p>
                Move from veined marble to warm sandstone, cool slate, aged
                copper, or amethyst crystal. Your choice is remembered on your device.
              </p>
              <ul className="theme-list">
                {themes.map((theme) => (
                  <li key={theme.name}>
                    <span className={`theme-swatch ${theme.className}`} aria-hidden="true" />
                    {theme.name}
                  </li>
                ))}
              </ul>
            </div>
            <div className="theme-preview">
              <picture>
                <source
                  type="image/webp"
                  srcSet="/media/themes-360.webp 360w, /media/themes-720.webp 720w"
                  sizes="(max-width: 760px) 78vw, 370px"
                />
                <img
                  src="/media/themes-720.webp"
                  width="720"
                  height="1280"
                  loading="lazy"
                  decoding="async"
                  alt="Theme picker showing five Drawless Chess board styles."
                />
              </picture>
            </div>
          </div>
        </section>

        <section className="section section-shell" id="privacy" aria-labelledby="privacy-title">
          <div className="privacy-panel">
            <div className="privacy-copy">
              <p className="eyebrow">Private by design</p>
              <h2 id="privacy-title">Your game stays your game.</h2>
              <p>
                No account. No ads. No analytics or tracking. Drawless Chess
                does not request Android internet, location, camera, microphone,
                contacts, or shared-storage permissions.
              </p>
              <a className="text-link" href="/privacy/">Read the privacy policy</a>
            </div>
            <div className="privacy-points" aria-label="Privacy highlights">
              <span>No account</span>
              <span>No ads</span>
              <span>No tracking</span>
              <span>No internet permission</span>
            </div>
          </div>
        </section>

        <section className="section section-shell open-source-panel" id="open-source" aria-labelledby="source-title">
          <div>
            <p className="eyebrow">Open source</p>
            <h2 id="source-title">Built in the open.</h2>
          </div>
          <div>
            <p>
              Drawless Chess is licensed under GNU GPL version 3 or later.
              Explore the project, its license, and third-party notices.
            </p>
            <div className="inline-links">
              <a className="text-link" href={SOURCE_URL}>GitHub project</a>
              <a className="text-link" href="/open-source/">License details</a>
            </div>
          </div>
        </section>

        <section className="section section-shell final-cta" aria-labelledby="cta-title">
          <p className="eyebrow">Drawless Chess for Android</p>
          <h2 id="cta-title">Ready when the position isn’t.</h2>
          <p>
            The public release is still in preparation, but the closed beta is
            open now. Join with the Google account you use in the Play Store.
          </p>
          <div className="button-row button-row-center">
            <a className="button button-primary" href="#beta">Join the beta</a>
            <a className="button button-secondary" href={SOURCE_URL}>View the source</a>
          </div>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
