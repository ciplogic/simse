// onebrc.mjs - deterministic data generator and reference aggregation for the
// 1 Billion Row Challenge benchmark (benchmarks/onebrc/).
//
//   bun benchmarks/onebrc/onebrc.mjs gen <rows> <out-file> [--seed <n>] [--stations <csv>]
//   bun benchmarks/onebrc/onebrc.mjs check <file>
//   bun benchmarks/onebrc/onebrc.mjs --selftest
//
// `check` writes the report to stdout in the 1BRC format (sorted by station,
// `{name=min/mean/max}`) and its timing to stderr, so stdout can be diffed
// byte for byte against another implementation.

import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

const USAGE = `usage:
  bun onebrc.mjs gen <rows> <out-file> [--seed <n>] [--stations <csv>]
  bun onebrc.mjs check <file>
  bun onebrc.mjs --selftest`;

const NL = 10, CR = 13, SEMI = 59, MINUS = 45, DOT = 46, ZERO = 48, MB = 1e6;

// Built-in stations: name and annual mean in degrees C. ASCII only, no ';',
// means span roughly -30 (Vostok) to +34.5 (Dallol). Sorted below so runs are
// reproducible.
const STATION_TABLE = [
  ["Abha", 18.0], ["Abu Dhabi", 28.0], ["Alert", -18.0], ["Alice Springs", 21.0], ["Amsterdam", 10.5],
  ["Anchorage", 2.5], ["Athens", 18.5], ["Atlanta", 17.0], ["Auckland", 15.5], ["Baghdad", 24.0],
  ["Bangkok", 28.5], ["Barcelona", 17.5], ["Beijing", 12.5], ["Beirut", 20.5], ["Berlin", 10.0],
  ["Boston", 11.0], ["Brisbane", 21.0], ["Brussels", 11.0], ["Bucharest", 11.0], ["Buenos Aires", 17.5],
  ["Cairo", 22.5], ["Cape Town", 16.5], ["Casablanca", 18.0], ["Chicago", 10.0], ["Copenhagen", 9.0],
  ["Dakar", 25.0], ["Dallol", 34.5], ["Dar es Salaam", 26.0], ["Delhi", 25.0], ["Doha", 28.0],
  ["Dubai", 28.5], ["Edinburgh", 9.0], ["Fairbanks", -3.0], ["Frankfurt", 10.5], ["Hanoi", 24.5],
  ["Havana", 25.5], ["Helsinki", 6.0], ["Hong Kong", 23.5], ["Honolulu", 25.0], ["Houston", 21.0],
  ["Istanbul", 14.5], ["Jakarta", 27.5], ["Jeddah", 28.5], ["Johannesburg", 16.0], ["Karachi", 26.0],
  ["Kathmandu", 18.0], ["Khartoum", 29.5], ["Kuala Lumpur", 27.5], ["Kuwait City", 28.5], ["Kyiv", 9.0],
  ["Lagos", 26.5], ["Las Vegas", 20.5], ["Lima", 19.5], ["Lisbon", 17.0], ["London", 11.5],
  ["Los Angeles", 17.5], ["Madrid", 15.0], ["Manila", 27.5], ["Melbourne", 15.5], ["Mexico City", 16.0],
  ["Miami", 25.0], ["Milan", 13.5], ["Montreal", 6.5], ["Moscow", 6.0], ["Mumbai", 27.0],
  ["Munich", 9.5], ["Nairobi", 18.0], ["Naples", 16.5], ["New York", 13.0], ["Norilsk", -9.5],
  ["Oslo", 6.0], ["Ottawa", 6.0], ["Paris", 12.0], ["Perth", 18.5], ["Phoenix", 23.0],
  ["Prague", 8.5], ["Reykjavik", 5.0], ["Rio de Janeiro", 23.5], ["Riyadh", 27.0], ["Rome", 16.0],
  ["San Francisco", 14.5], ["Santiago", 14.5], ["Sao Paulo", 19.5], ["Seattle", 11.5], ["Seoul", 12.5],
  ["Shanghai", 16.5], ["Singapore", 27.5], ["St. Petersburg", 5.5], ["Stockholm", 7.0], ["Sydney", 18.5],
  ["Taipei", 23.0], ["Tokyo", 16.0], ["Toronto", 9.0], ["Vancouver", 10.5], ["Vienna", 11.0],
  ["Vostok Station", -30.0], ["Warsaw", 8.5], ["Wellington", 13.5], ["Yakutsk", -8.5], ["Zurich", 9.5],
];

