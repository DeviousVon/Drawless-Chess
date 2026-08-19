import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SOURCE_URL, SUPPORT_EMAIL } from "../site-chrome";

export const metadata: Metadata = {
  title: "Support",
  description: "Support and project status for Drawless Chess.",
  alternates: { canonical: "/support/" },
  openGraph: {
    type: "website",
    siteName: "Drawless Chess",
    url: "/support/",
    title: "Support · Drawless Chess",
    description: "Support and project status for Drawless Chess.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Support · Drawless Chess",
    description: "Support and project status for Drawless Chess.",
    images: ["/og.png"],
  },
};

export default function SupportPage() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <PageIntro eyebrow="Support" title="How can we help?">
          <p>Drawless Chess is still preparing for its public iPhone, iPad, and Android releases. Questions and test feedback are welcome.</p>
        </PageIntro>
        <div className="prose-shell">
          <section>
            <h2>Contact</h2>
            <div className="info-card">
              <h3>Email support</h3>
              <p><a className="text-link" href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a></p>
            </div>
            <div className="info-card">
              <h3>Project source</h3>
              <p>Browse the currently published code, documentation, and public project history on <a className="text-link" href={SOURCE_URL}>GitHub</a>. For source matched to a particular app release, use the <a className="text-link" href="/open-source/">Open source page</a>.</p>
            </div>
          </section>
          <section>
            <h2>Reporting a problem</h2>
            <p>To help us reproduce an issue, include:</p>
            <ul>
              <li>Your platform, operating-system version, and device model.</li>
              <li>The Drawless Chess version and build shown in the app or system app information.</li>
              <li>What you expected, what happened, and the steps leading to it.</li>
              <li>A screenshot if it does not contain information you prefer to keep private.</li>
            </ul>
          </section>
          <section>
            <h2>Privacy questions</h2>
            <p>The mobile apps run without an account or a developer-operated network service. Read the complete <a className="text-link" href="/privacy/">privacy policy</a> for local storage, Apple and Android backup, and platform-specific deletion details.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
