import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";

const root = path.resolve(process.argv[2] ?? "release");
const port = Number(process.argv[3] ?? "4173");
const host = process.argv[4] ?? "127.0.0.1";
const types = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".ico", "image/x-icon"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml; charset=utf-8"],
  [".wasm", "application/wasm"],
  [".webmanifest", "application/manifest+json; charset=utf-8"],
  [".webp", "image/webp"],
  [".xml", "application/xml; charset=utf-8"],
]);
const compressible = new Set([".css", ".html", ".js", ".json", ".svg", ".wasm", ".webmanifest", ".xml"]);

function securityHeaders(pathname = "/") {
  const scriptPolicy = pathname === "/play" || pathname.startsWith("/play/")
    ? "script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'"
    : "script-src 'self'";
  return {
    "Content-Security-Policy": [
      "default-src 'self'",
      "base-uri 'self'",
      "object-src 'none'",
      "frame-ancestors 'none'",
      "form-action 'self'",
      "img-src 'self' data:",
      "style-src 'self' 'unsafe-inline'",
      scriptPolicy,
      "worker-src 'self'",
      "connect-src 'self'",
      "font-src 'self'",
      "manifest-src 'self'",
    ].join("; "),
    "Permissions-Policy": "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  };
}

function cacheControl(pathname, extension) {
  if (extension === ".html" || extension === ".webmanifest" || pathname.endsWith("robots.txt") || pathname.endsWith("sitemap.xml")) {
    return "no-cache";
  }
  if (pathname.startsWith("/assets/") || pathname.startsWith("/game/")) {
    return "public, max-age=31536000, immutable";
  }
  return "public, max-age=86400, must-revalidate";
}

function parseRange(value, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(value ?? "");
  if (!match) return null;
  const start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  const end = match[2] ? Number(match[2]) : size - 1;
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || start >= size) return null;
  return { start, end: Math.min(end, size - 1) };
}

const server = createServer(async (request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { ...securityHeaders(), Allow: "GET, HEAD" }).end("Method not allowed");
    return;
  }

  let pathname;
  try {
    pathname = decodeURIComponent(new URL(request.url ?? "/", `http://${host}:${port}`).pathname);
  } catch {
    response.writeHead(400, securityHeaders()).end("Bad request");
    return;
  }

  const relative = pathname.replace(/^\/+/, "");
  let file = path.resolve(root, relative || "index.html");
  if (!file.startsWith(`${root}${path.sep}`) && file !== path.join(root, "index.html")) {
    response.writeHead(400, securityHeaders(pathname)).end("Bad request");
    return;
  }

  let status = 200;
  let fileInfo = await stat(file).catch(() => null);
  if (fileInfo?.isDirectory()) {
    file = path.join(file, "index.html");
    fileInfo = await stat(file).catch(() => null);
  }
  if (!fileInfo?.isFile()) {
    status = 404;
    file = path.join(root, "404.html");
    fileInfo = await stat(file).catch(() => null);
  }
  if (!fileInfo?.isFile()) {
    response.writeHead(404, securityHeaders(pathname)).end("Not found");
    return;
  }

  const extension = path.extname(file).toLowerCase();
  const hasRange = typeof request.headers.range === "string";
  let servedFile = file;
  let servedInfo = fileInfo;
  let contentEncoding;
  if (!hasRange && compressible.has(extension) && /(?:^|,)\s*gzip\s*(?:,|$)/i.test(request.headers["accept-encoding"] ?? "")) {
    const gzipFile = `${file}.gz`;
    const gzipInfo = await stat(gzipFile).catch(() => null);
    if (gzipInfo?.isFile()) {
      servedFile = gzipFile;
      servedInfo = gzipInfo;
      contentEncoding = "gzip";
    }
  }

  const etag = `"${servedInfo.size.toString(16)}-${Math.trunc(servedInfo.mtimeMs).toString(16)}${contentEncoding ? "-gz" : ""}"`;
  const headers = {
    ...securityHeaders(pathname),
    "Accept-Ranges": "bytes",
    "Cache-Control": cacheControl(pathname, extension),
    "Content-Type": types.get(extension) ?? "application/octet-stream",
    ETag: etag,
    "Last-Modified": fileInfo.mtime.toUTCString(),
    Vary: "Accept-Encoding",
  };
  if (contentEncoding) headers["Content-Encoding"] = contentEncoding;

  if (request.headers["if-none-match"] === etag) {
    response.writeHead(304, headers).end();
    return;
  }

  let streamOptions;
  if (hasRange) {
    const range = parseRange(request.headers.range, fileInfo.size);
    if (!range) {
      response.writeHead(416, { ...headers, "Content-Range": `bytes */${fileInfo.size}` }).end();
      return;
    }
    status = 206;
    streamOptions = range;
    headers["Content-Range"] = `bytes ${range.start}-${range.end}/${fileInfo.size}`;
    headers["Content-Length"] = String(range.end - range.start + 1);
  } else {
    headers["Content-Length"] = String(servedInfo.size);
  }

  response.writeHead(status, headers);
  if (request.method === "HEAD") {
    response.end();
    return;
  }
  createReadStream(servedFile, streamOptions).pipe(response);
});

server.listen(port, host, () => {
  const address = server.address();
  const actualPort = typeof address === "object" && address ? address.port : port;
  console.log(`Serving ${root} at http://${host}:${actualPort}/`);
});
