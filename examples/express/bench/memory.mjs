// memory.mjs - private bytes and working set of the express example vs Bun.serve, under load.
//
//   bun examples/express/bench/memory.mjs [concurrency] [seconds]
//
// Samples the server process (not the client) with `Get-Process` every 200 ms while a `fetch`
// pool drives `GET /json`, and prints the idle figure and the peak during the load. The Simse
// server is given as many workers as the client has connections, the same as `run.mjs`.

import * as path from "node:path";

const benchDir = import.meta.dir;
const repo = path.resolve(benchDir, "../../..");
const simseExe = path.join(repo, "examples/express/express.exe");
const bunServer = path.join(benchDir, "bun-server.mjs");

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

// One `Get-Process` reading, as { priv, ws } bytes.
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

// A single long-lived PowerShell that samples every 200 ms for `ms`; read after the load.
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

async function run(name, cmd, port) {
  const url = `http://127.0.0.1:${port}/json`;
  const child = Bun.spawn(cmd, { cwd: repo, stdout: "ignore", stderr: "ignore" });
  const pid = child.pid;
  if (!(await waitReady(url))) {
    console.log(`${name}: did not come up`);
    child.kill();
    return;
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
    `${name.padEnd(6)} req/s ${String(Math.round(r.ok / (ms / 1000))).padStart(9)}  |  ` +
    `private idle ${idle ? mb(idle.priv) : "?"} -> peak ${mb(p.priv)}  |  ` +
    `working set idle ${idle ? mb(idle.ws) : "?"} -> peak ${mb(p.ws)}  ` +
    `(${samples.length} samples, ${r.ok} ok, ${r.errors} errors, c=${concurrency})`
  );
}

console.log(`GET /json, ${seconds}s at concurrency ${concurrency}, server process memory`);
await run("simse", [simseExe, "8097", "0", String(concurrency)], 8097);
await run("bun", [process.execPath, "run", bunServer, "8098"], 8098);
