import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SUPPORT_EMAIL } from "../site-chrome";

export const metadata: Metadata = {
  title: "Privacy policy",
  description: "How Drawless Chess handles information on Android, iPhone, and iPad.",
  alternates: { canonical: "/privacy/" },
  openGraph: {
    type: "website",
    siteName: "Drawless Chess",
    url: "/privacy/",
    title: "Privacy policy · Drawless Chess",
    description: "How Drawless Chess handles information on Android, iPhone, and iPad.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Privacy policy · Drawless Chess",
    description: "How Drawless Chess handles information on Android, iPhone, and iPad.",
    images: ["/og.png"],
  },
};

export default function PrivacyPage() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <PageIntro eyebrow="Privacy" title="Privacy policy">
          <p>Drawless Chess is an offline, single-player game for Android, iPhone, and iPad. This policy explains how the app handles information.</p>
          <div className="legal-meta">
            <span><strong>Effective:</strong> August 19, 2026</span>
            <span><strong>Updated:</strong> August 19, 2026</span>
          </div>
        </PageIntro>
        <article className="prose-shell">
          <section>
            <h2>Privacy at a glance</h2>
            <ul>
              <li>No account or sign-in.</li>
              <li>The app does not send personal information or app activity to BB_Games.</li>
              <li>No advertising, analytics, tracking, crash-reporting, or in-app purchase SDKs.</li>
              <li>Gameplay and Game Review run on your device without sending game data to a developer-operated server.</li>
              <li>No access to your location, camera, microphone, contacts, photos, or shared files is requested.</li>
            </ul>
          </section>
          <section>
            <h2>Information kept on your device</h2>
            <p>Drawless Chess stores information in the app’s private storage so it can run games, restore progress, show local statistics, and remember preferences. This may include:</p>
            <ul>
              <li>Saved games, positions, moves, results, clocks, rules, player side, opponent level, hints, undos, and pauses.</li>
              <li>A random device-local player identifier and completed-game history used for local records and scores.</li>
              <li>Your selected theme and whether you dismissed the introductory rules guide.</li>
            </ul>
            <p>The local identifier is not an account, is not linked to an online identity, and is not sent to BB_Games or an in-app third party.</p>
          </section>
          <section>
            <h2>Device and cloud backups</h2>
            <p>Android system backup may include locally stored game and preference data, depending on your device, Google account, and backup settings. On iPhone and iPad, the same types of local data may be included in a device or iCloud backup according to your Apple settings.</p>
            <p>Your operating system or backup provider controls those backups. BB_Games does not receive, access, or control them.</p>
          </section>
          <section>
            <h2>Network access and external services</h2>
            <p>The app does not send gameplay, Game Review, or preference data to BB_Games over the internet. Fixed source and privacy links open in an external browser or app only when you choose them.</p>
            <p>If you install or purchase through Google Play or Apple’s App Store, the store provider processes download, transaction, device, and related platform information under its own terms. BB_Games does not receive your payment-card or bank details. The stores may provide BB_Games with aggregate installation information or platform-generated performance and crash reports; those reports come from the store platform, not from an analytics or crash-reporting SDK in Drawless Chess.</p>
          </section>
          <section>
            <h2>Sharing and sale</h2>
            <p>Drawless Chess does not transmit personal information or app activity to BB_Games, so we do not sell it or share it with third parties. The app has no advertising network, profiling, cross-app tracking, or developer-operated server.</p>
          </section>
          <section>
            <h2>Retention and deletion</h2>
            <p>An in-progress saved game remains until it is completed, forfeited, or app data is cleared. Completed-game history, local statistics, and preferences remain until app data is cleared or the app is uninstalled.</p>
            <ul>
              <li><strong>Android:</strong> Use <strong>Settings → Apps → Drawless Chess → Storage → Clear storage/data</strong>, or uninstall the app.</li>
              <li><strong>iPhone or iPad:</strong> Delete the app from the Home Screen or use <strong>Settings → General → iPhone Storage or iPad Storage → Drawless Chess → Delete App</strong>. Offloading the app keeps its documents and data.</li>
            </ul>
            <p>Separate device or cloud backups may remain under your Android, Google, Apple, or iCloud backup settings. Because there is no Drawless account or developer-operated gameplay store, BB_Games has no server-side account or gameplay record to locate or delete.</p>
          </section>
          <section>
            <h2>Children and security</h2>
            <p>Drawless Chess is not specifically directed to children under 13 and does not knowingly collect personal information from children or adults.</p>
            <p>Local data is protected by the app sandbox and security settings on your device. No storage method can be guaranteed completely secure, so keep your device, operating system, and screen lock up to date.</p>
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
