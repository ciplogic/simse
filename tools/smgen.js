// smgen.js - the two spellings of a generated declaration must emit the same C++.
//
//   bun tools/smgen.js [--simse <exe>]
//
// `impl_specs/generators.md`: `native("sym")` is sugar for
// `@SmGen("cpp", "sym")`, and the two forms must mean one
// declaration - the emitter writes the same prototype and the same call site for
// either. That is a byte-equality between two whole amalgamations, which no `stress/`
// golden can state on its own: the emitted source comments name the fixture's own
// path, so the two files are compared after replacing that path with `CASE`.
//
// The pairs are stress cases (`src/main.kt`, line for line identical except the
// declaration), so the harness runs them too and their `expected.cpp` goldens pin the
// emitted text: a change to one spelling alone fails here *and* changes a golden.
//
// Exit code 0 when every pair agrees, 1 on the first difference.

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import * as path from "node:path";

import { fail as failTool, REPO } from "./msvc.mjs";

const TOOL = "smgen";
const fail = (message) => failTool(TOOL, message);

// `native` is the case written with `native("sym")`, `smgen` the same program written
// with `@SmGen("cpp", "sym")`.
const PAIRS = [
  { native: "stress/smgen-native", smgen: "stress/smgen-cpp" },
];

function parseArgs(argv) {
  const opts = { simse: null };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case "--simse":
        if (i + 1 >= argv.length) fail("missing value for --simse");
        opts.simse = argv[i + 1];
        i++;
        break;
      case "-h":
      case "--help":
        console.log(readFileSync(import.meta.path, "utf8").split("\nimport ")[0]
            .replace(/^\/\/ ?/gm, "").trim());
        return null;
      default:
        fail(`unknown option '${argv[i]}' (try --help)`);
    }
  }
  return opts;
}

// The amalgamation a case produces, with the case's own directory replaced by `CASE`:
// what is left of a source comment is its line number, which the pair shares.
function emit(compiler, dir, work, name) {
  const out = path.join(work, `${name}.cpp`);
  const result = spawnSync(compiler, ["--root", `${dir}/src`, "-o", out], { cwd: REPO, encoding: "utf8" });
  if (result.status !== 0) {
    fail(`${dir} did not transpile (exit ${result.status})\n${result.stderr || result.stdout}`);
  }
  return readFileSync(out, "utf8").replaceAll(`${dir}/src`, "CASE");
}

function firstDifference(left, right) {
  const mine = left.split("\n");
  const theirs = right.split("\n");
  const count = Math.max(mine.length, theirs.length);
  for (let i = 0; i < count; i++) {
    if (mine[i] !== theirs[i]) {
      return `line ${i + 1}\n  native: ${JSON.stringify(theirs[i] ?? "<no line>")}\n` +
          `  @SmGen: ${JSON.stringify(mine[i] ?? "<no line>")}`;
    }
  }
  return "identical";
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts) return 0;

  const compiler = path.resolve(REPO, opts.simse || "simse.exe");
  if (!existsSync(compiler)) fail(`no compiler at ${path.relative(REPO, compiler)} (bun build.js)`);

  const work = path.join(REPO, "build", "smgen");
  mkdirSync(work, { recursive: true });

  let failed = 0;
  for (const pair of PAIRS) {
    const fromSmGen = emit(compiler, pair.smgen, work, "smgen");
    const fromNative = emit(compiler, pair.native, work, "native");
    if (fromSmGen === fromNative) {
      console.log(`  ${pair.smgen} == ${pair.native}   yes, byte for byte ` +
          `(${fromSmGen.split("\n").length} lines)`);
      continue;
    }
    failed++;
    console.log(`  ${pair.smgen} != ${pair.native}\n${firstDifference(fromSmGen, fromNative)}`);
  }
  console.log(`smgen: ${PAIRS.length - failed} of ${PAIRS.length} pair(s) agree`);
  return failed === 0 ? 0 : 1;
}

process.exit(main());
