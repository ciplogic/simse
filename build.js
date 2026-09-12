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
//   --release       release build: /O2 /Ob3 /DNDEBUG and the release CMake libs
//                    (cmake-build-release, /MD)
//   --lto           whole-program optimization: /GL + /LTCG (with --release)
//   --debug         debug build (default: cmake-build-debug, /MDd)
//   --arch <arch>   vcvarsall target architecture (default: the CMake build's
//                    compiler architecture, else arm64)
//   --define <m[=v]> add a preprocessor define to the compile (repeatable),
//                    e.g. --define SIMSE_LIST_STD_VECTOR. The RTL's List/Str
//                    backing and Str's inline capacity are mirrored from the
//                    CMake build's cache, because the libraries it links bake
//                    those choices in.
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
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
import * as path from "node:path";

import {
  cachedArch,
  cachedBuildType,
  compilerTarget,
  developerEnv,
  fail as failTool,
  normalizeArch,
  REPO,
  runCl,
  whichCl,
} from "./tools/msvc.mjs";

const fail = (message) => failTool("build", message);

function usage() {
  console.log(`usage: bun build.js [options] [<output.exe>]

  --cpp <file>    compile this C++ file instead of regenerating (implies --no-gen)
  --exe <file>    executable name/path (default: simse.exe)
  --out <file>    generated C++ name/path (default: simse_out.cpp)
  --root <dir>    source root to transpile, relative to the repo (default: cppsrc)
  --no-gen        skip transpiling; compile the existing/--cpp file
  --release       release build: /O2 /Ob3 /DNDEBUG and the release CMake libs
                  (cmake-build-release, /MD)
  --lto           whole-program optimization: /GL + /LTCG (with --release)
  --debug         debug build (default: cmake-build-debug, /MDd)
  --arch <arch>   vcvarsall target architecture (default: from CMakeCache.txt,
                  else arm64)
  --define <m[=v]> add a preprocessor define to the compile (repeatable)
  -h, --help      show this help`);
}

function parseArgs(argv) {
  const opts = {
    root: "cppsrc",
    out: "simse_out.cpp",
    exe: "simse.exe",
    gen: true,
    release: false,
    lto: false,
    configSet: false,
    arch: null,
    defines: [],
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
      case "--lto": opts.lto = true; break;
      case "--debug": opts.release = false; opts.configSet = true; break;
      case "--arch": opts.arch = value(i); i++; break;
      case "--define": opts.defines.push(value(i)); i++; break;
      case "-h": case "--help": opts.help = true; break;
      default:
        if (arg.startsWith("-")) fail(`unknown option '${arg}' (try --help)`);
        opts.exe = arg;
    }
  }
  return opts;
}

// Whether the CMake build was configured with SIMSE_LIST_STD_VECTOR: the RTL
// libraries the amalgamation links against bake in the List<T> backing, so the
// compile of the amalgamation has to match them or linking fails with unresolved
// `simse_listFiles`-style symbols over SmallVector/std::vector.
function cachedStdVectorList(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return false;
  return /^SIMSE_LIST_STD_VECTOR:BOOL=(ON|TRUE|1)$/im.test(readFileSync(cache, "utf8"));
}

// The same for SIMSE_STR_STD_STRING: the RTL libraries bake in the Str backing
// (SmString or std::string), so the amalgamation must be compiled alike or the
// native symbols (simse_str_*, file I/O) fail to resolve at link time.
function cachedStdStringStr(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return false;
  return /^SIMSE_STR_STD_STRING:BOOL=(ON|TRUE|1)$/im.test(readFileSync(cache, "utf8"));
}

