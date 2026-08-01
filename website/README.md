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

## Release-state copy

As of July 30, 2026, the verified Play closed track serves 0.3.0 (version code 3).
The 1.0.0 candidate (version code 4), including Vesper and Game Review Beta, is not yet
on that track. Public site copy therefore distinguishes the current test build from the
next major update and intentionally omits version numbers. Update that copy only after
independently verifying the Play rollout state.

The public Android release is still in preparation. Until launch, do not claim public
availability, a launch price, or a public version number. Temporary test access may link
only to the verified tester group and closed-track Play listing. Support and privacy mail
use `support@drawlesschess.com`; mailbox delivery is an external release gate, not proven
by the site tests. Verify send and receive before deploying a site that advertises it.
