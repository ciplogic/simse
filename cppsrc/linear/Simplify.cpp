#include "Simplify.h"

#include <string>

namespace linear {
    using ast::Stmt;
    using ast::StmtKind;
    using ast::StmtPtr;

    namespace {
        bool isLabel(const StmtPtr &stmt) {
            return stmt && stmt->kind == StmtKind::Label;
        }

        bool isGoto(const StmtPtr &stmt) {
            return stmt && stmt->kind == StmtKind::Goto;
        }

        bool isCondJump(const StmtPtr &stmt) {
            return stmt && (stmt->kind == StmtKind::IfTrue || stmt->kind == StmtKind::IfFalse);
        }

        bool isTerminator(const StmtPtr &stmt) {
            return stmt && (stmt->kind == StmtKind::Goto || stmt->kind == StmtKind::Return);
        }

        // A copy of a conditional jump with the condition negated (IfTrue <->
        // IfFalse) and a new target.
        StmtPtr invertedJump(const StmtPtr &jump, const Str &target) {
            auto stmt = std::make_shared<Stmt>(*jump);
            stmt->kind = jump->kind == StmtKind::IfTrue ? StmtKind::IfFalse : StmtKind::IfTrue;
            stmt->name = target;
            return stmt;
        }

        // A label belongs to the sequence it sits in, but a jump to it may sit in
        // any scope inside that sequence: the expression lowering wraps a jump in
        // the block that carries its temporaries, and `break`/`continue` jump out of
        // the body they are written in. The scan therefore looks through blocks.
        bool jumpsTo(const List<StmtPtr> &stmts, const Str &name) {
            for (const StmtPtr &stmt: stmts) {
                if (!stmt) continue;
                if ((isGoto(stmt) || isCondJump(stmt)) && stmt->name == name) return true;
                if (stmt->kind == StmtKind::Block && jumpsTo(stmt->body, name)) return true;
            }
            return false;
        }

        List<StmtPtr> prunePass(const List<StmtPtr> &stmts, bool &changed) {
            List<StmtPtr> out;
            for (int i = 0; i < (int) stmts.size(); i++) {
                const StmtPtr &stmt = stmts[i];
                if (!stmt) continue;
                // A jump to the statement right after it does nothing.
                if ((isGoto(stmt) || isCondJump(stmt)) && i + 1 < (int) stmts.size()
                    && isLabel(stmts[i + 1]) && stmts[i + 1]->name == stmt->name) {
                    changed = true;
                    continue;
                }
                // `ifTrue (c) goto A; goto B; A:` is `ifFalse (c) goto B;`. The
                // label A stays in the stream and is dropped below if nothing
                // else jumps to it.
                if (isCondJump(stmt) && i + 2 < (int) stmts.size() && isGoto(stmts[i + 1])
                    && isLabel(stmts[i + 2]) && stmts[i + 2]->name == stmt->name) {
                    out.push_back(invertedJump(stmt, stmts[i + 1]->name));
                    changed = true;
                    i += 1;
                    continue;
                }
                out.push_back(stmt);
                // Nothing before the next label can be reached.
                if (!isTerminator(stmt)) continue;
                while (i + 1 < (int) stmts.size() && !isLabel(stmts[i + 1])) {
                    changed = true;
                    i++;
                }
            }
            return out;
        }

        List<StmtPtr> labelPass(const List<StmtPtr> &stmts, bool &changed) {
            List<StmtPtr> out;
            for (const StmtPtr &stmt: stmts) {
                if (isLabel(stmt) && !jumpsTo(stmts, stmt->name)) {
                    changed = true;
                    continue;
                }
                out.push_back(stmt);
            }
            return out;
        }

        // ---- block flattening ------------------------------------------

        // A region of the linear form is a statement sequence; the blocks left in
        // it exist only where a declaration needs a C++ scope, which is why this
        // pass folds every other block into its parent. A block *is* needed when
        // splicing it would move one of its declarations across a jump: C++
        // rejects a jump that skips the initialization of a variable in scope at
        // the label ([stmt.dcl]/3, MSVC C2362), and the linear form is full of
        // jumps.

