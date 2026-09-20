// vscheck.mjs - build the Visual Studio profiling project from the command line.
//
//   bun tools/vscheck.mjs [Debug|Release]
//
// `simse.vcxproj` + `simse.slnx` are the profiling project: they compile the published
// bootstrap `cppsrc/simse_bootstrap.cpp` - one translation unit, the runtime generated into
// it - into `profile\<Configuration>\simse.exe`, with the debugger arguments already set for running the
// compiler over its own tree. This tool exists because nothing else in the harness touches
// those files: without it, a change to the RTL's includes or to the amalgamation's shape could
// break the project without any check noticing. It runs MSBuild with the same Visual Studio
// environment the other tools use (`tools/msvc.mjs`), so it needs no developer prompt.
//
// Exit code 0 when MSBuild succeeded; the last lines of its output are printed either way.
// The built compiler is the project's own, so it is worth one functional check by hand after a
// change that moved the amalgamation: `profile/Release/simse.exe --root cppsrc -o out.cpp`
// reproduces `cppsrc/simse_bootstrap.cpp` byte for byte.
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import * as path from "node:path";

import { developerEnv, hostArch, normalizeArch, REPO } from "./msvc.mjs";

const env = developerEnv(normalizeArch(hostArch()), "build");

function findMsbuild() {
  const candidates = [];
  if (env.VCINSTALLDIR) {
    candidates.push(path.join(env.VCINSTALLDIR, "MSBuild", "Current", "Bin", "MSBuild.exe"));
  }
  if (env.VSINSTALLDIR) {
    candidates.push(path.join(env.VSINSTALLDIR, "MSBuild", "Current", "Bin", "MSBuild.exe"));
    candidates.push(path.join(env.VSINSTALLDIR, "MSBuild", "Current", "Bin", "amd64", "MSBuild.exe"));
  }
  candidates.push("msbuild");
  for (const candidate of candidates) {
    if (candidate === "msbuild") return candidate;
    if (existsSync(candidate)) return candidate;
  }
  return "msbuild";
}

const msbuild = findMsbuild();
const config = process.argv[2] || "Debug";
if (config !== "Debug" && config !== "Release") {
  console.log("vscheck: usage: bun tools/vscheck.mjs [Debug|Release]");
  process.exit(1);
}
console.log(`vscheck: ${msbuild} (Configuration=${config}, Platform=ARM64)`);
const result = spawnSync(msbuild, [
  "simse.vcxproj", `/p:Configuration=${config}`, "/p:Platform=ARM64", "/v:m", "/nologo",
], { cwd: REPO, env, encoding: "utf8" });
console.log(`vscheck: exit ${result.status}`);
const out = `${result.stdout || ""}${result.stderr || ""}`.trim().split("\n");
console.log(out.slice(-30).join("\n"));
process.exit(result.status === 0 ? 0 : 1);
