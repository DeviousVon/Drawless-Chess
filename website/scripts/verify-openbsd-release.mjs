import { createHash } from "node:crypto";
import { lstat, readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDirectory, "..");
const textExtensions = new Set([
  ".css",
  ".html",
  ".js",
  ".json",
  ".mjs",
  ".svg",
  ".txt",
  ".webmanifest",
  ".xml",
]);
const imageExtensions = new Set([
  ".avif",
  ".gif",
  ".jpeg",
  ".jpg",
  ".png",
  ".svg",
  ".webp",
]);
const allowedAssetOrigins = new Set([
  "https://drawlesschess.com",
  "https://www.drawlesschess.com",
  ...((process.env.OPENBSD_ALLOWED_ASSET_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)),
]);

const budgets = {
  htmlGzip: integerEnvironment("OPENBSD_MAX_HTML_GZIP_BYTES", 25 * 1024),
  cssGzip: integerEnvironment("OPENBSD_MAX_CSS_GZIP_BYTES", 35 * 1024),
  firstLoad: integerEnvironment("OPENBSD_MAX_FIRST_LOAD_BYTES", 200 * 1024),
  image: integerEnvironment("OPENBSD_MAX_IMAGE_BYTES", 300 * 1024),
  total: integerEnvironment("OPENBSD_MAX_RELEASE_BYTES", 8 * 1024 * 1024),
};

function integerEnvironment(name, fallback) {
  const rawValue = process.env[name]?.trim();
  if (!rawValue) return fallback;
  if (!/^\d+$/.test(rawValue) || Number(rawValue) <= 0 || !Number.isSafeInteger(Number(rawValue))) {
    throw new Error(`${name} must be a positive integer.`);
  }
  return Number(rawValue);
}

function compareNames(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function parseArguments() {
  let root = null;
  const routes = [];
  const args = process.argv.slice(2);

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--route") {
      const route = args[index + 1];
      if (!route) throw new Error("--route requires a value.");
      routes.push(route);
      index += 1;
    } else if (argument.startsWith("--route=")) {
      routes.push(argument.slice("--route=".length));
    } else if (argument.startsWith("-")) {
      throw new Error(`Unknown option: ${argument}`);
    } else if (root === null) {
      root = argument;
    } else {
      throw new Error(`Unexpected argument: ${argument}`);
    }
  }

  return {
    releaseRoot: path.resolve(root ?? path.join(projectRoot, "release")),
    routes: routes.length > 0 ? routes : ["/"],
  };
}

async function walkFiles(root) {
  const files = [];

  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => compareNames(left.name, right.name));
    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name);
      const relativePath = path.relative(root, absolutePath);
      const info = await lstat(absolutePath);
      if (info.isSymbolicLink()) throw new Error(`Release contains symbolic link: ${toPosix(relativePath)}`);
      if (info.isDirectory()) await visit(absolutePath);
      else if (info.isFile()) files.push(absolutePath);
      else throw new Error(`Release contains unsupported entry: ${toPosix(relativePath)}`);
    }
  }

  await visit(root);
  return files;
}

async function sha256(file) {
  return createHash("sha256").update(await readFile(file)).digest("hex");
}

