import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const resourceRoot = path.join(repositoryRoot, "android", "app", "src", "main", "res");
const sourceRoot = path.join(
  repositoryRoot,
  "android",
  "app",
  "src",
  "main",
  "kotlin",
  "com",
  "drawlesschess",
  "ui",
);
const allowlistPath = path.join(repositoryRoot, "scripts", "localization-allowlist.json");
const errors = [];

function relative(file) {
  return path.relative(repositoryRoot, file).split(path.sep).join("/");
}

function readFiles(directory, predicate) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const location = path.join(directory, entry.name);
    return entry.isDirectory()
      ? readFiles(location, predicate)
      : predicate(location) ? [location] : [];
  });
}

function attribute(attributes, name) {
  const match = new RegExp(`\\b${name}\\s*=\\s*(["'])(.*?)\\1`, "s").exec(attributes);
  return match?.[2];
}

function placeholders(value) {
  const found = [];
  let implicitIndex = 1;
  const withoutLiteralPercent = value.replaceAll("%%", "");
  const matcher = /%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT]?[a-zA-Z])/g;
  for (const match of withoutLiteralPercent.matchAll(matcher)) {
    const index = Number(match[1] ?? implicitIndex++);
    found.push(`${index}:${match[2].toLowerCase()}`);
  }
  return found.sort().join(",");
}

function markup(value) {
  const tags = [];
  for (const match of value.matchAll(/<\/?([A-Za-z][\w:.-]*)\b[^>]*>/g)) {
    tags.push(`${match[0].startsWith("</") ? "/" : ""}${match[1]}`);
  }
  return tags.sort().join(",");
}

function parseItems(body) {
  const items = [];
  for (const match of body.matchAll(/<item\b([^>]*)>([\s\S]*?)<\/item>/g)) {
    items.push({ quantity: attribute(match[1], "quantity"), value: match[2].trim() });
  }
  return items;
}

function parseResourceFiles(files, label) {
  const resources = new Map();
  for (const file of files) {
    const xml = fs.readFileSync(file, "utf8").replace(/<!--[\s\S]*?-->/g, "");
    for (const match of xml.matchAll(/<(string|plurals|string-array)\b([^>]*)>([\s\S]*?)<\/\1>/g)) {
      const type = match[1];
      const attributes = match[2];
      const name = attribute(attributes, "name");
      if (!name) {
        errors.push(`${relative(file)}: ${type} resource is missing a name`);
        continue;
      }
      if (resources.has(name)) {
        errors.push(`${label}: duplicate resource key '${name}'`);
        continue;
      }
      resources.set(name, {
        type,
        translatable: attribute(attributes, "translatable") !== "false",
        value: type === "string" ? match[3].trim() : undefined,
        items: type === "string" ? undefined : parseItems(match[3]),
      });
    }
  }
  return resources;
}

function validateResourceValue(key, canonical, translated, locale) {
  if (canonical.type !== translated.type) {
    errors.push(`${locale}: '${key}' changed type from ${canonical.type} to ${translated.type}`);
    return;
  }
  if (canonical.type === "string") {
    if (placeholders(canonical.value) !== placeholders(translated.value)) {
      errors.push(`${locale}: '${key}' placeholder mapping differs from canonical English`);
    }
    if (markup(canonical.value) !== markup(translated.value)) {
      errors.push(`${locale}: '${key}' inline markup differs from canonical English`);
    }
    return;
  }

  if (translated.items.length === 0) {
    errors.push(`${locale}: '${key}' has no <item> values`);
    return;
  }
  if (canonical.type === "string-array" && canonical.items.length !== translated.items.length) {
    errors.push(`${locale}: '${key}' has ${translated.items.length} array items; expected ${canonical.items.length}`);
  }
  if (canonical.type === "plurals" && !translated.items.some((item) => item.quantity === "other")) {
    errors.push(`${locale}: plural '${key}' is missing the required 'other' quantity`);
  }
  translated.items.forEach((item, index) => {
    const source = canonical.type === "plurals"
      ? canonical.items.find((candidate) => candidate.quantity === item.quantity) ??
        canonical.items.find((candidate) => candidate.quantity === "other")
      : canonical.items[index];
    if (!source) return;
    if (placeholders(source.value) !== placeholders(item.value)) {
      const suffix = item.quantity ? ` quantity '${item.quantity}'` : ` item ${index}`;
      errors.push(`${locale}: '${key}'${suffix} placeholder mapping differs from canonical English`);
    }
    if (markup(source.value) !== markup(item.value)) {
      const suffix = item.quantity ? ` quantity '${item.quantity}'` : ` item ${index}`;
      errors.push(`${locale}: '${key}'${suffix} inline markup differs from canonical English`);
    }
  });
}

function looksLikeLocaleDirectory(name) {
  if (!name.startsWith("values-")) return false;
  const qualifier = name.slice("values-".length);
  return /^b\+[A-Za-z]{2,3}(?:\+[A-Za-z0-9]+)*$/.test(qualifier) ||
    /^[a-z]{2,3}(?:-r[A-Z]{2})?(?:-|$)/.test(qualifier);
}

