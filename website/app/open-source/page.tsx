import type { Metadata } from "next";
import { PageIntro, SiteFooter, SiteHeader, SOCIAL_IMAGE, SOURCE_URL } from "../site-chrome";

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
            <p>The repository includes the Android application, rules core, tests, build material, documentation, notices, and the patched engine integration.</p>
            <p><a className="button button-primary" href={SOURCE_URL}>View the project on GitHub</a></p>
          </section>
          <section>
            <h2>License</h2>
            <p>The complete application is licensed under <strong>GNU GPL-3.0-or-later</strong>. Any authorized binary release will include access to its matching corresponding source.</p>
            <p><a className="text-link" href={`${SOURCE_URL}/blob/main/LICENSE`}>Read the license</a></p>
          </section>
          <section>
            <h2>Third-party work</h2>
            <p>Drawless Chess includes and credits third-party open-source software and audio. The repository maintains project notices and provenance records.</p>
            <div className="inline-links">
              <a className="text-link" href={`${SOURCE_URL}/blob/main/THIRD_PARTY_NOTICES.md`}>Third-party notices</a>
              <a className="text-link" href={`${SOURCE_URL}/blob/main/NOTICE`}>Project notice</a>
            </div>
          </section>
          <section>
            <h2>Release status</h2>
            <p>The public Android release is still in preparation. A matching immutable source identity will accompany an authorized release.</p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  );
}