function safeManifestPath(releaseRoot, relativePath) {
  if (
    !relativePath ||
    relativePath.includes("\\") ||
    relativePath.startsWith("/") ||
    relativePath.split("/").includes("..")
  ) {
    throw new Error(`Unsafe path in SHA256 manifest: ${relativePath}`);
  }
  const absolutePath = path.resolve(releaseRoot, ...relativePath.split("/"));
  const relative = path.relative(releaseRoot, absolutePath);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Path escapes release root in SHA256 manifest: ${relativePath}`);
  }
  return absolutePath;
}

async function verifyIntegrity(releaseRoot, files) {
  const checksumPath = path.join(releaseRoot, "SHA256");
  const checksumText = await readFile(checksumPath, "utf8").catch(() => null);
  if (checksumText === null) throw new Error("Release is missing SHA256.");

  const expected = new Map();
  for (const line of checksumText.split(/\r?\n/).filter(Boolean)) {
    const match = line.match(/^SHA256 \((.+)\) = ([a-f0-9]{64})$/);
    if (!match) throw new Error(`Malformed SHA256 manifest line: ${line}`);
    if (expected.has(match[1])) throw new Error(`Duplicate SHA256 entry: ${match[1]}`);
    expected.set(match[1], match[2]);
  }

  const actualPaths = files
    .filter((file) => path.basename(file) !== "SHA256")
    .map((file) => toPosix(path.relative(releaseRoot, file)))
    .sort(compareNames);
  const expectedPaths = [...expected.keys()].sort(compareNames);
  if (JSON.stringify(actualPaths) !== JSON.stringify(expectedPaths)) {
    const missing = actualPaths.filter((file) => !expected.has(file));
    const extra = expectedPaths.filter((file) => !actualPaths.includes(file));
    throw new Error(`SHA256 file list mismatch; unlisted=[${missing.join(", ")}], missing=[${extra.join(", ")}].`);
  }

  for (const [relativePath, digest] of expected) {
    const file = safeManifestPath(releaseRoot, relativePath);
    if ((await sha256(file)) !== digest) throw new Error(`SHA256 mismatch: ${relativePath}`);
  }
}

function routeCandidates(route) {
  const parsed = new URL(route, "https://release.invalid/");
  if (parsed.origin !== "https://release.invalid") throw new Error(`Expected route must be local: ${route}`);
  const pathname = decodeURIComponent(parsed.pathname);
  const relative = pathname.replace(/^\/+/, "");
  if (relative === "" || pathname.endsWith("/")) return [path.posix.join(relative, "index.html")];
  if (path.posix.extname(relative)) return [relative];
  return [relative, `${relative}.html`, path.posix.join(relative, "index.html")];
}

async function verifyExpectedRoutes(releaseRoot, routes) {
  for (const route of routes) {
    let found = false;
    for (const candidate of routeCandidates(route)) {
      const file = safeManifestPath(releaseRoot, candidate);
      if ((await stat(file).catch(() => null))?.isFile()) found = true;
    }
    if (!found) throw new Error(`Expected route is missing: ${route}`);
  }
  if (!(await stat(path.join(releaseRoot, "404.html")).catch(() => null))?.isFile()) {
    throw new Error("Expected route is missing: /404.html");
  }
}

function htmlAttribute(tag, name) {
  const pattern = new RegExp(
    `\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`,
    "i",
  );
  const match = tag.match(pattern);
  return match ? decodeHtml(match[1] ?? match[2] ?? match[3] ?? "") : null;
}

function decodeHtml(value) {
  return value
    .replace(/&amp;/gi, "&")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(Number.parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, decimal) => String.fromCodePoint(Number.parseInt(decimal, 10)));
}

function publicPathForHtml(releaseRoot, file) {
  const relative = toPosix(path.relative(releaseRoot, file));
  if (relative === "index.html") return "/";
  if (relative.endsWith("/index.html")) return `/${relative.slice(0, -"index.html".length)}`;
  return `/${relative}`;
}

function publicPathForAsset(releaseRoot, file) {
  return `/${toPosix(path.relative(releaseRoot, file))}`;
}

function isSkippedUrl(value) {
  return /^(?:#|mailto:|tel:|data:|blob:|javascript:)/i.test(value.trim());
}

function parsedUrl(value, basePath) {
  const trimmed = value.trim();
  if (/^javascript:/i.test(trimmed)) {
    throw new Error(`JavaScript URL is not allowed: ${value}`);
  }
  if (!trimmed || isSkippedUrl(trimmed)) return null;
  try {
    return new URL(trimmed, `https://release.invalid${basePath}`);
  } catch {
    throw new Error(`Malformed URL: ${value}`);
  }
}

function srcsetUrls(value) {
  if (/^\s*data:/i.test(value)) return [];
  return value
    .split(",")
    .map((candidate) => candidate.trim().split(/\s+/, 1)[0])
    .filter(Boolean);
}

function isAssetLink(tag) {
  const rel = (htmlAttribute(tag, "rel") ?? "")
    .toLowerCase()
    .split(/\s+/);
  return rel.some((value) =>
    ["icon", "manifest", "modulepreload", "preload", "stylesheet"].includes(value),
  );
}

