#pragma once

#include "common/common.h"

// The shared core of the transpiler drivers.
//
// `simse_transpile` (cppsrc/codegen/TranspileMain.cpp) is the low-level CLI: it
// takes explicit input files and an output path. `simse` (cppsrc/main.cpp) is the
// directory compiler: it scans a folder, drops the prelude files, and maps onto
// the same core. Keeping the parse -> sema -> codegen -> write pipeline here means
// both drivers agree on prelude loading, import resolution, and diagnostics.
//
// Prelude loading and import merging follow the compiler's existing rules
// (impl_specs/native-interop.md, specs/functions.md): the prelude participates in
// symbol collection but is never emitted, and `import a.b.c` selects every file
// that declares `package a.b.c` under the resolution root.

namespace compiler {
    struct Request {
        // Prefix for CLI-level diagnostics, e.g. "simse_transpile: cannot write X".
        Str programName = "simse";

        // The top-level input files, already discovered and ordered by the caller.
        List<Str> inputs;

        // Explicit prelude path (a file or a directory). Used only when
        // `preludeExplicit` is true; otherwise the baked-in default is used.
        Str preludePath;
        bool preludeExplicit = false;

        // Root under which `import a.b.c` directories are resolved.
        Str root = ".";

        // The amalgamated output path. Written only when everything succeeds.
        Str output;

        // Directory mode (the `simse` tool): `inputs` is the raw folder scan.
        // The core expands it to the ordered, de-duplicated import set (each file
        // exactly once via parser::collectImportSet) and parses each file on its
        // own - imports are resolved for discovery, but not re-merged - so every
        // file is emitted once and sema runs per file, naming the real file in
        // diagnostics. `excludePreludeFiles` then drops the prelude from the set.
        bool directoryMode = false;

        // Directory mode: skip inputs whose canonical path is a file in the
        // prelude set, so a prelude file discovered by a folder scan is not
        // compiled twice. Off for `simse_transpile`, which passes explicit files.
        bool excludePreludeFiles = false;

        // When true, keep going after a failing input and report every error
        // before returning; when false, stop at the first failing input.
        bool collectAllErrors = false;
    };

    // Parses imports, runs name/type resolution, emits one C++ translation unit,
    // and writes it to `request.output`. Diagnostics go to stderr as
    // "<file>:<line>:<col>: <message>". Returns 0 on success, 1 for input /
    // analysis / codegen / write failures, and 2 for a bad prelude path.
    int transpile(const Request& request);
}