        // Whether a jump to `jumpName` taken at index `at` would skip the
        // initialization of a declaration the splice brings into the parent's scope
        // and land past it - the one thing C++ rejects about the splice.
        bool jumpCrosses(const Str &jumpName, int at, const List<int> &decls,
                         const List<Str> &labelNames, const List<int> &labelAt) {
            for (int l = 0; l < (int) labelNames.size(); l++) {
                if (labelNames[l] != jumpName) continue;
                for (int d: decls) {
                    if (at < d && d <= labelAt[l]) return true;
                }
            }
            return false;
        }

        // Every jump inside `stmt` (each of them running after everything before the
        // top-level statement it sits in) tested against the spliced declarations.
        bool stmtCrosses(const StmtPtr &stmt, int at, const List<int> &decls,
                         const List<Str> &labelNames, const List<int> &labelAt) {
            if (!stmt) return false;
            if (isGoto(stmt) || isCondJump(stmt)) {
                return jumpCrosses(stmt->name, at, decls, labelNames, labelAt);
            }
            if (stmt->kind != StmtKind::Block) return false;
            for (const StmtPtr &inner: stmt->body) {
                if (stmtCrosses(inner, at, decls, labelNames, labelAt)) return true;
            }
            return false;
        }

        // Whether the block at `i` can be spliced into `stmts`: after the splice the
        // block's own declarations are in the parent's scope, so the splice is
        // legal exactly when no jump `J` and label `L` satisfy
        // `pos (J) < pos (D) <= pos (L)` for a declaration `D` it brings up.
        bool spliceIsSafe(const List<StmtPtr> &stmts, int i) {
            const StmtPtr &block = stmts[i];
            const int len = (int) block->body.size();
            if (len == 0) return true;

            List<int> decls;
            for (int k = 0; k < len; k++) {
                const StmtPtr &inner = block->body[k];
                if (inner && inner->kind == StmtKind::VarDecl) decls.push_back(i + k);
            }
            if (decls.empty()) return true;

            // Where a statement lands once the block's body takes its place.
            auto mergedIndex = [&](int p) { return p < i ? p : p + len - 1; };

            // Every label the sequence has at this level after the splice.
            List<Str> labelNames;
            List<int> labelAt;
            for (int p = 0; p < (int) stmts.size(); p++) {
                if (p == i) continue;
                if (isLabel(stmts[p])) {
                    labelNames.push_back(stmts[p]->name);
                    labelAt.push_back(mergedIndex(p));
                }
            }
            for (int k = 0; k < len; k++) {
                if (isLabel(block->body[k])) {
                    labelNames.push_back(block->body[k]->name);
                    labelAt.push_back(i + k);
                }
            }

            // ... and every jump that stays in it.
            for (int p = 0; p < (int) stmts.size(); p++) {
                if (p != i && stmtCrosses(stmts[p], mergedIndex(p), decls, labelNames, labelAt)) {
                    return false;
                }
            }
            for (int k = 0; k < len; k++) {
                if (stmtCrosses(block->body[k], i + k, decls, labelNames, labelAt)) return false;
            }
            return true;
        }

        List<StmtPtr> flattenPass(const List<StmtPtr> &stmts, bool &changed);

        // The same statement with its own nesting flattened. A copy keeps the tree
        // the caller handed in untouched.
        StmtPtr flattenInner(const StmtPtr &stmt, bool &changed) {
            if (!stmt || stmt->kind != StmtKind::Block) return stmt;
            auto flattened = std::make_shared<Stmt>(*stmt);
            flattened->body = flattenPass(stmt->body, changed);
            return flattened;
        }

        // Folds nested blocks into the parent sequence. Children come first: a
        // spliced child is what makes its parent's declarations cross jumps, so
        // the parent is judged on the body its children leave behind.
        List<StmtPtr> flattenPass(const List<StmtPtr> &stmts, bool &changed) {
            List<StmtPtr> flattened;
            for (const StmtPtr &stmt: stmts) flattened.push_back(flattenInner(stmt, changed));

            List<StmtPtr> out;
            for (int i = 0; i < (int) flattened.size(); i++) {
                const StmtPtr &stmt = flattened[i];
                if (!stmt) continue;
                if (stmt->kind == StmtKind::Block && spliceIsSafe(flattened, i)) {
                    for (const StmtPtr &inner: stmt->body) out.push_back(inner);
                    changed = true;
                    continue;
                }
                out.push_back(stmt);
            }
            return out;
        }