function assetReferencesInHtml(html) {
  const references = [];
  for (const match of html.matchAll(/<([a-z][\w:-]*)\b[^>]*>/gi)) {
    const tagName = match[1].toLowerCase();
    const tag = match[0];
    for (const attribute of ["src", "poster"]) {
      const value = htmlAttribute(tag, attribute);
      if (value) references.push(value);
    }
    const srcset = htmlAttribute(tag, "srcset");
    if (srcset) references.push(...srcsetUrls(srcset));
    if (tagName === "link" && isAssetLink(tag)) {
      const href = htmlAttribute(tag, "href");
      if (href) references.push(href);
    }
    if (tagName === "meta" && /^(?:og:image|twitter:image)$/i.test(htmlAttribute(tag, "property") ?? htmlAttribute(tag, "name") ?? "")) {
      const content = htmlAttribute(tag, "content");
      if (content) references.push(content);
    }
  }
  return references;
}

function localReferencesInHtml(html) {
  const references = [];
  for (const match of html.matchAll(/<([a-z][\w:-]*)\b[^>]*>/gi)) {
    const tagName = match[1].toLowerCase();
    const tag = match[0];
    if (tagName === "a") {
      const href = htmlAttribute(tag, "href");
      if (href) references.push({ value: href, route: true });
    }
    if (tagName === "link" && isAssetLink(tag)) {
      const href = htmlAttribute(tag, "href");
      if (href) references.push({ value: href, route: false });
    }
    for (const attribute of ["src", "poster"]) {
      const value = htmlAttribute(tag, attribute);
      if (value) references.push({ value, route: false });
    }
    const srcset = htmlAttribute(tag, "srcset");
    if (srcset) {
      for (const value of srcsetUrls(srcset)) references.push({ value, route: false });
    }
  }
  return references;
}

function localReferencesInCss(css) {
  const references = [];
  for (const match of css.matchAll(/url\(\s*(?:"([^"]+)"|'([^']+)'|([^\s)'";]+))\s*\)/gi)) {
    references.push(match[1] ?? match[2] ?? match[3]);
  }
  for (const match of css.matchAll(/@import\s+(?:url\(\s*)?["']([^"']+)["']/gi)) references.push(match[1]);
  return references;
}

async function localReferenceExists(releaseRoot, reference, basePath, route) {
  const url = parsedUrl(reference, basePath);
  if (!url || url.origin !== "https://release.invalid") return true;
  let pathname;
  try {
    pathname = decodeURIComponent(url.pathname);
  } catch {
    throw new Error(`URL has malformed percent encoding: ${reference}`);
  }
  const relative = pathname.replace(/^\/+/, "");
  const candidates = route ? routeCandidates(pathname) : [relative || "index.html"];
  for (const candidate of candidates) {
    const file = safeManifestPath(releaseRoot, candidate);
    if ((await stat(file).catch(() => null))?.isFile()) return true;
  }
  return false;
}

function assertNoThirdPartyAsset(reference, basePath, context) {
  const url = parsedUrl(reference, basePath);
  if (!url || url.origin === "https://release.invalid") return;
  if (allowedAssetOrigins.has(url.origin)) return;
  throw new Error(`Third-party asset URL in ${context}: ${reference}`);
}

