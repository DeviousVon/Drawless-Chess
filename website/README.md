# Drawless Chess website

The static marketing and support site for [drawlesschess.com](https://drawlesschess.com).
It is intentionally serverless at runtime: the build produces plain HTML, CSS, images,
and metadata for OpenBSD `httpd`.

## Requirements

- Node.js 22.13 or newer
- pnpm 11

## Local development

```powershell
pnpm install
pnpm run dev
```

The site uses system fonts, project-local images, and no browser-side client components.

## Build and verify

```powershell
pnpm test
```

That command builds the static export, prepares `release/`, and verifies:

- the homepage, privacy, support, open-source, and 404 routes;
- all local links and asset references;
- absence of browser-side framework JavaScript;
- deterministic gzip siblings and SHA-256 checksums; and
- compressed HTML, CSS, image, first-load, and total-release budgets.

The verified OpenBSD payload is written to `release/`. Deploy the payload as an
immutable release directory and switch the site's `current` symlink atomically.

## Release-state copy

The public Android release is still in preparation. Until launch, do not claim public
availability, a launch price, or a public version number. Temporary beta access may link
only to the verified tester group and closed-track Play listing. Support and privacy mail
use `support@drawlesschess.com`; configure and test that mailbox before the production
site launches.
