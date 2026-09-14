#pragma once

#include "../ast/Ast.h"

// Lowering of nested expressions into temporaries (impl_specs/linear-lowering.md,
// "Expression lowering"). `linear::lowerBody` gives the emitter one statement
// vocabulary; this pass gives it one *expression* vocabulary: after it runs, every
// expression is either a simple operand or an operation over simple operands, and
// anything deeper has been bound to a `_sm_expr<n>` local:
//
//   var a = (b + c) * d;
//     ->
//   auto _sm_expr1 = b + c;
//   auto a = _sm_expr1 * d;
//
//   x = a[i + 2].toString();
//     ->
//   auto _sm_expr1 = i + 2;
//   x = a[_sm_expr1].toString();
//
// It runs on a lowered body, after `linear::simplifyBody` and before emission, so
// the peephole pass never sees the temporaries and nothing can fold them back.
// Temporaries are named from a per-body counter (`_sm_expr1`, ...), like the labels.
//
// Two boundaries are deliberate:
//
//   - an **lvalue path** (a name, or a member/index/deref chain rooted at one) is
//     left as it is: it is already a simple operand, and binding it to a value
//     temporary would copy what is behind it, so a mutating call on the copy would
//     be lost. Its indices and arguments are flattened;
//   - **`&&` and `||`** (and a future `?:`) are left untouched: their operands are
//     evaluated conditionally, so hoisting anything out of them would change the
//     program. They belong to the control-flow lowering (`ifTrue`/`ifFalse` plus
//     labels), not to this pass.
namespace linear {
    // Rewrites the expressions of one linear body. Pure: the input statements are
    // not modified.
    List<ast::StmtPtr> lowerExprs(const List<ast::StmtPtr>& body);
}