        // ---- slot hoisting ---------------------------------------------

        ast::ExprPtr nameExpr(const Str &name, const ast::SourcePos &pos) {
            auto expr = std::make_shared<ast::Expr>();
            expr->kind = ast::ExprKind::Name;
            expr->pos = pos;
            expr->text = name;
            return expr;
        }

        // ---- one scope per body -----------------------------------------
        //
        // The hoisting below moves *every* declaration of a body to the top of it, so a
        // body has one scope. That is what makes a name have to be unique *in the body*:
        // the language lets two scopes reuse a name (shadowing), and one flat C++ scope
        // cannot, so the second declaration of a name is renamed - and the uses that
        // resolve to it move with it, so a name never changes what it means
        // (impl_specs/linear-il.md, "the frame is flat"). A generated name carries the
        // `_sm_` prefix the language reserves for the compiler (`_sm_expr1`, `_sm_for1`),
        // so a rename is never mistaken for a name the program wrote.
        //
        // `reserved` is what the emitter has already put in the body's own C++ scope: the
        // parameters (and `self`), which are declared next to the hoisted storage.
        struct RenameScope {
            Dictionary<Str, Str> renamed; // the name as written -> the name to emit
        };

        // The name a shadowed declaration gets: `_sm_` and the original name, then the
        // counter *after an underscore* - so a rename can never collide with a name the
        // compiler generates itself (`_sm_expr1`, `_sm_base1`, `simse_sw_1`), which is the
        // one thing a reserved prefix alone does not buy: `base2` renamed to `_sm_base2`
        // would be the lowering's own place slot.
        static Str shadowName(const Str &name, const Dictionary<Str, bool> &used) {
            int n = 2;
            Str candidate = "_sm_" + name + "_" + std::to_string(n);
            while (used.count(candidate) > 0) {
                n++;
                candidate = "_sm_" + name + "_" + std::to_string(n);
            }
            return candidate;
        }

        // The innermost scope that renames this name, if any.
        static const Str *renamedTo(const List<RenameScope> &scopes, const Str &name) {
            for (int i = (int) scopes.size() - 1; i >= 0; i--) {
                auto found = scopes[i].renamed.find(name);
                if (found != scopes[i].renamed.end()) return &found->second;
            }
            return nullptr;
        }

        // Every name a lambda body binds: its parameters and the declarations anywhere
        // inside it. A use of one of those is the lambda's own wherever it stands, so the
        // enclosing scopes must not rewrite it, and the lambda's own pass names them.
        void collectBoundNames(const List<StmtPtr> &stmts, Dictionary<Str, bool> &bound) {
            for (const StmtPtr &stmt: stmts) {
                if (!stmt) continue;
                if (stmt->kind == StmtKind::VarDecl) bound[stmt->name] = true;
                collectBoundNames(stmt->body, bound);
                collectBoundNames(stmt->thenBody, bound);
                collectBoundNames(stmt->elseBody, bound);
            }
        }

        void renameInList(List<StmtPtr> &stmts, List<RenameScope> &scopes,
                          Dictionary<Str, bool> &used);
        void rewriteUses(List<StmtPtr> &stmts, List<RenameScope> &scopes,
                         Dictionary<Str, bool> &used, bool nameNested);
        void rewriteExpr(ast::ExprPtr &expr, List<RenameScope> &scopes,
                         Dictionary<Str, bool> &used);

        void rewriteNestedLists(Stmt &stmt, List<RenameScope> &scopes,
                                Dictionary<Str, bool> &used, bool nameNested) {
            List<StmtPtr> *nested[] = {&stmt.body, &stmt.thenBody, &stmt.elseBody};
            for (List<StmtPtr> *list: nested) {
                if (nameNested) renameInList(*list, scopes, used);
                else rewriteUses(*list, scopes, used, false);
            }
        }

