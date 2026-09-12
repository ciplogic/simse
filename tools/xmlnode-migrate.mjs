// migrate.mjs - promote read-only `XmlNode` parameters to `*XmlNode` in a
// .simse file.
//
// For every function whose parameter is declared exactly `: XmlNode` or, for
// lists, `: List<XmlNode>`:
//   - the parameter becomes `: *XmlNode` / `: *List<XmlNode>`;
//   - inside that function's body, `*param` becomes `param` (a pointer passed
//     to another migrated function needs no address-of);
//   - at call sites, each argument that lands on an XmlNode parameter gets a
//     leading `*`, unless it is already `*...` or a migrated parameter of the
//     enclosing function.
//
// Matches inside string literals and comments are ignored. Function
// signatures must be on one line (they are in this codebase).

import { readFileSync, writeFileSync } from "node:fs";

const file = process.argv[2];
if (!file) {
  console.error("usage: bun migrate.mjs <file.simse>");
  process.exit(2);
}
const src = readFileSync(file, "utf8");

// ---- code/string/comment mask ----------------------------------------------
function maskCode(text) {
  const mask = new Uint8Array(text.length); // 1 = code
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '"') {
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === "\\") i++;
        i++;
      }
      i++;
      continue;
    }
    if (ch === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
      continue;
    }
    mask[i] = 1;
    i++;
  }
  return mask;
}
const code = maskCode(src);

function isCode(pos) {
  return code[pos] === 1;
}

// ---- functions -------------------------------------------------------------
function findFunctions(text) {
  const lines = text.split("\n");
  const starts = [];
  let offset = 0;
  for (let i = 0; i < lines.length; i++) {
    starts.push(offset);
    offset += lines[i].length + 1;
  }
  const fns = [];
  const lineOf = (pos) => {
    let lo = 0, hi = starts.length - 1, best = 0;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (starts[mid] <= pos) { best = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return best;
  };
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)fun\s+([A-Za-z0-9_]+)\s*\(/);
    if (!m) continue;
    const indent = m[1].length;
    const name = m[2];
    const open = starts[i] + lines[i].indexOf("(", m[1].length);
    let depth = 0, close = -1;
    for (let k = open; k < starts[i] + lines[i].length; k++) {
      if (src[k] === "(") depth++;
      else if (src[k] === ")") { depth--; if (depth === 0) { close = k; break; } }
    }
    if (close < 0) continue;
    const paramsText = src.slice(open + 1, close);
    let endLine = -1;
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].replace(/\r$/, "") === " ".repeat(indent) + "}") { endLine = j; break; }
    }
    if (endLine < 0) continue;
    const bodyEnd = starts[endLine] + lines[endLine].length;
    fns.push({ name, indent, line: i + 1, open, close, paramsText, bodyStart: starts[i] + lines[i].length, bodyEnd, sigStart: starts[i] });
    void lineOf;
  }
  return fns;
}

function splitTopLevel(text, start, end) {
  const parts = [];
  let depth = 0, from = start, i = start;
  while (i < end) {
    const ch = text[i];
    if (ch === '"') {
      i++;
      while (i < end && text[i] !== '"') { if (text[i] === "\\") i++; i++; }
      i++;
      continue;
    }
    if (ch === "(" || ch === "[" || ch === "{") depth++;
    else if (ch === ")" || ch === "]" || ch === "}") depth--;
    else if (ch === "<" && !(text[i - 1] === "-")) depth++;
    else if (ch === ">" && !(text[i - 1] === "-")) depth--;
    else if (ch === "," && depth === 0) { parts.push([from, i]); from = i + 1; }
    i++;
  }
  if (text.slice(from, end).trim() !== "") parts.push([from, end]);
  return parts;
}

function parseParams(paramsText) {
  // Returns [{name, type, isXml}] for `name: Type` parameters.
  const out = [];
  const parts = splitTopLevel(paramsText, 0, paramsText.length);
  for (const [a, b] of parts) {
    const text = paramsText.slice(a, b);
    const colon = text.indexOf(":");
    if (colon < 0) continue;
    const name = text.slice(0, colon).trim();
    const type = text.slice(colon + 1).trim();
    out.push({ name, type, isXml: type === "XmlNode" || type === "List<XmlNode>" });
  }
  return out;
}

