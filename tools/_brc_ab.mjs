// Scratch: the 1BRC legs interleaved, so a machine that throttles over the window cannot
// bias one leg, reading each program's own reported loop time (its stderr) rather than the
// wall clock. `bun check` is the reference aggregate and the canary.
//   bun tools/_brc_ab.mjs <rounds> <data-file>
import { $ } from "bun";

const rounds = Number(process.argv[2] || 4);
const data = process.argv[3];
const legs = [
  ["naive   ", ["./benchmarks/onebrc/brc_naive.exe", data]],
  ["simseold", ["./build/brc_old/onebrc_old.exe", data]],
  ["simsepre", ["./build/brc_pre/onebrc_pre.exe", data]],
  ["simsenew", ["./benchmarks/onebrc/onebrc.exe", data]],
  ["bun     ", ["bun", "benchmarks/onebrc/onebrc.mjs", "check", data]],
];

async function run(argv) {
  const result = await $`${argv}`.nothrow().quiet();
  if (result.exitCode !== 0) {
    console.error(`${argv[0]} failed (exit ${result.exitCode}): ${result.stderr.toString().slice(0, 300)}`);
    process.exit(1);
  }
  const err = result.stderr.toString().trim();
  const ms = err.match(/([\d.]+) ms/);
  const seconds = err.match(/([\d.]+) s,/);
  const loop = ms ? Number(ms[1]) : Number(seconds[1]) * 1000;
  return { loop, out: result.stdout.toString().replace(/\r\n/g, "\n") };
}

const times = new Map(legs.map(([name]) => [name.trim(), []]));
const outs = new Map();
for (let round = 0; round < rounds; round++) {
  const parts = [];
  for (const [name, argv] of legs) {
    const result = await run(argv);
    times.get(name.trim()).push(result.loop);
    outs.set(name.trim(), result.out);
    parts.push(`${name} ${result.loop.toFixed(0).padStart(6)} ms`);
  }
  console.log(`round ${round + 1}  ${parts.join("   ")}`);
}

const first = [...outs.values()][0];
console.log(`reports byte-identical: ${[...outs.values()].every((out) => out === first)}`);
console.log("--- min / median, the program's own loop time ---");
for (const [name, list] of times) {
  const sorted = [...list].sort((a, b) => a - b);
  console.log(`${name}  min ${sorted[0].toFixed(0).padStart(6)} ms   median ${sorted[sorted.length >> 1].toFixed(0).padStart(6)} ms`);
}