function byName(a, b) {
  return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
}

const BUILTIN = STATION_TABLE.map(([name, mean]) => ({ name, tenths: Math.round(mean * 10) })).sort(byName);

// `--stations` file: one `name;mean` per line, '#' starts a comment, blank
// lines ignored (the official weather_stations.csv shape).
function loadStations(file) {
  const out = [];
  const lines = fs.readFileSync(file, "utf8").split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/#.*/, "").trim();
    if (line === "") continue;
    const semi = line.indexOf(";");
    const name = line.slice(0, semi).trim();
    const mean = line.slice(semi + 1).trim();
    if (semi < 0 || !/^[\x20-\x7e]+$/.test(name) || !/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(mean))
      throw new Error(`${file}:${i + 1}: expected '<ascii-name>;<mean>', got '${lines[i].trim()}'`);
    out.push({ name, tenths: Math.round(Number(mean) * 10) });
  }
  return out.sort(byName);
}

function formatTenths(t) {
  const a = Math.abs(t);
  return `${t < 0 ? "-" : ""}${(a / 10) | 0}.${a % 10}`;
}

// Java's Math.round(mean * 10) / 10 for a mean held in exact tenths: nearest
// tenth, ties toward +infinity. Integer-only, so no float rounding creeps in.
function roundHalfUpTenths(sum, count) {
  return sum >= 0
    ? Math.floor((2 * sum + count) / (2 * count))
    : -Math.floor((2 * -sum + count - 1) / (2 * count));
}

function badLine(what, buf, from, to) {
  throw new Error(`${what}: '${buf.toString("latin1", from, to)}'`);
}

// Parses `name;temperature` from buf[from..to) and folds it into `stats`.
// Temperatures stay in exact tenths; at most one decimal digit is accepted.
function addSample(stats, buf, from, to) {
  let sep = from;
  while (sep < to && buf[sep] !== SEMI) sep++;
  if (sep === to) badLine("malformed line (no ';')", buf, from, to);
  let p = sep + 1;
  let neg = false;
  if (p < to && buf[p] === MINUS) { neg = true; p++; }
  let tenths = 0;
  const intFrom = p;
  while (p < to && buf[p] !== DOT) {
    const d = buf[p] - ZERO;
    if (d < 0 || d > 9) badLine("malformed temperature", buf, sep + 1, to);
    tenths = tenths * 10 + d;
    p++;
  }
  if (p === intFrom) badLine("empty temperature", buf, from, to);
  if (p < to) {
    p++;
    if (p >= to) badLine("temperature has no decimal digit", buf, from, to);
    const d = buf[p] - ZERO;
    if (d < 0 || d > 9) badLine("malformed temperature", buf, sep + 1, to);
    tenths = tenths * 10 + d;
    if (++p !== to) badLine("temperature has more than one decimal digit", buf, from, to);
  } else {
    tenths *= 10;
  }
  if (neg) tenths = -tenths;
  const name = buf.toString("latin1", from, sep);
  const s = stats.get(name);
  if (s === undefined) {
    stats.set(name, { min: tenths, max: tenths, sum: tenths, count: 1 });
  } else {
    if (tenths < s.min) s.min = tenths;
    if (tenths > s.max) s.max = tenths;
    s.sum += tenths;
    s.count++;
  }
}

// Streams the file in `chunk`-byte reads, carrying a partial trailing line into
// the next chunk. Handles '\r\n' and a final line without '\n'.
function aggregateFile(file, chunk = 1 << 20) {
  const fd = fs.openSync(file, "r");
  const buf = Buffer.allocUnsafe(chunk + 4096);
  const stats = new Map();
  let len = 0, lines = 0, bytes = 0;
  try {
    for (;;) {
      const n = fs.readSync(fd, buf, len, Math.min(chunk, buf.length - len), null);
      if (n <= 0) break;
      bytes += n;
      len += n;
      let lineStart = 0;
      for (let i = 0; i < len; i++) {
        if (buf[i] !== NL) continue;
        const end = i > lineStart && buf[i - 1] === CR ? i - 1 : i;
        addSample(stats, buf, lineStart, end);
        lines++;
        lineStart = i + 1;
      }
      if (lineStart > 0) buf.copyWithin(0, lineStart, len);
      len -= lineStart;
    }
    if (len > 0) {
      const end = buf[len - 1] === CR ? len - 1 : len;
      addSample(stats, buf, 0, end);
      lines++;
    }
  } finally {
    fs.closeSync(fd);
  }
  return { stats, lines, bytes };
}

