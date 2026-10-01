// Scratch: what the C++ compiler does with the emitted frame's shape (`/O2`, ARM64).
//
// The IL frame declares every slot at the top of the body, so a *trivial* slot's storage is
// free for the optimizer to reuse, while a *non-trivial* one (`std::string`,
// `std::vector<std::string>`, the RTL's `Str`/`List`/`AstXmlNode`) is constructed at the
// declaration and destroyed at the end of the body - its lifetime is the whole body, so two
// such slots are alive at once and cannot share storage. This compiles the shapes and prints
// each function's stack allocation from the `/FAs` listing, so the claim is a measurement:
//
//   bun tools/_coalesce.mjs
import { writeFileSync } from "node:fs";
import { readFileSync, mkdirSync } from "node:fs";
import { developerEnv, runCl, whichCl, hostArch } from "./msvc.mjs";

const N = 24;
const lines = ["#include <string>", "#include <vector>", ""];

// 1. Trivial slots declared at the top (the frame's shape, integer type).
lines.push("int frameInts(int seed) {");
for (let i = 0; i < N; i++) lines.push(`    int i${i};`);
lines.push("    int total = 0;");
for (let i = 0; i < N; i++) {
  lines.push(`    i${i} = seed + ${i}; total += i${i};`);
}
lines.push("    return total;");
lines.push("}\n");

// 2. Non-trivial slots declared at the top (the frame's shape, `std::string`).
lines.push("int frameStrings(const char* seed) {");
for (let i = 0; i < N; i++) lines.push(`    std::string s${i};`);
lines.push("    int total = 0;");
for (let i = 0; i < N; i++) {
  lines.push(`    s${i} = seed; total += (int) s${i}.size();`);
}
lines.push("    return total;");
lines.push("}\n");

// 3. Non-trivial slots, each constructed where it is used (the shape the proposed slot-reuse
//    produces: narrow lifetimes).
lines.push("int scopedStrings(const char* seed) {");
lines.push("    int total = 0;");
for (let i = 0; i < N; i++) {
  lines.push(`    { std::string s = seed; total += (int) s.size(); }`);
}
lines.push("    return total;");
lines.push("}\n");

// 4. Two strings reused across narrow lifetimes (what the pass emits for a type that needs
//    two storages: the simultaneity count).
lines.push("int reusedStrings(const char* seed) {");
lines.push("    std::string s0, s1;");
lines.push("    int total = 0;");
for (let i = 0; i < N; i++) {
  lines.push(`    s${i % 2} = seed; total += (int) s${i % 2}.size();`);
}
lines.push("    return total;");
lines.push("}\n");

// 5. Vectors of strings declared at the top, and in narrow scopes.
lines.push("int frameVectors(const char* seed) {");
for (let i = 0; i < N; i++) lines.push(`    std::vector<std::string> v${i};`);
lines.push("    int total = 0;");
for (let i = 0; i < N; i++) {
  lines.push(`    v${i}.push_back(seed); total += (int) v${i}.size();`);
}
lines.push("    return total;");
lines.push("}\n");

lines.push("int main() { return frameInts(1) + frameStrings(\"x\") + scopedStrings(\"x\") + reusedStrings(\"x\") + frameVectors(\"x\"); }");

mkdirSync("build/coal", { recursive: true });
writeFileSync("build/coal/frame.cpp", lines.join("\n"));

const env = developerEnv(hostArch(), "_coalesce");
const cl = whichCl(env);
if (!cl) {
  console.log("cl.exe not found");
  process.exit(1);
}
const code = runCl(env, [cl, "/nologo", "/O2", "/FAs", "/Fabuild/coal/frame.asm", "/c", "build/coal/frame.cpp", "/Fo:build/coal/frame.obj"]);
if (code !== 0) {
  console.log(`cl failed (${code})`);
  process.exit(1);
}

const asm = readFileSync("build/coal/frame.asm", "utf8").split("\n");
console.log("the stack the prologue reserves, from the /FAs listing:\n");
for (const name of ["frameInts", "frameStrings", "scopedStrings", "reusedStrings", "frameVectors"]) {
  const at = asm.findIndex((l) => l.includes("PROC") && l.trim().endsWith(`; ${name}`));
  if (at < 0) {
    console.log(`  ${name}: not found in the listing`);
    continue;
  }
  const alloc = [];
  for (let i = at; i < at + 400 && alloc.length < 4; i++) {
    const l = asm[i].trim();
    if (/^(sub +sp,sp,#0x[0-9a-f]+|stp +x[0-9]+,.*\[sp,#-0x[0-9a-f]+\]!|str +x[0-9]+,.*\[sp,#-0x[0-9a-f]+\]!|str +lr,.*\[sp,#-0x[0-9a-f]+\]!)$/.test(l)) {
      alloc.push(l.replace(/[ \t]+/g, " "));
    }
  }
  const bytes = alloc
    .map((l) => parseInt((l.split("#-0x")[1] || l.split("#0x")[1] || "0").replace(/\].*/, ""), 16))
    .reduce((a, b) => a + b, 0);
  console.log(`  ${name.padEnd(15)} ~${bytes} bytes   ${alloc.join(" | ")}`);
}
