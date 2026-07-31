import assert from "node:assert/strict";
import { readFile, readdir, stat } from "node:fs/promises";
import test from "node:test";

const releaseRoot = new URL("../release/", import.meta.url);

async function releaseFile(path) {
  return readFile(new URL(path, releaseRoot), "utf8");
}

test("exports every public route and the custom not-found page", async () => {
  const routes = [
    "index.html",
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

test("renders the major-update story without presenting it as the live Play build", async () => {
  const html = await releaseFile("index.html");

  assert.match(html, /<html[^>]+lang="en"/i);
  assert.match(html, /Every game has a winner\./);
  assert.match(html, /next closed-test update adds Vesper as the eighth[\s\S]*Drawless-tuned Fairy-Stockfish engine/i);
  assert.match(html, /id="review"/i);
  assert.match(html, /The game ends\. The learning starts\./);
  assert.match(html, /Familiar chess\. Decisive endings\./);
  assert.match(html, /Bare king loses/);
  assert.match(html, /After 50 moves without a pawn move or capture, material points decide the winner\./);
  assert.doesNotMatch(html, /No automatic 50-move draw|There is no automatic 50-move ending/i);
  assert.match(html, /Closed Android\s*test open · major update in preparation/);
  assert.match(html, /current Play build remains in the closed test/i);
  assert.match(html, /major update with Vesper and Game Review Beta is still in preparation[\s\S]*for that track/i);
  assert.match(html, /public release is (?:still )?in\s*preparation/i);
  assert.match(html, /Level names are descriptive—not Elo claims\./);
  assert.match(html, /Meet Vesper\. Then climb the ranks\./);
  assert.match(html, /<strong>Vesper<\/strong><span>Adaptive<\/span>/);
  assert.ok(
    html.indexOf("Vesper") < html.indexOf("Mira"),
    "Vesper leads the opponent roster",
  );
  assert.match(html, /No internet permission/);
  assert.doesNotMatch(html, /\b(?:0\.3\.0|1\.0\.0|version code)\b/i);
  assert.doesNotMatch(html, /Download now|Get it on Google Play|Available now/i);
  assert.doesNotMatch(html, /Codex is working|starter loading skeleton/i);
});

test("explains the rules-aware, private Game Review beta without overclaiming", async () => {
  const html = await releaseFile("index.html");
  const openSource = await releaseFile("open-source/index.html");

  for (const claim of [
    "Game Review Beta",
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
    "Reviews are not saved as a review history",
    "Game Review remains in beta",
  ]) {
    assert.match(html, new RegExp(claim, "i"));
  }

  assert.match(html, /href="\/#review"[^>]*>Game review/i);
  assert.match(openSource, /modified Fairy-Stockfish engine, derived from Stockfish/i);
  assert.doesNotMatch(html, /accuracy (?:score|percentage)|reviews? every move/i);
});

test("publishes the temporary closed-test access flow in the right order", async () => {
  const html = await releaseFile("index.html");
  const groupUrl = "https://groups.google.com/g/drawless-chess-testers";
  const downloadUrl = "https://play.google.com/store/apps/details?id=com.drawlesschess";

  assert.match(html, /id="beta"/i);
  assert.match(html, /Closed Android test/i);
  assert.match(html, /Use the same Google account for both steps/i);
  assert.match(html, new RegExp(groupUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(html, new RegExp(downloadUrl.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.ok(html.indexOf(groupUrl) < html.indexOf(downloadUrl), "group step precedes download");
  assert.match(html, /href="\/#beta"[^>]*>Test access/i);
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
    html.indexOf('class="hero-artwork"') < html.indexOf("Offline chess · Decisive by design"),
    "hero artwork precedes the eyebrow",
  );

  assert.ok(gameplay, "gameplay preview");
  assert.match(gameplay, /loading="lazy"/i);
  assert.doesNotMatch(gameplay, /fetchpriority="high"/i);
  assert.match(css, /\.theme-preview img\s*{\s*width:\s*100%;\s*height:\s*auto;\s*}/i);
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
    "No internet permission",
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

test("ships accessible metadata and no browser-side runtime", async () => {
  const pages = await Promise.all([
    releaseFile("index.html"),
    releaseFile("privacy/index.html"),
    releaseFile("support/index.html"),
    releaseFile("open-source/index.html"),
    releaseFile("404.html"),
  ]);

  for (const html of pages) {
    assert.match(html, /href="#main"[^>]*>Skip to content</i);
    assert.match(html, /<main[^>]+id="main"/i);
    assert.doesNotMatch(html, /<script\b/i);
    assert.doesNotMatch(html, /modulepreload|__VINEXT|vite-rsc/i);
  }

  assert.match(pages[0], /name="description"[^>]+content="[^"]*next closed-test update[^"]*Game Review Beta[^"]*"/i);
  assert.match(pages[0], /property="og:description"[^>]+content="[^"]*next closed-test update[^"]*Game Review Beta[^"]*Drawless-tuned Fairy-Stockfish[^"]*"/i);
  assert.match(pages[0], /property="og:image"[^>]+content="https:\/\/drawlesschess\.com\/og\.png"/i);
  assert.match(pages[0], /name="twitter:card"[^>]+content="summary_large_image"/i);
  assert.match(pages[0], /name="twitter:description"[^>]+content="[^"]*Next closed-test update[^"]*Game Review Beta[^"]*Drawless-tuned Fairy-Stockfish[^"]*"/i);
  assert.match(pages[0], /rel="manifest"[^>]+href="\/site\.webmanifest"/i);
  const manifest = JSON.parse(await releaseFile("site.webmanifest"));
  assert.match(manifest.description, /next closed-test update/i);
  assert.match(manifest.description, /Game Review Beta/i);
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

test("keeps pricing and release claims out until launch", async () => {
  const homePage = await releaseFile("index.html");
  const sourcePage = await releaseFile("open-source/index.html");
  const privacyPage = await releaseFile("privacy/index.html");
  const supportPage = await releaseFile("support/index.html");

  assert.doesNotMatch(sourcePage, /free and open-source/i);
  assert.match(sourcePage, /public Android release is still in preparation/i);
  assert.match(privacyPage, /Android’s system backup may include/i);
  assert.match(privacyPage, /Updated:<\/strong> July 30, 2026/i);
  for (const page of [homePage, privacyPage, supportPage]) {
    assert.match(page, /support@drawlesschess\.com/);
    assert.doesNotMatch(page, /realitymaster@protonmail\.ch/);
  }
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
