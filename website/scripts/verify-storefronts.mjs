const storefronts = [
  {
    name: "Google Play",
    publicUrl: "https://play.google.com/store/apps/details?id=com.drawlesschess",
    verificationUrl: "https://play.google.com/store/apps/details?id=com.drawlesschess&hl=en_US&gl=US",
    expectedHost: "play.google.com",
    identityPatterns: [/Drawless Chess/i, /com\.drawlesschess/i],
  },
  {
    name: "Apple App Store",
    publicUrl: "https://apps.apple.com/app/drawless-chess/id6801584008",
    verificationUrl: "https://apps.apple.com/us/app/drawless-chess/id6801584008",
    expectedHost: "apps.apple.com",
    identityPatterns: [/Drawless Chess/i, /6801584008/],
  },
];

const pricePatterns = [
  /(?:US)?\$\s*4\.99/i,
  /["']price["']\s*:\s*["']?4\.99/i,
  /["']formattedPrice["']\s*:\s*["'](?:US)?\$4\.99/i,
];

async function verifyStorefront(storefront) {
  const response = await fetch(storefront.verificationUrl, {
    redirect: "follow",
    headers: {
      Accept: "text/html,application/xhtml+xml",
      "Accept-Language": "en-US,en;q=0.9",
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36",
    },
    signal: AbortSignal.timeout(20_000),
  });
  const body = await response.text();
  const finalUrl = new URL(response.url);
  const contentType = response.headers.get("content-type") ?? "";

  if (response.status !== 200) {
    throw new Error(`${storefront.name} returned HTTP ${response.status} for a signed-out US request.`);
  }
  if (finalUrl.hostname !== storefront.expectedHost) {
    throw new Error(`${storefront.name} redirected to unexpected host ${finalUrl.hostname}.`);
  }
  if (!/^text\/html\b/i.test(contentType)) {
    throw new Error(`${storefront.name} returned unexpected content type ${contentType || "(missing)"}.`);
  }
  for (const pattern of storefront.identityPatterns) {
    if (!pattern.test(body)) throw new Error(`${storefront.name} page does not contain expected Drawless Chess identity evidence.`);
  }
  if (!pricePatterns.some((pattern) => pattern.test(body))) {
    throw new Error(`${storefront.name} page does not expose the verified US $4.99 price.`);
  }

  return {
    name: storefront.name,
    publicUrl: storefront.publicUrl,
    status: response.status,
    finalUrl: response.url,
    contentType,
    price: "USD 4.99",
  };
}

const results = [];
const failures = [];
for (const storefront of storefronts) {
  try {
    results.push(await verifyStorefront(storefront));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    failures.push({ name: storefront.name, publicUrl: storefront.publicUrl, error: message });
    console.error(`${storefront.name}: ${message}`);
  }
}

console.log(JSON.stringify({ verified: results, failed: failures }, null, 2));
if (failures.length > 0) process.exitCode = 1;
