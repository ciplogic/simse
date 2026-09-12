// Scratch: run a command N times and report min / median / max wall time.
//   bun tools/_bench_run.mjs <runs> <command...>
import { $ } from "bun";

const runs = Number(process.argv[2]);
const cmd = process.argv.slice(3);
if (!runs || cmd.length === 0) {
  console.error("usage: bun tools/_bench_run.mjs <runs> <command...>");
  process.exit(1);
}

const times = [];
for (let i = 0; i < runs; i++) {
  const begin = performance.now();
  const result = await $`${cmd}`.nothrow().quiet();
  const ms = performance.now() - begin;
  if (result.exitCode !== 0) {
    console.error(`run ${i} failed (exit ${result.exitCode}): ${result.stderr.toString().slice(0, 400)}`);
    process.exit(1);
  }
  times.push(ms);
}
times.sort((a, b) => a - b);
const pad = (x) => x.toFixed(1).padStart(8);
console.log(`min ${pad(times[0])} ms   median ${pad(times[times.length >> 1])} ms   max ${pad(times[times.length - 1])} ms`);
