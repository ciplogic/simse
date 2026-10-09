// smt.js - the Simse toolset: a project CLI in the shape of npm.
//
//   bun tools/smt.js <command> [options] [-- <args>...]
//
// Commands:
//   init [<dir>]           scaffold a project: simse.md + src/main.kt   (stub)
//   build                  transpile and compile the project            (stub)
//   run [-- <args>...]     build the project, then run its program      (stub)
//   install [<module>...]  resolve the project's modules               (stub)
//   help [<command>]       the command list, or one command's
//   version                the toolset's version
//
// Options:
//   --root <dir>   the project root (default: .) - the directory a simse.md sits in
//   --release      optimize the build (what `build.js --release` means); build, run
//   --quiet        one summary line per step; build, run
//   -h, --help     this text; with a command, that command's
//   --version      the toolset's version
//
// The toolset is a front door to what the repository already has: `build` and `run`
// are `build.js` (and the `cl.exe` environment `tools/msvc.mjs` materializes) over the
// program it produces; `install` is the module resolution `specs/modules.md` defers
// ("External modules (deferred)"); `init` writes the `simse.md` manifest
// `specs/simse-md.md` defines.
//
// Every command is a **stub**: it prints the plan it will carry out and exits 1, so
// nothing is half-done silently. `help` and `version` answer normally (exit 0). The
// toolset is not the compiler - `simse.exe` (`ai/building.md`) is what compiles a
// program; smt is the project-level wrapper a user types instead.

import { readFileSync } from "node:fs";
import * as path from "node:path";

import { fail as failTool } from "./msvc.mjs";

const TOOL = "smt";
const fail = (message) => failTool(TOOL, message);
const VERSION = "0.1.0";

// One entry per command: how it is spelled, what it is for, and - while it is a stub -
// the plan it will carry out. `main` dispatches on the name; help prints the rest.
const COMMANDS = {
  init: {
    usage: "init [<dir>]",
    summary: "scaffold a Simse project (simse.md + src/main.kt)",
    plan: [
      "write <dir>/simse.md - the manifest a project root carries (specs/simse-md.md)",
      "write <dir>/src/main.kt - a `main(args)` that prints hello",
      "report the `smt build` / `smt run` commands to try next",
    ],
  },
  build: {
    usage: "build",
    summary: "transpile and compile the project into an executable",
    plan: [
      "read the module list from <root>/simse.md, else scan <root> whole (specs/simse-md.md)",
      "transpile it with the compiler CLI (`--root <root> [--module <dir>]... -o <out>.cpp`, ai/building.md)",
      "compile the one translation unit with cl.exe, the environment tools/msvc.mjs materializes",
    ],
  },
  run: {
    usage: "run [-- <args>...]",
    summary: "build the project, then run its program",
    plan: [
      "run `smt build` first (above)",
      "run the produced executable, forwarding `-- <args>...` to `main(args)`",
    ],
  },
  install: {
    usage: "install [<module>...]",
    summary: "resolve the project's modules (external modules are deferred)",
    plan: [
      "resolve the module list from <root>/simse.md",
      "stage external modules beside the project (specs/modules.md: external modules are deferred)",
    ],
  },
  help: {
    usage: "help [<command>]",
    summary: "the command list, or one command's",
  },
  version: {
    usage: "version",
    summary: "the toolset's version",
  },
};

// The header comment above is the general help (`// smt.js` up to the imports), the
// shape tools/stress.js uses, so the two cannot drift apart.
function usage() {
  const header = readFileSync(import.meta.path, "utf8");
  const start = header.indexOf("// smt.js");
  const end = header.indexOf("\nimport ");
  console.log(header.slice(start, end).replace(/^\/\/ ?/gm, "").trim());
}

function commandHelp(name) {
  if (!COMMANDS[name]) fail(`unknown command '${name}' (try 'smt help')`);
  const command = COMMANDS[name];
  console.log(`${TOOL} ${command.usage} - ${command.summary}`);
  if (command.plan) {
    console.log("");
    console.log("not implemented yet; it will:");
    for (const step of command.plan) console.log(`  - ${step}`);
  }
}

function parse(argv, opts) {
  const value = (i) => {
    if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
    return argv[i + 1];
  };
  let i = 0;
  while (i < argv.length) {
    const arg = argv[i];
    if (arg === "--") {
      opts.args.push(...argv.slice(i + 1));
      break;
    }
    switch (arg) {
      case "--root": opts.root = value(i); i += 2; break;
      case "--release": opts.release = true; i += 1; break;
      case "--quiet": opts.quiet = true; i += 1; break;
      case "--version": opts.version = true; i += 1; break;
      case "-h": case "--help": opts.help = true; i += 1; break;
      default:
        if (arg.startsWith("-")) fail(`unknown option '${arg}' (try 'smt help')`);
        if (opts.command === null) opts.command = arg;
        else opts.args.push(arg);
        i += 1;
    }
  }
}

// The one stub body: say what the command is for, echo the arguments it was given,
// and hand back a failure code. Nothing is written, so nothing can be half-done.
function notImplemented(name, opts) {
  const command = COMMANDS[name];
  const flags = [opts.release ? "--release" : null, opts.quiet ? "--quiet" : null]
      .filter((flag) => flag !== null);
  console.log(`${TOOL} ${name}: not implemented yet - ${command.summary}.`);
  console.log(`  project root: ${path.resolve(opts.root)}`);
  console.log(`  flags:        ${flags.length > 0 ? flags.join(" ") : "none"}`);
  if (opts.args.length > 0) console.log(`  arguments:    ${opts.args.join(" ")}`);
  console.log("  it will:");
  for (const step of command.plan) console.log(`    - ${step}`);
  return 1;
}

function main(argv) {
  const opts = { root: ".", release: false, quiet: false, command: null, args: [], help: false, version: false };
  parse(argv, opts);

  if (opts.version || opts.command === "version") {
    console.log(`${TOOL} ${VERSION}`);
    return 0;
  }
  if (opts.command === null || opts.command === "help") {
    if (opts.command === "help" && opts.args.length > 0) {
      commandHelp(opts.args[0]);
      return 0;
    }
    usage();
    return 0;
  }
  if (!COMMANDS[opts.command]) {
    usage();
    fail(`unknown command '${opts.command}' (try 'smt help')`);
  }
  if (opts.help) {
    commandHelp(opts.command);
    return 0;
  }
  return notImplemented(opts.command, opts);
}

process.exitCode = main(process.argv.slice(2));
