// bootstrap.js - measure the bootstrap, step by step.
//
//   bun tools/bootstrap.js [--runs N] [--debug] [--simse <exe>]
//
// "Bootstrapping" Simse means: `cppsrc/simse_bootstrap.cpp` - the published amalgamation of
// the compiler source tree - is checked in, so Simse can be built with a C++ compiler alone;
// and the compiler that comes out of it must transpile the same sources back into the same
// bytes. This tool times each step and checks that fixed point:
//
//   1. compile     the published `cppsrc/simse_bootstrap.cpp` into `simse_boot.exe`, with
//                  cl.exe only - no build system, nothing generated, and no second file to
//                  link (docs/getting-started.md, "Building the compiler without a
//                  compiler")
//   2. transpile   `simse_boot.exe` compiling `cppsrc`, and - when a working compiler
//                  exists - that compiler doing the same, so the two can be compared
//   3. fixed point the regenerated file must equal `cppsrc/simse_bootstrap.cpp` byte for
//                  byte, whichever compiler produced it. That is what makes the published
//                  file a *bootstrap* and not a snapshot
//
// Timings are wall-clock, best and median of `--runs` (default 3, compiles run once
// because they dominate). Everything lands in `build/<mode>/bootstrap/`.

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, statSync } from "node:fs";
import * as path from "node:path";

import { developerEnv, fail as failTool, hostArch, normalizeArch, REPO, whichCl } from "./msvc.mjs";

const TOOL = "bootstrap";
const fail = (message) => failTool(TOOL, message);
const BOOTSTRAP = path.join("cppsrc", "simse_bootstrap.cpp");

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

// The same bytes on both sides of a `fc /b`.
function sameBytes(left, right) {
  return spawnSync("cmd", ["/c", "fc", "/b", left, right], { cwd: REPO, encoding: "utf8" }).status === 0;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts) return 0;

  const mode = opts.debug ? "debug" : "release";
  const work = path.join(REPO, "build", mode, "bootstrap");
  mkdirSync(work, { recursive: true });

  const bootstrap = path.join(REPO, BOOTSTRAP);
  const bootExe = path.join(work, "simse_boot.exe");
  const bootOut = path.join(work, "simse_boot_out.cpp");
  const workingOut = path.join(work, "simse_working_out.cpp");
  const working = opts.simse ? path.resolve(REPO, opts.simse) : path.join(REPO, "simse.exe");
  const hasWorking = existsSync(working);

  for (const required of [bootstrap]) {
    if (!existsSync(required)) fail(`missing ${path.relative(REPO, required)}`);
  }

  const arch = normalizeArch(hostArch());
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

  console.log(`${TOOL}: ${mode} build, ${opts.runs} run(s) for the transpiles`);
  console.log(`  sources    ${lines} lines of Simse under cppsrc`);
  console.log(`  bootstrap  ${BOOTSTRAP}: ${outLines} lines, ` +
              `${(statSync(bootstrap).size / 1048576).toFixed(2)} MB (checked in, do not edit)`);
  console.log("");
  console.log("1. compile the published bootstrap (cl.exe only, no build system)");
  const compile = timed(`${BOOTSTRAP} -> simse_boot.exe`, cl, [
    "/nologo", "/std:c++20", "/EHsc", "/W3", "/I" + REPO,
    ...(opts.debug ? ["/MDd", "/Od", "/Zi"] : ["/MD", "/O2", "/Ob3", "/DNDEBUG"]),
    ...(opts.debug ? [`/Fd${bootExe}.pdb`] : []),
    BOOTSTRAP,
    "/Fo" + path.join(work, "") + "\\", "/Fe:" + bootExe,
  ], 1, env);
  console.log("");
  console.log("2. transpile the compiler's own source tree");
  const bootTime = timed(`simse_boot.exe (just built) --root cppsrc`,
        bootExe, ["--root", "cppsrc", "-o", bootOut], opts.runs);
  let workTime = null;
  if (hasWorking) {
    workTime = timed(`working compiler (${path.relative(REPO, working)}) --root cppsrc`,
          working, ["--root", "cppsrc", "-o", workingOut], opts.runs);
  } else {
    console.log(`  ${"(no ./simse.exe to compare with)".padEnd(52)} build one with \`bun build.js\``);
  }
  console.log("");
  console.log("3. fixed point: both outputs must equal the published bootstrap");
  const bootSame = spawnSync("cmd", ["/c", "fc", "/b", bootstrap, bootOut], { cwd: REPO, encoding: "utf8" });
  console.log(`  ${"simse_boot.exe's output == the bootstrap".padEnd(52)} ` +
              (bootSame.status === 0 ? "yes, byte for byte" : "NO - the published file is stale"));
  let workSame = true;
  if (hasWorking) {
    workSame = sameBytes(bootstrap, workingOut);
    console.log(`  ${"the working compiler's output == the bootstrap".padEnd(52)} ` +
                (workSame ? "yes, byte for byte" : "NO - rebuild it with `bun build.js`"));
  }
  if (bootSame.status !== 0 || !workSame) return 1;

  const total = (bootTime ?? 0) + (compile ?? 0);
  console.log("");
  console.log(`  from the published file to a working compiler: ${seconds(compile ?? 0)}`);
  console.log(`  and that compiler reproduces itself in:        ${ms(bootTime ?? 0)}`);
  console.log(`  full cycle (compile + self-transpile):         ${seconds(total)}`);
  if (lines > 0 && bootTime) {
    console.log(`  throughput: ${Math.round(lines / (bootTime / 1000))} lines/s of Simse ` +
                `(${Math.round(outLines / (bootTime / 1000))} lines/s of C++ out)`);
  }
  if (workTime) console.log(`  the working compiler transpiles the same tree in: ${ms(workTime)}`);
  return 0;
}

process.exit(main());