        void rewriteExpr(ast::ExprPtr &expr, List<RenameScope> &scopes,
                         Dictionary<Str, bool> &used) {
            if (!expr) return;
            if (expr->kind == ast::ExprKind::Name) {
                if (const Str *mapped = renamedTo(scopes, expr->text)) expr->text = *mapped;
            }
            if (expr->kind == ast::ExprKind::Lambda) {
                // A lambda is a body of its own, so its own declarations are not this
                // body's to name - but a name it does not bind is captured from *this*
                // body, and that is the name the scopes decide.
                RenameScope inner;
                for (const Str &name: expr->paramNames) inner.renamed[name] = name;
                Dictionary<Str, bool> bound;
                collectBoundNames(expr->body, bound);
                for (const auto &entry: bound) inner.renamed[entry.first] = entry.first;
                scopes.push_back(inner);
                rewriteUses(expr->body, scopes, used, false);
                scopes.pop_back();
            }
            rewriteExpr(expr->lhs, scopes, used);
            rewriteExpr(expr->rhs, scopes, used);
            for (ast::ExprPtr &arg: expr->args) rewriteExpr(arg, scopes, used);
        }

        // One statement: its expressions rewritten, and the statement lists inside it
        // named by `renameInList` (a block, a branch, an arm - each is a scope of its
        // own) or, inside a lambda body, rewritten for uses only.
        void rewriteStmt(Stmt &stmt, List<RenameScope> &scopes, Dictionary<Str, bool> &used,
                         bool nameNested) {
            rewriteExpr(stmt.init, scopes, used);
            rewriteExpr(stmt.cond, scopes, used);
            rewriteExpr(stmt.target, scopes, used);
            rewriteExpr(stmt.value, scopes, used);
            rewriteExpr(stmt.returnValue, scopes, used);
            rewriteExpr(stmt.expr, scopes, used);
            rewriteNestedLists(stmt, scopes, used, nameNested);
        }

        void rewriteUses(List<StmtPtr> &stmts, List<RenameScope> &scopes,
                         Dictionary<Str, bool> &used, bool nameNested) {
            for (StmtPtr &stmt: stmts) {
                if (stmt) rewriteStmt(*stmt, scopes, used, nameNested);
            }
        }

        // One statement list: name its own declarations first - a use may stand before
        // the declaration it means (`hoisting.kt`), and the scope answers for the whole
        // list either way - then rewrite the list with that scope pushed.
        void renameInList(List<StmtPtr> &stmts, List<RenameScope> &scopes,
                          Dictionary<Str, bool> &used) {
            RenameScope scope;
            for (const StmtPtr &stmt: stmts) {
                if (!stmt || stmt->kind != StmtKind::VarDecl) continue;
                const Str original = stmt->name;
                Str emitted = original;
                if (used.count(original) > 0) {
                    emitted = shadowName(original, used);
                    scope.renamed[original] = emitted;
                }
                used[emitted] = true;
                stmt->name = emitted;
            }
            scopes.push_back(scope);
            rewriteUses(stmts, scopes, used, true);
            scopes.pop_back();
        }

        void renameShadowed(List<StmtPtr> &body, const List<Str> &reserved) {
            Dictionary<Str, bool> used;
            for (const Str &name: reserved) used[name] = true;
            List<RenameScope> scopes;
            renameInList(body, scopes, used);
        }

        // Whether a declaration is one the hoisting can move: a declaration has to be
        // writable bare, and that needs its *whole* type - `auto x;` is not a declaration,
        // a machine's `..T` has no spelling at all, and the inference leaves some slots
        // partly unknown (`*?`: a pointer to nothing it could name).
        static bool isSpellableType(const ast::TypeExpr *type) {
            if (!type) return false;
            switch (type->kind) {
                case ast::TypeKind::Named:
                case ast::TypeKind::Generic:
                    return !type->name.empty();
                case ast::TypeKind::IntLit:
                    return true;
                case ast::TypeKind::Reference:
                case ast::TypeKind::Pointer:
                    return isSpellableType(type->inner.get());
                case ast::TypeKind::Function:
                    if (!isSpellableType(type->returnType.get())) return false;
                    for (const ast::TypePtr &param: type->paramTypes) {
                        if (!isSpellableType(param.get())) return false;
                    }
                    return true;
                case ast::TypeKind::Yield:
                    return false;
            }
            return false;
        }

        static bool isHoistable(const Stmt &stmt) {
            return stmt.kind == StmtKind::VarDecl && isSpellableType(stmt.type.get());
        }

