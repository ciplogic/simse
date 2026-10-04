// Scratch: the *true* per-method inclusive cost of a --profile report.
//
// The report's "by total" summary sums a body over every path it ran on, which double-counts a
// recursive body (codegen.collectNames reads ~0.7 s there but is really ~0.08 s). This walks
// the tree and adds a row's total only when its parent is a *different* body, so each
// recursion level is counted once, inside its own root-most line.
//
// usage: bun tools/_cost.mjs <report file> [limit] [substring]
import { readFileSync } from "node:fs";

const file = process.argv[2];
const limit = Number(process.argv[3] ?? 40);
const only = process.argv[4] ?? "";
const lines = readFileSync(file, "utf8").split(/\r?\n/);

let inTree = false;
const stack = []; // name per depth
const incl = new Map(); // name -> { total, calls }
for (const line of lines) {
  if (!inTree) {
    if (line === "tree:") inTree = true;
    continue;
  }
  if (line.trim().length === 0) continue;
  const m = line.match(/^(\s*)\+?(.*?\(\)):(\d+) (\w+):\s*(\d+) calls/);
  if (!m) continue;
  const depth = m[1].length;
  const name = m[2];
  const total = Number(m[3]);
  const calls = Number(m[5]);
  while (stack.length > depth) stack.pop();
  const parent = depth > 0 ? stack[depth - 1] : "";
  if (only === "" || name.includes(only)) {
    const row = incl.get(name) ?? { total: 0, calls: 0 };
    if (parent !== name) {
      row.total += total;
    }
    row.calls += calls;
    incl.set(name, row);
  }
  stack[depth] = name;
}

const rows = [...incl.entries()].sort((a, b) => b[1].total - a[1].total);
for (const [name, v] of rows.slice(0, limit)) {
  console.log(`${String(Math.round(v.total / 1000)).padStart(7)} ms  ${String(v.calls).padStart(11)} calls  ${name}`);
}
