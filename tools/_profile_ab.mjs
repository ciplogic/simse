// A/B of profiled builds (`--profile`): each program writes its own report when it exits,
// and the metric is the report's `main():` line - the instrumented run's total, excluding
// the report itself (it is written by a static destructor after main). Runs are interleaved,
// round-robin, so drift (thermal, AV scanning) hits every variant alike; min and median are
// printed. This is the probe for "does the profiler's own bookkeeping get cheaper?".
//
// usage: bun tools/_profile_ab.mjs --runs 3 name=exe=report [name=exe=report ...]
//
// Example (three profiled release compilers over their own source tree):
//   bun tools/_profile_ab.mjs --runs 3 \
//     stl=build/linq/ab_stl.exe=build/linq/ab_stl.txt \
//     str=build/linq/ab_str.exe=build/linq/ab_str.txt
import { spawnSync } from "node:child_process";
import fs from "node:fs";

const args = process.argv.slice(2);
let runs = 2;
const variants = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--runs") {
    runs = Number(args[++i]);
    continue;
  }
  const parts = args[i].split("=");
  if (parts.length !== 3) {
    console.error(`bad variant '${args[i]}' (want name=exe=report)`);
    process.exit(2);
  }
  variants.push({ name: parts[0], exe: parts[1], report: parts[2] });
}
if (variants.length < 2) {
  console.error("need at least two name=exe=report variants");
  process.exit(2);
}

const rows = new Map(variants.map((v) => [v.name, []]));
for (let round = 0; round < runs; round++) {
  for (const v of variants) {
    const started = performance.now();
    const proc = spawnSync(v.exe, ["--root", "src", "-o", "build/linq/ab_out.cpp"], { stdio: ["ignore", "ignore", "inherit"] });
    if (proc.status !== 0) {
      console.error(`${v.name}: exit ${proc.status}`);
      process.exit(1);
    }
    const wall = (performance.now() - started) / 1000;
    const text = fs.readFileSync(v.report, "utf8");
    const m = text.match(/^main\(\):(\d+) /m);
    if (!m) {
      console.error(`${v.name}: no main(): line in ${v.report}`);
      process.exit(1);
    }
    const us = Number(m[1]);
    rows.get(v.name).push({ us, wall });
    console.log(`${v.name}: main ${(us / 1e6).toFixed(3)} s, wall ${wall.toFixed(1)} s`);
  }
}

const stats = (xs) => {
  const sorted = [...xs].sort((a, b) => a - b);
  return { min: sorted[0], median: sorted[Math.floor(sorted.length / 2)] };
};
console.log("");
for (const v of variants) {
  const us = stats(rows.get(v.name).map((r) => r.us));
  const wall = stats(rows.get(v.name).map((r) => r.wall));
  console.log(`${v.name}: main min ${(us.min / 1e6).toFixed(3)} s, median ${(us.median / 1e6).toFixed(3)} s | wall min ${wall.min.toFixed(1)} s, median ${wall.median.toFixed(1)} s`);
}
