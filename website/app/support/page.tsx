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
          <p>Get help with Drawless Chess, report a problem, or ask a privacy question.</p>
        </PageIntro>
        <div className="prose-shell">
          <section>
            <h2>Contact</h2>
            <div className="info-card">
              <h3>Email support</h3>
              <p><a className="text-link" href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a></p>
              <p>Response times vary, especially when a report requires technical investigation.</p>
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
              <li>Your platform—Android, iPhone, or iPad—plus the operating-system version and device model.</li>
              <li>Whether you installed the app from Google Play or Apple’s App Store.</li>
              <li>The Drawless Chess version shown in the app or store information.</li>
              <li>What you expected, what happened, and the steps leading to it.</li>
              <li>A screenshot if it does not contain information you prefer to keep private.</li>
            </ul>
          </section>
          <section>
            <h2>Purchases, downloads, and refunds</h2>
            <p>Google Play and Apple’s App Store process purchases, receipts, payment methods, and refund requests under their own policies. BB_Games does not receive your payment-card or bank details and cannot directly change a store transaction.</p>
            <p>If a previous purchase is missing, first sign in to the same Google or Apple account used to buy the app and check that store’s purchase history. Contact the store for billing or refund decisions. For help with access after a purchase, email us with your platform, store, app version, and order or purchase identifier. Do not send payment-card or bank details.</p>
          </section>
          <section>
            <h2>Privacy questions</h2>
            <p>Gameplay on both platforms—and Game Review on Android—runs on your device without a Drawless account or developer-operated server. Read the complete <a className="text-link" href="/privacy/">privacy policy</a> for local storage, device and cloud backups, store processing, and deletion details.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
