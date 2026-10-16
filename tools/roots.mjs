// The compiler's own source roots - what `build.js` transpiles into `simse_out.cpp` and what
// `tools/bootstrap.js` transpiles on both sides of the fixed point. Both import this file, so the
// two can never disagree about what the compiler is built from.
//
// Each entry is a *tree* (`--root`): scanned whole, `generators/` included. That is why this is a
// list of trees rather than one `modules` root - with one root, every module directory would be
// part of the compiler, and `modules/minui` is not: it is a module for *programs* (SDL, Skia, a UI
// layer), named on a program's own command line (`--module modules/minui`).
//
// The entries are the module directories in the order the single-`modules` scan used to visit
// them, so the published bootstrap keeps its bytes. Adding a module that the compiler itself uses
// means adding it here; adding one that only programs use does not.
//
// (`--module` is deliberately not used: it excludes a module's `generators/`, and the compiler
// needs `modules/json/generators/JsonGen.kt`.)
export const compilerRoots = [
  "src",
  "modules/compiler",
  "modules/gzip",
  "modules/http",
  "modules/io",
  "modules/json",
  "modules/jsonnode",
  "modules/linq",
  "modules/process",
  "modules/streams",
  "modules/unsafe",
  "modules/xml",
  "modules/zip",
];

// The same, as the command line spells it (`--root a --root b ...`).
export const compilerRootArgs = compilerRoots.flatMap((root) => ["--root", root]);
