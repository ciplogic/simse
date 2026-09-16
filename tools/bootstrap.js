// bootstrap.js - measure the bootstrap, step by step.
//
//   bun tools/bootstrap.js [--runs N] [--debug] [--simse <exe>]
//
// "Bootstrapping" Simse means: transpile the compiler source tree (`cppsrc`) into
// `cppsrc/simse_bootstrap.cpp` - the amalgamation that is checked in, so that Simse can
// be built from a C++ compiler alone - and compile that file. This tool times each step
// and checks the fixed point:
//
//   1. transpile   the compiler compiling `cppsrc`, by the self-hosted binary and (for
//                  comparison) by the hand-written C++ ring
//   2. compile     `cppsrc/simse_bootstrap.cpp` -> `simse.exe`, with cl.exe only
//                  (no CMake libraries: the file plus `cppsrc/native/Native.cpp` and
//                  `cppsrc/common/common.cpp`, exactly as docs/getting-started.md
//                  documents it)
//   3. fixed point the compiler that came out of step 2, transpiling `cppsrc`, must
//                  reproduce `cppsrc/simse_bootstrap.cpp` byte for byte - which is what
//                  makes the published file a *bootstrap* and not a snapshot
//
// Timings are wall-clock, best and median of `--runs` (default 3, compiles run once
// because they dominate). Everything lands in `cmake-build-<mode>/bootstrap/`.

import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, statSync } from "node:fs";
import * as path from "node:path";

import { cachedArch, developerEnv, fail as failTool, normalizeArch, REPO, whichCl } from "./msvc.mjs";

const TOOL = "bootstrap";
const fail = (message) => failTool(TOOL, message);

function parseArgs(argv) {
  const opts = { runs: 3, debug: false, simse: null };
  const value = (i) => {
    if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case "--runs": opts.runs = Number(value(i)); i++; break;
      case "--debug": opts.debug = true; break;
      case "--simse": opts.simse = value(i); i++; break;
      case "-h": case "--help":
        console.log(readFileSync(import.meta.path, "utf8").split("\nimport ")[0]
                        .replace(/^\/\/ ?/gm, "").trim());
        return null;
      default:
        if (argv[i].startsWith("-")) fail(`unknown option '${argv[i]}' (try --help)`);
    }
  }
  return opts;
}

function timed(label, cmd, args, runs, env) {
  const times = [];
  let last = null;
  for (let i = 0; i < runs; i++) {
    const start = performance.now();
    last = spawnSync(cmd, args, { cwd: REPO, env, encoding: "utf8" });
    times.push(performance.now() - start);
    if (last.status !== 0) {
      console.log(`  ${label}: FAILED (exit ${last.status})`);
      console.log((last.stderr || last.stdout || "").split("\n").slice(0, 6).join("\n"));
      return null;
    }
  }
  times.sort((a, b) => a - b);
  const best = times[0];
  const median = times[times.length >> 1];
  console.log(`  ${label.padEnd(52)} best ${ms(best)}, median ${ms(median)}`);
  return best;
}

const ms = (value) => `${value.toFixed(value < 100 ? 1 : 0)} ms`;
const seconds = (value) => `${(value / 1000).toFixed(2)} s`;

