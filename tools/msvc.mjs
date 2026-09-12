// msvc.mjs - the Visual Studio plumbing shared by build.js (which compiles the
// amalgamated compiler) and tools/stress.js (which compiles the transpiled
// programs). Nothing here knows about Simse: it locates vcvarsall, materializes
// the developer environment for one architecture, and reads the CMake cache.
//
// Keeping it in one module is what lets `build.js` and the stress harness agree
// on the environment without either of them owning a copy: the ambient prompt's
// cl.exe may target another architecture than the CMake libraries, and linking
// across that mix fails with LNK4272 plus a wall of LNK2019 unresolved
// externals that hides the real cause.

import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import * as path from "node:path";

// The repository root (this file lives in <root>/tools).
export const REPO = path.resolve(import.meta.dir, "..");

// Each tool prefixes its diagnostics so a failure says who failed.
export function fail(tool, message) {
  console.error(`${tool}: ${message}`);
  process.exit(1);
}

// The target architecture the CMake build compiles for, read from the compiler
// path in CMakeCache.txt (`.../bin/Host<host>/<target>/cl.exe`).
export function cachedArch(buildDir) {
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
export function findVcvars() {
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

export function normalizeArch(value) {
  const lower = String(value).toLowerCase();
  return lower === "amd64" ? "x64" : lower;
}

// The CMake build type recorded in CMakeCache.txt (Debug/Release/...), or null.
export function cachedBuildType(buildDir) {
  const cache = path.join(buildDir, "CMakeCache.txt");
  if (!existsSync(cache)) return null;
  const match = readFileSync(cache, "utf8").match(/^CMAKE_BUILD_TYPE:[^=\r\n]*=(.*)$/m);
  if (!match) return null;
  const value = match[1].trim();
  return value || null;
}

// The architecture the compiler targets, from its banner ("... for ARM64").
export function compilerTarget(cl, env) {
  const probe = Bun.spawnSync([cl], { env, stdout: "pipe", stderr: "pipe" });
  const banner = probe.stdout.toString() + probe.stderr.toString();
  const match = banner.match(/ for (ARM64EC|ARM64|ARM|x64|x86)\b/i);
  return match ? match[1].toLowerCase() : null;
}

// The Visual Studio developer environment for `arch`. A `cl` already on PATH is
// only trusted when Visual Studio cannot be located: the ambient prompt may
// target another architecture than the CMake libraries (LNK4272/LNK2019).
export function developerEnv(arch, tool) {
  const vcvars = findVcvars();
  if (!vcvars) {
    if (Bun.which("cl")) {
      console.warn(`${tool}: warning: Visual Studio not found; using the cl.exe already on PATH`);
      return process.env;
    }
    fail(tool, "cannot locate Visual Studio and no cl.exe on PATH");
  }

  const script = path.join(tmpdir(), `simse-vcenv-${process.pid}.bat`);
  writeFileSync(script, `@echo off\r\ncall "${vcvars}" ${arch} >nul\r\nset\r\n`);
  try {
    const sourced = Bun.spawnSync(["cmd.exe", "/d", "/c", script], { stdout: "pipe", stderr: "pipe" });
    if (sourced.exitCode !== 0) fail(tool, `vcvarsall ${arch} failed:\n${sourced.stderr.toString()}`);
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

// cl.exe from the developer environment, or null when it is not there.
export function whichCl(env) {
  return Bun.which("cl", { PATH: env.PATH });
}

// Run cl.exe with stdio inherited; returns its exit code.
export function runCl(env, args, cwd = REPO) {
  const result = Bun.spawnSync(args, { cwd, env, stdout: "inherit", stderr: "inherit" });
  return result.exitCode ?? 1;
}
