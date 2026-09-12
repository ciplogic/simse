// stress.js - the end-to-end stress harness.
//
//   bun tools/stress.js [options]
//
// Every folder under `stress/` is one small Simse project: `src/` holds the
// sources (the transpiler's `--root`), and the folder's `expected.*` files say
// what the project must do. The harness transpiles the project with the Simse
// compiler, compiles the generated C++ with cl.exe, runs the program, and
// compares its output. One folder, one project, one expectation - so a failing
// case can be copied out and debugged on its own, and adding a case is adding a
// folder.
//
// What a case can declare (all but `expected.stdout` are optional):
//   src/                      the project sources (required)
//   expected.stdout           the program's stdout (required; else see below)
//   expected.stderr           the program's stderr
//   expected.exit             the program's exit code (default 0)
//   expected.cpp              the transpiled amalgamation, byte for byte
//   expected.transpile-error  substring of the transpiler's stderr; the case
//                             passes when the transpile fails and prints it
//                             (no compile or run happens)
//   args                      one line of arguments for the program
//   stdin                     fed to the program on stdin
//
// Options:
//   --filter <text>   only cases whose path contains <text> (repeatable)
//   --simse <exe>     compiler to test (default: ./simse.exe, else the CMake
//                     build's simse_transpile.exe)
//   --release         compile the generated programs with /O2 /Ob3 (default: /MDd)
//   --define <d[=v]>  extra preprocessor define for the compile (repeatable)
//   --jobs <n>        cases to run at once (default: 1; 0 = one per CPU)
//   --update          rewrite the `expected.*` files from this run
//   --list            list the cases and their expectations, then exit
//   --keep-going      report every failure instead of stopping at the first
//   --verbose         print the commands, and each program's output
//   -h, --help        this text
//
// The compiler under test is the point: by default the harness runs the
// self-hosted `simse.exe` (build it with `bun build.js --release`), and only
// falls back to the hand-written ring when there is no such binary.

import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import * as path from "node:path";

import { cachedArch, developerEnv, fail as failTool, normalizeArch, REPO, runCl, whichCl } from "./msvc.mjs";

const TOOL = "stress";
const fail = (message) => failTool(TOOL, message);
const STRESS = path.join(REPO, "stress");
const WORK = path.join(STRESS, ".work");

function usage() {
  const header = readFileSync(import.meta.path, "utf8");
  const start = header.indexOf("// stress.js");
  const end = header.indexOf("\nimport ");
  console.log(header.slice(start, end).replace(/^\/\/ ?/gm, "").trim());
}

function parseArgs(argv) {
  const opts = {
    filters: [],
    simse: null,
    release: false,
    defines: [],
    jobs: 1,
    update: false,
    list: false,
    keepGoing: false,
    verbose: false,
    help: false,
  };
  const value = (i) => {
    if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case "--filter": opts.filters.push(value(i)); i++; break;
      case "--simse": opts.simse = value(i); i++; break;
      case "--release": opts.release = true; break;
      case "--define": opts.defines.push(value(i)); i++; break;
      case "--jobs": opts.jobs = Number(value(i)); i++; break;
      case "--update": opts.update = true; break;
      case "--list": opts.list = true; break;
      case "--keep-going": opts.keepGoing = true; break;
      case "--verbose": opts.verbose = true; break;
      case "-h": case "--help": opts.help = true; break;
      default: fail(`unknown option '${argv[i]}' (try --help)`);
    }
  }
  if (opts.jobs === 0) opts.jobs = Math.max(1, (Bun.env.NUMBER_OF_PROCESSORS || 4) - 1);
  if (opts.jobs < 0 || !Number.isFinite(opts.jobs)) fail("--jobs wants a count");
  return opts;
}

