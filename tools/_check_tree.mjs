// Checker: verify a --profile report (`impl_specs/profiling.md`).
//
// The report is two top-25 summaries, a `tree:` marker, then the call tree. This checks:
//   - each summary descends;
//   - every summary row's value is what the tree below implies for that body (summed over
//     all its nodes: total; total minus direct children: self);
//   - no body with a bigger implied value was left out of a summary;
//   - the tree nests: no child exceeds its parent, and the sum of a node's children's
//     totals is at most the node's (tolerance for clock quantization).
//
// usage: bun tools/_check_tree.mjs <report file> [tolerance]
import fs from "node:fs";

const [file, tolArg] = process.argv.slice(2);
const tol = Number(tolArg ?? 0);
const text = fs.readFileSync(file, "utf8");
const all = text.split(/\r?\n/);
const treeAt = all.findIndex((l) => l.trim() === "tree:");
if (treeAt < 0) {
  console.error("no 'tree:' marker");
  process.exit(1);
}
const lines = all.slice(treeAt + 1).filter((l) => l.length > 0);

let bad = 0;

// The summaries, and what they claim.
const printed = { total: [], self: [] };
let kind = null;
for (const line of all.slice(0, treeAt)) {
  if (line.startsWith("top ") && line.endsWith(":")) {
    kind = line.includes(" by total:") ? "total" : line.includes(" by self:") ? "self" : null;
    if (kind === null) {
      bad++;
      console.error(`unknown summary header: ${line}`);
    }
    continue;
  }
  const m = line.match(/^\s*(\d+)\.\s+(\S+)\s+(\d+) (us|ns):\s+(\d+) calls$/);
  if (!m) continue;
  if (kind === null) {
    bad++;
    console.error(`summary row before its header: ${line.trim()}`);
    continue;
  }
  const value = Number(m[3]);
  const list = printed[kind];
  if (list.length > 0 && value > list[list.length - 1].value) {
    bad++;
    console.error(`${kind} summary not sorted: ${line.trim()}`);
  }
  list.push({ name: m[2], value });
}
if (printed.total.length === 0 || printed.self.length === 0) {
  bad++;
  console.error("missing a summary");
}

// The tree, and the aggregates it implies.
const totalByName = new Map();
const selfByName = new Map();
let nodes = 0;
let maxDepth = 0;
const stack = [];

function finish(node) {
  if (node.childTotal - node.total > tol) {
    bad++;
    if (bad <= 20) console.error(`sum: ${node.name} children total ${node.childTotal} > own ${node.total}`);
  }
  totalByName.set(node.name, (totalByName.get(node.name) ?? 0) + node.total);
  selfByName.set(node.name, (selfByName.get(node.name) ?? 0) + node.total - node.childTotal);
  const parent = stack[stack.length - 1];
  if (parent) {
    parent.childTotal += node.total;
  }
}

for (const line of lines) {
  const plus = line.indexOf("+");
  const depth = plus === -1 ? 0 : plus + 1;
  const body = plus === -1 ? line : line.slice(plus + 1);
  const m = body.match(/^(\S.*?\(\)):(\d+) (us|ns): (\d+) calls$/);
  if (!m) {
    console.error(`unparsed: ${line.slice(0, 80)}`);
    bad++;
    continue;
  }
  const node = { name: m[1].replace(/\(\)$/, ""), total: Number(m[2]), calls: Number(m[4]), childTotal: 0 };
  nodes++;
  if (depth > maxDepth) maxDepth = depth;
  while (stack.length > depth) finish(stack.pop());
  const parent = stack[stack.length - 1];
  if (parent && node.total - parent.total > tol) {
    bad++;
    if (bad <= 20) console.error(`nest: ${node.name} ${node.total} > parent ${parent.name} ${parent.total}`);
  }
  stack.push(node);
}
while (stack.length > 0) finish(stack.pop());

// Each printed row must match the tree, and nothing bigger may be missing.
function checkList(kind, computed) {
  const rows = printed[kind];
  if (rows.length === 0) return;
  const minPrinted = Math.min(...rows.map((r) => r.value));
  const listed = new Set(rows.map((r) => r.name));
  for (const row of rows) {
    const value = computed.get(row.name);
    if (value !== row.value) {
      bad++;
      console.error(`${kind}: ${row.name} printed ${row.value}, tree says ${value}`);
    }
  }
  for (const [name, value] of computed) {
    if (!listed.has(name) && value > minPrinted) {
      bad++;
      if (bad <= 20) console.error(`${kind}: ${name} (${value}) is missing from the top list`);
    }
  }
}
checkList("total", totalByName);
checkList("self", selfByName);

console.log(`${file}: ${nodes} nodes, max depth ${maxDepth}, ${bad} violations (tolerance ${tol})`);
process.exitCode = bad === 0 ? 0 : 1;
