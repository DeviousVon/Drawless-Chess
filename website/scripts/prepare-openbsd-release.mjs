import { createHash } from "node:crypto";
import {
  copyFile,
  lstat,
  mkdir,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDirectory, "..");
const inputRoot = path.resolve(
  process.argv[2] ?? path.join(projectRoot, "dist", "client"),
);
const releaseRoot = path.resolve(
  process.argv[3] ?? path.join(projectRoot, "release"),
);

const sourceExtensions = [".tsx", ".ts", ".jsx", ".js", ".mjs", ".cjs"];
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

function compareNames(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function isInside(parent, child) {
  const relative = path.relative(parent, child);
  return relative !== "" && !relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative);
}

function shouldCopy(relativePath) {
  const normalized = toPosix(relativePath);
  const segments = normalized.split("/");
  const basename = segments.at(-1);

  if (segments.includes(".vite") || segments.includes(".openai")) return false;
  if (normalized === "_headers" || normalized === ".assetsignore") return false;
  if (basename === ".DS_Store" || basename === "Thumbs.db") return false;
  if (normalized.endsWith(".map") || normalized.endsWith(".gz")) return false;
  return true;
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

      if (info.isSymbolicLink()) {
        throw new Error(`Refusing symbolic link in release input: ${toPosix(relativePath)}`);
      }
      if (info.isDirectory()) {
        await visit(absolutePath);
      } else if (info.isFile()) {
        files.push(absolutePath);
      } else {
        throw new Error(`Unsupported filesystem entry in release input: ${toPosix(relativePath)}`);
      }
    }
  }

  await visit(root);
  return files;
}

async function copyStaticExport() {
  const inputInfo = await stat(inputRoot).catch(() => null);
  if (!inputInfo?.isDirectory()) {
    throw new Error(`Static export directory does not exist: ${inputRoot}`);
  }
  if (
    inputRoot === releaseRoot ||
    isInside(inputRoot, releaseRoot) ||
    isInside(releaseRoot, inputRoot)
  ) {
    throw new Error(
      "The release and static export directories must not contain one another.",
    );
  }

  await rm(releaseRoot, { recursive: true, force: true });
  await mkdir(releaseRoot, { recursive: true });

  for (const sourcePath of await walkFiles(inputRoot)) {
    const relativePath = path.relative(inputRoot, sourcePath);
    if (!shouldCopy(relativePath)) continue;

    const targetPath = path.join(releaseRoot, relativePath);
    await mkdir(path.dirname(targetPath), { recursive: true });
    await copyFile(sourcePath, targetPath);
  }
}

async function ensureNotFoundPage() {
  const target = path.join(releaseRoot, "404.html");
  if ((await stat(target).catch(() => null))?.isFile()) return false;

  const candidates = [
    "_not-found/index.html",
    "_not-found.html",
    "not-found/index.html",
    "not-found.html",
    "404/index.html",
  ];

  for (const candidate of candidates) {
    const source = path.join(releaseRoot, ...candidate.split("/"));
    if ((await stat(source).catch(() => null))?.isFile()) {
      await copyFile(source, target);
      return true;
    }
  }

  throw new Error(
    "The static export has no 404.html or recognizable exported not-found page.",
  );
}

async function findSourceFile(candidate) {
  const candidates = [];
  if (path.extname(candidate)) candidates.push(candidate);
  else {
    for (const extension of sourceExtensions) candidates.push(`${candidate}${extension}`);
    for (const extension of sourceExtensions) candidates.push(path.join(candidate, `index${extension}`));
  }

  for (const possiblePath of candidates) {
    if ((await stat(possiblePath).catch(() => null))?.isFile()) return possiblePath;
  }
  return null;
}

