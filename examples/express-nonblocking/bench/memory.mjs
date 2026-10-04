// memory.mjs - private bytes and working set of the non-blocking server under load.
//
//   bun examples/express-nonblocking/bench/memory.mjs [concurrency] [seconds]
//
// Samples the server process (not the client) with `Get-Process` every 200 ms while a `fetch`
// pool drives `GET /json`. The non-blocking server is one thread regardless of the connection
// count, so concurrency here is a connection count, not a worker count.

import * as path from "node:path";

const repo = path.resolve(import.meta.dir, "../../..");
const exe = path.join(repo, "examples/express-nonblocking/express-nonblocking.exe");
const port = 8096;
const concurrency = Number(process.argv[2] ?? 16);
const seconds = Number(process.argv[3] ?? 4);
const durationMs = seconds * 1000;

async function hit(url) {
  const res = await fetch(url);
  await res.arrayBuffer();
  if (!res.ok) throw new Error(`status ${res.status}`);
}

async function loadFor(url, ms) {
  const end = performance.now() + ms;
  let ok = 0;
  let errors = 0;
  const worker = async () => {
    while (performance.now() < end) {
      try { await hit(url); ok++; } catch { errors++; }
    }
  };
  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  return { ok, errors };
}

function sampleOnce(pid) {
  const out = Bun.spawnSync(
    ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
      `$p=Get-Process -Id ${pid} -ErrorAction SilentlyContinue; if($p){ "{0} {1}" -f $p.PrivateMemorySize64,$p.WorkingSet64 }`],
    { stdout: "pipe", stderr: "pipe" }
  );
  const parts = out.stdout.toString().trim().split(/\s+/).map(Number);
  if (parts.length < 2 || !Number.isFinite(parts[0])) return null;
  return { priv: parts[0], ws: parts[1] };
}

function startSampler(pid, ms) {
  const script =
    `$id=${pid}; $end=(Get-Date).AddMilliseconds(${ms}); ` +
    `while((Get-Date) -lt $end){ $p=Get-Process -Id $id -ErrorAction SilentlyContinue; ` +
    `if($p){ Write-Output ("{0} {1}" -f $p.PrivateMemorySize64,$p.WorkingSet64) }; ` +
    `Start-Sleep -Milliseconds 200 }`;
  return Bun.spawn(
    ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
    { stdout: "pipe", stderr: "ignore" }
  );
}

function peak(samples) {
  let priv = 0;
  let ws = 0;
  for (const s of samples) {
    if (s.priv > priv) priv = s.priv;
    if (s.ws > ws) ws = s.ws;
  }
  return { priv, ws };
}

async function waitReady(url, timeoutMs = 10000) {
  const t0 = performance.now();
  while (performance.now() - t0 < timeoutMs) {
    try { const r = await fetch(url); await r.arrayBuffer(); return true; } catch {}
    await Bun.sleep(50);
  }
  return false;
}

const mb = (bytes) => (bytes / 1048576).toFixed(1) + " MB";

const url = `http://127.0.0.1:${port}/json`;
const child = Bun.spawn([exe, String(port)], { cwd: repo, stdout: "ignore", stderr: "ignore" });
const pid = child.pid;
if (!(await waitReady(url))) {
  console.log("nonblocking: did not come up");
  child.kill();
  process.exit(1);
}
await Bun.sleep(300);
const idle = sampleOnce(pid);

const sampler = startSampler(pid, durationMs + 400);
const t0 = performance.now();
const r = await loadFor(url, durationMs);
const ms = performance.now() - t0;
const text = await new Response(sampler.stdout).text();
child.kill();

const samples = text.trim().split(/\n/).map((line) => {
  const parts = line.trim().split(/\s+/).map(Number);
  return parts.length >= 2 ? { priv: parts[0], ws: parts[1] } : null;
}).filter((s) => s && Number.isFinite(s.priv));

const p = peak(samples);
console.log(
  `nonblocking req/s ${String(Math.round(r.ok / (ms / 1000))).padStart(9)}  |  ` +
  `private idle ${idle ? mb(idle.priv) : "?"} -> peak ${mb(p.priv)}  |  ` +
  `working set idle ${idle ? mb(idle.ws) : "?"} -> peak ${mb(p.ws)}  ` +
  `(${samples.length} samples, ${r.ok} ok, ${r.errors} errors, c=${concurrency})`
);
