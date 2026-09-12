// Scratch: interleave two commands run-for-run and report min/median/max each, so
// a machine that throttles over the window cannot bias one leg.
//   bun tools/_bench_ab.mjs <runs> <cmdA...> -- <cmdB...>
import { $ } from "bun";

const runs = Number(process.argv[2]);
const rest = process.argv.slice(3);
const sep = rest.indexOf("--");
if (!runs || sep < 0 || sep === rest.length - 1) {
  console.error("usage: bun tools/_bench_ab.mjs <runs> <cmdA...> -- <cmdB...>");
  process.exit(2);
}
const legs = [["A", rest.slice(0, sep), []], ["B", rest.slice(sep + 1), []]];

for (let i = 0; i < runs; i++) {
  for (const leg of legs) {
    const begin = performance.now();
    const result = await $`${leg[1]}`.nothrow().quiet();
    if (result.exitCode !== 0) {
      console.error(`${leg[0]} failed (exit ${result.exitCode}): ${result.stderr.toString().slice(0, 300)}`);
      process.exit(1);
    }
    leg[2].push(performance.now() - begin);
  }
}

for (const [name, cmd, times] of legs) {
  const sorted = [...times].sort((a, b) => a - b);
  const pad = (x) => x.toFixed(1).padStart(8);
  console.log(`${name}  ${cmd[0]}  min ${pad(sorted[0])} ms   median ${pad(sorted[sorted.length >> 1])} ms   max ${pad(sorted[sorted.length - 1])} ms`);
}
