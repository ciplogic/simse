// Scratch: compile one amalgamation with the bench's release flags plus /FA, so a hot
// loop's assembly can be read (the for-walk's particles analysis). Run by hand, never by
// the loop.
//   bun tools/_asmbuild.mjs <name> <src.cpp>   -> <src-dir>/<name>.asm
import { developerEnv, hostArch, whichCl } from "./msvc.mjs";
import { $ } from "bun";
import path from "node:path";

const name = process.argv[2];
const src = path.resolve(process.argv[3]);
const env = developerEnv(hostArch(), "_asmbuild");
const cl = whichCl(env);
const out = src.replace(/\.cpp$/, "") + "." + name;
const flags = ["/nologo", "/std:c++20", "/EHsc", "/W3", "/bigobj", "/MD", "/O2", "/Ob3", "/DNDEBUG", "/GL",
    "/I.", `/Fa${out}.asm`, `/Fo${out}.obj`, `/Fe${out}.exe`, src];
const r = await $`${[cl, ...flags]}`.env(env).nothrow().quiet();
console.log(name, "exit", r.exitCode, out + ".asm");
if (r.exitCode !== 0) console.error(r.stderr.toString().slice(0, 400));