function printReport(stats) {
  const names = [...stats.keys()].sort();
  if (names.length === 0) return;
  const out = [];
  for (const name of names) {
    const s = stats.get(name);
    out.push(`{${name}=${formatTenths(s.min)}/${formatTenths(roundHalfUpTenths(s.sum, s.count))}/${formatTenths(s.max)}}`);
  }
  process.stdout.write(out.join("\n") + "\n");
}

// mulberry32: deterministic 32-bit PRNG (Math.random is never used here).
function mulberry32(seed) {
  let a = seed | 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Writes `rows` lines of `station;temperature\n`. Each temperature has exactly
// one decimal digit in [-99.9, 99.9], drawn from a Gaussian around the
// station's mean with sigma = 10 degrees (clamped). Rows are built into a 1 MB
// Buffer with hand-written digits and flushed with fs.writeSync.
function genFile(rows, outPath, seed, stations) {
  const prefixes = stations.map((s) => Buffer.from(s.name + ";", "latin1"));
  const means = Int32Array.from(stations, (s) => s.tenths);
  let rowMax = 8;
  for (const p of prefixes) rowMax = Math.max(rowMax, p.length + 8);

  const CAP = 1 << 20;
  const buf = Buffer.allocUnsafe(CAP);
  const fd = fs.openSync(outPath, "w");
  const t0 = performance.now();
  let pos = 0, bytes = 0;
  const flush = () => {
    for (let off = 0; off < pos; ) off += fs.writeSync(fd, buf, off, pos - off);
    bytes += pos;
    pos = 0;
  };
  const rand = mulberry32(seed);
  let spare = 0, hasSpare = false;
  const gaussian = () => {
    if (hasSpare) { hasSpare = false; return spare; }
    let u = rand();
    if (u === 0) u = Number.MIN_VALUE;
    const r = Math.sqrt(-2 * Math.log(u));
    const angle = 2 * Math.PI * rand();
    spare = r * Math.sin(angle);
    hasSpare = true;
    return r * Math.cos(angle);
  };
  try {
    for (let row = 0; row < rows; row++) {
      if (pos + rowMax > CAP) flush();
      const k = (rand() * stations.length) | 0;
      pos += prefixes[k].copy(buf, pos);
      let tenths = means[k] + Math.round(100 * gaussian());
      if (tenths > 999) tenths = 999;
      else if (tenths < -999) tenths = -999;
      if (tenths < 0) { buf[pos++] = MINUS; tenths = -tenths; }
      const ip = (tenths / 10) | 0;
      if (ip >= 100) buf[pos++] = ZERO + ((ip / 100) | 0);
      if (ip >= 10) buf[pos++] = ZERO + (((ip / 10) | 0) % 10);
      buf[pos++] = ZERO + (ip % 10);
      buf[pos++] = DOT;
      buf[pos++] = ZERO + (tenths % 10);
      buf[pos++] = NL;
    }
    flush();
  } finally {
    fs.closeSync(fd);
  }
  return { bytes, elapsed: performance.now() - t0 };
}

function cmdGen(args) {
  const pos = [];
  let seed = 42, stationsFile = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--seed") seed = Number(args[++i]);
    else if (args[i] === "--stations") stationsFile = args[++i];
    else if (args[i].startsWith("--")) throw new Error(`gen: unknown option '${args[i]}'`);
    else pos.push(args[i]);
  }
  if (pos.length !== 2) {
    console.error(USAGE);
    process.exitCode = 1;
    return;
  }
  const rows = Number(pos[0]);
  if (!Number.isInteger(rows) || rows <= 0) throw new Error(`gen: '${pos[0]}' is not a positive integer row count`);
  if (!Number.isInteger(seed)) throw new Error("gen: --seed wants an integer");
  const stations = stationsFile === null ? BUILTIN : loadStations(stationsFile);
  if (stations.length === 0) throw new Error(`gen: no stations in ${stationsFile === null ? "the built-in list" : stationsFile}`);
  const { bytes, elapsed } = genFile(rows, pos[1], seed, stations);
  const mbs = elapsed > 0 ? bytes / MB / (elapsed / 1000) : 0;
  console.log(`gen: ${rows} rows, ${bytes} bytes, ${stations.length} stations, seed ${seed}, ` +
    `${elapsed.toFixed(1)} ms, ${mbs.toFixed(1)} MB/s`);
}

