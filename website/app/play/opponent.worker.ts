interface ChooseRequest {
  type: "choose";
  requestId: string;
  candidates: Array<{ move: string; score: number; terminal: "WIN" | "LOSS" | null }>;
  seed: number;
}

interface CancelRequest {
  type: "cancel";
  requestId: string;
}

const canceled = new Set<string>();

function seededIndex(seed: number, size: number): number {
  let value = seed | 0;
  value ^= value << 13;
  value ^= value >>> 17;
  value ^= value << 5;
  return Math.abs(value) % size;
}

self.onmessage = (event: MessageEvent<ChooseRequest | CancelRequest>) => {
  const message = event.data;
  if (message.type === "cancel") {
    canceled.add(message.requestId);
    return;
  }

  const ordered = [...message.candidates].sort((left, right) => right.score - left.score);
  const poolSize = 4;
  const pool = ordered.slice(0, Math.max(1, Math.min(poolSize, ordered.length)));
  const choice = pool[seededIndex(message.seed, pool.length)];

  setTimeout(() => {
    if (canceled.delete(message.requestId)) return;
    self.postMessage({ type: "move", requestId: message.requestId, move: choice?.move ?? null });
  }, 300);
};

export {};