function importSpecifiers(source) {
  const specifiers = new Set();
  const patterns = [
    /\b(?:import|export)\s+(?!type\b)(?:[^"'`]*?\s+from\s*)?["']([^"']+)["']/g,
    /\bimport\s*\(\s*["']([^"']+)["']\s*\)/g,
  ];

  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) specifiers.add(match[1]);
  }
  return [...specifiers].sort(compareNames);
}

async function resolveSourceImport(importer, specifier) {
  const cleanSpecifier = specifier.split(/[?#]/, 1)[0];
  let candidate;

  if (cleanSpecifier.startsWith("@/")) {
    candidate = path.join(projectRoot, cleanSpecifier.slice(2));
  } else if (cleanSpecifier.startsWith(".")) {
    candidate = path.resolve(path.dirname(importer), cleanSpecifier);
  } else {
    return null;
  }

  return findSourceFile(candidate);
}

async function routeEntryFiles() {
  const entries = [];
  const appRoot = path.join(projectRoot, "app");
  const pagesRoot = path.join(projectRoot, "pages");
  const appInfo = await stat(appRoot).catch(() => null);

  if (appInfo?.isDirectory()) {
    const specialNames = new Set([
      "default",
      "error",
      "global-error",
      "layout",
      "loading",
      "not-found",
      "page",
      "template",
    ]);
    for (const file of await walkFiles(appRoot)) {
      if (!sourceExtensions.includes(path.extname(file))) continue;
      if (specialNames.has(path.basename(file, path.extname(file)))) entries.push(file);
    }
  }

  const pagesInfo = await stat(pagesRoot).catch(() => null);
  if (pagesInfo?.isDirectory()) {
    for (const file of await walkFiles(pagesRoot)) {
      if (!sourceExtensions.includes(path.extname(file))) continue;
      const relative = toPosix(path.relative(pagesRoot, file));
      if (!relative.startsWith("api/")) entries.push(file);
    }
  }

  return entries.sort(compareNames);
}

async function reachableClientComponents() {
  const queue = await routeEntryFiles();
  const visited = new Set();
  const clients = [];

  while (queue.length > 0) {
    const file = queue.shift();
    if (visited.has(file)) continue;
    visited.add(file);

    const source = await readFile(file, "utf8");
    if (/(?:^|\n)\s*["']use client["']\s*;?/.test(source)) clients.push(file);

    for (const specifier of importSpecifiers(source)) {
      const dependency = await resolveSourceImport(file, specifier);
      if (dependency && !visited.has(dependency)) queue.push(dependency);
    }
    queue.sort(compareNames);
  }

  return clients
    .map((file) => toPosix(path.relative(projectRoot, file)))
    .sort(compareNames);
}

function htmlAttribute(tag, name) {
  const pattern = new RegExp(
    `\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`,
    "i",
  );
  const match = tag.match(pattern);
  return match ? (match[1] ?? match[2] ?? match[3] ?? "") : null;
}

function isFrameworkScript(openingTag, body) {
  const type = (htmlAttribute(openingTag, "type") ?? "").toLowerCase();
  const source = htmlAttribute(openingTag, "src") ?? "";

  if (type === "application/ld+json" || type === "application/json") return false;
  if (type === "module") return true;
  if (/\.(?:m?js)(?:[?#]|$)/i.test(source)) return true;
  if (/(?:\/_next\/|\/_vinext\/|\/assets\/)/i.test(source)) return true;
  return /(?:__VINEXT|__next_f|vite-rsc|self\.__|\bimport\s*\()/i.test(body);
}

function stripFrameworkMarkup(html) {
  let scripts = 0;
  let modulePreloads = 0;
  let runtimeAttributes = 0;
  let duplicateTitles = 0;

  let transformed = html.replace(
    /<script\b[^>]*>[\s\S]*?<\/script\s*>/gi,
    (tag) => {
      const endOfOpeningTag = tag.indexOf(">");
      const openingTag = tag.slice(0, endOfOpeningTag + 1);
      const body = tag.slice(endOfOpeningTag + 1, tag.toLowerCase().lastIndexOf("</script"));
      if (!isFrameworkScript(openingTag, body)) return tag;
      scripts += 1;
      return "";
    },
  );

  transformed = transformed.replace(/<link\b[^>]*>/gi, (tag) => {
    const rel = (htmlAttribute(tag, "rel") ?? "")
      .toLowerCase()
      .split(/\s+/)
      .filter(Boolean);
    const as = (htmlAttribute(tag, "as") ?? "").toLowerCase();
    const href = htmlAttribute(tag, "href") ?? "";
    const isScriptPreload = rel.includes("preload") && (as === "script" || /\.m?js(?:[?#]|$)/i.test(href));

    if (!rel.includes("modulepreload") && !isScriptPreload) return tag;
    modulePreloads += 1;
    return "";
  });

  transformed = transformed.replace(
    /\s+data-rsc-css-href(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?/gi,
    () => {
      runtimeAttributes += 1;
      return "";
    },
  );
  transformed = transformed.replace(
    /\s+data-precedence\s*=\s*(?:"vite-rsc\/importer-resources"|'vite-rsc\/importer-resources'|vite-rsc\/importer-resources)/gi,
    () => {
      runtimeAttributes += 1;
      return "";
    },
  );

  const titleTags = [...transformed.matchAll(/<title\b[^>]*>[\s\S]*?<\/title\s*>/gi)];
  if (titleTags.length > 1) {
    const lastTitleOffset = titleTags.at(-1).index;
    transformed = transformed.replace(
      /<title\b[^>]*>[\s\S]*?<\/title\s*>/gi,
      (tag, offset) => {
        if (offset === lastTitleOffset) return tag;
        duplicateTitles += 1;
        return "";
      },
    );
  }

  return {
    html: transformed,
    scripts,
    modulePreloads,
    runtimeAttributes,
    duplicateTitles,
  };
}

async function transformHtml(clientComponents) {
  const result = {
    files: 0,
    scripts: 0,
    modulePreloads: 0,
    runtimeAttributes: 0,
    duplicateTitles: 0,
  };
  if (clientComponents.length > 0) return result;

  for (const file of await walkFiles(releaseRoot)) {
    if (path.extname(file).toLowerCase() !== ".html") continue;
    const source = await readFile(file, "utf8");
    const transformed = stripFrameworkMarkup(source);
    await writeFile(file, transformed.html, "utf8");
    result.files += 1;
    result.scripts += transformed.scripts;
    result.modulePreloads += transformed.modulePreloads;
    result.runtimeAttributes += transformed.runtimeAttributes;
    result.duplicateTitles += transformed.duplicateTitles;
  }
  return result;
}

async function pruneRuntimeArtifacts(clientComponents) {
  if (clientComponents.length > 0) return [];

  const removed = [];
  for (const file of await walkFiles(releaseRoot)) {
    if (!/[.](?:rsc|m?js)$/i.test(file)) continue;
    removed.push(toPosix(path.relative(releaseRoot, file)));
    await rm(file, { force: true });
  }
  return removed.sort(compareNames);
}

function isTextAsset(file) {
  return textExtensions.has(path.extname(file).toLowerCase());
}

function deterministicGzip(content) {
  const compressed = gzipSync(content, { level: 9 });
  compressed.writeUInt32LE(0, 4);
  compressed[9] = 255;
  return compressed;
}

async function precompressTextAssets() {
  for (const file of await walkFiles(releaseRoot)) {
    if (!isTextAsset(file) || file.endsWith(".gz")) continue;
    await writeFile(`${file}.gz`, deterministicGzip(await readFile(file)));
  }
}

async function fileSha256(file) {
  return createHash("sha256").update(await readFile(file)).digest("hex");
}

async function payloadEntries(excludedBasenames = new Set()) {
  const entries = [];
  for (const file of await walkFiles(releaseRoot)) {
    if (excludedBasenames.has(path.basename(file))) continue;
    const relativePath = toPosix(path.relative(releaseRoot, file));
    entries.push({ path: relativePath, sha256: await fileSha256(file) });
  }
  entries.sort((left, right) => compareNames(left.path, right.path));
  return entries;
}

function aggregateSha256(entries) {
  const hash = createHash("sha256");
  for (const entry of entries) hash.update(`${entry.path}\0${entry.sha256}\n`);
  return hash.digest("hex");
}

function sourceDate() {
  const rawValue = process.env.SOURCE_DATE_EPOCH?.trim();
  if (!rawValue) return { epoch: null, iso: null };
  if (!/^\d+$/.test(rawValue)) {
    throw new Error("SOURCE_DATE_EPOCH must be a non-negative integer number of seconds.");
  }
  const epoch = Number(rawValue);
  if (!Number.isSafeInteger(epoch)) throw new Error("SOURCE_DATE_EPOCH is outside the safe integer range.");
  const date = new Date(epoch * 1000);
  if (Number.isNaN(date.valueOf())) throw new Error("SOURCE_DATE_EPOCH is not a valid date.");
  return { epoch, iso: date.toISOString() };
}

async function writeReleaseMetadata(clientComponents, transformResult, synthesized404) {
  const entriesBeforeMetadata = await payloadEntries(new Set(["SHA256", "release.json"]));
  const buildId = aggregateSha256(entriesBeforeMetadata);
  const timestamp = sourceDate();
  const input = toPosix(path.relative(projectRoot, inputRoot)) || ".";
  const metadata = {
    schemaVersion: 1,
    artifact: "drawlesschess-openbsd-static",
    buildId,
    generatedAt: timestamp.iso,
    sourceDateEpoch: timestamp.epoch,
    input,
    clientComponents,
    frameworkMarkup: {
      stripped: clientComponents.length === 0,
      htmlFilesProcessed: transformResult.files,
      scriptTagsRemoved: transformResult.scripts,
      preloadTagsRemoved: transformResult.modulePreloads,
      runtimeAttributesRemoved: transformResult.runtimeAttributes,
      duplicateTitleTagsRemoved: transformResult.duplicateTitles,
    },
    runtimeArtifactsRemoved: transformResult.runtimeArtifactsRemoved,
    synthesized404,
    compression: "gzip-static; level=9; mtime=0; os=unknown",
    integrity: "SHA256",
  };

  const metadataPath = path.join(releaseRoot, "release.json");
  await writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, "utf8");
  await writeFile(`${metadataPath}.gz`, deterministicGzip(await readFile(metadataPath)));

  const entries = await payloadEntries(new Set(["SHA256"]));
  for (const entry of entries) {
    if (/[\r\n)]/.test(entry.path)) {
      throw new Error(`Unsupported path in checksum manifest: ${entry.path}`);
    }
  }
  const checksumText = entries
    .map((entry) => `SHA256 (${entry.path}) = ${entry.sha256}`)
    .join("\n");
  await writeFile(path.join(releaseRoot, "SHA256"), `${checksumText}\n`, "utf8");
  return metadata;
}

async function main() {
  await copyStaticExport();
  const synthesized404 = await ensureNotFoundPage();
  const clientComponents = await reachableClientComponents();
  const transformResult = await transformHtml(clientComponents);
  transformResult.runtimeArtifactsRemoved = await pruneRuntimeArtifacts(clientComponents);
  await precompressTextAssets();
  const metadata = await writeReleaseMetadata(
    clientComponents,
    transformResult,
    synthesized404,
  );

  if (clientComponents.length > 0) {
    console.warn(
      `Static release preserved framework scripts because reachable client components were found: ${clientComponents.join(", ")}`,
    );
  }
  console.log(
    JSON.stringify(
      {
        release: releaseRoot,
        buildId: metadata.buildId,
        frameworkMarkupStripped: metadata.frameworkMarkup.stripped,
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
