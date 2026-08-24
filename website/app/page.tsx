import type { Metadata } from "next";
import {
  APP_STORE_URL,
  GOOGLE_PLAY_URL,
  SiteFooter,
  SiteHeader,
  SOURCE_URL,
} from "./site-chrome";

export const metadata: Metadata = {
  title: "Offline Chess for Android & iOS",
  description:
    "Play decisive offline chess against eight on-device opponents on Android, iPhone, and iPad. Android also includes private, on-device Game Review.",
  alternates: { canonical: "/" },
};

const applicationStructuredData = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: "Drawless Chess",
  applicationCategory: "GameApplication",
  operatingSystem: "Android, iOS, iPadOS",
  description:
    "Decisive offline chess with eight on-device opponents on Android, iPhone, and iPad. Android also includes private post-game review.",
  downloadUrl: [GOOGLE_PLAY_URL, APP_STORE_URL],
  offers: {
    "@type": "Offer",
    price: "4.99",
    priceCurrency: "USD",
    description: "One-time purchase. Local storefront pricing may vary.",
  },
  author: {
    "@type": "Organization",
    name: "BB_Games",
    url: "https://drawlesschess.com/",
  },
};

const opponents = [
  { id: "adaptive", name: "Vesper", level: "Adaptive" },
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

function StoreBadges({ compact = false }: { compact?: boolean }) {
  return (
    <div
      className={`store-badges${compact ? " hero-store-badges" : ""}`}
      aria-label="Download Drawless Chess"
    >
      <a
        className="store-badge-link"
        href={GOOGLE_PLAY_URL}
        target="_blank"
        rel="noreferrer"
        aria-label="Get Drawless Chess on Google Play (opens in a new tab)"
      >
        <img
          src="/media/google-play-badge.png"
          width="646"
          height="250"
          alt="Get it on Google Play"
        />
      </a>
      <a
        className="store-badge-link"
        href={APP_STORE_URL}
        target="_blank"
        rel="noreferrer"
        aria-label="Download Drawless Chess on the App Store (opens in a new tab)"
      >
        <img
          src="/media/app-store-badge.svg"
          width="120"
          height="40"
          alt="Download on the App Store"
        />
      </a>
    </div>
  );
}

export default function Home() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(applicationStructuredData).replace(/</g, "\\u003c"),
        }}
      />
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
            <p className="eyebrow">Offline chess for Android and iOS</p>
            <h1 id="hero-title">Every game has a winner.</h1>
            <p className="hero-lede">
              For chess players who want familiar play without routine draws.
              Choose from eight on-device opponents on Android, iPhone, or iPad.
              The Android app also shows where the game turned and what you
              could have played instead—privately, on your device.
            </p>
            <StoreBadges compact />
            <p className="price-note">
              One-time purchase · US price $4.99 · No subscriptions or in-app purchases
            </p>
            <div className="button-row">
              <a className="button button-secondary" href="#review">See Game Review</a>
              <a className="button button-secondary" href="/play/">Try the web preview</a>
            </div>
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
                  alt="Drawless Chess game against Vesper on the Imperial Marble board."
                  loading="lazy"
                  decoding="async"
                />
              </picture>
            </div>
            <div className="visual-tag visual-tag-bottom">
              <span aria-hidden="true">♟</span> Material decides after 50 moves
            </div>
          </div>
        </section>

        <section className="proof-strip" aria-label="Drawless Chess promise">
          <div className="section-shell proof-strip-inner">
            <span>Checkmate still wins</span>
            <span>Five board themes</span>
            <span>Eight opponents</span>
            <span>Android Game Review</span>
          </div>
        </section>

        <section className="section review-section" id="review" aria-labelledby="review-title">
          <div className="section-shell review-layout">
            <div className="review-copy">
              <p className="eyebrow">Private, on-device Game Review for Android</p>
              <h2 id="review-title">The game ends. The learning starts.</h2>
              <p className="review-lede">
                In the Android app, see where the position changed and what you
                could have played instead. The Drawless-tuned Fairy-Stockfish
                engine analyzes your decisions using the exact rules you
                played. Everything runs on your device—there is no game upload
                or cloud analysis. Reviews are available from the completed
                game and are not kept as a separate review history.
              </p>
              <ol className="review-feature-list">
                <li>
                  <span aria-hidden="true">01</span>
                  <div>
                    <h3>Grades focused on your choices</h3>
                    <p>
                      See Best, Good, Inaccuracy, Mistake, and Blunder grades
                      for the decisions you controlled.
                    </p>
                  </div>
                </li>
                <li>
                  <span aria-hidden="true">02</span>
                  <div>
                    <h3>Better moves, with context</h3>
                    <p>
                      Compare stronger alternatives, short suggested lines,
                      and the engine evaluation from your side.
                    </p>
                  </div>
                </li>
                <li>
                  <span aria-hidden="true">03</span>
                  <div>
                    <h3>Replay the turning points</h3>
                    <p>
                      Step through every position, jump between issues, flip
                      the board, and follow the better-move arrow.
                    </p>
                  </div>
                </li>
              </ol>
            </div>

            <figure className="review-preview">
              <div className="review-screenshot-frame">
                <picture>
                  <source
                    type="image/webp"
                    srcSet="/media/game-review-360.webp 360w, /media/game-review-720.webp 720w"
                    sizes="(max-width: 760px) calc(100vw - 60px), 452px"
                  />
                  <img
                    src="/media/game-review-720.webp"
                    width="720"
                    height="1280"
                    alt="Drawless Chess Game Review on the Imperial Marble board, grading f3 as a Blunder, recommending c4, and showing the c4 Nc6 Nc3 e5 line, evaluation, and better-move arrow."
                    loading="lazy"
                    decoding="async"
                  />
                </picture>
              </div>
              <figcaption>Android Game Review · analysis runs on your device</figcaption>
            </figure>
          </div>
        </section>

        <section className="section section-shell store-section" id="download" aria-labelledby="download-title">
          <div className="store-panel">
            <div className="store-copy">
              <p className="eyebrow">Choose your platform</p>
              <h2 id="download-title">Take Drawless Chess with you.</h2>
              <p>
                Buy the full game once on your chosen platform. There are no
                subscriptions, ads, or purchases inside the app.
              </p>
              <span className="store-status">US price $4.99 · Local storefront prices may vary</span>
            </div>
            <StoreBadges />
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
            After 50 moves without a pawn move or capture, material points decide the winner.{" "}
            <a className="text-link" href="/play/">Try the rules in the Web Casual preview</a>.
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
              <p>
                Resume, hints, undo, rematches, local records, and streaks. Launch
                Game Review from the completed result on Android.
              </p>
            </article>
          </div>
        </section>

        <section className="section section-shell opponents-section" id="opponents" aria-labelledby="opponents-title">
          <div className="split-heading">
            <div>
              <p className="eyebrow">Eight opponents</p>
              <h2 id="opponents-title">Meet Vesper. Then climb the ranks.</h2>
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
                No account. No ads. No analytics or tracking. Games, settings,
                and preferences stay on your device. Android Game Reviews also
                stay local, subject to the backup settings you choose.
              </p>
              <a className="text-link" href="/privacy/">Read the privacy policy</a>
            </div>
            <div className="privacy-points" aria-label="Privacy highlights">
              <span>No account</span>
              <span>No ads</span>
              <span>No tracking</span>
              <span>Android on-device review</span>
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

      </main>
      <SiteFooter />
    </>
  );
}