        // One statement list rewritten: every declaration becomes an assignment (when it
        // had an initializer) and its declaration is collected for the top of the body -
        // so the caller learns whether anything moved from `decls` alone. Blocks keep
        // their place (the folding is what deals with them); a lambda is a body of its
        // own, so the walk does not enter one. A declaration *already* at the top of the
        // list is not collected: it is where the hoisting puts one, so collecting it again
        // would make the pass report work it did not do.
        List<StmtPtr> hoistInList(const List<StmtPtr> &stmts, List<StmtPtr> &decls,
                                  bool atTop) {
            List<StmtPtr> out;
            for (const StmtPtr &stmt: stmts) {
                if (!stmt) continue;
                if (isHoistable(*stmt)) {
                    if (stmt->init) {
                        auto assignment = std::make_shared<Stmt>();
                        assignment->kind = StmtKind::Assign;
                        assignment->pos = stmt->pos;
                        assignment->op = "=";
                        assignment->target = nameExpr(stmt->name, stmt->pos);
                        assignment->value = stmt->init;
                        out.push_back(assignment);

                        auto declaration = std::make_shared<Stmt>(*stmt);
                        declaration->init = nullptr;
                        decls.push_back(declaration);
                        continue;
                    }
                    if (!atTop) {
                        // A declaration with nothing to initialize: it moves to the top
                        // for the same reason as the rest (a jump may not skip it - the
                        // default construction of a `Str` is an initialization too), and
                        // nothing is left where it stood.
                        decls.push_back(std::make_shared<Stmt>(*stmt));
                        continue;
                    }
                    // Already at the top: it is where the hoisting puts one, and it has
                    // nothing to move with it.
                    out.push_back(stmt);
                    continue;
                }
                if (stmt->kind == StmtKind::Block) {
                    auto block = std::make_shared<Stmt>(*stmt);
                    block->body = hoistInList(stmt->body, decls, false);
                    out.push_back(block);
                    continue;
                }
                out.push_back(stmt);
            }
            return out;
        }
    }

    Lowered simplifyBody(const List<StmtPtr> &body) {
        List<StmtPtr> current = body;
        bool any = false;
        bool changed = true;
        int guard = 0;
        while (changed && guard < 16) {
            guard++;
            changed = false;
            current = prunePass(current, changed);
            current = labelPass(current, changed);
            any = any || changed;
        }
        Lowered result;
        result.body = std::move(current);
        result.changed = any;
        return result;
    }

    Lowered flattenBlocks(const List<StmtPtr> &body) {
        bool changed = false;
        Lowered result;
        result.body = flattenPass(body, changed);
        result.changed = changed;
        return result;
    }

    Lowered hoistSlots(const List<StmtPtr> &body) {
        Lowered result;
        List<StmtPtr> decls;
        List<StmtPtr> rewritten = hoistInList(body, decls, true);
        if (decls.empty()) {
            result.body = std::move(rewritten);
            result.changed = false;
            return result;
        }
        List<StmtPtr> hoisted;
        for (const StmtPtr &decl: decls) hoisted.push_back(decl);
        for (StmtPtr &stmt: rewritten) hoisted.push_back(std::move(stmt));
        result.body = std::move(hoisted);
        result.changed = true;
        return result;
    }

    Lowered hoistSlots(const List<StmtPtr> &body, const List<Str> &reserved) {
        List<StmtPtr> renamed = body;
        renameShadowed(renamed, reserved);
        return hoistSlots(renamed);
    }

    List<StmtPtr> finishForEmission(const List<StmtPtr> &body) {
        return finishForEmission(body, List<Str>());
    }

    List<StmtPtr> finishForEmission(const List<StmtPtr> &body, const List<Str> &reserved) {
        List<StmtPtr> current = body;
        renameShadowed(current, reserved);
        bool canChange = true;
        int guard = 0;
        while (canChange && guard < 256) {
            guard++;
            canChange = false;
            Lowered hoisted = hoistSlots(current);
            current = std::move(hoisted.body);
            canChange = canChange || hoisted.changed;
            Lowered simplified = simplifyBody(current);
            current = std::move(simplified.body);
            canChange = canChange || simplified.changed;
            Lowered folded = flattenBlocks(current);
            current = std::move(folded.body);
            canChange = canChange || folded.changed;
        }
        return current;
    }
}
