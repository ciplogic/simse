// Scratch: interleave the onebrc legs the way benchmark.md's method describes - each
// round runs the C++ baseline, then the Simse binary, then the bun canary; each binary
// reports its own loop time on stderr (ms + MB/s for both, the baseline's in its own
// wording). Run by hand, never by the loop:
//   bun tools/_onebrc_ab.mjs [rounds]
import { $ } from "bun";

const rounds = Number(process.argv[2] ?? 5);
const exe1 = "benchmarks/onebrc/brc_naive.exe";
const exe2 = "benchmarks/onebrc/onebrc.exe";
const arg = "benchmarks/onebrc/data/measurements.txt";

// Both binaries report "<rows> rows, ..., <n> ms, <n> MB/s"; the baseline's older
// wording (<n> s) is handled too.
const own = (stderr) => {
    const ms = stderr.match(/([\d]+) ms/);
    if (ms) return parseFloat(ms[1]);
    const s = stderr.match(/([\d.]+) s\b/);
    if (s) return parseFloat(s[1]) * 1000;
    return null;
};

const legs = { cpp: [], simse: [], canary: [] };
for (let round = 0; round < rounds; round++) {
    for (const [name, cmd] of [["cpp", [exe1, arg]], ["simse", [exe2, arg]]]) {
        const r = await $`${cmd}`.nothrow().quiet();
        const t = own(r.stderr.toString());
        console.log(`round ${round} ${name}: ${t}`);
        legs[name].push(t);
    }
    const begin = performance.now();
    await $`bun benchmarks/onebrc/onebrc.mjs check ${arg}`.nothrow().quiet();
    legs.canary.push(performance.now() - begin);
}
for (const [name, times] of [["cpp", legs.cpp], ["simse", legs.simse], ["canary(bun, wall)", legs.canary]]) {
    const sorted = [...times].sort((a, b) => a - b);
    console.log(`${name}: min ${sorted[0].toFixed(0)} median ${sorted[sorted.length >> 1].toFixed(0)} all [${times.map((t) => t.toFixed(0)).join(", ")}]`);
}
