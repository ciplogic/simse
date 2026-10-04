// Scratch checker: verify that the --profile call tree nests correctly.
// For every node: each child's total <= the node's total, and the sum of the
// children's totals <= the node's total. Tolerates a small clock-quantization delta.
import fs from "node:fs";

const [file, tolArg] = process.argv.slice(2);
const tol = Number(tolArg ?? 0);
const text = fs.readFileSync(file, "utf8");
const lines = text.split(/\r?\n/).filter((l) => l.length > 0);
let nodes = 0;
let bad = 0;
let maxDepth = 0;
const stack = [];

function finish(node) {
  if (node.childTotal - node.total > tol) {
    bad++;
    if (bad <= 20) console.error(`sum: ${node.name} children total ${node.childTotal} > own ${node.total}`);
  }
  const parent = stack[stack.length - 1];
  if (parent) {
    parent.childTotal += node.total;
  }
}

for (const line of lines) {
  const plus = line.indexOf("+");
  const depth = plus === -1 ? 0 : plus + 1;
  const m = line.match(/(\S.*?\(\)):(\d+) (us|ns): (\d+) calls$/);
  if (!m) {
    console.error(`unparsed: ${line.slice(0, 80)}`);
    bad++;
    continue;
  }
  const node = { name: m[1], total: Number(m[2]), calls: Number(m[4]), childTotal: 0 };
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
console.log(`${file}: ${nodes} nodes, max depth ${maxDepth}, ${bad} violations (tolerance ${tol})`);
process.exitCode = bad === 0 ? 0 : 1;
