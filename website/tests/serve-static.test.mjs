import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { stat } from "node:fs/promises";
import { request } from "node:http";
import test, { after, before } from "node:test";

const websiteRoot = new URL("../", import.meta.url);
const releaseRoot = new URL("../release/", import.meta.url);
let server;
let baseUrl;

before(async () => {
  server = spawn(process.execPath, ["scripts/serve-static.mjs", "release", "0", "127.0.0.1"], {
    cwd: websiteRoot,
    stdio: ["ignore", "pipe", "pipe"],
  });

  baseUrl = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Static test server did not start.")), 10_000);
    let stderr = "";
    server.stderr.setEncoding("utf8");
    server.stderr.on("data", (chunk) => { stderr += chunk; });
    server.once("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`Static test server exited with ${code}: ${stderr}`));
    });
    server.stdout.setEncoding("utf8");
    server.stdout.on("data", (chunk) => {
      const match = chunk.match(/Serving .+ at (http:\/\/127\.0\.0\.1:\d+\/)/);
      if (!match) return;
      clearTimeout(timeout);
      resolve(match[1]);
    });
  });
});

after(() => {
  server?.kill();
});

test("serves the browser-security and cache baseline", async () => {
  const response = await fetch(baseUrl);

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-security-policy") ?? "", /default-src 'self'/);
  assert.doesNotMatch(response.headers.get("content-security-policy") ?? "", /unsafe-eval/);
  assert.equal(response.headers.get("x-frame-options"), "DENY");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.equal(response.headers.get("referrer-policy"), "strict-origin-when-cross-origin");
  assert.match(response.headers.get("permissions-policy") ?? "", /camera=\(\)/);
  assert.equal(response.headers.get("cache-control"), "no-cache");
  assert.equal(response.headers.get("content-type"), "text/html; charset=utf-8");

  const playResponse = await fetch(new URL("play/", baseUrl));
  assert.match(playResponse.headers.get("content-security-policy") ?? "", /script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'/);
});

test("selects deterministic gzip siblings for compressible assets", async () => {
  const html = await (await fetch(new URL("play/", baseUrl))).text();
  const scriptPath = html.match(/<script\b[^>]*\bsrc="([^"]+\.js)"/i)?.[1];
  assert.ok(scriptPath, "play entry script");

  for (const pathname of [scriptPath, "/game/ffish-0.7.9.wasm"]) {
    const response = await fetch(new URL(pathname, baseUrl), {
      method: "HEAD",
      headers: { "Accept-Encoding": "gzip" },
    });
    const gzipInfo = await stat(new URL(`${pathname.replace(/^\//, "")}.gz`, releaseRoot));
    assert.equal(response.status, 200, pathname);
    assert.equal(response.headers.get("content-encoding"), "gzip", pathname);
    assert.equal(Number(response.headers.get("content-length")), gzipInfo.size, pathname);
    assert.match(response.headers.get("vary") ?? "", /Accept-Encoding/i, pathname);
  }
});

test("preserves byte ranges and conditional requests", async () => {
  const target = new URL("game/ffish-0.7.9.wasm", baseUrl);
  const ranged = await fetch(target, {
    headers: { Range: "bytes=0-15", "Accept-Encoding": "identity" },
  });
  assert.equal(ranged.status, 206);
  assert.equal(ranged.headers.get("content-range"), "bytes 0-15/920681");
  assert.equal(ranged.headers.get("content-encoding"), null);
  assert.equal((await ranged.arrayBuffer()).byteLength, 16);

  const initial = await fetch(target, { method: "HEAD", headers: { "Accept-Encoding": "identity" } });
  const etag = initial.headers.get("etag");
  assert.ok(etag);
  const conditional = await fetch(target, {
    headers: { "If-None-Match": etag, "Accept-Encoding": "identity" },
  });
  assert.equal(conditional.status, 304);
});

test("returns genuine errors and limits methods", async () => {
  assert.equal((await fetch(new URL("missing-page", baseUrl))).status, 404);
  const trace = await new Promise((resolve, reject) => {
    const call = request(baseUrl, { method: "TRACE" }, (response) => {
      response.resume();
      resolve(response);
    });
    call.on("error", reject);
    call.end();
  });
  assert.equal(trace.statusCode, 405);
  assert.equal(trace.headers.allow, "GET, HEAD");
});
