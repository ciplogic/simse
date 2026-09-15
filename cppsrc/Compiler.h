#pragma once

#include "common/common.h"

// The shared core of the transpiler drivers.
//
// `simse_transpile` (cppsrc/codegen/TranspileMain.cpp) is the C++ CLI: explicit
// input files plus the module roots (`--root` / `--module-root`) scanned for
// `.simse` files, and an output path. The self-hosted driver
// (cppsrc/compiler/Driver.simse) is a Simse port of it. Keeping the
// parse -> sema -> codegen -> write pipeline here means both agree on prelude
// loading, module scanning, and diagnostics.
//
// Modules and packages (specs/modules.md): a module is a directory, scanned from
// the project root and any extra module roots; every `.simse` file found is part
// of the compilation. A package is a namespace declared per file; `import a.b.c`
// makes package `a.b.c` visible unqualified and never adds files. The pass is
// compilation-wide, so duplicate definitions across files of one package are
// reported and imports resolve by package name regardless of the directory
// layout or the current working directory. The RTL prelude (`rtl`) is implicitly
// in scope and is never emitted.

namespace compiler {
    struct Request {
        // Prefix for CLI-level diagnostics, e.g. "simse_transpile: cannot write X".
        Str programName = "simse_transpile";

        // Explicit input files. Always part of the compilation.
        List<Str> inputs;

        // Directories scanned recursively for `*.simse`; every file found is part
        // of the compilation. The project root and any external module roots.
        List<Str> moduleRoots;

        // Explicit prelude path (a file or a directory). Used only when
        // `preludeExplicit` is true; otherwise the baked-in default is used.
        Str preludePath;
        bool preludeExplicit = false;

        // The amalgamated output path. Written only when everything succeeds.
        Str output;

        // When true, report every input's parse error before returning; when
        // false, stop at the first failing input.
        bool collectAllErrors = false;

        // `--showLinearRepresentation`: write the linear IL of every emitted body
        // to stderr, next to nothing else - the C++ output is untouched
        // (impl_specs/linear-il.md). A debug view, so the emitted file stays
        // byte-identical with and without it.
        bool showLinearRepresentation = false;

        // `--linearCodegen`: emit every body from its linear IL, next to the
        // statement path, and report where the two disagree. The output is
        // unchanged while they disagree anywhere (the report is the work list).
        bool linearCodegen = false;

        // `--linearCodegenEmit`: use the IL's text for every body it could express
        // (byte-identical, or the same code with the blocks folded away), and the
        // statement text for the rest. This is the step that makes the bytecode the
        // source of the output.
        bool linearCodegenEmit = false;
    };

    // Scans the module roots, parses every input, runs the compilation-wide
    // name/type resolution pass, emits one C++ translation unit, and writes it to
    // `request.output`. Diagnostics go to stderr as
    // "<file>:<line>:<col>: <message>". Returns 0 on success, 1 for input /
    // analysis / codegen / write failures, and 2 for a bad prelude path.
    int transpile(const Request& request);
}
