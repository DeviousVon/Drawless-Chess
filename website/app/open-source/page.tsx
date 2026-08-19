import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SOURCE_RELEASES_URL, SOURCE_URL } from "../site-chrome";

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
            <p>The public repository is the publication home for Drawless Chess code, documentation, notices, and project history. Browse it for the material that has already been published.</p>
            <p><a className="button button-primary" href={SOURCE_URL}>Browse the public project on GitHub</a></p>
          </section>
          <section>
            <h2>License</h2>
            <p>The complete application is licensed under <strong>GNU GPL-3.0-or-later</strong>. This covers the Android application, SwiftUI iPhone and iPad application, shared rules and game core, platform engine adapters, and modified Fairy-Stockfish integration.</p>
            <p><a className="text-link" href={`${SOURCE_URL}/blob/main/LICENSE`}>Read the license</a></p>
          </section>
          <section>
            <h2>Matching source for released apps</h2>
            <p>Every authorized iPhone, iPad, or Android binary release must have a public release entry containing or linking its complete corresponding-source archive and SHA-256 checksum no later than the binary becomes available.</p>
            <p>The current <code>main</code> branch is not a substitute for source matched to a particular binary. If a version has no public release entry with matching source, that version is not authorized for distribution.</p>
            <p><a className="text-link" href={SOURCE_RELEASES_URL}>View public release entries</a></p>
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
            <p>The public iPhone, iPad, and Android releases are still in preparation. Website deployment, binary distribution, and corresponding-source publication are separate release gates.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
