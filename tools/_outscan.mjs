// _outscan.mjs - the out-slot scan (impl_specs/linear-form-plan.md, section 3 and step 3a).
//
// Walks a `--showLinearRepresentation` dump body by body, classifying each op's operands by
// the signature table's roles: the first `Var` is a write, every other slot operand a read,
// a `Declare` is storage only, arguments arrive written - and a call argument whose
// position is in the method record's `writes` mask (`:writes=` in the dump's methods line)
// is an `Out`: the callee writes it. Reports every read of a slot not yet written, grouped
// by the method the call that first took the slot names - and, when the residue is small
// enough to read, by the slot itself.
//
//   ./simse.exe --root src --root modules -o build/x.cpp --showLinearRepresentation 2> il.txt
//   bun tools/_outscan.mjs il.txt          # the measurement
//   bun tools/_outscan.mjs il.txt --no-roles   # the table believed, the masks not
//
// Operand texts are rendered (slot names, quoted literals), so the split is quote-aware: a
// string literal may hold `, `. A method operand renders as the callee's *name*, so the
// masks are keyed by name, same-named entries unioned. A `CallCtor`'s type operand is
// skipped (types carry commas and a construction's args are never Out).

import { readFileSync } from "node:fs";

const SIGNATURES = {
  Label: ["Label"], Goto: ["Label"], IfTrue: ["Value", "Label"], IfFalse: ["Value", "Label"],
  Declare: ["Var"], DeclareInit: ["Var"], SetVar: ["Var", "Value"], SetVar_Null: ["Var"],
  BinaryOp: ["Var", "Text", "Value", "Value"], UnaryOp: ["Var", "Text", "Value"],
  Cast: ["Var", "Value"], Box: ["Var", "Var"], Deref: ["Var", "Var"], CopyValue: ["Var", "Var"],
  Store: ["Var", "Value"], GetField: ["Var", "Var", "Text"], SetField: ["Var", "Text", "Value"],
  GetIndex: ["Var", "Var", "Value"], SetIndex: ["Var", "Value", "Value"],
  FieldAddr: ["Var", "Var", "Text"], IndexAddr: ["Var", "Var", "Value"],
  GetStatic: ["Var", "Text"], GetStaticAddr: ["Var", "Text"], SetStatic: ["Text", "Value"],
  Call: ["Var", "Method", "Value..."], CallVoid: ["Method", "Value..."],
  CallIndirect: ["Var", "Var", "Value..."], CallIndirectVoid: ["Var", "Value..."],
  CallCtor: ["Var", "Type", "Value..."], Pack: ["Var", "Value..."], Concat: ["Var", "Value..."],
  Return: ["Value"], ReturnVoid: [], Lambda: ["Var"], Unsupported: ["Var", "Text"],
};

function splitOperands(text) {
  const parts = [];
  let start = 0, inQuote = null;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuote) { if (ch === inQuote) inQuote = null; continue; }
    if (ch === '"' || ch === "'") { inQuote = ch; continue; }
    if (ch === "," && text[i + 1] === " ") { parts.push(text.slice(start, i)); start = i + 2; i++; }
  }
  parts.push(text.slice(start));
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

function parseVars(body, text) {
  for (const entry of text.trim().split(/\s{3,}/)) {
    const em = entry.match(/^(\d+) ([^:]+):(\d+):([A-Za-z]+)/);
    if (em) {
      body.vars.set(em[2], Number(em[1]));
      body.kinds[Number(em[1])] = em[4];
    }
  }
}

function parseMethods(body, text) {
  for (const entry of text.trim().split(/\s{3,}/)) {
    const em = entry.match(/^(\d+) ([^:]+):([A-Za-z]+):(\d+)(.*)$/);
    if (em) {
      const wm = em[5].match(/:writes=([\d,]+)/);
      const writes = new Set(wm ? wm[1].split(",").map(Number) : []);
      const held = body.methods.get(em[2]);
      if (held) { for (const w of writes) held.add(w); }
      else body.methods.set(em[2], writes);
    }
  }
}

