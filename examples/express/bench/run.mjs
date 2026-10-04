// run.mjs - benchmark the express example against the Bun.serve baseline, one load client.
//
//   bun examples/express/bench/run.mjs                 # 32 connections, 20000 requests each
//   bun examples/express/bench/run.mjs 64 40000 2000   # concurrency, requests, warmup
//
// The two servers are different on purpose, and that difference is the result: the Simse
// server is single-threaded and closes every connection (`Connection: close`); `Bun.serve` is
// multi-threaded and keeps connections alive. Both are driven by this same `fetch` worker pool,
// so the numbers are relative to one client, not a controlled study.
//
// Build the Simse server first (see ../README.md):
//   simse.exe --root examples/express/src --module examples/http/sockets \
//       --module src/modules/http --module src/modules/jsonnode -o examples/express/out.cpp
//   bun build.js --release --cpp examples/express/out.cpp --exe examples/express/express.exe

import * as path from "node:path";

const benchDir = import.meta.dir;
const repo = path.resolve(benchDir, "../../..");
const simseExe = path.join(repo, "examples/express/express.exe");
const bunServer = path.join(benchDir, "bun-server.mjs");

const concurrency = Number(process.argv[2] ?? 32);
const total = Number(process.argv[3] ?? 20000);
const warmup = Number(process.argv[4] ?? 1000);

async function hit(url, close) {
  const res = await fetch(url, close ? { headers: { connection: "close" } } : undefined);
  await res.arrayBuffer();
  if (!res.ok) throw new Error(`status ${res.status}`);
}

// Warm up, then `concurrency` workers pull from a shared counter until `total` requests have
// been issued. The socket-per-request cost the Simse server imposes is inside the measured
// window, because that is how it is meant to be used.
async function load(url, close) {
  for (let i = 0; i < warmup; i++) {
    try { await hit(url, close); } catch {}
  }
  let next = 0;
  let ok = 0;
  let errors = 0;
  const worker = async () => {
    for (;;) {
      const i = next++;
      if (i >= total) return;
      try { await hit(url, close); ok++; } catch { errors++; }
    }
  };
  const t0 = performance.now();
  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  const ms = performance.now() - t0;
  return { ok, errors, ms, rps: total / (ms / 1000) };
}

async function waitReady(url, timeoutMs = 10000) {
  const t0 = performance.now();
  while (performance.now() - t0 < timeoutMs) {
    try { const r = await fetch(url); await r.arrayBuffer(); return true; } catch {}
    await Bun.sleep(50);
  }
  return false;
}

async function run(name, cmd, port, close) {
  const url = `http://127.0.0.1:${port}/json`;
  const child = Bun.spawn(cmd, { cwd: repo, stdout: "pipe", stderr: "pipe" });
  if (!(await waitReady(url))) {
    console.log(`${name}: did not come up`);
    child.kill();
    return;
  }
  const r = await load(url, close);
  child.kill();
  console.log(
    `${name.padEnd(10)} ${String(Math.round(r.rps)).padStart(9)} req/s  ` +
    `(${r.ok} ok, ${r.errors} errors, ${Math.round(r.ms)} ms, c=${concurrency}${close ? ", close" : ""})`
  );
}

console.log(`GET /json {"message":"Hello, World!"} - ${total} requests, concurrency ${concurrency}`);
await run("simse", [simseExe, "8097", "0"], 8097, false);
await run("bun", [process.execPath, "run", bunServer, "8098"], 8098, false);
await run("bun-close", [process.execPath, "run", bunServer, "8099"], 8099, true);
