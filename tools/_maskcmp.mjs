// Scratch: compare two amalgamations modulo the `// path:line` source comments, with the
// line numbers masked. Comment and formatting changes in cppsrc cannot reach the emitted
// C++ except through those numbers, so a masked-equal pair is a behaviour-preserving edit.
//   bun tools/_maskcmp.mjs build/base.cpp build/new.cpp
import { readFileSync } from "fs";

const mask = (p) =>
  readFileSync(p, "utf8")
    .split("\n")
    .map((l) => l.replace(/([A-Za-z0-9_./\\-]+\.kt):[0-9]+/g, "$1:N"))
    .join("\n");

const [a, b] = process.argv.slice(2);
const left = mask(a);
const right = mask(b);
if (left === right) {
  console.log(`masked-equal: ${a} == ${b}`);
  process.exit(0);
}
const la = left.split("\n");
const lb = right.split("\n");
let shown = 0;
for (let i = 0; i < Math.max(la.length, lb.length) && shown < 20; i++) {
  if (la[i] !== lb[i]) {
    console.log(`line ${i + 1}\n  A: ${JSON.stringify(la[i])}\n  B: ${JSON.stringify(lb[i])}`);
    shown++;
  }
}
console.log(`\n${la.length} lines (A) vs ${lb.length} lines (B), differ`);
process.exit(1);
