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

    // Moves **every** declaration of a body to the top of it, so a body has one C++
    // scope and one only - the lowering's own temporaries (`_sm_expr<n>`, `simse_sw_<n>`)
    // and the program's `val`/`var` alike - and turns each initializer into an assignment
    // where the declaration stood:
    //
    //     { Bool _sm_expr2 = i == 3; if (_sm_expr2) goto L4; }
    //       ->
    //     Bool _sm_expr2;                          (at the top of the body)
    //     ...
    //     _sm_expr2 = i == 3;
    //     if (_sm_expr2) goto L4;
    //
    // A declaration at the top of the body is a declaration no jump can bypass, which is
    // the one thing the folding above needs (C2362), so this is what turns the linear form
    // into one flat sequence: after it, no block is left for a declaration's sake. The
    // initialization stays where it was, so evaluation order and side effects do not move;
    // what moves is where the storage is declared, which makes every slot of the body live
    // for the whole body (a bytecode frame's slots, without liveness reuse).
    //
    // It runs **after the type pass**, because a declaration has to keep the type that
    // pass proved - `auto x;` is not a declaration - so a declaration the inference could
    // not spell, and a machine's `..T` (no type to write), keep their place.
    //
    // One scope is also why a name has to be unique in the body: the language lets two
    // scopes reuse a name, and `renameShadowed` resolves that before anything moves (a
    // rename in the lowering, impl_specs/linear-il.md).
    Lowered hoistSlots(const List<ast::StmtPtr>& body);

    // `hoistSlots` with the shadowing resolved first: the names the emitter has already
    // declared in the body's own C++ scope (its parameters, `self`) are `reserved`, and a
    // declaration that would collide with one of those - or with another declaration of
    // the body - is renamed with its uses.
    Lowered hoistSlots(const List<ast::StmtPtr>& body, const List<Str>& reserved);

    // The second half of the pipeline for one function-like body, run once
    // `sema::inferTypes` has spelled the declarations: the shadowing is resolved, the
    // declarations move to the top of the body (`hoistSlots`), which is what lets the
    // folding fold the blocks the declarations forced, and the peephole gets another look
    // at the flatter body (a jump a block hid is a jump it can fold, and a folded jump can
    // free a label). The loop is the shape `lowerForEmission` runs, with the hoisting in
    // the place of the rewriting stages - there is nothing left to rewrite.
    List<ast::StmtPtr> finishForEmission(const List<ast::StmtPtr>& body);
    List<ast::StmtPtr> finishForEmission(const List<ast::StmtPtr>& body,
                                         const List<Str>& reserved);
}