function countLines(file) {
  return readFileSync(file, "utf8").split("\n").length;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts) return 0;

  const buildDir = path.join(REPO, opts.debug ? "cmake-build-debug" : "cmake-build-release");
  const work = path.join(buildDir, "bootstrap");
  mkdirSync(work, { recursive: true });

  const bootstrap = path.join(REPO, "cppsrc", "simse_bootstrap.cpp");
  const generated = path.join(work, "simse_out.cpp");
  const selfHosted = opts.simse ?? path.join(REPO, "simse.exe");
  const cppRing = path.join(buildDir, "simse_transpile.exe");
  const exe = path.join(work, "simse_boot.exe");

  for (const required of [bootstrap, selfHosted, cppRing]) {
    try {
      statSync(required);
    } catch {
      fail(`missing ${path.relative(REPO, required)}` +
           (required === selfHosted ? " (build it with `bun build.js`)" : ""));
    }
  }

  const arch = normalizeArch(cachedArch(buildDir) || "arm64");
  const env = developerEnv(arch, "build");
  const cl = whichCl(env);
  if (!cl) fail(`cl.exe not found on the Visual Studio PATH (arch ${arch})`);

  const sourceLines = spawnSync("bun", ["-e",
    `import {execFileSync} from "node:child_process";`
    + `const out=execFileSync("cmd",["/c","dir /b /s cppsrc\\\\*.kt"],{encoding:"utf8"});`
    + `let n=0;for(const f of out.split(/\\r?\\n/)){if(f.trim())n+=require("fs").readFileSync(f.trim(),"utf8").split("\\n").length;}`
    + `console.log(n);`], { cwd: REPO, encoding: "utf8" });
  const lines = Number((sourceLines.stdout || "0").trim()) || 0;
  const outLines = countLines(bootstrap);

  console.log(`${TOOL}: ${opts.debug ? "debug" : "release"} build, ${opts.runs} run(s) for the transpiles`);
  console.log(`  sources    ${lines} lines of Simse under cppsrc`);
  console.log(`  bootstrap  ${path.relative(REPO, bootstrap)}: ${outLines} lines, ` +
              `${(statSync(bootstrap).size / 1048576).toFixed(2)} MB (checked in, do not edit)`);
  console.log("");
  console.log("1. transpile the compiler's own source tree");
  timed(`self-hosted compiler (${path.relative(REPO, selfHosted)}) --root cppsrc`,
        selfHosted, ["--root", "cppsrc", "-o", generated], opts.runs);
  if (!opts.simse) {
    timed(`hand-written C++ ring (${path.relative(buildDir, cppRing)}) --root cppsrc`,
          cppRing, ["--root", "cppsrc", "-o", generated], opts.runs);
  }
  console.log("");
  console.log("2. compile the published bootstrap (cl.exe only, no CMake libraries)");
  const includeRoot = REPO;
  const defines = [
    `/DSIMSE_DEFAULT_PRELUDE="${path.join(REPO, "cppsrc", "rtl")}"`,
    `/DSIMSE_SOURCE_ROOT="${REPO}"`,
  ];
  const compile = timed("bootstrap + Native.cpp + common.cpp -> simse_boot.exe", cl, [
    "/nologo", "/std:c++20", "/EHsc", "/W3", "/I" + includeRoot, ...defines,
    ...(opts.debug ? ["/MDd", "/Od", "/Zi"] : ["/MD", "/O2", "/Ob3", "/DNDEBUG"]),
    "cppsrc/simse_bootstrap.cpp", "cppsrc/native/Native.cpp", "cppsrc/common/common.cpp",
    "/Fo" + path.join(work, "") + "\\", "/Fe:" + exe,
  ], 1, env);
  console.log("");
  console.log("3. fixed point: the compiled bootstrap transpiles cppsrc again");
  const regen = path.join(work, "simse_boot_out.cpp");
  const regenTime = timed("simse_boot.exe --root cppsrc", exe, ["--root", "cppsrc", "-o", regen], opts.runs);
  const same = spawnSync("cmd", ["/c", "fc", "/b", bootstrap, regen], { cwd: REPO, encoding: "utf8" });
  console.log(`  ${"output == cppsrc/simse_bootstrap.cpp".padEnd(52)} ` +
              (same.status === 0 ? "yes, byte for byte" : "NO - the published file is stale"));
  if (same.status !== 0) return 1;

  const total = (regenTime ?? 0) + (compile ?? 0);
  console.log("");
  console.log(`  from the published file to a working compiler: ${seconds(compile ?? 0)}`);
  console.log(`  and that compiler reproduces itself in:        ${ms(regenTime ?? 0)}`);
  console.log(`  full cycle (compile + self-transpile):         ${seconds(total)}`);
  if (lines > 0 && regenTime) {
    console.log(`  throughput: ${Math.round(lines / (regenTime / 1000))} lines/s of Simse ` +
                `(${Math.round(outLines / (regenTime / 1000))} lines/s of C++ out)`);
  }
  return 0;
}

process.exit(main());
