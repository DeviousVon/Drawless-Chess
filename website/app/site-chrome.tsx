export const SOURCE_URL = "https://github.com/DeviousVon/Drawless-Chess";
export const SUPPORT_EMAIL = "support@drawlesschess.com";
export const BETA_GROUP_URL = "https://groups.google.com/g/drawless-chess-testers";
export const BETA_DOWNLOAD_URL = "https://play.google.com/store/apps/details?id=com.drawlesschess";
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
          <a className="nav-beta" href="/#beta">Beta access</a>
          <a href="/#rules">How it works</a>
          <a href="/#opponents">Opponents</a>
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
          <a href="/#beta">Beta access</a>
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
