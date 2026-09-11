// build.js - build the self-hosted Simse compiler with cl.exe.
//
//   bun build.js [options] [<output.exe>]
//
// By default it transpiles the compiler source tree (`cppsrc`) into
// `simse_out.cpp` and compiles that into `simse.exe`, both in the current
// folder. `build.bat` is a thin launcher for this script.
//
// Options:
//   --cpp <file>    compile this C++ file instead of regenerating (implies --no-gen)
//   --exe <file>    executable name/path (default: simse.exe)
//   --out <file>    generated C++ name/path (default: simse_out.cpp)
//   --root <dir>    source root to transpile, relative to the repo (default: cppsrc)
//   --no-gen        skip transpiling; compile the existing/--cpp file
//   --release       link the release CMake build (cmake-build-release, /MD)
//   --debug         link the debug CMake build (default: cmake-build-debug, /MDd)
//   --arch <arch>   vcvarsall target architecture (default: the CMake build's
//                    compiler architecture, else arm64)
//   -h, --help      show this help
//
// Environment:
//   SIMSE_BUILD_DIR  CMake build folder holding simse_native.lib / simse_lib.lib
//
// The generated file includes "cppsrc/rtl/simse.hpp", so the repository root is
// on the include path; the RTL/native static libraries from the CMake build are
// linked. The CRT flag must match the CMake build (/MDd for debug, /MD for
// release).

import { $ } from "bun";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import * as path from "node:path";

const REPO = import.meta.dir;

function fail(message) {
  console.error(`build: ${message}`);
  process.exit(1);
}

function usage() {
  console.log(`usage: bun build.js [options] [<output.exe>]

  --cpp <file>    compile this C++ file instead of regenerating (implies --no-gen)
  --exe <file>    executable name/path (default: simse.exe)
  --out <file>    generated C++ name/path (default: simse_out.cpp)
  --root <dir>    source root to transpile, relative to the repo (default: cppsrc)
  --no-gen        skip transpiling; compile the existing/--cpp file
  --release       link the release CMake build (cmake-build-release, /MD)
  --debug         link the debug CMake build (default: cmake-build-debug, /MDd)
  --arch <arch>   vcvarsall target architecture (default: from CMakeCache.txt,
                  else arm64)
  -h, --help      show this help`);
}

function parseArgs(argv) {
  const opts = {
    root: "cppsrc",
    out: "simse_out.cpp",
    exe: "simse.exe",
    gen: true,
    release: false,
    arch: null,
  };
  const value = (i) => {
    if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    switch (arg) {
      case "--cpp": opts.cpp = value(i); opts.gen = false; i++; break;
      case "--exe": opts.exe = value(i); i++; break;
      case "--out": opts.out = value(i); i++; break;
      case "--root": opts.root = value(i); i++; break;
      case "--no-gen": opts.gen = false; break;
      case "--release": opts.release = true; break;
      case "--debug": opts.release = false; break;
      case "--arch": opts.arch = value(i); i++; break;
      case "-h": case "--help": opts.help = true; break;
      default:
        if (arg.startsWith("-")) fail(`unknown option '${arg}' (try --help)`);
        opts.exe = arg;
    }
  }
  return opts;
}

// The target architecture the CMake build compiles for, read from the compiler
// path in CMakeCache.txt (`.../bin/Host<host>/<target>/cl.exe`).
function cachedArch(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return null;
  const match = readFileSync(cache, "utf8").match(
      /^CMAKE_CXX_COMPILER:[^=\r\n]*=.*[\\/]bin[\\/]Host[^\\/]+[\\/]([^\\/]+)[\\/]cl\.exe\s*$/im);
  if (!match) return null;
  const target = match[1].toLowerCase();
  return target === "amd64" ? "x64" : target;
}

// The vcvarsall.bat of the newest Visual Studio install, or null when none is
// found (vswhere first, then the known VS 18 default path).
function findVcvars() {
  const programFilesX86 = process.env["ProgramFiles(x86)"] || "C:\\Program Files (x86)";
  const vswhere = path.join(programFilesX86, "Microsoft Visual Studio", "Installer", "vswhere.exe");
  if (existsSync(vswhere)) {
    const query = Bun.spawnSync([vswhere, "-latest", "-products", "*", "-property", "installationPath"],
        { stdout: "pipe", stderr: "pipe" });
    const vsPath = query.stdout.toString().trim();
    if (query.exitCode === 0 && vsPath) {
      const candidate = path.join(vsPath, "VC", "Auxiliary", "Build", "vcvarsall.bat");
      if (existsSync(candidate)) return candidate;
    }
  }
  const fallback = "C:\\Program Files\\Microsoft Visual Studio\\18\\Community\\VC\\Auxiliary\\Build\\vcvarsall.bat";
  return existsSync(fallback) ? fallback : null;
}

function normalizeArch(value) {
  const lower = String(value).toLowerCase();
  return lower === "amd64" ? "x64" : lower;
}

