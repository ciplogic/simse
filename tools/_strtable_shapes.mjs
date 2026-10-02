// Scratch: classify the *shapes* of the string-table uses in an emitted amalgamation, so
// the set of operations that would need a `StrView` overload (if a table entry stopped
// being an owned `Str`) is a measured list rather than a guess.
import { readFileSync } from "fs";

const path = process.argv[2] ?? "src/simse_bootstrap.cpp";
const lines = readFileSync(path, "utf8").split("\n");

const shapes = new Map();
const bump = (shape) => shapes.set(shape, (shapes.get(shape) ?? 0) + 1);

for (const line of lines) {
  if (!line.includes("__sm_stringTable[")) continue;
  // The table's own definition.
  if (/static const Str __sm_stringTable\[/.test(line)) continue;
  const entry = line.match(/__sm_stringTable\[\d+\]/)?.[0];
  if (!entry) continue;
  const [before, after] = [line.slice(0, line.indexOf(entry)), line.slice(line.indexOf(entry) + entry.length)];

  // A call: the nearest identifier before the entry on this line.
  const call = before.match(/([A-Za-z_][A-Za-z0-9_]*)\s*\([^()]*$/);
  if (call) {
    bump(`${call[1]}(... entry ...)`);
    continue;
  }
  if (before.includes("std::cout")) { bump("std::cout << entry"); continue; }
  if (/\b(==|!=)\s*$/.test(before) || /^\s*(==|!=)/.test(after)) { bump("entry == / != other"); continue; }
  if (/\+\s*$/.test(before)) { bump("... + entry"); continue; }
  if (/^\s*\+/.test(after)) { bump("entry + ..."); continue; }
  if (/=\s*$/.test(before)) { bump("slot/field = entry"); continue; }
  if (/^\s*,/.test(after) || /^\s*\)/.test(after)) { bump("... entry, ..."); continue; }
  if (/^\s*;/.test(after)) { bump("... entry;"); continue; }
  bump(`other: ${line.trim().slice(0, 60)}`);
}

const sorted = [...shapes].sort((a, b) => b[1] - a[1]);
let total = 0;
for (const [shape, count] of sorted) {
  total += count;
  console.log(`${String(count).padStart(5)}  ${shape}`);
}
console.log(`${String(total).padStart(5)}  total uses`);
