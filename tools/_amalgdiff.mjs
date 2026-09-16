// Scratch: compare two emitted amalgamations function by function, ignoring the
// scratch-slot numbering and the string-table indices - the things that legitimately
// renumber when a temporary disappears. What is left is the *shape* of each function
// (types, callees, operators), which is what a semantic change would move.
import { readFileSync } from "node:fs";

const [pathA, pathB] = process.argv.slice(2);

function functions(path) {
  const src = readFileSync(path, "utf8").split("\n");
  const out = new Map();
  let name = null;
  let body = [];
  let depth = 0;
  let started = false;
  for (const line of src) {
    if (!started) {
      const m = /^[A-Za-z_][A-Za-z0-9_:<>,*&\s]*?\b([A-Za-z_][A-Za-z0-9_]*)\([^;]*\)\s*\{$/.exec(line);
      if (m && !line.startsWith("//")) {
        name = m[1];
        body = [];
        depth = 1;
        started = true;
      }
      continue;
    }
    depth += (line.match(/\{/g) ?? []).length - (line.match(/\}/g) ?? []).length;
    if (depth <= 0) {
      started = false;
      if (out.has(name)) name = name + "#dup";
      out.set(name, body);
      continue;
    }
    body.push(line);
  }
  return out;
}

// The statement shapes of a body: every line with the scratch slots, the string-table
// indices and the leading indentation erased.
function shapes(body) {
  const seen = [];
  for (const line of body) {
    const s = line.trim();
    if (s === "" || s.startsWith("//")) continue;
    seen.push(
      s.replace(/__sm_stringTable\[\d+\]/g, "__sm_stringTable[?]")
       .replace(/^[A-Za-z_][A-Za-z0-9_:<>,*&\s]*?\s_sm_(?:base|expr|for|step|when)\d+/g, "SLOT")
       .replace(/_sm_(?:base|expr|for|step|when)\d+/g, "SLOT")
       .replace(/\bL\d+\b/g, "LABEL"));
  }
  return seen.sort();
}

const A = functions(pathA);
const B = functions(pathB);
let onlyA = 0;
let onlyB = 0;
let differ = 0;
const names = new Set([...A.keys(), ...B.keys()]);
for (const name of names) {
  if (!A.has(name)) { onlyB++; console.log("ONLY IN B:", name); continue; }
  if (!B.has(name)) { onlyA++; console.log("ONLY IN A:", name); continue; }
  const a = shapes(A.get(name));
  const b = shapes(B.get(name));
  if (a.join("\n") === b.join("\n")) continue;
  differ++;
  const sa = new Set(a);
  const sb = new Set(b);
  const gone = a.filter((x) => !sb.has(x));
  const added = b.filter((x) => !sa.has(x));
  console.log(`--- ${name}`);
  for (const g of gone.slice(0, 6)) console.log("   -", g);
  for (const g of added.slice(0, 6)) console.log("   +", g);
}
console.log(`functions A=${A.size} B=${B.size}; only-A=${onlyA} only-B=${onlyB} differing=${differ}`);