// The architecture the compiler targets, from its banner ("... for ARM64").
function compilerTarget(cl, env) {
  const probe = Bun.spawnSync([cl], { env, stdout: "pipe", stderr: "pipe" });
  const banner = probe.stdout.toString() + probe.stderr.toString();
  const match = banner.match(/ for (ARM64EC|ARM64|ARM|x64|x86)\b/i);
  return match ? match[1].toLowerCase() : null;
}

// The Visual Studio developer environment for `arch`. A `cl` already on PATH is
// only trusted when Visual Studio cannot be located: the ambient prompt may
// target another architecture than the CMake libraries (LNK4272/LNK2019).
function developerEnv(arch) {
  const vcvars = findVcvars();
  if (!vcvars) {
    if (Bun.which("cl")) {
      console.warn("build: warning: Visual Studio not found; using the cl.exe already on PATH");
      return process.env;
    }
    fail("cannot locate Visual Studio and no cl.exe on PATH");
  }

  const script = path.join(tmpdir(), `simse-vcenv-${process.pid}.bat`);
  writeFileSync(script, `@echo off\r\ncall "${vcvars}" ${arch} >nul\r\nset\r\n`);
  try {
    const sourced = Bun.spawnSync(["cmd.exe", "/d", "/c", script], { stdout: "pipe", stderr: "pipe" });
    if (sourced.exitCode !== 0) fail(`vcvarsall ${arch} failed:\n${sourced.stderr.toString()}`);
    const env = { ...process.env };
    for (const line of sourced.stdout.toString().split(/\r?\n/)) {
      const eq = line.indexOf("=");
      if (eq > 0) env[line.slice(0, eq)] = line.slice(eq + 1);
    }
    return env;
  } finally {
    rmSync(script, { force: true });
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    return;
  }
  const cwd = process.cwd();

  // --- the CMake build folder that holds the RTL/native libraries ------------
  let release = opts.release;
  let buildDir = process.env.SIMSE_BUILD_DIR
      ? path.resolve(process.env.SIMSE_BUILD_DIR)
      : path.join(REPO, release ? "cmake-build-release" : "cmake-build-debug");
  if (!existsSync(path.join(buildDir, "simse_native.lib"))) {
    const other = path.join(REPO, release ? "cmake-build-debug" : "cmake-build-release");
    if (existsSync(path.join(other, "simse_native.lib"))) {
      buildDir = other;
      release = !release;
    } else {
      fail(`missing ${path.join(buildDir, "simse_native.lib")}; build the project first (` +
           `cd cmake-build-debug && _msvc_build.bat)`);
    }
  }

  // --- step 1: transpile the compiler source tree ----------------------------
  const outCpp = path.resolve(cwd, opts.out);
  if (opts.gen) {
    const transpileExe = path.join(buildDir, "simse_transpile.exe");
    if (!existsSync(transpileExe)) {
      fail(`missing ${transpileExe}; build the project first (cd cmake-build-debug && _msvc_build.bat)`);
    }
    console.log(`build: transpiling ${opts.root} -> ${outCpp}`);
    // Run from the repository root so the source-map comments use the same
    // relative paths as the build's stage-1 output.
    const result = await $`"${transpileExe}" --root ${opts.root} -o ${outCpp}`.cwd(REPO).nothrow();
    if (result.exitCode !== 0) fail(`transpiling failed (exit ${result.exitCode})`);
  }

  const cpp = opts.cpp ? path.resolve(cwd, opts.cpp) : outCpp;
  const exe = path.resolve(cwd, opts.exe);
  if (!existsSync(cpp)) fail(`C++ source not found: ${cpp} (pass --cpp or drop --no-gen)`);

  // --- step 2: load the Visual Studio environment and compile ----------------
  const arch = normalizeArch(opts.arch || cachedArch(buildDir) || "arm64");
  const env = developerEnv(arch);
  const cl = Bun.which("cl", { PATH: env.PATH });
  if (!cl) fail(`cl.exe not found on the Visual Studio PATH (arch ${arch})`);

  // Guard against an ambient developer prompt for another architecture: the
  // CMake libraries cannot link into a different machine type (LNK4272, then a
  // wall of LNK2019 unresolved externals).
  const target = compilerTarget(cl, env);
  if (target && target !== arch) {
    fail(`cl.exe targets ${target} but the CMake build is ${arch}; ` +
         `use a ${arch} Visual Studio environment (vcvarsall ${arch})`);
  }

  const objDir = path.join(buildDir, "manual");
  mkdirSync(objDir, { recursive: true });
  const obj = path.join(objDir, path.basename(cpp, path.extname(cpp)) + ".obj");

  console.log(`build: compiling ${cpp}`);
  console.log(`build:            -> ${exe}`);
  const args = [
    cl, "/nologo", "/std:c++20", "/EHsc", "/W3", release ? "/MD" : "/MDd",
    `/I${REPO}`, `/Fo${obj}`, `/Fe${exe}`,
    cpp,
    path.join(buildDir, "simse_native.lib"),
    path.join(buildDir, "simse_lib.lib"),
  ];
  const compile = Bun.spawnSync(args, { cwd: REPO, env, stdout: "inherit", stderr: "inherit" });
  if (compile.exitCode !== 0) fail("cl.exe failed");
  console.log(`build: wrote ${exe}`);
}

main();