// Every directory under stress/ that has a src/ is a case. `.work` holds the
// harness's own outputs and is never a case.
function findCases(filters) {
  if (!existsSync(STRESS)) fail(`no ${path.relative(REPO, STRESS)} directory`);
  const cases = [];
  for (const entry of readdirSync(STRESS, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
    const dir = path.join(STRESS, entry.name);
    if (!existsSync(path.join(dir, "src"))) continue;
    if (filters.length > 0 && !filters.some((filter) => entry.name.includes(filter))) continue;
    cases.push({ name: entry.name, dir });
  }
  cases.sort((a, b) => a.name.localeCompare(b.name));
  return cases;
}

function readIfPresent(file) {
  return existsSync(file) ? readFileSync(file, "utf8") : null;
}

// Text comparison ignores the platform's line endings: the same program prints
// whatever its own newline convention is, and a golden must not care.
function normalize(text) {
  return text.replace(/\r\n/g, "\n");
}

function firstDifference(actual, expected) {
  const mine = normalize(actual).split("\n");
  const theirs = normalize(expected).split("\n");
  const count = Math.max(mine.length, theirs.length);
  for (let i = 0; i < count; i++) {
    if (mine[i] !== theirs[i]) {
      return `line ${i + 1}\n  expected: ${JSON.stringify(theirs[i] ?? "<no line>")}\n` +
          `  actual:   ${JSON.stringify(mine[i] ?? "<no line>")}`;
    }
  }
  return "identical";
}

function pickCompiler(explicit) {
  if (explicit) {
    const resolved = path.resolve(REPO, explicit);
    if (!existsSync(resolved)) fail(`compiler not found: ${explicit}`);
    return resolved;
  }
  const selfHosted = path.join(REPO, "simse.exe");
  if (existsSync(selfHosted)) return selfHosted;
  for (const dir of ["cmake-build-release", "cmake-build-debug"]) {
    const candidate = path.join(REPO, dir, "simse_transpile.exe");
    if (existsSync(candidate)) return candidate;
  }
  fail("no compiler to test: build one with `bun build.js` or `cmake --build cmake-build-debug`");
}

// The two C++ translation units every generated program may need: the `native`
// boundary (file I/O, diagnostics) and the few `common` helpers it calls. They
// are the part of the RTL that is not header-only, and compiling them once per
// flag set keeps a full stress run to one compile per case.
function sharedObjects(env, flags) {
  const key = Bun.hash(`${flags.join(" ")} ${process.platform} ${process.arch}`).toString(36);
  const dir = path.join(WORK, `native-${key}`);
  const objects = [
    { source: path.join(REPO, "cppsrc", "native", "Native.cpp"), object: path.join(dir, "Native.obj") },
    { source: path.join(REPO, "cppsrc", "common", "common.cpp"), object: path.join(dir, "common.obj") },
  ];
  if (objects.every((entry) => existsSync(entry.object) && statSync(entry.object).mtimeMs > statSync(entry.source).mtimeMs)) {
    return objects.map((entry) => entry.object);
  }
  mkdirSync(dir, { recursive: true });
  for (const entry of objects) {
    const args = ["cl", "/nologo", "/c", "/std:c++20", "/EHsc", "/W3", ...flags,
      `/I${REPO}`, `/Fo${entry.object}`, entry.source];
    if (runCl(env, args) !== 0) fail(`compiling ${path.relative(REPO, entry.source)} failed`);
  }
  return objects.map((entry) => entry.object);
}

function runProcess(command, options) {
  const started = performance.now();
  const result = Bun.spawnSync(command, {
    cwd: options.cwd ?? REPO,
    env: options.env,
    stdin: options.stdin ? new TextEncoder().encode(options.stdin) : undefined,
    stdout: "pipe",
    stderr: "pipe",
  });
  return {
    exit: result.exitCode ?? 1,
    stdout: result.stdout.toString(),
    stderr: result.stderr.toString(),
    ms: performance.now() - started,
  };
}

// One case: transpile, compile, run, compare. Returns { name, ok, detail, ms }.
function runCase(build, study) {
  const { name, dir } = study;
  const work = path.join(WORK, name);
  rmSync(work, { recursive: true, force: true });
  mkdirSync(work, { recursive: true });

  const expectedCpp = readIfPresent(path.join(dir, "expected.cpp"));
  const expectedTranspileError = readIfPresent(path.join(dir, "expected.transpile-error"));
  const expectedOut = readIfPresent(path.join(dir, "expected.stdout"));
  const expectedErr = readIfPresent(path.join(dir, "expected.stderr"));
  const expectedExit = readIfPresent(path.join(dir, "expected.exit"));
  const args = (readIfPresent(path.join(dir, "args")) || "").trim().split(/\s+/).filter((part) => part.length > 0);
  const stdin = readIfPresent(path.join(dir, "stdin"));

  const outCpp = path.join(work, "out.cpp");
  const transpile = runProcess([build.simse, "--root", path.join("stress", name, "src"), "-o", outCpp], {
    env: build.env,
  });
  const verbose = build.opts.verbose ? `\n${transpile.stdout}${transpile.stderr}` : "";

  if (expectedTranspileError !== null) {
    const wanted = normalize(expectedTranspileError).trim();
    if (transpile.exit === 0) {
      return { name, ok: false, detail: `expected the transpile to fail with '${wanted}', but it succeeded${verbose}` };
    }
    if (!normalize(transpile.stderr).includes(wanted)) {
      return { name, ok: false, detail: `transpile diagnostic mismatch\n  expected: ${JSON.stringify(wanted)}\n` +
          `  actual:   ${JSON.stringify(normalize(transpile.stderr).trim())}` };
    }
    if (build.opts.update) rmSync(path.join(dir, "expected.stdout"), { force: true });
    return { name, ok: true, detail: `transpile rejected as expected (${transpile.ms.toFixed(0)} ms)` };
  }

  if (transpile.exit !== 0) {
    return { name, ok: false, detail: `transpile failed (exit ${transpile.exit})\n${transpile.stderr}${verbose}` };
  }
  if (expectedCpp !== null && normalize(readIfPresent(outCpp)) !== normalize(expectedCpp)) {
    return {
      name, ok: false,
      detail: `the emitted C++ differs from expected.cpp\n${firstDifference(readIfPresent(outCpp), expectedCpp)}`,
    };
  }

  const exe = path.join(work, "prog.exe");
  const compileArgs = ["cl", "/nologo", "/std:c++20", "/EHsc", "/W3", ...build.flags,
    `/I${REPO}`, `/Fo${work}${path.sep}`, `/Fe${exe}`, outCpp, ...build.nativeObjects];
  if (build.opts.verbose) console.log(`  ${compileArgs.join(" ")}`);
  const compiled = runProcess(compileArgs, { env: build.env });
  if (compiled.exit !== 0) {
    const log = `${compiled.stdout}${compiled.stderr}`.split("\n").slice(0, 40).join("\n");
    return { name, ok: false, detail: `cl.exe failed (the generated C++ did not compile)\n${log}` };
  }

  const program = runProcess([exe, ...args], { env: build.env, stdin });
  const actualOut = normalize(program.stdout);
  const actualErr = normalize(program.stderr);

  // A case without an expectation is not a passing case: it is an unfinished
  // one. `--update` is how a new case gets its expectations, from a run whose
  // output has been read.
  if (expectedOut === null) {
    if (build.opts.update) {
      writeFileSync(path.join(dir, "expected.stdout"), actualOut);
      if (actualErr.length > 0) writeFileSync(path.join(dir, "expected.stderr"), actualErr);
      if (program.exit !== 0) writeFileSync(path.join(dir, "expected.exit"), `${program.exit}\n`);
      return { name, ok: true, detail: `expected.stdout written from this run (${actualOut.length} bytes)` };
    }
    return { name, ok: false, detail: "no expected.stdout; read this program's output, then capture it with --update" };
  }

  const expectedCode = expectedExit === null ? 0 : Number(normalize(expectedExit).trim());
  const ok = actualOut === normalize(expectedOut) &&
      (expectedErr === null || actualErr === normalize(expectedErr)) &&
      program.exit === expectedCode;
  if (ok) {
    return { name, ok: true, detail: `${program.ms.toFixed(0)} ms, ${actualOut.length} bytes of stdout` };
  }

  if (build.opts.update) {
    writeFileSync(path.join(dir, "expected.stdout"), actualOut);
    if (actualErr.length > 0) writeFileSync(path.join(dir, "expected.stderr"), actualErr);
    if (program.exit !== 0) writeFileSync(path.join(dir, "expected.exit"), `${program.exit}\n`);
    return { name, ok: true, detail: "expected.* rewritten from this run" };
  }

  const problems = [];
  if (actualOut !== normalize(expectedOut)) {
    problems.push(`stdout differs\n${firstDifference(actualOut, expectedOut)}`);
  }
  if (expectedErr !== null && actualErr !== normalize(expectedErr)) {
    problems.push(`stderr differs\n${firstDifference(actualErr, expectedErr)}`);
  }
  if (program.exit !== expectedCode) {
    problems.push(`exit code ${program.exit}, expected ${expectedCode}`);
  }
  return { name, ok: false, detail: problems.join("\n") };
}

function describe(caseDir) {
  const marks = [];
  if (existsSync(path.join(caseDir, "expected.transpile-error"))) marks.push("transpile-error");
  if (existsSync(path.join(caseDir, "expected.cpp"))) marks.push("cpp-golden");
  if (existsSync(path.join(caseDir, "args"))) marks.push("args");
  if (existsSync(path.join(caseDir, "stdin"))) marks.push("stdin");
  if (existsSync(path.join(caseDir, "expected.exit"))) marks.push("exit-code");
  if (existsSync(path.join(caseDir, "expected.stderr"))) marks.push("stderr");
  return marks.length > 0 ? ` [${marks.join(", ")}]` : "";
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    return;
  }
  const cases = findCases(opts.filters);
  if (cases.length === 0) fail("no cases matched" + (opts.filters.length > 0 ? ` (filters: ${opts.filters.join(", ")})` : ""));
  if (opts.list) {
    for (const study of cases) console.log(`${study.name}${describe(study.dir)}`);
    console.log(`${cases.length} cases`);
    return;
  }

  const simse = pickCompiler(opts.simse);
  const arch = normalizeArch(cachedArch(path.join(REPO, "cmake-build-debug")) || "arm64");
  const env = developerEnv(arch, TOOL);
  const flags = opts.release ? ["/MD", "/O2", "/Ob3", "/DNDEBUG"] : ["/MDd"];
  for (const define of opts.defines) flags.push(`/D${define}`);

  console.log(`${TOOL}: ${path.relative(REPO, simse)} (${opts.release ? "release" : "debug"} programs, ` +
      `${cases.length} cases${opts.jobs > 1 ? `, ${opts.jobs} at a time` : ""})`);
  const nativeObjects = sharedObjects(env, flags);
  const build = { simse, env, flags, nativeObjects, opts };

  const results = [];
  const pending = [...cases];
  let stopped = false;
  const workers = Array.from({ length: Math.max(1, Math.min(opts.jobs, cases.length)) }, async () => {
    while (!stopped) {
      const study = pending.shift();
      if (study === undefined) return;
      let result;
      try {
        result = runCase(build, study);
      } catch (error) {
        result = { name: study.name, ok: false, detail: `harness error: ${error.message}` };
      }
      results.push(result);
      const line = result.ok ? `PASS ${result.name}` : `FAIL ${result.name}`;
      console.log(`${line}${result.detail ? `  (${result.detail})` : ""}`);
      // Stop the remaining work at the first failure unless asked to continue.
      if (!result.ok && !opts.keepGoing) stopped = true;
    }
  });
  await Promise.all(workers);

  const passed = results.filter((result) => result.ok).length;
  const failed = results.filter((result) => !result.ok).length;
  console.log(`${TOOL}: ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exitCode = 1;
}

await main();
