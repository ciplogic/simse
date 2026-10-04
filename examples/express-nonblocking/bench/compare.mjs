// compare.mjs - the non-blocking server against the blocking one and against Bun.serve.
//
//   bun examples/express-nonblocking/bench/compare.mjs [concurrency]
//
// One `fetch` worker pool drives `GET /json` at the same concurrency against three servers:
//
//   nonblocking  examples/express-nonblocking   one thread, one WSAPoll loop
//   blocking     examples/express              one worker per client connection (see below)
//   bun          Bun.serve                     Bun's built-in HTTP server
//
// The blocking server gets as many workers as the client has connections: a blocking `netReceive`
// holds its worker for the whole keep-alive session, so fewer workers than connections would leave
// the surplus connections unread and the run would stall - the ceiling the non-blocking server
// removes. The non-blocking server is always one thread. Both Simse servers are the same routes
// and handlers - only the transport differs.
//
// Build both Simse servers first (see ../README.md and ../../express/README.md).

import * as path from "node:path";

const benchDir = import.meta.dir;
const repo = path.resolve(benchDir, "../../..");
const nonblockingExe = path.join(repo, "examples/express-nonblocking/express-nonblocking.exe");
const blockingExe = path.join(repo, "examples/express/express.exe");
const bunServer = path.join(repo, "examples/express/bench/bun-server.mjs");

const concurrency = Number(process.argv[2] ?? 64);

async function hit(url) {
  const res = await fetch(url);
  await res.arrayBuffer();
  if (!res.ok) throw new Error(`status ${res.status}`);
}

async function load(url) {
  const warmup = 500;
  for (let i = 0; i < warmup; i++) {
    try { await hit(url); } catch {}
  }
  let next = 0;
  let ok = 0;
  let errors = 0;
  const worker = async () => {
    for (;;) {
      const i = next++;
      if (i >= 20000) return;
      try { await hit(url); ok++; } catch { errors++; }
    }
  };
  const t0 = performance.now();
  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  const ms = performance.now() - t0;
  return { ok, errors, rps: ok / (ms / 1000) };
}

async function waitReady(url, timeoutMs = 10000) {
  const t0 = performance.now();
  while (performance.now() - t0 < timeoutMs) {
    try { const r = await fetch(url); await r.arrayBuffer(); return true; } catch {}
    await Bun.sleep(50);
  }
  return false;
}

async function run(name, cmd, port) {
  const url = `http://127.0.0.1:${port}/json`;
  const child = Bun.spawn(cmd, { cwd: repo, stdout: "ignore", stderr: "ignore" });
  if (!(await waitReady(url))) {
    console.log(`${name}: did not come up`);
    child.kill();
    return;
  }
  const r = await load(url);
  child.kill();
  console.log(
    `${name.padEnd(12)} ${String(Math.round(r.rps)).padStart(9)} req/s  ` +
    `(${r.ok} ok, ${r.errors} errors, c=${concurrency})`
  );
}

console.log(
  `GET /json - 20000 requests each, concurrency ${concurrency}, ` +
  `blocking server ${concurrency} workers`
);
await run("nonblocking", [nonblockingExe, "8096"], 8096);
await run("blocking", [blockingExe, "8097", "0", String(concurrency)], 8097);
await run("bun", [process.execPath, "run", bunServer, "8098"], 8098);