const useRoles = !process.argv.includes("--no-roles");
const byTaker = new Map();
const bySlot = new Map();
let total = 0;
let header = "";
const bodies = [];
let body = null;

const dump = readFileSync(process.argv[2] ?? "il.txt", "utf8").split(/\r?\n/);
for (const line of dump) {
  if (line.startsWith("# ")) {
    header = line.slice(2, 90);
    body = { ops: [], vars: new Map(), methods: new Map(), kinds: [], header };
    bodies.push(body);
    continue;
  }
  if (!body) continue;
  if (line.startsWith("vars:")) { parseVars(body, line.slice(line.indexOf(":") + 1)); continue; }
  if (line.startsWith("methods:")) { parseMethods(body, line.slice(line.indexOf(":") + 1)); continue; }
  if (line.startsWith("pool:") || line.startsWith("types:") || line.startsWith("labels:")) continue;
  if (line.startsWith("## closure")) { body = null; continue; }
  const m = line.match(/^\s*(\d+)\s+,\s+([A-Za-z_]+)(.*)$/);
  if (m) {
    const rest = m[3].replace(/\s*#.*$/, "");
    body.ops.push({ kind: m[2], operands: splitOperands(rest) });
  }
}

for (const body of bodies) {
  const written = new Set();
  // Arguments arrive written: the caller's value is the frame's starting fact.
  for (let i = 0; i < body.kinds.length; i++) {
    if (body.kinds[i] === "Argument") written.add(i);
  }
  for (const op of body.ops) {
    const sig = SIGNATURES[op.kind];
    if (!sig) continue;
    const ops = op.operands;
    const slotAt = (i) => (ops[i] !== undefined && body.vars.has(ops[i]) ? body.vars.get(ops[i]) : -1);
    const isCall = op.kind === "Call" || op.kind === "CallVoid";
    const methodAt = isCall ? (op.kind === "Call" ? 1 : 0) : -1;
    const argBase = isCall ? (op.kind === "Call" ? 2 : 1) : -1;
    const writesSet = useRoles && isCall && body.methods.has(ops[methodAt])
      ? body.methods.get(ops[methodAt]) : new Set();
    // writes: the destination, and every Out argument. A `Declare` defines its slot with
    // the type's default value - the emitted C++ (`Str out;`) constructs it - so an early
    // `return out` on the empty path reads a defined slot, not a hole.
    if (op.kind === "Declare" || op.kind === "DeclareInit") {
      const d = slotAt(0);
      if (d >= 0) written.add(d);
      continue;
    }
    if (sig[0] === "Var") {
      const d = slotAt(0);
      if (d >= 0) written.add(d);
    }
    for (let i = argBase; i >= 0 && i < ops.length; i++) {
      if (writesSet.has(i - argBase)) {
        const s = slotAt(i);
        if (s >= 0) written.add(s);
      }
    }
    // reads: every slot operand that is neither the dst nor an Out position
    for (let i = 0; i < ops.length; i++) {
      const kind = i < sig.length ? sig[i] : sig[sig.length - 1];
      if (kind !== "Var" && kind !== "Value") continue;
      if (i === 0 && sig[0] === "Var") continue;
      if (i >= argBase && writesSet.has(i - argBase)) continue;
      const s = slotAt(i);
      if (s < 0 || written.has(s)) continue;
      total++;
      const taker = isCall && body.methods.has(ops[methodAt]) ? ops[methodAt] : op.kind;
      byTaker.set(taker, (byTaker.get(taker) ?? 0) + 1);
      const slotKey = body.header + " :: " + ops[i];
      bySlot.set(slotKey, (bySlot.get(slotKey) ?? 0) + 1);
    }
  }
}

console.log(`bodies: ${bodies.length}, reads before a write: ${total}${useRoles ? "" : " (roles off)"}`);
const sorted = [...byTaker.entries()].sort((a, b) => b[1] - a[1]);
for (const [taker, count] of sorted) console.log(`  ${count}\t${taker}`);
if (total <= 50) {
  console.log("  -- the reads, by slot:");
  const sortedSlots = [...bySlot.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [slot, count] of sortedSlots) console.log(`  ${count}\t${slot}`);
}
