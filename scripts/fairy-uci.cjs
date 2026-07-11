const fs = require("fs");
const readline = require("readline");

// This Emscripten build predates Node's global fetch. Disabling fetch makes it
// use its supported filesystem loader for the adjacent WASM binary.
global.fetch = undefined;
const Stockfish = require("fairy-stockfish-nnue.wasm/stockfish.js");

async function main() {
  const engine = await Stockfish();
  const variantsPath = process.env.DRAWLESS_VARIANTS;
  if (variantsPath) {
    engine.FS.writeFile("/drawless-variants.ini", fs.readFileSync(variantsPath));
    engine.postMessage("setoption name VariantPath value /drawless-variants.ini");
  }
  const input = readline.createInterface({ input: process.stdin });
  for await (const command of input) {
    engine.postMessage(command);
    // The WASM worker's postMessage shim can reorder back-to-back commands on
    // newer Node releases. A tiny yield preserves UCI command ordering.
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
