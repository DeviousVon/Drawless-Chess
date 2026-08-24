import type { Metadata } from "next";
import {
  ANDROID_SOURCE_RELEASE_URL,
  IOS_SOURCE_RELEASE_URL,
  PageIntro,
  SiteFooter,
  SiteHeader,
  SOCIAL_IMAGE,
  SOURCE_URL,
} from "../site-chrome";

export const metadata: Metadata = {
  title: "Open source",
  description: "Source code and licensing information for Drawless Chess.",
  alternates: { canonical: "/open-source/" },
  openGraph: {
    type: "website",
    siteName: "Drawless Chess",
    url: "/open-source/",
    title: "Open source · Drawless Chess",
    description: "Source code and licensing information for Drawless Chess.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Open source · Drawless Chess",
    description: "Source code and licensing information for Drawless Chess.",
    images: ["/og.png"],
  },
};

export default function OpenSourcePage() {
  return (
    <>
      <SiteHeader />
      <main id="main">
        <PageIntro eyebrow="Open source" title="Built in the open.">
          <p>Drawless Chess is open-source software licensed under GNU GPL version 3 or later.</p>
        </PageIntro>
        <div className="prose-shell">
          <section>
            <h2>Project source</h2>
            <p>The public repository is the home for Drawless Chess source, the shared rules core, tests, build material, documentation, notices, and the modified Fairy-Stockfish engine integration.</p>
            <p>BB_Games publishes Drawless Chess. The public project is hosted in the DeviousVon GitHub account.</p>
            <p>Android and iOS use separate release identities. For Android 1.0.2 (code 6), use the Android release. For iOS 1.0.2 (build 2), use the iOS source release. Each release identifies the immutable source and verification material corresponding to that store binary.</p>
            <div className="inline-links">
              <a className="button button-primary" href={ANDROID_SOURCE_RELEASE_URL}>Android 1.0.2 source</a>
              <a className="button button-secondary" href={IOS_SOURCE_RELEASE_URL}>iOS 1.0.2 (build 2) source</a>
              <a className="text-link" href={SOURCE_URL}>Browse the project</a>
            </div>
          </section>
          <section>
            <h2>License</h2>
            <p>Drawless Chess is licensed under <strong>GNU GPL-3.0-or-later</strong>. The corresponding source for an authorized binary is identified through its matching GitHub release.</p>
            <p><a className="text-link" href={`${SOURCE_URL}/blob/main/LICENSE`}>Read the license</a></p>
          </section>
          <section>
            <h2>Third-party work</h2>
            <p>Drawless Chess includes a modified Fairy-Stockfish engine, derived from Stockfish under GPL-3.0-or-later, plus other credited open-source software and audio. The casual browser preview uses the GPL-3.0 <strong>ffish-es6 0.7.9</strong> WebAssembly library for local chess move generation. The repository maintains exact version, source-identity, notice, and provenance records.</p>
            <div className="inline-links">
              <a className="text-link" href={`${SOURCE_URL}/blob/main/THIRD_PARTY_NOTICES.md`}>Third-party notices</a>
              <a className="text-link" href={`${SOURCE_URL}/blob/main/NOTICE`}>Project notice</a>
            </div>
          </section>
          <section>
            <h2>Release status</h2>
            <p>Android and iOS are separate builds. To find source for an installed build, match its platform and app version to the corresponding GitHub release. An Android source archive is not the corresponding source for an iOS binary, and vice versa.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
