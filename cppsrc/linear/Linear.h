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
    // What one stage of the linear form produced: the body, and whether the stage
    // changed anything. The stages run in a loop - each can leave work for the
    // others - and stop when a whole round changes nothing, so every stage has to
    // report the work it did and, just as important, the work it did not do.
    struct Lowered {
        List<ast::StmtPtr> body;
        bool changed = false;
    };

    // The names the lowering generates for its own storage: the expression lowering's
    // temporaries (`_sm_expr<n>`) and the switch subjects this pass hoists
    // (`simse_sw_<n>`). A name the program wrote can collide with one of these - the
    // switch subject's collision is an accepted, documented risk - but nothing the
    // lowering generates came from the source, which is what the hoisting below asks
    // about.
    bool isSlotName(const Str& name);

    // Rewrites one function-like body (a function/method body or a lambda body)
    // into linear form. Pure: the input statements are not modified.
    Lowered lowerBody(const List<ast::StmtPtr>& body);

    // The whole linear form of one function-like body, ready to emit: the stages
    // (`lowerBody`, `simplifyBody`, `lowerExprs`) run in a loop until none of them
    // has work left, then the block folding (`flattenBlocks`) runs, and while that
    // changed something the loop starts over. Folding is what lets the next round
    // see a flatter body - and a jump a block used to hide is a jump the peephole
    // can fold.
    //
    // This is the first half of the pipeline; `finishForEmission` (Simplify.h) is the
    // second, and it needs the types `sema::inferTypes` spells in between.
    List<ast::StmtPtr> lowerForEmission(const List<ast::StmtPtr>& body);
}
