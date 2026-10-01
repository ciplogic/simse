// Scratch: the section sizes of a PE/COFF executable, so the two compiler binaries can be
// compared where it matters (.text / .rdata / .data) rather than by file size alone.
//   bun tools/_pe_sections.mjs <exe>...
import { readFileSync } from "node:fs";

const read = (path) => {
  const bytes = readFileSync(path);
  const pe = bytes.readUInt32LE(0x3c);
  const sections = bytes.readUInt16LE(pe + 6);
  const optional = bytes.readUInt16LE(pe + 20);
  const table = pe + 24 + optional;
  const out = [];
  for (let i = 0; i < sections; i++) {
    const at = table + i * 40;
    const name = bytes.subarray(at, at + 8).toString("utf8").replace(/\0.*$/, "");
    out.push({ name, virtual: bytes.readUInt32LE(at + 8), raw: bytes.readUInt32LE(at + 16) });
  }
  return { total: bytes.length, sections: out };
};

const rows = [];
for (const path of process.argv.slice(2)) {
  const { total, sections } = read(path);
  rows.push({ path, total, sections });
}
const names = [];
for (const row of rows) for (const s of row.sections) if (!names.includes(s.name)) names.push(s.name);

for (const name of names) {
  const cells = rows.map((row) => {
    const s = row.sections.find((x) => x.name === name);
    return s ? s.virtual : 0;
  });
  const delta = cells[1] - cells[0];
  console.log(`${name.padEnd(10)} ${cells.map((c) => String(c).padStart(9)).join("")}   ${(delta >= 0 ? "+" : "") + delta}`);
}
console.log(`${"file".padEnd(10)} ${rows.map((r) => String(r.total).padStart(9)).join("")}   ${(rows[1].total - rows[0].total >= 0 ? "+" : "") + (rows[1].total - rows[0].total)}`);
console.log(`\ncolumns: ${rows.map((r) => r.path).join("  ")}`);