// And for SIMSE_STR_INLINE_CAPACITY: the RTL libraries were compiled with a
// particular Str layout, which the amalgamation has to share — a mismatch is not
// a link error but silent memory corruption. An empty cache value means the
// libraries use the header's own default, so nothing is passed and both sides
// read the same number from strsmallvector.hpp.
function cachedStrInlineCapacity(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return null;
  const match = readFileSync(cache, "utf8").match(/^SIMSE_STR_INLINE_CAPACITY:[^=\r\n]*=(.*)$/m);
  const value = match ? match[1].trim() : "";
  return value || null;
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
  const env = developerEnv(arch, "build");
  const cl = whichCl(env);
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
  // /DNDEBUG; Debug uses /MDd). /O2 is MSVC's maximum optimization level (/O3 is
  // a GCC/Clang spelling), and /Ob3 lets it inline as far as it wants on top of
  // /O2's /Ob2: on the amalgamated compiler that is worth ~4% of runtime for
  // ~10% more code (impl_specs/capability-matrix.md, T34). `--lto` adds MSVC's
  // whole-program optimization (/GL compiles to an intermediate form that /LTCG
  // optimizes together with the linked libraries at link time), which measures
  // neutral here — the amalgamation already is one translation unit — and makes
  // the link slower.
  const flags = isRelease ? ["/MD", "/O2", "/Ob3", "/DNDEBUG"] : ["/MDd"];
  if (opts.lto) flags.push("/GL", "/LTCG");
  // Mirror the RTL's List<T>/Str backing choices so the amalgamation links
  // against the CMake libraries built in this folder.
  const defines = [...opts.defines];
  const stdVectorList = cachedStdVectorList(buildDir);
  if (stdVectorList && !defines.includes("SIMSE_LIST_STD_VECTOR")) {
    defines.push("SIMSE_LIST_STD_VECTOR");
  } else if (!stdVectorList && defines.includes("SIMSE_LIST_STD_VECTOR")) {
    console.warn(`build: warning: --define SIMSE_LIST_STD_VECTOR does not match ${path.basename(buildDir)}, ` +
        `whose RTL libraries use SmallVector; linking may fail`);
  }
  const stdStringStr = cachedStdStringStr(buildDir);
  if (stdStringStr && !defines.includes("SIMSE_STR_STD_STRING")) {
    defines.push("SIMSE_STR_STD_STRING");
  } else if (!stdStringStr && defines.includes("SIMSE_STR_STD_STRING")) {
    console.warn(`build: warning: --define SIMSE_STR_STD_STRING does not match ${path.basename(buildDir)}, ` +
        `whose RTL libraries use SmString; linking may fail`);
  }
  const strCapacity = cachedStrInlineCapacity(buildDir);
  const passedCapacities = defines.filter((define) => define.startsWith("SIMSE_STR_INLINE_CAPACITY"));
  if (strCapacity && passedCapacities.length === 0) {
    defines.push(`SIMSE_STR_INLINE_CAPACITY=${strCapacity}`);
  } else if (!strCapacity && passedCapacities.length > 0) {
    console.warn(`build: warning: --define SIMSE_STR_INLINE_CAPACITY does not match ${path.basename(buildDir)}, ` +
        `whose RTL libraries use the header default; mixing layouts corrupts memory`);
  } else if (strCapacity && passedCapacities.some((define) => !define.endsWith(`=${strCapacity}`))) {
    console.warn(`build: warning: --define SIMSE_STR_INLINE_CAPACITY does not match ${path.basename(buildDir)} ` +
        `(${strCapacity}); mixing layouts corrupts memory`);
  }
  const args = [
    cl, "/nologo", "/std:c++20", "/EHsc", "/W3", ...flags,
    ...defines.map((define) => `/D${define}`),
    `/I${REPO}`, `/Fo${obj}`, `/Fe${exe}`,
    cpp,
    path.join(buildDir, "simse_native.lib"),
    path.join(buildDir, "simse_lib.lib"),
  ];
  const compile = runCl(env, args);
  if (compile !== 0) fail("cl.exe failed");
  console.log(`build: wrote ${exe}`);
}

main();