function cmdCheck(args) {
  if (args.length !== 1) {
    console.error(USAGE);
    process.exitCode = 1;
    return;
  }
  const t0 = performance.now();
  const { stats, lines, bytes } = aggregateFile(args[0]);
  const elapsed = performance.now() - t0;
  printReport(stats);
  const mbs = elapsed > 0 ? bytes / MB / (elapsed / 1000) : 0;
  console.error(`check: ${lines} lines, ${bytes} bytes, ${stats.size} stations, ${elapsed.toFixed(1)} ms, ${mbs.toFixed(1)} MB/s`);
}

function cmdSelftest() {
  const rand = mulberry32(1);
  let rounding = 0, format = 0;
  for (let i = 0; i < 500; i++) {
    const count = 1 + ((rand() * 2000) | 0);
    const sum = Math.round((rand() * 2 - 1) * 999 * count);
    const got = roundHalfUpTenths(sum, count), want = Math.round(sum / count);
    if (got !== want) throw new Error(`rounding: ${sum}/${count} -> ${got}, want ${want}`);
    rounding++;
  }
  for (const count of [2, 4, 6, 8, 10]) {
    for (let k = -20; k <= 20; k++) {
      const sum = count * k + count / 2; // exact .5 ties, both signs
      const got = roundHalfUpTenths(sum, count), want = Math.round(sum / count);
      if (got !== want) throw new Error(`rounding tie: ${sum}/${count} -> ${got}, want ${want}`);
      rounding++;
    }
  }
  for (let t = -999; t <= 999; t++) {
    const line = Buffer.from(`X;${formatTenths(t)}`, "latin1");
    const one = new Map();
    addSample(one, line, 0, line.length);
    if (one.get("X").min !== t) throw new Error(`format/parse round trip: ${t}`);
    format++;
  }
  // gen -> check round trip on a temp file, against a naive parseFloat
  // aggregation; chunk = 13 forces lines to straddle read-chunk boundaries.
  const tmp = path.join(os.tmpdir(), `onebrc-selftest-${process.pid}.txt`);
  let rows = 0;
  try {
    const g = genFile(3000, tmp, 7, BUILTIN);
    const { stats, lines } = aggregateFile(tmp, 13);
    rows = lines;
    const want = new Map();
    for (const line of fs.readFileSync(tmp, "utf8").split(/\r?\n/)) {
      if (line === "") continue;
      const semi = line.indexOf(";"), name = line.slice(0, semi);
      const v = Math.round(Number(line.slice(semi + 1)) * 10);
      const s = want.get(name);
      if (s === undefined) want.set(name, { min: v, max: v, sum: v, count: 1 });
      else { s.min = Math.min(s.min, v); s.max = Math.max(s.max, v); s.sum += v; s.count++; }
    }
    if (lines !== 3000 || g.bytes !== fs.statSync(tmp).size || want.size !== stats.size)
      throw new Error(`gen/check mismatch: ${lines} lines, ${g.bytes} bytes, ${want.size} vs ${stats.size} stations`);
    for (const [name, w] of want) {
      const s = stats.get(name);
      if (s === undefined || s.min !== w.min || s.max !== w.max || s.sum !== w.sum || s.count !== w.count)
        throw new Error(`gen/check mismatch for '${name}'`);
    }
  } finally {
    fs.unlinkSync(tmp);
  }
  console.log(`selftest: ok (${rounding} rounding cases, ${format} format/parse cases, ` +
    `${rows} generated rows aggregated with a 13-byte read chunk)`);
}

const [, , cmd, ...rest] = process.argv;
try {
  if (cmd === "gen") cmdGen(rest);
  else if (cmd === "check") cmdCheck(rest);
  else if (cmd === "--selftest") cmdSelftest();
  else {
    console.error(USAGE);
    process.exitCode = 1;
  }
} catch (err) {
  console.error(`onebrc: ${err instanceof Error ? err.message : String(err)}`);
  process.exitCode = 1;
}