async function verifyMarkupAndReferences(releaseRoot, files) {
  const runtimePattern = /(?:\/_vinext(?:\/|\b)|\/_next\/data(?:\/|\b)|\/api(?:\/|\b)|__VINEXT|vite-rsc|dist\/server)/i;
  const localhostPattern = /(?:localhost|127\.0\.0\.1|\[::1\])/i;

  for (const file of files) {
    const extension = path.extname(file).toLowerCase();
    if (![".css", ".html", ".json", ".webmanifest", ".xml"].includes(extension)) continue;
    if (file.endsWith(".gz") || path.basename(file) === "release.json") continue;
    const source = await readFile(file, "utf8");
    const relative = toPosix(path.relative(releaseRoot, file));
    if (localhostPattern.test(source)) throw new Error(`Localhost reference in ${relative}.`);
    if (runtimePattern.test(source)) throw new Error(`Runtime endpoint or marker in ${relative}.`);

    if (extension === ".html") {
      if (/<script\b/i.test(source)) throw new Error(`Script tag is not allowed in ${relative}.`);
      if (/<link\b[^>]*\brel\s*=\s*["'][^"']*modulepreload/i.test(source)) {
        throw new Error(`Module preload is not allowed in ${relative}.`);
      }
      const titles = [...source.matchAll(/<title\b[^>]*>([\s\S]*?)<\/title\s*>/gi)];
      if (titles.length !== 1) {
        throw new Error(`${relative} must contain exactly one title tag; found ${titles.length}.`);
      }
      const titleText = decodeHtml(titles[0][1]).replace(/<[^>]*>/g, "").trim();
      if (!titleText) throw new Error(`${relative} contains an empty title tag.`);
      const basePath = publicPathForHtml(releaseRoot, file);
      for (const reference of assetReferencesInHtml(source)) {
        assertNoThirdPartyAsset(reference, basePath, relative);
      }
      for (const reference of localReferencesInHtml(source)) {
        if (!(await localReferenceExists(releaseRoot, reference.value, basePath, reference.route))) {
          throw new Error(`Unresolved local reference in ${relative}: ${reference.value}`);
        }
      }
    }

    if (extension === ".css") {
      const basePath = publicPathForAsset(releaseRoot, file);
      for (const reference of localReferencesInCss(source)) {
        assertNoThirdPartyAsset(reference, basePath, relative);
        if (!(await localReferenceExists(releaseRoot, reference, basePath, false))) {
          throw new Error(`Unresolved local CSS reference in ${relative}: ${reference}`);
        }
      }
    }
  }
}

function isTextAsset(file) {
  return textExtensions.has(path.extname(file).toLowerCase());
}

async function verifyGzipSiblings(files) {
  const fileSet = new Set(files);
  for (const file of files) {
    if (!isTextAsset(file) || file.endsWith(".gz") || path.basename(file) === "SHA256") continue;
    const gzipPath = `${file}.gz`;
    if (!fileSet.has(gzipPath)) throw new Error(`Missing gzip sibling: ${toPosix(file)}`);
    const original = await readFile(file);
    const compressed = await readFile(gzipPath);
    if (!gunzipSync(compressed).equals(original)) throw new Error(`Gzip content mismatch: ${toPosix(gzipPath)}`);
    if (compressed.readUInt32LE(4) !== 0 || compressed[9] !== 255) {
      throw new Error(`Gzip header is not deterministic: ${toPosix(gzipPath)}`);
    }
  }
}

function verifyNoRuntimePayloads(releaseRoot, files) {
  for (const file of files) {
    const relative = toPosix(path.relative(releaseRoot, file));
    if (/[.](?:rsc|m?js)(?:[.]gz)?$/i.test(relative)) {
      throw new Error(`Runtime payload is not allowed: ${relative}`);
    }
  }
}

async function compressedSize(file) {
  const gzipPath = `${file}.gz`;
  const gzipInfo = await stat(gzipPath).catch(() => null);
  return gzipInfo?.isFile() ? gzipInfo.size : (await stat(file)).size;
}

async function localFileForReference(releaseRoot, reference, basePath) {
  const url = parsedUrl(reference, basePath);
  if (!url || url.origin !== "https://release.invalid") return null;
  const pathname = decodeURIComponent(url.pathname);
  const relative = pathname.replace(/^\/+/, "") || "index.html";
  const file = safeManifestPath(releaseRoot, relative);
  return (await stat(file).catch(() => null))?.isFile() ? file : null;
}

async function verifyBudgets(releaseRoot, files) {
  const indexPath = path.join(releaseRoot, "index.html");
  const indexHtml = await readFile(indexPath, "utf8");
  const htmlBytes = await compressedSize(indexPath);
  if (htmlBytes > budgets.htmlGzip) {
    throw new Error(`Homepage compressed HTML is ${htmlBytes} bytes; budget is ${budgets.htmlGzip}.`);
  }

  const stylesheets = new Set();
  const eagerAssets = new Set();
  for (const match of indexHtml.matchAll(/<([a-z][\w:-]*)\b[^>]*>/gi)) {
    const tagName = match[1].toLowerCase();
    const tag = match[0];
    if (tagName === "link") {
      const rel = (htmlAttribute(tag, "rel") ?? "").toLowerCase().split(/\s+/);
      const href = htmlAttribute(tag, "href");
      if (href && rel.includes("stylesheet")) {
        const file = await localFileForReference(releaseRoot, href, "/");
        if (file) stylesheets.add(file);
      }
      if (href && rel.includes("preload") && ["font", "image"].includes((htmlAttribute(tag, "as") ?? "").toLowerCase())) {
        const file = await localFileForReference(releaseRoot, href, "/");
        if (file) eagerAssets.add(file);
      }
    }
    if (tagName === "img" && (htmlAttribute(tag, "loading") ?? "").toLowerCase() !== "lazy") {
      const source = htmlAttribute(tag, "src");
      if (source) {
        const file = await localFileForReference(releaseRoot, source, "/");
        if (file) eagerAssets.add(file);
      }
    }
  }

  let cssBytes = 0;
  for (const stylesheet of stylesheets) {
    cssBytes += await compressedSize(stylesheet);
    const css = await readFile(stylesheet, "utf8");
    for (const reference of localReferencesInCss(css)) {
      const file = await localFileForReference(
        releaseRoot,
        reference,
        publicPathForAsset(releaseRoot, stylesheet),
      );
      if (file) eagerAssets.add(file);
    }
  }
  if (cssBytes > budgets.cssGzip) {
    throw new Error(`Homepage compressed CSS is ${cssBytes} bytes; budget is ${budgets.cssGzip}.`);
  }

  let eagerBytes = 0;
  for (const asset of eagerAssets) eagerBytes += await compressedSize(asset);
  const firstLoadBytes = htmlBytes + cssBytes + eagerBytes;
  if (firstLoadBytes > budgets.firstLoad) {
    throw new Error(`Estimated first load is ${firstLoadBytes} bytes; budget is ${budgets.firstLoad}.`);
  }

  for (const file of files) {
    if (!imageExtensions.has(path.extname(file).toLowerCase()) || file.endsWith(".gz")) continue;
    const size = (await stat(file)).size;
    if (size > budgets.image) {
      throw new Error(`Image exceeds ${budgets.image} byte budget: ${toPosix(path.relative(releaseRoot, file))} (${size}).`);
    }
  }

  let releaseBytes = 0;
  for (const file of files) {
    if (file.endsWith(".gz") || path.basename(file) === "SHA256") continue;
    releaseBytes += (await stat(file)).size;
  }
  if (releaseBytes > budgets.total) {
    throw new Error(`Uncompressed release is ${releaseBytes} bytes; budget is ${budgets.total}.`);
  }

  return { htmlBytes, cssBytes, eagerBytes, firstLoadBytes, releaseBytes };
}

async function main() {
  const { releaseRoot, routes } = parseArguments();
  if (!(await stat(releaseRoot).catch(() => null))?.isDirectory()) {
    throw new Error(`Release directory does not exist: ${releaseRoot}`);
  }
  const files = await walkFiles(releaseRoot);
  await verifyIntegrity(releaseRoot, files);
  await verifyExpectedRoutes(releaseRoot, routes);
  await verifyMarkupAndReferences(releaseRoot, files);
  verifyNoRuntimePayloads(releaseRoot, files);
  await verifyGzipSiblings(files);
  const measurements = await verifyBudgets(releaseRoot, files);
  const metadata = JSON.parse(await readFile(path.join(releaseRoot, "release.json"), "utf8"));
  if (metadata.artifact !== "drawlesschess-openbsd-static" || metadata.schemaVersion !== 1) {
    throw new Error("release.json has an unsupported artifact type or schema version.");
  }
  if (metadata.frameworkMarkup?.stripped !== true || metadata.clientComponents?.length !== 0) {
    throw new Error("Release was not prepared as a zero-client-component static artifact.");
  }

  console.log(
    JSON.stringify(
      {
        release: releaseRoot,
        buildId: metadata.buildId,
        routes,
        budgets: measurements,
      },
      null,
      2,
    ),
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
