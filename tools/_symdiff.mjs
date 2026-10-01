// Scratch: compare the set of emitted function symbols of two amalgamations, so a
// split/reorder that lost or renamed a function shows up before the compiler is linked.
import { readFileSync } from "fs";

function symbols(path) {
  const out = [];
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const m = /^(?:template\s*<[^;]*>\s*)?[A-Za-z_][A-Za-z0-9_:<>*,& ]*\s([A-Za-z_][A-Za-z0-9_]*)\([^;{}]*\)\s*\{$/.exec(line);
    if (m) out.push(m[1]);
  }
  return out;
}

const [a, b] = process.argv.slice(2);
const before = symbols(a).sort();
const after = symbols(b).sort();
console.log(`${a}: ${before.length} definitions, ${b}: ${after.length}`);

const count = (list) => {
  const map = new Map();
  for (const name of list) map.set(name, (map.get(name) ?? 0) + 1);
  return map;
};
const beforeCount = count(before);
const afterCount = count(after);
let differences = 0;
for (const key of new Set([...beforeCount.keys(), ...afterCount.keys()])) {
  const x = beforeCount.get(key) ?? 0;
  const y = afterCount.get(key) ?? 0;
  if (x !== y) {
    differences++;
    if (differences <= 40) console.log(`  ${key}: ${x} -> ${y}`);
  }
}
console.log(differences === 0 ? "SYMBOL SETS IDENTICAL" : `${differences} differing symbol(s)`);
