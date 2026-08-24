export const SOURCE_URL = "https://github.com/DeviousVon/Drawless-Chess";
export const ANDROID_SOURCE_RELEASE_URL = `${SOURCE_URL}/releases/tag/v1.0.2`;
export const IOS_SOURCE_RELEASE_URL = `${SOURCE_URL}/releases/tag/ios-v1.0.2-build-2`;
export const SUPPORT_EMAIL = "support@drawlesschess.com";
export const GOOGLE_PLAY_URL = "https://play.google.com/store/apps/details?id=com.drawlesschess";
export const APP_STORE_URL = "https://apps.apple.com/app/drawless-chess/id6801584008";
export const SOCIAL_IMAGE = {
  url: "/og.png",
  width: 1200,
  height: 630,
  alt: "Drawless Chess — Every game has a winner.",
};

export function SiteHeader() {
  return (
    <header className="site-header">
      <div className="header-inner section-shell">
        <a className="brand" href="/" aria-label="Drawless Chess home">
          <img src="/media/app-icon.png" width="40" height="40" alt="" />
          <span>Drawless Chess</span>
        </a>
        <nav className="site-nav" aria-label="Primary navigation">
          <a className="nav-play" href="/play/">Web preview</a>
          <a className="nav-store" href="/#download">Get the game</a>
          <a className="nav-review" href="/#review">Game review</a>
          <a href="/#rules">How it works</a>
          <a href="/privacy/">Privacy</a>
          <a href="/support/">Support</a>
        </nav>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="section-shell footer-grid">
        <div>
          <a className="brand footer-brand" href="/">
            <img src="/media/app-icon.png" width="36" height="36" alt="" />
            <span>Drawless Chess</span>
          </a>
          <p>Offline chess. Decisive by design.</p>
        </div>
        <nav aria-label="Footer navigation">
          <a href="/play/">Web preview</a>
          <a href="/#download">Get the game</a>
          <a href="/#review">Game review</a>
          <a href="/#rules">How it works</a>
          <a href="/privacy/">Privacy</a>
          <a href="/support/">Support</a>
          <a href="/open-source/">Open source</a>
        </nav>
        <div className="footer-meta">
          <p>Published by BB_Games</p>
          <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>
        </div>
      </div>
      <p className="section-shell footer-legal">
        Apple and the Apple logo are trademarks of Apple Inc., registered in the U.S. and other
        countries. App Store is a service mark of Apple Inc. Google Play and the Google Play logo
        are trademarks of Google LLC.
      </p>
    </footer>
  );
}

export function PageIntro({ eyebrow, title, children }: {
  eyebrow: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="page-intro section-shell">
      <p className="eyebrow">{eyebrow}</p>
      <h1>{title}</h1>
      <div className="page-intro-copy">{children}</div>
    </section>
  );
}
