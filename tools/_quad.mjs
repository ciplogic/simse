// Scratch: the sema scaling probe (`agents.md` §8 item 6, T40): one file's declaration
// count is what made sema quadratic - `collectGlobal` copied the whole key's list out with
// `get`, appended, and wrote it back with `insert`. This measures the compiler built before
// the `getPtr` conversion (`build/old_simse.exe`) against the current one on the same input,
// interleaved so machine drift hits both.
//
//   bun tools/_quad.mjs
import { writeFileSync, mkdirSync } from "fs";
import { spawnSync } from "child_process";

function write(n) {
  mkdirSync("build/quad/src", { recursive: true });
  const parts = ["package quad\n"];
  for (let i = 0; i < n; i++) {
    parts.push(`fun f${i}(a: Int): Int {\n    return a + ${i}\n}\n`);
  }
  parts.push("fun main(): Int {\n    return f" + (n - 1) + "(1)\n}\n");
  writeFileSync("build/quad/src/main.kt", parts.join("\n"));
}

function timeOne(exe) {
  const begin = performance.now();
  const run = spawnSync(exe, ["--root", "build/quad/src", "-o", "build/quad/out.cpp"], {
    encoding: "utf8",
  });
  const ms = performance.now() - begin;
  if (run.status !== 0) {
    console.log(`  ${exe} FAILED: ${(run.stderr || "").slice(0, 200)}`);
    return -1;
  }
  return ms;
}

for (const n of [1000, 4000, 8000]) {
  write(n);
  const oldMs = [];
  const newMs = [];
  for (let round = 0; round < 3; round++) {
    oldMs.push(timeOne("./build/old_simse.exe"));
    newMs.push(timeOne("./simse.exe"));
  }
  const best = (a) => Math.min(...a).toFixed(0);
  console.log(
    `${String(n).padStart(5)} declarations   before ${best(oldMs).padStart(6)} ms   after ` +
      `${best(newMs).padStart(6)} ms   (3 interleaved rounds each)`
  );
}
