# Drawless Chess website

The static marketing, support, and casual-play site for
[drawlesschess.com](https://drawlesschess.com). It is intentionally serverless at runtime:
the build produces plain HTML, CSS, images, a single isolated `/play/` browser bundle, and
metadata for OpenBSD `httpd`.

## Requirements

- Node.js 22.13 or newer
- pnpm 11

## Local development

```powershell
pnpm install
pnpm run dev
```

The site uses system fonts and project-local images. Marketing and support routes have no
browser-side client components; `/play/` is the only interactive route.

## Build and verify

```powershell
pnpm test
```

That command builds the static export, prepares `release/`, and verifies:

- the homepage, play, privacy, support, open-source, and 404 routes;
- all local links and asset references;
- absence of browser-side framework JavaScript on marketing and support routes;
- the isolated same-origin `/play/` module, worker, and pinned WebAssembly payload;
- deterministic gzip siblings and SHA-256 checksums; and
- compressed HTML, CSS, image, first-load, and total-release budgets.

The verified OpenBSD payload is written to `release/`. Deploy the payload as an
immutable release directory and switch the site's `current` symlink atomically.

## Storefront and release-source boundaries

Storefront availability is external state and must be verified independently before each
production deployment. The site uses the canonical App Store and Google Play destinations,
states the US price as $4.99, and notes that local storefront prices may vary.

Android and iOS have separate immutable corresponding-source releases. The open-source page
links Android 1.0.2 (code 6) and iOS 1.0.2 (build 2) separately so neither platform is routed
to the other platform's source archive.

The marketing pages describe Android Game Review as private and on-device without implying
that the App Store build contains it. They do not claim that reviews are retained as a separate
history, and they preserve the distinction between the shipped mobile engines and the lightweight
browser preview.

Support and privacy mail use `support@drawlesschess.com`. Mail delivery and each public store
destination remain separate operational checks; a successful static-site build does not prove
either one.
