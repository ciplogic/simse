// Scratch: like _bench_ab.mjs, but with each leg's stdout sent to a file, for
// benchmarks whose program prints its whole result (the stage differential
// drivers dump a tree per file; on a console that write dominates the timing).
//   bun tools/_bench_out.mjs <runs> <cmdA...> -- <cmdB...>
// The files are tools/_bench_out.A.txt / .B.txt.
import { openSync } from "node:fs";

const runs = Number(process.argv[2]);
const rest = process.argv.slice(3);
const sep = rest.indexOf("--");
if (!runs || sep < 0 || sep === rest.length - 1) {
  console.error("usage: bun tools/_bench_out.mjs <runs> <cmdA...> -- <cmdB...>");
  process.exit(2);
}
const legs = [
  { name: "A", cmd: rest.slice(0, sep), times: [], fd: openSync("tools/_bench_out.A.txt", "w") },
  { name: "B", cmd: rest.slice(sep + 1), times: [], fd: openSync("tools/_bench_out.B.txt", "w") },
];

for (let i = 0; i < runs; i++) {
  for (const leg of legs) {
    const begin = performance.now();
    const result = Bun.spawnSync(leg.cmd, { stdout: leg.fd, stderr: "pipe" });
    if (result.exitCode !== 0) {
      console.error(`${leg.name} failed (exit ${result.exitCode}): ${result.stderr.toString().slice(0, 300)}`);
      process.exit(1);
    }
    leg.times.push(performance.now() - begin);
  }
}

const pad = (x) => x.toFixed(1).padStart(9);
for (const leg of legs) {
  const sorted = [...leg.times].sort((a, b) => a - b);
  console.log(`${leg.name}  ${leg.cmd[0]}  min ${pad(sorted[0])} ms   median ${pad(sorted[sorted.length >> 1])} ms`);
}
