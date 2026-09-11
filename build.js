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
//   --release       release build: /O2 /DNDEBUG and the release CMake libs
//                    (cmake-build-release, /MD)
//   --debug         debug build (default: cmake-build-debug, /MDd)
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
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
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
  --release       release build: /O2 /DNDEBUG and the release CMake libs
                  (cmake-build-release, /MD)
  --debug         debug build (default: cmake-build-debug, /MDd)
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
    configSet: false,
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
      case "--release": opts.release = true; opts.configSet = true; break;
      case "--debug": opts.release = false; opts.configSet = true; break;
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

// The CMake build type recorded in CMakeCache.txt (Debug/Release/...), or null.
function cachedBuildType(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return null;
  const match = readFileSync(cache, "utf8").match(/^CMAKE_BUILD_TYPE:[^=\r\n]*=(.*)$/m);
  if (!match) return null;
  const value = match[1].trim();
  return value || null;
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

// The newest mtime among the C++ sources the hand-written transpiler is built
// from (the `.simse` sources are runtime inputs, not build inputs).
function newestCompiledSource(dir) {
  let newest = 0;
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (/\.(cpp|h|hpp)$/.test(entry.name)) newest = Math.max(newest, statSync(full).mtimeMs);
    }
  }
  return newest;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    usage();
    return;
  }
  const cwd = process.cwd();

  // --- the CMake build folder that holds the RTL/native libraries ------------
  let buildDir = process.env.SIMSE_BUILD_DIR
      ? path.resolve(process.env.SIMSE_BUILD_DIR)
      : path.join(REPO, opts.release ? "cmake-build-release" : "cmake-build-debug");
  if (!existsSync(path.join(buildDir, "simse_native.lib"))) {
    const other = process.env.SIMSE_BUILD_DIR
        ? null
        : path.join(REPO, opts.release ? "cmake-build-debug" : "cmake-build-release");
    if (!opts.configSet && other && existsSync(path.join(other, "simse_native.lib"))) {
      buildDir = other;
    } else {
      fail(`missing ${path.join(buildDir, "simse_native.lib")}; build that CMake folder first ` +
           `(from it: vcvarsall arm64 && cmake --build . --target simse_transpile simse_native)`);
    }
  }
  // The build type comes from the CMake cache; --release/--debug only choose the
  // folder and are the fallback when the cache does not say.
  const buildType = cachedBuildType(buildDir);
  const isRelease = buildType ? /^(Rel|MinSizeRel)/i.test(buildType) : opts.release;
  console.log(`build: using ${buildDir} (${buildType || (isRelease ? "Release" : "Debug")})`);

  // --- step 1: transpile the compiler source tree ----------------------------
  const outCpp = path.resolve(cwd, opts.out);
  if (opts.gen) {
    const transpileExe = path.join(buildDir, "simse_transpile.exe");
    if (!existsSync(transpileExe)) {
      fail(`missing ${transpileExe}; build the project first (cd cmake-build-debug && _msvc_build.bat)`);
    }
    if (statSync(transpileExe).mtimeMs < newestCompiledSource(path.join(REPO, "cppsrc"))) {
      console.warn(`build: warning: the CMake build in ${path.basename(buildDir)} is older than the ` +
          `compiler sources, so ${path.basename(transpileExe)} may be stale; rebuild it first ` +
          `(from that folder: vcvarsall arm64 && cmake --build . --target simse_transpile)`);
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
  const libArch = cachedArch(buildDir);
  const arch = normalizeArch(opts.arch || libArch || "arm64");
  const env = developerEnv(arch);
  const cl = Bun.which("cl", { PATH: env.PATH });
  if (!cl) fail(`cl.exe not found on the Visual Studio PATH (arch ${arch})`);

  // Guard against a different machine type than the CMake libraries: linking an
  // x86/x64 object against them fails with LNK4272 plus a wall of LNK2019
  // unresolved externals that hides the real cause.
  const target = compilerTarget(cl, env);
  if (target && libArch && target !== normalizeArch(libArch)) {
    fail(`cl.exe targets ${target} but the CMake libraries in ${path.basename(buildDir)} are ${libArch}; ` +
         `use an ${libArch} Visual Studio environment (vcvarsall ${libArch})`);
  }

  const objDir = path.join(buildDir, "manual");
  mkdirSync(objDir, { recursive: true });
  const obj = path.join(objDir, path.basename(cpp, path.extname(cpp)) + ".obj");

  console.log(`build: compiling ${cpp}`);
  console.log(`build:            -> ${exe}`);
  // Match the CMake build's runtime and optimization (Release uses /MD + /O2 +
  // /DNDEBUG; Debug uses /MDd).
  const flags = isRelease ? ["/MD", "/O2", "/DNDEBUG"] : ["/MDd"];
  const args = [
    cl, "/nologo", "/std:c++20", "/EHsc", "/W3", ...flags,
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
