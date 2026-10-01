// Scratch: how much does the join/dedupe scheme buy on a real table? Reads the emitted
// `__sm_stringTable` of an amalgamation, decodes the literals, then simulates the
// algorithm (sort by length desc / text asc, join with substring reuse) and reports sizes.
import { readFileSync } from "fs";

const path = process.argv[2] ?? "cppsrc/simse_bootstrap.cpp";
const text = readFileSync(path, "utf8");

const start = text.indexOf("static const Str __sm_stringTable[");
if (start < 0) throw new Error("no string table found");
const open = text.indexOf("{", start);
const close = text.indexOf("\n};", open);
const body = text.slice(open + 1, close);

// One entry per line, each a C++ string literal.
const raw = body.split("\n").map((line) => line.trim()).filter((line) => line.length > 0)
    .map((line) => line.replace(/,$/, ""));
console.log(`entries: ${raw.length}`);

function decode(literal) {
  // A C++ string literal (leading/trailing quote, escapes decoded).
  const inner = literal.slice(1, -1);
  let out = "";
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i];
    if (ch !== "\\") {
      out += ch;
      continue;
    }
    const next = inner[++i];
    switch (next) {
      case "n": out += "\n"; break;
      case "r": out += "\r"; break;
      case "t": out += "\t"; break;
      case "0": out += "\0"; break;
      case "\\": out += "\\"; break;
      case '"': out += '"'; break;
      case "'": out += "'"; break;
      case "x": {
        let hex = "";
        while (i + 1 < inner.length && /[0-9a-fA-F]/.test(inner[i + 1])) hex += inner[++i];
        out += String.fromCharCode(parseInt(hex, 16) & 0xff);
        break;
      }
      default: out += next;
    }
  }
  return out;
}

const entries = raw.map(decode);
const bytes = entries.reduce((sum, entry) => sum + entry.length, 0);
console.log(`decoded bytes in literals: ${bytes}`);

// The scheme: longest first (ties alphabetical), join with substring reuse.
const sorted = [...entries].sort((a, b) => (b.length - a.length) || (a < b ? -1 : a > b ? 1 : 0));
let pool = "";
const slices = [];
let appended = 0;
for (const entry of sorted) {
  let at = pool.indexOf(entry);
  if (at < 0) {
    at = pool.length;
    pool += entry;
    appended++;
  }
  slices.push([at, entry.length]);
}

// The slice encoding: 7 payload bits a byte, the high bit set on the *last* byte, a
// signed delta's first byte spending bit 6 on the sign and 6 bits on its value.
function encodeUnsigned(value) {
  const bytes = [];
  let rest = value;
  while (rest > 127) {
    bytes.push(rest & 0x7f);
    rest = Math.floor(rest / 128);
  }
  bytes.push(0x80 | rest);
  return bytes;
}
function encodeSigned(value) {
  const negative = value < 0;
  let rest = Math.abs(value);
  const first = rest & 0x3f;
  rest = Math.floor(rest / 64);
  if (rest === 0) return [0x80 | (negative ? 0x40 : 0) | first];
  const bytes = [(negative ? 0x40 : 0) | first];
  while (rest > 0) {
    const group = rest & 0x7f;
    rest = Math.floor(rest / 128);
    bytes.push(group | (rest === 0 ? 0x80 : 0));
  }
  return bytes;
}

let encoded = [];
let previous = 0;
for (const [at, len] of slices) {
  encoded.push(...encodeSigned(at - previous));
  encoded.push(...encodeUnsigned(len));
  previous = at;
}
const pairs16 = slices.length * 2 * 2;
const joinedBytes = pool.length;
const int16 = joinedBytes < 32768;
const tableBytes = slices.length * (int16 ? 2 : 4) * 2;
console.log(`appended pieces: ${appended} of ${entries.length} (${(100 * appended / entries.length).toFixed(1)}%)`);
console.log(`joined text: ${joinedBytes} bytes (dedupe saves ${bytes - joinedBytes})`);
console.log(`slices: ${encoded.length} bytes as varints, ${pairs16} as int16 pairs, ${tableBytes} as the widest choice`);
console.log(`total: ${joinedBytes + tableBytes} bytes vs ${bytes} now (${(100 * (joinedBytes + tableBytes) / bytes).toFixed(1)}%)`);

// `Str` is 32 bytes (4 len + 4 cap + 24 inline); `StrView` is a pointer plus a length.
// A literal longer than 23 characters allocates at static-init time today.
const long = entries.filter((entry) => entry.length > 23);
console.log(`literals longer than the inline capacity: ${long.length}, ${long.reduce((sum, entry) => sum + entry.length, 0)} bytes`);
console.log(`static table, today (Str kept inline):        ${entries.length * 32 + long.reduce((s, e) => s + e.length, 0)} bytes (+ heap for the long ones)`);
console.log(`static table, join + StrView entries:          ${entries.length * 16 + joinedBytes} bytes (no heap, no copies; sizeof(StrView) = 16 measured)`);
console.log(`static table, join + materialized Str entries: ${entries.length * 32 + joinedBytes + encoded.length} bytes`);
