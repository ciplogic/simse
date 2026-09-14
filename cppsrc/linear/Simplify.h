#pragma once

#include "../ast/Ast.h"
#include "Linear.h"

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
    Lowered simplifyBody(const List<ast::StmtPtr>& body);

    // Folds nested blocks into their parent sequence. A block stays where splicing
    // it would move one of its declarations across a jump: C++ rejects a jump that
    // skips an initialization in scope at the label ([stmt.dcl]/3, MSVC C2362), and
    // the linear form is full of jumps. Pure, and a single bottom-up pass: children
    // first, then the parent judged on what they leave behind.
    //
    // This is the last stage of `lowerForEmission`, so it sees the body the
    // expression lowering leaves behind - the pass that wraps a statement and its
    // temporaries in a block.
    Lowered flattenBlocks(const List<ast::StmtPtr>& body);

    // Moves the lowering's own declarations - the `_sm_expr<n>` temporaries and the
    // `simse_sw_<n>` switch subjects - to the top of the body, and turns each
    // initializer into an assignment where the declaration stood:
    //
    //     { Bool _sm_expr2 = i == 3; if (_sm_expr2) goto L4; }
    //       ->
    //     Bool _sm_expr2;                          (at the top of the body)
    //     ...
    //     _sm_expr2 = i == 3;
    //     if (_sm_expr2) goto L4;
    //
    // A declaration at the top of the body is a declaration no jump can bypass,
    // which is the one thing the folding above needs (C2362), so this is what turns
    // the linear form into one flat sequence: after it, every block left is one the
    // *program* asked for, not one a temporary forced. The initialization stays
    // where it was, so evaluation order and side effects do not move; what moves is
    // where the storage is declared, which makes every slot of the body live for the
    // whole body (a bytecode frame's slots, without liveness reuse).
    //
    // It runs **after the type pass**, because a declaration has to keep the type
    // that pass proved - `auto x;` is not a declaration - and a slot that stayed
    // untyped keeps its declaration (and the block around it) exactly as it was.
    //
    // A source-level `val`/`var` never moves: its scope is the program's, two scopes
    // may reuse a name, and `val` is the program's own binding.
    Lowered hoistSlots(const List<ast::StmtPtr>& body);

    // The second half of the pipeline for one function-like body, run once
    // `sema::inferTypes` has spelled the declarations: the slots move to the top of
    // the body (`hoistSlots`), which is what lets the folding fold the blocks the
    // temporaries forced, and the peephole gets another look at the flatter body
    // (a jump a block hid is a jump it can fold, and a folded jump can free a label).
    // The loop is the shape `lowerForEmission` runs, with the hoisting in the place
    // of the rewriting stages - there is nothing left to rewrite.
    List<ast::StmtPtr> finishForEmission(const List<ast::StmtPtr>& body);
}
