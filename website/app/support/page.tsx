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
          <p>Drawless Chess is still preparing for its public Android release. Questions and test feedback are welcome.</p>
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
              <p>Review the code, documentation, and public project history on <a className="text-link" href={SOURCE_URL}>GitHub</a>.</p>
            </div>
          </section>
          <section>
            <h2>Reporting a problem</h2>
            <p>To help us reproduce an issue, include:</p>
            <ul>
              <li>Your Android version and device model.</li>
              <li>The Drawless Chess version shown in Android app information.</li>
              <li>What you expected, what happened, and the steps leading to it.</li>
              <li>A screenshot if it does not contain information you prefer to keep private.</li>
            </ul>
          </section>
          <section>
            <h2>Privacy questions</h2>
            <p>The app runs without an account or internet permission. Read the complete <a className="text-link" href="/privacy/">privacy policy</a> for local storage, Android backup, and deletion details.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
