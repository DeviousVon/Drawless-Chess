import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SUPPORT_EMAIL } from "../site-chrome";

export const metadata: Metadata = {
  title: "Privacy policy",
  description: "How the Drawless Chess Android app handles information.",
  alternates: { canonical: "/privacy/" },
  openGraph: {
    type: "website",
    siteName: "Drawless Chess",
    url: "/privacy/",
    title: "Privacy policy · Drawless Chess",
    description: "How the Drawless Chess Android app handles information.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Privacy policy · Drawless Chess",
    description: "How the Drawless Chess Android app handles information.",
    images: ["/og.png"],
  },
};

export default function PrivacyPage() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <PageIntro eyebrow="Privacy" title="Privacy policy">
          <p>Drawless Chess is an offline, single-player Android game. This policy explains how the app handles information.</p>
          <div className="legal-meta">
            <span><strong>Effective:</strong> July 11, 2026</span>
            <span><strong>Updated:</strong> July 14, 2026</span>
          </div>
        </PageIntro>
        <article className="prose-shell">
          <section>
            <h2>Privacy at a glance</h2>
            <ul>
              <li>No account or sign-in.</li>
              <li>No personal information sent to BB_Games.</li>
              <li>No advertising, analytics, tracking, crash-reporting, or in-app purchase SDKs.</li>
              <li>No network, location, camera, microphone, contacts, or shared-storage permissions.</li>
            </ul>
          </section>
          <section>
            <h2>Information kept on your device</h2>
            <p>Drawless Chess stores information in Android’s private app storage so it can run games, restore progress, show local statistics, and remember preferences. This may include:</p>
            <ul>
              <li>Saved games, positions, moves, results, clocks, rules, player side, opponent level, hints, undos, and pauses.</li>
              <li>A random device-local player identifier and completed-game history used for local records and scores.</li>
              <li>Your selected theme and whether you dismissed the introductory rules guide.</li>
            </ul>
            <p>The local identifier is not an account, is not linked to an online identity, and is not sent to BB_Games or an in-app third party.</p>
          </section>
          <section>
            <h2>Android backup</h2>
            <p>Android’s system backup may include locally stored game and preference data, depending on your device, account, and settings. The operating system or backup provider controls that process. BB_Games does not receive or control those backups.</p>
          </section>
          <section>
            <h2>Network access and external services</h2>
            <p>The app has no Android internet permission and does not directly send gameplay or preference data over the internet. Fixed source and privacy links open in an external browser or app only when you choose them.</p>
            <p>If you install or purchase through Google Play, Google processes store, transaction, device, and related platform information under its own terms. BB_Games does not receive payment-card or bank details.</p>
          </section>
          <section>
            <h2>Sharing and sale</h2>
            <p>Drawless Chess does not transmit personal information or app activity to BB_Games, so we do not sell it or share it with third parties. The app has no advertising network, profiling, cross-app tracking, or developer-operated server.</p>
          </section>
          <section>
            <h2>Retention and deletion</h2>
            <p>An in-progress saved game remains until it is completed, forfeited, or app data is cleared. Completed-game history, local statistics, and preferences remain until app data is cleared or the app is uninstalled.</p>
            <p>Delete installed app data through <strong>Android Settings → Apps → Drawless Chess → Storage → Clear storage/data</strong>, or uninstall the app.</p>
            <p>Separate Android or cloud backups may remain under your device or Google account settings. Because there is no Drawless account or developer-operated gameplay store, BB_Games has no server-side record to locate or delete.</p>
          </section>
          <section>
            <h2>Children and security</h2>
            <p>Drawless Chess is not specifically directed to children under 13 and does not knowingly collect personal information from children or adults.</p>
            <p>Local data is protected by Android’s app sandbox and your device security settings. No storage method can be guaranteed completely secure, so keep your device, operating system, and screen lock up to date.</p>
          </section>
          <section>
            <h2>Changes and contact</h2>
            <p>If information handling changes, this policy and its updated date will change before or when the relevant app update is released.</p>
            <div className="info-card">
              <p><strong>Developer:</strong> BB_Games</p>
              <p><strong>Privacy and support:</strong> <a className="text-link" href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a></p>
            </div>
          </section>
        </article>
      </main>
      <SiteFooter />
    </>
  );
}