const fns = findFunctions(src);
const migrated = new Map(); // name -> params
for (const fn of fns) {
  const params = parseParams(fn.paramsText);
  if (params.some(p => p.isXml)) migrated.set(fn.name, params);
}

// ---- edits -----------------------------------------------------------------
let insertions = []; // {pos, text}
let removals = [];   // {pos} - positions of '*' to delete

// 1. signatures + body pointer pass-throughs
for (const fn of fns) {
  if (!migrated.has(fn.name)) continue;
  const params = migrated.get(fn.name);
  for (const p of params) {
    if (!p.isXml) continue;
    // signature: `name: XmlNode` -> `name: *XmlNode`
    const sigText = src.slice(fn.open, fn.close);
    const idx = sigText.indexOf(p.name + ": " + p.type);
    if (idx >= 0) insertions.push({ pos: fn.open + idx + p.name.length + 2, text: "*" });
    // body: `*name` -> `name`, but only when the star addresses the parameter
    // itself. `*name[i]` / `*name.field` address an element/field (which still
    // auto-dereferences), so their star stays.
    const body = src.slice(fn.bodyStart, fn.bodyEnd);
    const re = new RegExp("([^\\w\\)\\]])" + "\\*" + p.name + "\\b", "g");
    let m;
    while ((m = re.exec(body)) !== null) {
      const after = body[m.index + m[0].length];
      if (after === "[" || after === ".") continue;
      const starPos = fn.bodyStart + m.index + m[1].length;
      if (isCode(starPos)) removals.push({ pos: starPos });
    }
  }
}

// 2. call sites: add `*` to XmlNode arguments
const migratedNames = [...migrated.keys()].sort((a, b) => b.length - a.length);
for (const fn of fns) {
  const enclosing = migrated.get(fn.name);
  const ownParams = enclosing ? enclosing.filter(p => p.isXml).map(p => p.name) : [];
  const body = src.slice(fn.bodyStart, fn.bodyEnd);
  for (const callee of migratedNames) {
    const params = migrated.get(callee);
    const re = new RegExp("\\b" + callee + "\\s*\\(", "g");
    let m;
    while ((m = re.exec(body)) !== null) {
      const openPos = fn.bodyStart + m.index + m[0].length - 1;
      if (!isCode(openPos)) continue;
      if (openPos < fn.open || openPos > fn.close) {
        // not the definition of this function - a real call
      } else if (fn.name === callee) {
        // the call is inside the definition of `callee` itself: fine
      }
      // find matching close paren
      let depth = 0, close = -1;
      for (let k = openPos; k < src.length; k++) {
        if (src[k] === '"') {
          k++;
          while (k < src.length && src[k] !== '"') { if (src[k] === "\\") k++; k++; }
          continue;
        }
        if (src[k] === "(") depth++;
        else if (src[k] === ")") { depth--; if (depth === 0) { close = k; break; } }
      }
      if (close < 0) continue;
      const args = splitTopLevel(src, openPos + 1, close);
      for (let i = 0; i < args.length && i < params.length; i++) {
        if (!params[i].isXml) continue;
        const [a, b] = args[i];
        const argText = src.slice(a, b);
        const trimmed = argText.trim();
        if (trimmed.startsWith("*")) continue;
        if (ownParams.includes(trimmed)) continue;
        const lead = argText.length - argText.trimStart().length;
        insertions.push({ pos: a + lead, text: "*" });
      }
    }
  }
}

// ---- apply (descending positions, one pass) ---------------------------------
const edits = [
  ...insertions.map(e => ({ pos: e.pos, text: e.text })),
  ...removals.map(e => ({ pos: e.pos, text: "" })),
];
edits.sort((x, y) => y.pos - x.pos);
let out = src;
for (const e of edits) out = out.slice(0, e.pos) + e.text + out.slice(e.pos + (e.text === "" ? 1 : 0));

writeFileSync(file, out);
console.log(`migrate: ${migrated.size} function(s), ${removals.length} pass-through(s), ${insertions.length} argument(s)`);
for (const name of migratedNames) {
  const ps = migrated.get(name).filter(p => p.isXml).map(p => p.name).join(", ");
  console.log(`  ${name}(${ps})`);
}
