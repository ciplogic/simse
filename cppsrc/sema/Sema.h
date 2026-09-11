#pragma once

#include "../ast/Ast.h"

// A minimal name and type resolution pass. It is deliberately conservative: it
// only reports the problems listed below, and stays silent about anything it is
// not sure of (member calls, unresolved names, generics, lambdas).
//
// The pass is compilation-wide: it is given every file that participates in the
// compilation (the scanned module set plus the implicit prelude) and groups
// declarations by their declared package (specs/modules.md). A package is a
// namespace: two files that declare the same package define the same scope.
//
// Reported:
//   - duplicate top-level names within one package (across files too)
//   - an import of a package that no participating file declares
//   - unknown type names in type positions
//   - assignment to a `val` local
//   - `break` / `continue` outside a loop
//   - call arity mismatch for a call whose callee is a direct reference to a
//     known top-level function

namespace sema {
    // One participating file. `module`'s declared package namespaces its
    // declarations; the pointers must stay alive for the duration of `analyze`.
    // The package `rtl` (the runtime prelude) is implicitly in scope everywhere.
    struct Input {
        Str fileName;
        const ast::Module *module = nullptr;
    };

    // Returns diagnostics of the form "<fileName>:<line>:<col>: <message>".
    // An empty list means the compilation is clean.
    List<Str> analyze(const List<Input>& inputs);
}
