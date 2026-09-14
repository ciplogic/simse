#pragma once

#include "../ast/Ast.h"
#include "Linear.h"

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
// It runs on a lowered body, after `linear::simplifyBody` in the same round, and
// the peephole runs again only after the block folding (`linear::flattenBlocks`),
// so the temporaries this pass introduces are folded back only where the folding
// exposed them. Temporaries are named from a per-body counter (`_sm_expr1`, ...),
// like the labels.
//
// A body that is already lowered comes back unchanged and reports `changed` false:
// every position that is not bound keeps the shape it read.
//
// A *value* position - an operand, a call argument, a conditional jump's condition,
// a `return` value - never holds more than one operation: anything deeper is its own
// temporary, so a jump and a `return` end up reading one name:
//
//   if (i < 5) { ... }        ->  Bool _sm_expr1 = i < 5;
//                                 ifTrue (_sm_expr1) -> ...
//
//   return i < 2;             ->  Bool _sm_expr1 = i < 2;
//                                 return _sm_expr1;
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
    Lowered lowerExprs(const List<ast::StmtPtr>& body);
}
