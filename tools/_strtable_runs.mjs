// Scratch: how compact the string table's two index series are - the plain series, the
// difference series the emitter stores, and the run-length encoded stream it writes.
//   bun tools/_strtable_runs.mjs
import { readFileSync } from "node:fs";

const text = readFileSync("cppsrc/simse_bootstrap.cpp", "utf8");
const stream = (name) => {
  const match = text.match(new RegExp(`static const Int16 ${name}\\[\\] = \\{([^}]*)\\};`));
  if (!match) throw new Error(`no ${name}`);
  return match[1].split(",").map((x) => Number(x.trim()));
};

// The RLE stream (strtable.hpp): the series' length, then alternating literal blocks
// (count, values) and run blocks (count, times+value pairs) until the length is filled.
const expand = (encoded) => {
  const total = encoded[0];
  const values = [];
  let at = 1;
  while (values.length < total) {
    const literals = encoded[at++];
    for (let i = 0; i < literals; i++) values.push(encoded[at++]);
    if (values.length >= total) break;
    const runs = encoded[at++];
    for (let i = 0; i < runs; i++) {
      const times = encoded[at++];
      const value = encoded[at++];
      for (let j = 0; j < times; j++) values.push(value);
    }
  }
  return values;
};

for (const name of ["__sm_stringStarts", "__sm_stringLens"]) {
  const encoded = stream(name);
  const series = expand(encoded);
  // The series is stored as differences: value[i] = value[i-1] - stored[i].
  const values = [];
  let previous = 0;
  for (const delta of series) {
    previous -= delta;
    values.push(previous);
  }
  const zeros = series.filter((v) => v === 0).length;
  const runs = [];
  for (const value of series) {
    if (runs.length > 0 && runs[runs.length - 1].value === value) runs[runs.length - 1].times++;
    else runs.push({ value, times: 1 });
  }
  console.log(`${name}:`);
  console.log(`  series ${values.length} values; stored differences ${series.length * 2} B as Int16`);
  console.log(`  ${zeros} differences are 0 (${((zeros / series.length) * 100).toFixed(1)}%), ${runs.length} runs, longest ${Math.max(...runs.map((r) => r.times))}`);
  console.log(`  encoded stream ${encoded.length} numbers, ${encoded.length * 2} B as Int16 (${((encoded.length * 2) / (series.length * 2) * 100).toFixed(0)}% of the plain series)`);
  console.log(`  distinct magnitudes ${new Set(series.map((v) => Math.abs(v))).size}, max |difference| ${Math.max(...series.map((v) => Math.abs(v)))}`);
  console.log(`  values: ${values.slice(0, 8).join(",")} ...`);
}
const starts = expand(stream("__sm_stringStarts"));
const lens = expand(stream("__sm_stringLens"));
const same = starts.slice(1).every((v, i) => v === lens[i]) && starts[0] === 0;
console.log(`starts is lens shifted right, plus a leading 0: ${same}`);
