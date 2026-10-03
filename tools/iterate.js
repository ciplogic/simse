// tools/iterate.js - the one-command iteration loop.
//
//   bun tools/iterate.js [--full] [--filter <text>] [--quiet]
//
// Fast (the default) is the loop for a change whose effect you want to see:
//
//   1. build      the fast compiler (cl /O1 - a cheap compile that still runs the
//                 self-transpile at speed; --release is the commit loop's)
//   2. stress     the whole corpus, quietly (only failures print in full)
//   3. bootstrap  one transpile: is the published file still what the tree emits? When the
//                 emission moved, the file is refreshed and the line says so
//
// `--full` is the commit loop: the release build, the corpus, the bootstrap refresh and the
// two-way fixed point (including the published file's own compile). Run it before committing;
// iterate with the fast loop.
//
// Every step's output is captured: a passing run prints one line per step and the total, and
// a failing step's whole output. `--quiet` prints only the total. All of this is JS on bun -
// there is no perl or bash in the loop.

import * as path from "node:path";

import { fail as failTool, REPO } from "./msvc.mjs";

const TOOL = "iterate";
const fail = (message) => failTool(TOOL, message);

function usage() {
  console.log(`usage: bun tools/iterate.js [options]

  --full            the commit loop: release build + corpus + bootstrap refresh + fixed point
                    (default: the fast loop - debug build + corpus + bootstrap in-sync check)
  --filter <text>   only stress cases whose path contains <text> (repeatable)
  --quiet           only the final line
  -h, --help        this text`);
}

function parseArgs(argv) {
  const opts = { full: false, filters: [], quiet: false, help: false };
  const value = (i) => {
    if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case "--full": opts.full = true; break;
      case "--filter": opts.filters.push(value(i)); i++; break;
      case "--quiet": opts.quiet = true; break;
      case "-h": case "--help": opts.help = true; break;
      default: fail(`unknown option '${argv[i]}' (try --help)`);
    }
  }
  return opts;
}

// One child step of the loop: bun runs one of the tools, output captured. `process.execPath`
// is the bun running this script, so the loop needs nothing on PATH.
async function runStep(args) {
  const started = performance.now();
  const proc = Bun.spawn([process.execPath, ...args], {
    cwd: REPO,
    stdout: "pipe",
    stderr: "pipe",
  });
  const [stdout, stderr] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
  ]);
  const exit = await proc.exited;
  return { exit, stdout, stderr, seconds: (performance.now() - started) / 1000 };
}

const seconds = (value) => `${value < 10 ? value.toFixed(1) : Math.round(value)}s`;

function lastLine(text) {
  const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0);
  return lines.length > 0 ? lines[lines.length - 1].trim() : "";
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    return 0;
  }

  const filterArgs = opts.filters.flatMap((filter) => ["--filter", filter]);
  const steps = opts.full
      ? [
        ["build", ["build.js", "--release", "--no-lto", "--quiet"]],
        ["stress", ["tools/stress.js", "--quiet", ...filterArgs]],
        ["refresh", ["build.js", "--release", "--no-compile", "--out", "src/simse_bootstrap.cpp", "--quiet"]],
        ["bootstrap", ["tools/bootstrap.js", "--fast"]],
      ]
      : [
        ["build", ["build.js", "--fast", "--quiet"]],
        ["stress", ["tools/stress.js", "--quiet", ...filterArgs]],
        ["bootstrap", ["tools/bootstrap.js", "--quick", "--write"]],
      ];

  const totalStart = performance.now();
  for (const [label, args] of steps) {
    const step = await runStep(args);
    if (step.exit !== 0) {
      console.log(`${TOOL}: ${label} FAILED (${seconds(step.seconds)}, exit ${step.exit})`);
      const output = `${step.stdout}${step.stderr}`.trim();
      if (output.length > 0) console.log(output);
      console.log(`${TOOL}: stopped at '${label}'; nothing after it ran`);
      return 1;
    }
    if (!opts.quiet) {
      console.log(`${TOOL}: ${label} - ${lastLine(step.stdout)}`);
    }
  }
  const total = (performance.now() - totalStart) / 1000;
  console.log(`${TOOL}: ${opts.full ? "full" : "fast"} loop ok (${steps.length} steps, ${seconds(total)})`);
  return 0;
}

// Set the code and let the runtime exit naturally: `process.exit` here raced the pipe's
// pending writes, so the failing step's last lines could land after the shell's next command.
process.exitCode = await main();
