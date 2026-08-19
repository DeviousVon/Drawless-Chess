import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SUPPORT_EMAIL } from "../site-chrome";

export const metadata: Metadata = {
  title: "Privacy policy",
  description: "How the Drawless Chess iPhone, iPad, and Android apps handle information.",
  alternates: { canonical: "/privacy/" },
  openGraph: {
    type: "website",
    siteName: "Drawless Chess",
    url: "/privacy/",
    title: "Privacy policy · Drawless Chess",
    description: "How the Drawless Chess iPhone, iPad, and Android apps handle information.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Privacy policy · Drawless Chess",
    description: "How the Drawless Chess iPhone, iPad, and Android apps handle information.",
    images: ["/og.png"],
  },
};

export default function PrivacyPage() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <PageIntro eyebrow="Privacy" title="Privacy policy">
          <p>Drawless Chess is an offline, single-player game for iPhone, iPad, and Android. This policy explains how the mobile apps handle information.</p>
          <div className="legal-meta">
            <span><strong>Effective:</strong> July 11, 2026</span>
            <span><strong>Updated:</strong> August 14, 2026</span>
          </div>
        </PageIntro>
        <article className="prose-shell">
          <section>
            <h2>Privacy at a glance</h2>
            <ul>
              <li>No account or sign-in.</li>
              <li>No personal information sent to BB_Games.</li>
              <li>No advertising, analytics, tracking, developer-operated crash-reporting, or in-app purchase SDKs.</li>
              <li>No developer-operated network service or location, camera, microphone, contacts, or shared-storage access. The Android app also declares no internet permission.</li>
            </ul>
          </section>
          <section>
            <h2>Information kept on your device</h2>
            <p>Drawless Chess stores information in its private iOS, iPadOS, or Android app container so it can run games, restore progress, show local statistics, and remember preferences. This may include:</p>
            <ul>
              <li>Saved games, positions, moves, results, clocks, rules, player side, opponent level, hints, undos, and pauses.</li>
              <li>A random device-local player identifier and completed-game history used for local records and scores.</li>
              <li>Your theme, coordinates, sound, haptic, and presentation choices and whether you dismissed the introductory rules guide.</li>
            </ul>
            <p>The local identifier is not an account, is not linked to an online identity, and is not sent to BB_Games or an in-app third party.</p>
          </section>
          <section>
            <h2>Device and cloud backup</h2>
            <p>Apple device or iCloud Backup, or Android’s system backup, may include locally stored game and preference data, depending on your device and settings. Apple, Google, your device manufacturer, or another backup provider controls that process. BB_Games does not receive, access, or control those backups.</p>
          </section>
          <section>
            <h2>Network access and external services</h2>
            <p>The mobile apps do not send gameplay, preferences, identifiers, or statistics to BB_Games. Fixed source and privacy links open in an external browser or app only when you choose them.</p>
            <p>If you install or purchase through Apple’s App Store or Google Play, the store provider processes download, transaction, device, and related platform information under its own terms. BB_Games does not receive payment-card or bank details. Apple or Google may provide aggregate store statistics and platform-generated performance or crash diagnostics.</p>
          </section>
          <section>
            <h2>Sharing and sale</h2>
            <p>Drawless Chess does not transmit personal information or app activity to BB_Games, so we do not sell it or share it with third parties. The apps have no advertising network, profiling, cross-app tracking, or developer-operated server.</p>
          </section>
          <section>
            <h2>Retention and deletion</h2>
            <p>An in-progress saved game remains until it is completed, forfeited, or app data is removed. Completed-game history, local statistics, and preferences remain until app data is removed.</p>
            <ul>
              <li><strong>iPhone or iPad:</strong> use <strong>Delete App</strong> from the Home Screen or from <strong>Settings → General → iPhone Storage / iPad Storage → Drawless Chess</strong>. <strong>Offload App</strong> retains documents and data and is not the same as deleting the app.</li>
              <li><strong>Android:</strong> use <strong>Settings → Apps → Drawless Chess → Storage → Clear storage/data</strong>, or uninstall the app. Exact labels vary by device.</li>
            </ul>
            <p>Separate device or cloud backups may remain under your backup-provider settings. Because there is no Drawless account or developer-operated gameplay store, BB_Games has no server-side record to locate or delete.</p>
          </section>
          <section>
            <h2>Children and security</h2>
            <p>Drawless Chess is not specifically directed to children under 13 and does not knowingly collect personal information from children or adults.</p>
            <p>Local data is protected by the app sandbox and security settings of your Apple or Android device. No storage method can be guaranteed completely secure, so keep your device, operating system, and screen lock up to date.</p>
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
