// Scratch: A/B the merge-locals pass on the compiler's own source. `build/premerge_rel.exe` is
// the release compiler built from the published bootstrap before the pass; `./simse.exe` is the
// current one. Interleaved rounds, best of each, so machine drift hits both.
//
//   bun tools/_merge_ab.mjs
import { spawnSync } from "child_process";

function timeOne(exe, out) {
  const begin = performance.now();
  const run = spawnSync(exe, ["--root", "cppsrc", "-o", out], { encoding: "utf8" });
  const ms = performance.now() - begin;
  if (run.status !== 0) {
    console.log(`  ${exe} FAILED: ${(run.stderr || "").slice(0, 200)}`);
    return -1;
  }
  return ms;
}

const before = [];
const after = [];
for (let round = 0; round < 4; round++) {
  before.push(timeOne("./build/premerge_rel.exe", "build/ab_pre.cpp"));
  after.push(timeOne("./simse.exe", "build/ab_new.cpp"));
}
const best = (a) => Math.min(...a).toFixed(0);
console.log(
  `pre-merge ${best(before).padStart(6)} ms   post-merge ${best(after).padStart(6)} ms   ` +
    "(4 interleaved rounds, best of each, transpiling cppsrc)"
);
