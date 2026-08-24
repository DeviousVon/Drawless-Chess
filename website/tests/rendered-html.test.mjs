import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir, stat } from "node:fs/promises";
import test from "node:test";

const releaseRoot = new URL("../release/", import.meta.url);

async function releaseFile(path) {
  return readFile(new URL(path, releaseRoot), "utf8");
}

test("exports every public route and the custom not-found page", async () => {
  const routes = [
    "index.html",
    "play/index.html",
    "privacy/index.html",
    "support/index.html",
    "open-source/index.html",
    "404.html",
  ];

  for (const route of routes) {
    assert.equal((await stat(new URL(route, releaseRoot))).isFile(), true, route);
  }

  const notFound = await releaseFile("404.html");
  assert.deepEqual(
    [...notFound.matchAll(/<title>([^<]+)<\/title>/gi)].map((match) => match[1]),
    ["Page not found · Drawless Chess"],
  );
});

test("ships one isolated, same-origin playable runtime with its pinned WASM", async () => {
  const html = await releaseFile("play/index.html");
  const scripts = [...html.matchAll(/<script\b([^>]*)><\/script>/gi)].map((match) => match[1]);

  assert.match(html, /<html[^>]+lang="en"/i);
  assert.match(html, /<title>Play · Drawless Chess<\/title>/i);
  assert.match(html, /name="description"[^>]+casual game of Drawless Chess/i);
  assert.equal(scripts.length, 1, "play page has one entry script");
  assert.match(scripts[0], /type="module"/i);
  const scriptPath = scripts[0].match(/src="([^"]+)"/i)?.[1];
  assert.match(scriptPath ?? "", /^\/play\/assets\/[A-Za-z0-9_.-]+\.js$/);
  assert.equal((await stat(new URL(scriptPath.slice(1), releaseRoot))).isFile(), true, "play entry script");
  assert.equal((await stat(new URL(`${scriptPath.slice(1)}.gz`, releaseRoot))).isFile(), true, "compressed play entry script");
  const playRuntime = await releaseFile(scriptPath.slice(1));
  const playStylesheetPath = html.match(/href="(\/play\/assets\/[A-Za-z0-9_.-]+\.css)"/i)?.[1];
  assert.ok(playStylesheetPath, "play stylesheet");
  const playStylesheet = await releaseFile(playStylesheetPath.slice(1));
  assert.match(playRuntime, /Imperial Marble/);
  assert.match(playRuntime, /Original Drawless pieces/);
  assert.match(playRuntime, /M50 14 C39 14 33 22 33 32/, "original Drawless pawn silhouette");
  assert.match(playRuntime, /#fffcf2/, "Imperial Marble white-piece fill");
  assert.match(playRuntime, /#eaf1ec/, "Imperial Marble black-piece outline");
  assert.doesNotMatch(playRuntime, /[♔-♟]/u, "does not fall back to platform chess glyphs");
  assert.match(playStylesheet, /\.web-square-light\{background-color:#f2f0eb}/i);
  assert.match(playStylesheet, /\.web-square-dark\{background-color:#344a3f}/i);
  assert.doesNotMatch(html, /<script\b[^>]+https?:\/\//i);
  assert.doesNotMatch(html, /__VINEXT|vite-rsc/i);

  const wasm = await readFile(new URL("game/ffish-0.7.9.wasm", releaseRoot));
  assert.equal(wasm.byteLength, 920681);
  assert.equal(
    createHash("sha256").update(wasm).digest("hex"),
    "f524da0ccba29b5cc6e8c9bd0ec0fa1ec6fd887fe019dfc05bc209edb2e34882",
  );
  assert.equal((await stat(new URL("game/ffish-0.7.9.wasm.gz", releaseRoot))).isFile(), true, "compressed WASM");
  assert.match(await releaseFile("sitemap.xml"), /https:\/\/drawlesschess\.com\/play\//i);
});

test("renders the launch-ready cross-platform story without beta or closed-test copy", async () => {
  const html = await releaseFile("index.html");

  assert.match(html, /<html[^>]+lang="en"/i);
  assert.match(html, /Every game has a winner\./);
  assert.match(html, /Choose from eight\s*on-device opponents[\s\S]*see where the game turned/i);
  assert.match(html, /id="review"/i);
  assert.match(html, /The game ends\. The learning starts\./);
  assert.match(html, /Familiar chess\. Decisive endings\./);
  assert.match(html, /Bare king loses/);
  assert.match(html, /After 50 moves without a pawn move or capture, material points decide the winner\./);
  assert.doesNotMatch(html, /No automatic 50-move draw|There is no automatic 50-move ending/i);
  assert.match(html, /One-time purchase · US price \$4\.99 · No subscriptions or in-app purchases/i);
  assert.match(html, /Level names are descriptive—not Elo claims\./);
  assert.match(html, /Meet Vesper\. Then climb the ranks\./);
  assert.match(html, /<strong>Vesper<\/strong><span>Adaptive<\/span>/);
  assert.ok(
    html.indexOf("Vesper") < html.indexOf("Mira"),
    "Vesper leads the opponent roster",
  );
  assert.match(html, /On-device review/);
  assert.doesNotMatch(html, /\b(?:0\.3\.0|1\.0\.0|version code)\b/i);
  assert.doesNotMatch(html, /\bbeta\b|closed test|testing group|test build/i);
  assert.doesNotMatch(html, /Codex is working|starter loading skeleton/i);
  assert.doesNotMatch(html, /class="trust-row"|✓/i);
  assert.doesNotMatch(html, /class="section section-shell final-cta"/i);
});

test("publishes the exact App Store destination and launch purchase model", async () => {
  const html = await releaseFile("index.html");
  const appStoreUrl = "https://apps.apple.com/app/drawless-chess/id6801584008";

  assert.match(html, new RegExp(appStoreUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(html, /Download Drawless Chess on the App Store \(opens in a new tab\)/i);
  assert.match(html, /src="\/media\/app-store-badge\.svg"/i);
  assert.match(html, /US price \$4\.99 · Local storefront prices may vary/i);
  assert.match(html, /no\s+subscriptions, ads, or purchases inside the app/i);
  assert.doesNotMatch(html, /coming soon|store-badge-pending|App Store link/i);
});

test("explains the rules-aware, private Game Review without overclaiming", async () => {
  const html = await releaseFile("index.html");
  const openSource = await releaseFile("open-source/index.html");

  for (const claim of [
    "Private, on-device Game Review",
    "Drawless-tuned Fairy-Stockfish",
    "Grades focused on your choices",
    "Best",
    "Good",
    "Inaccuracy",
    "Mistake",
    "Blunder",
    "Better moves, with context",
    "Replay the turning points",
    "exact rules you played",
    "there is no game upload or cloud analysis",
    "not kept as a separate review history",
    "Private Game Review",
  ]) {
    assert.match(html, new RegExp(claim, "i"));
  }

  assert.match(html, /href="\/#review"[^>]*>Game review/i);
  const reviewFigure = html.match(/<figure class="review-preview">[\s\S]*?<\/figure>/i)?.[0] ?? "";
  assert.match(reviewFigure, /srcset="\/media\/game-review-360\.webp 360w, \/media\/game-review-720\.webp 720w"/i);
  assert.match(reviewFigure, /alt="Drawless Chess Game Review[^\"]*Imperial Marble board/i);
  assert.match(reviewFigure, /grading f3 as a Blunder/i);
  assert.match(reviewFigure, /recommending c4/i);
  assert.match(reviewFigure, /c4 Nc6 Nc3 e5 line/i);
  assert.match(reviewFigure, /Private Game Review · analysis runs on your device/i);
  assert.doesNotMatch(html, /Actual app screen/i);
  assert.doesNotMatch(reviewFigure, /Best move grade|Illustrated feature preview|review-square|♜|♟/i);
  assert.match(openSource, /modified Fairy-Stockfish engine, derived from Stockfish/i);
  assert.match(openSource, /casual browser preview uses the GPL-3\.0 <strong>ffish-es6 0\.7\.9<\/strong>/i);
  assert.match(openSource, /href="https:\/\/github\.com\/DeviousVon\/Drawless-Chess\/releases\/tag\/v1\.0\.2"[^>]*>Android 1\.0\.2 source/i);
  assert.match(openSource, /href="https:\/\/github\.com\/DeviousVon\/Drawless-Chess\/releases\/tag\/ios-v1\.0\.2-build-2"[^>]*>iOS 1\.0\.2 \(build 2\) source/i);
  assert.match(openSource, /Android and iOS use separate release identities/i);
  assert.doesNotMatch(html, /accuracy (?:score|percentage)|reviews? every move/i);
});

test("publishes both exact store destinations with accessible official badges", async () => {
  const html = await releaseFile("index.html");
  const downloadUrl = "https://play.google.com/store/apps/details?id=com.drawlesschess";
  const appStoreUrl = "https://apps.apple.com/app/drawless-chess/id6801584008";

  assert.match(html, /id="download"/i);
  assert.match(html, new RegExp(downloadUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(html, new RegExp(appStoreUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(html, /src="\/media\/google-play-badge\.png"/i);
  assert.match(html, /href="\/#download"[^>]*>Get the game/i);
  assert.doesNotMatch(html, /groups\.google\.com|BETA_|#beta/i);
  assert.equal((await stat(new URL("media/google-play-badge.png", releaseRoot))).isFile(), true);
  assert.equal((await stat(new URL("media/app-store-badge.svg", releaseRoot))).isFile(), true);
});

test("prioritizes the responsive hero artwork and preserves image proportions", async () => {
  const html = await releaseFile("index.html");
  const heroArtwork = html.match(/<img\b[^>]*class="hero-artwork"[^>]*>/i)?.[0];
  const gameplay = html.match(/<img\b[^>]*src="\/media\/gameplay-720\.webp"[^>]*>/i)?.[0];
  const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");

  assert.ok(heroArtwork, "responsive hero artwork");
  assert.match(heroArtwork, /src="\/media\/hero-kings-1200\.webp"/i);
  assert.match(
    heroArtwork,
    /srcset="\/media\/hero-kings-640\.webp 640w, \/media\/hero-kings-1200\.webp 1200w"/i,
  );
  assert.match(heroArtwork, /width="1200"/i);
  assert.match(heroArtwork, /height="630"/i);
  assert.match(heroArtwork, /alt=""/i);
  assert.match(heroArtwork, /fetchpriority="high"/i);
  assert.doesNotMatch(heroArtwork, /loading="lazy"/i);
  assert.ok(
    html.indexOf('class="hero-artwork"') < html.indexOf("Offline chess for Android and iOS"),
    "hero artwork precedes the eyebrow",
  );

  assert.ok(gameplay, "gameplay preview");
  assert.match(gameplay, /loading="lazy"/i);
  assert.doesNotMatch(gameplay, /fetchpriority="high"/i);
  assert.match(gameplay, /alt="Drawless Chess game against Vesper on the Imperial Marble board\."/i);
  assert.doesNotMatch(gameplay, /against Theo/i);
  assert.match(css, /\.theme-preview img\s*{\s*width:\s*100%;\s*height:\s*auto;\s*}/i);
});

test("ships the verified Android 1.0.2 marketing captures", async () => {
  const expectedHashes = new Map([
    ["media/game-review-360.webp", "87dc87a3bfdbcc122b88de1ad6ebb15a914fbb9eb3eebb23e117f4cef72a4cff"],
    ["media/game-review-720.webp", "05797d365d02a5f45c222bf739afd1ffdaa9d06c0072d5932195c62cc48a1b08"],
    ["media/gameplay-360.webp", "60a3a79649a1f006c5fc7c0b08924829756c0bc9c5d996aa026bd6b6a26d9097"],
    ["media/gameplay-720.webp", "f0efbc3227173393b12426df50bc42bc2a29b3e92322462432cd46e2933e57f0"],
    ["media/themes-360.webp", "92bd8cfc53f8edcbc103bf7cd2d7cddbd34a90d130c1f7ad2919385ecad17bbb"],
    ["media/themes-720.webp", "f2c157d4ffbd9b2969c289efdedf1a7ac4627565f834676f1304acb6541f20f2"],
  ]);

  for (const [asset, digest] of expectedHashes) {
    const bytes = await readFile(new URL(asset, releaseRoot));
    assert.equal(createHash("sha256").update(bytes).digest("hex"), digest, asset);
  }

  const html = await releaseFile("index.html");
  const reviewImage = html.match(/<img\b[^>]*src="\/media\/game-review-720\.webp"[^>]*>/i)?.[0] ?? "";
  assert.match(reviewImage, /width="720"/i);
  assert.match(reviewImage, /height="1280"/i);
});

test("gives the proof and privacy selling points a stronger visual hierarchy", async () => {
  const html = await releaseFile("index.html");
  const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");

  for (const point of [
    "Checkmate still wins",
    "Five board themes",
    "Eight opponents",
    "On-device Game Review",
    "Private by design",
    "No account",
    "No ads",
    "No tracking",
    "On-device review",
  ]) {
    assert.match(html, new RegExp(point));
  }

  assert.equal(
    [...html.matchAll(/class="opponent-card"/g)].length,
    8,
    "all eight opponent cards render",
  );
  assert.match(html, /src="\/media\/opponents\/adaptive-256\.webp"/i);
  assert.equal(
    (await stat(new URL("media/opponents/adaptive-256.webp", releaseRoot))).isFile(),
    true,
    "Vesper portrait",
  );

  assert.match(
    css,
    /\.proof-strip-inner\s*{[^}]*font-size:\s*clamp\(0\.92rem,\s*1\.15vw,\s*1\.05rem\)/s,
  );
  assert.match(
    css,
    /\.privacy-copy > \.eyebrow\s*{[^}]*font-size:\s*clamp\(0\.84rem,\s*1vw,\s*0\.94rem\)/s,
  );
  assert.match(
    css,
    /\.privacy-points span\s*{[^}]*font-size:\s*clamp\(0\.94rem,\s*1\.1vw,\s*1\.05rem\)/s,
  );
});

test("ships accessible metadata, static structured data, and no browser runtime on marketing pages", async () => {
  const pages = await Promise.all([
    releaseFile("index.html"),
    releaseFile("privacy/index.html"),
    releaseFile("support/index.html"),
    releaseFile("open-source/index.html"),
    releaseFile("404.html"),
  ]);

  for (const [index, html] of pages.entries()) {
    assert.match(html, /href="#main"[^>]*>Skip to content</i);
    assert.match(html, /<main[^>]+id="main"/i);
    const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
    if (index === 0) {
      assert.equal(scripts.length, 1, "home page has one JSON-LD data block");
      assert.match(scripts[0][1], /type="application\/ld\+json"/i);
      assert.doesNotMatch(scripts[0][1], /\bsrc=|type="module"/i);
      const structuredData = JSON.parse(scripts[0][2]);
      assert.equal(structuredData["@type"], "SoftwareApplication");
      assert.equal(structuredData.name, "Drawless Chess");
      assert.equal(structuredData.offers.price, "4.99");
      assert.equal(structuredData.offers.priceCurrency, "USD");
    } else {
      assert.equal(scripts.length, 0, "supporting marketing page has no script");
    }
    assert.doesNotMatch(html, /modulepreload|__VINEXT|vite-rsc/i);
  }

  assert.match(pages[0], /name="description"[^>]+content="[^"]*Android, iPhone, and iPad[^"]*privately on your device[^"]*"/i);
  assert.match(pages[0], /property="og:description"[^>]+content="[^"]*Android, iPhone, and iPad[^"]*privately on your device[^"]*"/i);
  assert.match(pages[0], /property="og:image"[^>]+content="https:\/\/drawlesschess\.com\/og\.png"/i);
  assert.match(pages[0], /name="twitter:card"[^>]+content="summary_large_image"/i);
  assert.match(pages[0], /name="twitter:description"[^>]+content="[^"]*Android, iPhone, and iPad[^"]*privately on your device[^"]*"/i);
  assert.match(pages[0], /name="apple-itunes-app"[^>]+content="app-id=6801584008,[^"]*id6801584008"/i);
  assert.match(pages[0], /http-equiv="Content-Security-Policy"/i);
  assert.match(pages[0], /script-src &#x27;self&#x27;/i);
  assert.doesNotMatch(pages[0], /unsafe-eval/i);
  assert.match(pages[0], /name="referrer"[^>]+content="strict-origin-when-cross-origin"/i);
  assert.match(pages[0], /rel="manifest"[^>]+href="\/site\.webmanifest"/i);
  const manifest = JSON.parse(await releaseFile("site.webmanifest"));
  assert.match(manifest.description, /on-device Game Review/i);
  assert.match(manifest.description, /iPhone, and iPad/i);
  assert.doesNotMatch(manifest.description, /beta|closed test/i);
});

test("ships a complete same-origin favicon set", async () => {
  const pages = await Promise.all([
    releaseFile("index.html"),
    releaseFile("privacy/index.html"),
    releaseFile("support/index.html"),
    releaseFile("open-source/index.html"),
    releaseFile("404.html"),
  ]);

  for (const asset of [
    "favicon.ico",
    "favicon-32x32.png",
    "apple-touch-icon.png",
    "media/app-icon-192.png",
    "media/app-icon.png",
  ]) {
    assert.equal((await stat(new URL(asset, releaseRoot))).isFile(), true, asset);
  }

  for (const html of pages) {
    assert.match(html, /rel="icon"[^>]+href="\/favicon\.ico"/i);
    assert.match(html, /rel="icon"[^>]+href="\/favicon-32x32\.png"/i);
    assert.match(html, /rel="apple-touch-icon"[^>]+href="\/apple-touch-icon\.png"/i);
    assert.doesNotMatch(html, /rel="(?:icon|apple-touch-icon)"[^>]+https:\/\//i);
  }

  const manifest = JSON.parse(await releaseFile("site.webmanifest"));
  assert.deepEqual(
    manifest.icons.map(({ src, sizes, type }) => ({ src, sizes, type })),
    [
      { src: "/media/app-icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/media/app-icon.png", sizes: "512x512", type: "image/png" },
    ],
  );
});

test("keeps pricing, privacy, support, and source promises launch-consistent", async () => {
  const homePage = await releaseFile("index.html");
  const sourcePage = await releaseFile("open-source/index.html");
  const privacyPage = await releaseFile("privacy/index.html");
  const supportPage = await releaseFile("support/index.html");

  assert.doesNotMatch(sourcePage, /free and open-source/i);
  assert.match(homePage, /One-time purchase · US price \$4\.99 · No subscriptions or in-app purchases/i);
  assert.match(sourcePage, /Android and iOS are separate builds/i);
  assert.match(sourcePage, /An Android source archive is not the corresponding source for an iOS binary/i);
  assert.match(privacyPage, /Android system backup may include/i);
  assert.match(privacyPage, /device or iCloud backup/i);
  assert.match(privacyPage, /Google Play or Apple’s App Store/i);
  assert.match(privacyPage, /Updated:<\/strong> August 19, 2026/i);
  assert.match(supportPage, /Purchases, downloads, and refunds/i);
  assert.match(supportPage, /Do not send payment-card or bank details/i);
  for (const page of [homePage, privacyPage, supportPage]) {
    assert.match(page, /support@drawlesschess\.com/);
    assert.doesNotMatch(page, /realitymaster@protonmail\.ch/);
  }
});

test("ships narrow-navigation, sitemap freshness, and readable small-copy safeguards", async () => {
  const html = await releaseFile("index.html");
  const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  const sitemap = await releaseFile("sitemap.xml");

  assert.match(html, /href="\/play\/"[^>]*>Web preview<\/a>/i);
  assert.match(css, /@media \(max-width: 760px\)[\s\S]*?\.site-nav\s*{[^}]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)[^}]*overflow:\s*visible/s);
  assert.match(css, /\.review-preview figcaption\s*{[^}]*font-size:\s*0\.8rem/s);
  assert.match(css, /\.footer-legal\s*{[^}]*font-size:\s*0\.78rem/s);
  assert.equal((sitemap.match(/<lastmod>2026-08-19<\/lastmod>/g) ?? []).length, 5);
});

test("does not publish local paths, development hosts, or common secret material", async () => {
  const pages = await Promise.all([
    releaseFile("index.html"),
    releaseFile("privacy/index.html"),
    releaseFile("support/index.html"),
    releaseFile("open-source/index.html"),
    releaseFile("404.html"),
  ]);
  const assetNames = await readdir(new URL("assets/", releaseRoot));
  const stylesheets = await Promise.all(
    assetNames
      .filter((name) => name.endsWith(".css"))
      .map((name) => releaseFile(`assets/${name}`)),
  );
  assert.ok(stylesheets.length > 0, "published stylesheet exists");
  const publishedText = [...pages, ...stylesheets].join("\n");

  for (const forbidden of [
    /[A-Z]:\\(?:Users|src|tmp)\\/i,
    /\/(?:Users|home)\/[^/<\s]+/i,
    /https?:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?/i,
    /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/i,
    /\bAIza[0-9A-Za-z_-]{30,}\b/,
    /\bgh[opsu]_[0-9A-Za-z]{30,}\b/,
    /\bsk-[0-9A-Za-z_-]{24,}\b/,
    /\b(?:storePassword|keyPassword|client_secret)\s*[=:]/i,
  ]) {
    assert.doesNotMatch(publishedText, forbidden);
  }
});
