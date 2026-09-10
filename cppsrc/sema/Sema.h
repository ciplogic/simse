#pragma once

#include "../ast/Ast.h"

// A minimal name and type resolution pass. It is deliberately conservative: it
// only reports the problems listed below, and stays silent about anything it is
// not sure of (member calls, unresolved names, generics, lambdas).
//
// Reported:
//   - duplicate top-level names
//   - unknown type names in type positions
//   - assignment to a `val` local
//   - `break` / `continue` outside a loop
//   - call arity mismatch for a call whose callee is a direct reference to a
//     known top-level function

namespace sema {
    // Returns diagnostics of the form "<fileName>:<line>:<col>: <message>".
    // An empty list means the module is clean.
    List<Str> analyze(const ast::Module& module, const Str& fileName);
}
