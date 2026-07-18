import { SiteFooter, SiteHeader } from "./site-chrome";

export default function NotFound() {
  return (
    <>
      <title>Page not found · Drawless Chess</title>
      <SiteHeader />
      <main id="main" className="not-found section-shell">
        <div>
          <p className="eyebrow">Position not found</p>
          <h1>404</h1>
          <p>This square is empty. Return to the Drawless Chess homepage.</p>
          <a className="button button-primary" href="/">Back home</a>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
