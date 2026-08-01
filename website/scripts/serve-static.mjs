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
  [".wasm", "application/wasm"],
  [".webmanifest", "application/manifest+json; charset=utf-8"],
  [".webp", "image/webp"],
  [".xml", "application/xml; charset=utf-8"],
]);

createServer(async (request, response) => {
  const pathname = decodeURIComponent(new URL(request.url ?? "/", `http://${host}:${port}`).pathname);
  const relative = pathname.replace(/^\/+/, "");
  let file = path.resolve(root, relative || "index.html");
  if (!file.startsWith(`${root}${path.sep}`) && file !== path.join(root, "index.html")) {
    response.writeHead(400).end("Bad request");
    return;
  }
  const info = await stat(file).catch(() => null);
  if (info?.isDirectory()) file = path.join(file, "index.html");
  const fileInfo = await stat(file).catch(() => null);
  if (!fileInfo?.isFile()) file = path.join(root, "404.html");
  response.writeHead(fileInfo?.isFile() ? 200 : 404, {
    "Content-Type": types.get(path.extname(file).toLowerCase()) ?? "application/octet-stream",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  createReadStream(file).pipe(response);
}).listen(port, host, () => {
  console.log(`Serving ${root} at http://${host}:${port}/`);
});
