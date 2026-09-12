#pragma once

#include "../ast/Ast.h"

// Lowering of structured control flow into labels and gotos
// (impl_specs/linear-lowering.md). The pass runs after sema and before C++
// emission, so the emitter never has to handle If/While/Switch/Break/Continue:
//
//   label L;            -> StmtKind::Label
//   goto L;             -> StmtKind::Goto
//   if (c) goto L;      -> StmtKind::IfTrue
//   if (!(c)) goto L;   -> StmtKind::IfFalse
//   { ... }             -> StmtKind::Block
//
// Bodies are wrapped in blocks because a C++ jump may not bypass a declaration
// that is still in scope at the target; the language already scopes each
// branch/loop body separately, so the wrapper preserves semantics.
namespace linear {
    // Rewrites one function-like body (a function/method body or a lambda body)
    // into linear form. Pure: the input statements are not modified.
    List<ast::StmtPtr> lowerBody(const List<ast::StmtPtr>& body);
}