function validateResources() {
  const baseDirectory = path.join(resourceRoot, "values");
  const canonical = parseResourceFiles(readFiles(baseDirectory, (file) => file.endsWith(".xml")), "values");
  const required = new Map([...canonical].filter(([, resource]) => resource.translatable));
  if (required.size === 0) {
    errors.push("values/: no translatable string, plural, or string-array resources were found");
    return;
  }
  for (const [key, resource] of required) {
    if (resource.type === "plurals" && !resource.items.some((item) => item.quantity === "other")) {
      errors.push(`values: plural '${key}' is missing the required 'other' quantity`);
    }
  }

  const localeDirectories = fs.readdirSync(resourceRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && looksLikeLocaleDirectory(entry.name));
  for (const directory of localeDirectories) {
    const localePath = path.join(resourceRoot, directory.name);
    const translated = parseResourceFiles(
      readFiles(localePath, (file) => file.endsWith(".xml")),
      directory.name,
    );
    for (const key of required.keys()) {
      if (!translated.has(key)) errors.push(`${directory.name}: missing translation key '${key}'`);
    }
    for (const key of translated.keys()) {
      if (!required.has(key)) errors.push(`${directory.name}: extra or non-translatable key '${key}'`);
    }
    for (const [key, source] of required) {
      const target = translated.get(key);
      if (target) validateResourceValue(key, source, target, directory.name);
    }
  }
}

function validateLocaleBuildConfiguration() {
  const propertiesPath = path.join(resourceRoot, "resources.properties");
  if (!fs.existsSync(propertiesPath)) {
    errors.push("resources.properties: missing canonical unqualified locale declaration");
  } else {
    const properties = fs.readFileSync(propertiesPath, "utf8");
    if (!/^unqualifiedResLocale=en-US\s*$/m.test(properties)) {
      errors.push("resources.properties: unqualifiedResLocale must be en-US");
    }
  }

  const gradlePath = path.join(repositoryRoot, "android", "app", "build.gradle.kts");
  const gradle = fs.readFileSync(gradlePath, "utf8");
  if (!/androidResources\s*\{[\s\S]*?generateLocaleConfig\s*=\s*true[\s\S]*?\}/.test(gradle)) {
    errors.push("build.gradle.kts: generated locale configuration is not enabled");
  }
  if (!/debug\s*\{[\s\S]*?isPseudoLocalesEnabled\s*=\s*true[\s\S]*?\}/.test(gradle)) {
    errors.push("build.gradle.kts: debug pseudolocales are not enabled");
  }
}

function kotlinStrings(text) {
  const matches = [];
  const matcher = /"""([\s\S]*?)"""|"((?:\\.|[^"\\])*)"/g;
  for (const match of text.matchAll(matcher)) {
    const raw = match[1] ?? match[2] ?? "";
    matches.push({
      index: match.index,
      line: text.slice(0, match.index).split("\n").length,
      value: raw.replace(/\\"/g, '"').replace(/\\n/g, "\\n"),
    });
  }
  return matches;
}

function isPlayerFacingLiteral(text, candidate) {
  if (!/[A-Za-z]/.test(candidate.value)) return false;
  const before = text.slice(Math.max(0, candidate.index - 220), candidate.index);
  const directText = /\bText\s*\([\s\S]{0,180}$/.test(before) &&
    !/\b(?:testTag|stringResource|pluralStringResource)\s*\([\s\S]{0,180}$/.test(before);
  const semanticAssignment = /\b(?:contentDescription|stateDescription|paneTitle|onClickLabel)\s*=\s*$/.test(before);
  const visibleModelAssignment = /\b(?:displayName|description|explanation|rulesLabel|modeLabel)\s*=\s*$/.test(before);
  const visibleHelper = /\b(?:SetupSection|OptionRow|StatsMessage|MetricCard|ResultMetric)\s*\([\s\S]{0,120}$/.test(before);
  return directText || semanticAssignment || visibleModelAssignment || visibleHelper;
}

function validateHardCodedStrings() {
  const parsed = JSON.parse(fs.readFileSync(allowlistPath, "utf8"));
  if (!Array.isArray(parsed.entries)) throw new Error("localization allowlist must contain an entries array");
  const allowlist = parsed.entries.map((entry, index) => {
    if (!entry.file || !entry.value || !entry.reason) {
      throw new Error(`localization allowlist entry ${index} requires file, value, and reason`);
    }
    return { ...entry, used: false };
  });

  for (const file of readFiles(sourceRoot, (location) => location.endsWith(".kt"))) {
    const text = fs.readFileSync(file, "utf8");
    const fileName = relative(file);
    for (const candidate of kotlinStrings(text).filter((item) => isPlayerFacingLiteral(text, item))) {
      const allowed = allowlist.find(
        (entry) => entry.file === fileName && entry.value === candidate.value,
      );
      if (allowed) {
        allowed.used = true;
      } else {
        errors.push(
          `${fileName}:${candidate.line}: hard-coded player-facing string ${JSON.stringify(candidate.value)}`,
        );
      }
    }
  }
  for (const entry of allowlist.filter((item) => !item.used)) {
    errors.push(`unused localization allowlist entry: ${entry.file} ${JSON.stringify(entry.value)}`);
  }
}

validateLocaleBuildConfiguration();
validateResources();
validateHardCodedStrings();

if (errors.length > 0) {
  console.error(`Localization validation failed with ${errors.length} issue(s):`);
  errors.forEach((error) => console.error(`  - ${error}`));
  process.exitCode = 1;
} else {
  console.log("Localization validation passed: resources, placeholders, markup, and Kotlin UI literals are clean.");
}
