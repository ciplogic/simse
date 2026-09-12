#pragma once

#include "../ast/Ast.h"

// Peephole simplification of the linear form produced by `linear::lowerBody`
// (impl_specs/linear-lowering.md). Deliberately simple and semantics-preserving;
// it exists so the emitted code stays close to the structured code it came from.
//
//   goto L; L:;                    -> L:;
//   if (c) goto L; L:;             -> L:;
//   ifTrue (c) goto A; goto B; A:; -> ifFalse (c) goto B;
//   goto L; <unreachable> L:;      -> goto L; L:;
//   L:; (nothing jumps to it)      -> (removed)
//
// Runs to a fixed point (dropping a jump can make a label unused, and dropping
// statements can expose another jump-to-next or an unreachable run).
namespace linear {
    List<ast::StmtPtr> simplifyBody(const List<ast::StmtPtr>& body);
}
