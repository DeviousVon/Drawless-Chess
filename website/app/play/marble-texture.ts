const CACHE = new Map<string, string>();

function javaHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = Math.imul(hash, 31) + value.charCodeAt(index);
  }
  return hash | 0;
}

function seededRandom(seed: number) {
  let state = seed || 0x6d2b79f5;
  const next = () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x1_0000_0000;
  };
  const integer = (minimum: number, maximumExclusive: number) =>
    minimum + Math.floor(next() * (maximumExclusive - minimum));
  return { next, integer };
}

function colorWithJitter(rgb: readonly [number, number, number], jitter: number): string {
  return `#${rgb.map((channel) => Math.max(0, Math.min(255, channel + jitter)).toString(16).padStart(2, "0")).join("")}`;
}

interface Point { x: number; y: number }

function pointText(value: number): string {
  return value.toFixed(2);
}

/**
 * A browser-native port of the Android Imperial Marble surface: exact board colors,
 * soft mineral clouds, tapered wandering veins, branches, and a stable cut per square.
 */
function marbleSvg(square: string, light: boolean): string {
  const file = square.charCodeAt(0) - 97;
  const rank = Number(square[1]) - 1;
  const seed = Math.imul(Math.imul(javaHash("marble"), 31) + file, 31) + rank;
  const rng = seededRandom(seed);
  const base = colorWithJitter(light ? [242, 240, 235] : [52, 74, 63], rng.integer(-5, 6));
  const elements: string[] = [`<rect width="100" height="100" fill="${base}"/>`];

  for (let index = 0, count = rng.integer(4, 7); index < count; index += 1) {
    const tint = light
      ? (rng.next() < 0.7 ? "#c4c6c8" : "#ded8c8")
      : (rng.next() < 0.6 ? "#26382f" : "#546e60");
    elements.push(
      `<circle cx="${pointText(rng.next() * 100)}" cy="${pointText(rng.next() * 100)}" r="${pointText(20 + rng.next() * 30)}" fill="${tint}" fill-opacity=".04"/>`,
    );
  }

  const vein = (
    start: Point,
    angleStart: number,
    width: number,
    tint: string,
    alpha: number,
    steps: number,
    wobble: number,
  ): Point[] => {
    let angle = angleStart;
    let point = start;
    const points: Point[] = [point];
    for (let index = 0; index < steps; index += 1) {
      angle += (rng.next() * 2 - 1) * wobble;
      const step = 1.04 + rng.next() * 1.04;
      point = { x: point.x + step * Math.cos(angle), y: point.y + step * Math.sin(angle) };
      points.push(point);
    }
    const path = points.map((item, index) => `${index ? "L" : "M"}${pointText(item.x)} ${pointText(item.y)}`).join(" ");
    elements.push(
      `<path d="${path}" fill="none" stroke="${tint}" stroke-opacity="${alpha.toFixed(3)}" stroke-width="${width.toFixed(2)}" stroke-linecap="round"/>`,
    );
    return points;
  };

  for (let veinIndex = 0, count = rng.integer(2, 4); veinIndex < count; veinIndex += 1) {
    const fromTop = rng.next() < 0.5;
    const start = fromTop ? { x: rng.next() * 100, y: -5 } : { x: -5, y: rng.next() * 100 };
    const angle = fromTop ? 1.57 + (rng.next() * 1.8 - 0.9) : rng.next() * 0.85 - 0.4;
    const tint = light
      ? (rng.next() < 0.8 ? "#96989e" : "#ac9876")
      : (rng.next() < 0.75 ? "#d6ded2" : "#96b29e");
    const bold = veinIndex === 0;
    const alpha = ((light ? 46 : 40) + rng.integer(0, 35) + (bold ? 26 : 0)) / 255;
    const points = vein(
      start,
      angle,
      bold ? 2.34 + rng.next() * 1.04 : 1.04 + rng.next() * 0.73,
      tint,
      alpha,
      bold ? rng.integer(60, 90) : rng.integer(40, 70),
      0.3,
    );
    for (let branch = 0, branches = rng.integer(1, 4); branch < branches; branch += 1) {
      const branchPoint = points[rng.integer(Math.floor(points.length / 4), points.length - 1)];
      vein(branchPoint, rng.next() * Math.PI * 2, 0.42 + rng.next() * 0.42, tint, alpha * 0.6, rng.integer(14, 30), 0.5);
    }
  }

  for (let index = 0, count = rng.integer(3, 7); index < count; index += 1) {
    vein(
      { x: rng.next() * 100, y: rng.next() * 100 },
      rng.next() * Math.PI * 2,
      0.47,
      light ? "#a8aaaf" : "#bcc8bc",
      0.12,
      rng.integer(10, 26),
      0.6,
    );
  }

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" preserveAspectRatio="none">${elements.join("")}</svg>`;
}

export function marbleSquareBackground(square: string, light: boolean): string {
  const key = `${square}:${light ? "light" : "dark"}`;
  const cached = CACHE.get(key);
  if (cached) return cached;
  const background = `url("data:image/svg+xml,${encodeURIComponent(marbleSvg(square, light))}")`;
  CACHE.set(key, background);
  return background;
}
