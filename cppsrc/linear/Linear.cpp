#include "Linear.h"

#include "ExpressionLowering.h"
#include "Simplify.h"

#include <string>
#include <utility>

namespace linear {
    using ast::ExprKind;
    using ast::ExprPtr;
    using ast::Stmt;
    using ast::StmtKind;
    using ast::StmtPtr;

    namespace {
        // Where `break` / `continue` go in the current lowering context; empty
        // means the construct is not enclosing (sema already rejected those).
        struct Ctx {
            Str breakTo;
            Str continueTo;
        };

        class Lowerer {
        public:
            // Whether this pass actually lowered anything: the stages of
            // `lowerForEmission` run until a whole round changes nothing, so a pass
            // has to report that it found no work just as much as the work it did.
            bool changed = false;

            List<StmtPtr> run(const List<StmtPtr> &stmts) {
                List<StmtPtr> out;
                Ctx ctx;
                lowerStmts(stmts, ctx, out);
                return out;
            }

        private:
            // Label numbering is per body (L1, L2, ...): labels are function
            // scoped in C++, so a per-body counter cannot collide, and each
            // body gets the same numbering no matter where it is emitted.
            int next = 1;

            int nextId() { return next++; }
            static Str labelName(int id) { return Str("L") + std::to_string(id); }
            Str freshLabel() { return labelName(nextId()); }

            static StmtPtr make(StmtKind kind, const ast::SourcePos &pos) {
                auto stmt = std::make_shared<Stmt>();
                stmt->kind = kind;
                stmt->pos = pos;
                return stmt;
            }

            static StmtPtr labelStmt(const Str &name, const ast::SourcePos &pos) {
                StmtPtr stmt = make(StmtKind::Label, pos);
                stmt->name = name;
                return stmt;
            }

            static StmtPtr gotoStmt(const Str &name, const ast::SourcePos &pos) {
                StmtPtr stmt = make(StmtKind::Goto, pos);
                stmt->name = name;
                return stmt;
            }

            static StmtPtr condGotoStmt(StmtKind kind, const ExprPtr &cond, const Str &name,
                                        const ast::SourcePos &pos) {
                StmtPtr stmt = make(kind, pos);
                stmt->name = name;
                stmt->cond = cond;
                return stmt;
            }

            static StmtPtr blockStmt(List<StmtPtr> body, const ast::SourcePos &pos) {
                StmtPtr stmt = make(StmtKind::Block, pos);
                stmt->body = std::move(body);
                return stmt;
            }

            // A name expression, used as the hoisted switch subject.
            static ExprPtr nameExpr(const Str &name, const ast::SourcePos &pos) {
                auto expr = std::make_shared<ast::Expr>();
                expr->kind = ExprKind::Name;
                expr->pos = pos;
                expr->text = name;
                return expr;
            }

            static ExprPtr equalsExpr(const ExprPtr &left, const ExprPtr &right,
                                      const ast::SourcePos &pos) {
                auto expr = std::make_shared<ast::Expr>();
                expr->kind = ExprKind::Binary;
                expr->pos = pos;
                expr->text = "==";
                expr->lhs = left;
                expr->rhs = right;
                return expr;
            }

            void lowerStmts(const List<StmtPtr> &stmts, const Ctx &ctx, List<StmtPtr> &out) {
                for (const StmtPtr &stmt: stmts) {
                    if (!stmt) continue;
                    lowerStmt(*stmt, ctx, out);
                }
            }

            // A region needs its own C++ scope only when it declares a variable
            // at its own level: a jump may not bypass an initialization that is
            // still in scope at the target. Everything else is spliced flat into
            // the enclosing sequence, which keeps the emitted code compact.
            static bool declares(const List<StmtPtr> &stmts) {
                for (const StmtPtr &stmt: stmts) {
                    if (stmt && stmt->kind == StmtKind::VarDecl) return true;
                }
                return false;
            }

            void appendBody(List<StmtPtr> body, const ast::SourcePos &pos, List<StmtPtr> &out) {
                if (declares(body)) {
                    out.push_back(blockStmt(std::move(body), pos));
                    return;
                }
                for (StmtPtr &stmt: body) out.push_back(std::move(stmt));
            }

            // Whether an expression contains `&&` or `||` anywhere - including inside
            // a call's arguments, where the *value* form applies and nothing here can
            // decompose it.
            static bool containsShortCircuit(const ExprPtr &e) {
                if (!e) return false;
                if (e->kind == ExprKind::Binary && (e->text == "&&" || e->text == "||")) {
                    return true;
                }
                if (containsShortCircuit(e->lhs) || containsShortCircuit(e->rhs)) return true;
                for (const ExprPtr &arg: e->args) {
                    if (containsShortCircuit(arg)) return true;
                }
                return false;
            }

            // A condition that is nothing but boolean operators (`&&`, `||`, `!`) and
            // leaves with no short-circuit inside them: the conditions this pass can
            // decompose into jumps.
            static bool isDecomposable(const ExprPtr &e) {
                if (!e) return false;
                if (e->kind == ExprKind::Binary && (e->text == "&&" || e->text == "||")) {
                    return isDecomposable(e->lhs) && isDecomposable(e->rhs);
                }
                if (e->kind == ExprKind::Unary && e->text == "!") return isDecomposable(e->lhs);
                return !containsShortCircuit(e);
            }

            // Lowers a boolean condition into conditional jumps, one per leaf, with
            // `&&`/`||` evaluating short-circuit exactly as the `if`/`while` they came
            // from (impl_specs/linear-lowering.md, "Short-circuit operators").
            //
            // A leaf (`pkg != "rtl"`, `list.contains(x)`) is tested with whichever of
            // `IfTrue`/`IfFalse` matches its value, so no negation is spelled out and
            // an `if` that tested a whole `&&` chain keeps the label it would have
            // had. A leaf emits *both* jumps; `simplifyBody` then folds each pair into
            // the single conditional jump the emitter wants, and drops the labels the
            // chain no longer needs.
            void lowerCondition(const ExprPtr &cond, const Str &trueTarget, const Str &falseTarget,
                                const ast::SourcePos &pos, List<StmtPtr> &out) {
                if (cond->kind == ExprKind::Binary && (cond->text == "&&" || cond->text == "||")
                    && cond->lhs && cond->rhs) {
                    const Str mid = freshLabel();
                    if (cond->text == "&&") {
                        // Both operands must hold: the first one that does not jumps
                        // straight past the rest.
                        lowerCondition(cond->lhs, mid, falseTarget, pos, out);
                        out.push_back(labelStmt(mid, pos));
                        lowerCondition(cond->rhs, trueTarget, falseTarget, pos, out);
                    } else {
                        // The first operand that holds jumps straight to the target.
                        lowerCondition(cond->lhs, trueTarget, mid, pos, out);
                        out.push_back(labelStmt(mid, pos));
                        lowerCondition(cond->rhs, trueTarget, falseTarget, pos, out);
                    }
                    return;
                }
                if (cond->kind == ExprKind::Unary && cond->text == "!") {
                    // `!x` is `x` with its outcomes swapped: the test itself is never
                    // negated here, `IfTrue`/`IfFalse` covers both polarities.
                    lowerCondition(cond->lhs, falseTarget, trueTarget, pos, out);
                    return;
                }
                out.push_back(condGotoStmt(StmtKind::IfTrue, cond, trueTarget, pos));
                out.push_back(gotoStmt(falseTarget, pos));
            }

            void lowerStmt(const Stmt &stmt, const Ctx &ctx, List<StmtPtr> &out) {
                switch (stmt.kind) {
                    case StmtKind::If:
                        changed = true;
                        lowerIf(stmt, ctx, out);
                        return;
                    case StmtKind::While:
                        changed = true;
                        lowerWhile(stmt, ctx, out);
                        return;
                    case StmtKind::Switch:
                        changed = true;
                        lowerSwitch(stmt, ctx, out);
                        return;
                    case StmtKind::Break:
                        // Invalid outside a loop/switch; sema reports it before
                        // this pass runs, so keep the node for the emitter.
                        if (!ctx.breakTo.empty()) {
                            changed = true;
                            out.push_back(gotoStmt(ctx.breakTo, stmt.pos));
                            return;
                        }
                        break;
                    case StmtKind::Continue:
                        if (!ctx.continueTo.empty()) {
                            changed = true;
                            out.push_back(gotoStmt(ctx.continueTo, stmt.pos));
                            return;
                        }
                        break;
                    default:
                        break;
                }
                out.push_back(std::make_shared<Stmt>(stmt));
            }

            void lowerIf(const Stmt &stmt, const Ctx &ctx, List<StmtPtr> &out) {
                const Str thenLabel = freshLabel();
                const Str elseLabel = freshLabel();
                if (containsShortCircuit(stmt.cond) && isDecomposable(stmt.cond)) {
                    lowerCondition(stmt.cond, thenLabel, elseLabel, stmt.pos, out);
                } else {
                    out.push_back(condGotoStmt(StmtKind::IfTrue, stmt.cond, thenLabel, stmt.pos));
                    out.push_back(gotoStmt(elseLabel, stmt.pos));
                }
                out.push_back(labelStmt(thenLabel, stmt.pos));
                List<StmtPtr> thenOut;
                lowerStmts(stmt.thenBody, ctx, thenOut);
                appendBody(std::move(thenOut), stmt.pos, out);
                if (stmt.hasElse) {
                    const Str endLabel = freshLabel();
                    out.push_back(gotoStmt(endLabel, stmt.pos));
                    out.push_back(labelStmt(elseLabel, stmt.pos));
                    List<StmtPtr> elseOut;
                    lowerStmts(stmt.elseBody, ctx, elseOut);
                    appendBody(std::move(elseOut), stmt.pos, out);
                    out.push_back(labelStmt(endLabel, stmt.pos));
                } else {
                    out.push_back(labelStmt(elseLabel, stmt.pos));
                }
            }

            void lowerWhile(const Stmt &stmt, const Ctx &ctx, List<StmtPtr> &out) {
                const Str condLabel = freshLabel();
                const Str endLabel = freshLabel();
                Ctx bodyCtx;
                bodyCtx.breakTo = endLabel;
                bodyCtx.continueTo = condLabel;
                List<StmtPtr> bodyOut;
                lowerStmts(stmt.body, bodyCtx, bodyOut);
                out.push_back(labelStmt(condLabel, stmt.pos));
                if (containsShortCircuit(stmt.cond) && isDecomposable(stmt.cond)) {
                    // The body label is where a holding operand lands; the fold in
                    // `simplifyBody` removes it again when only one jump remains.
                    const Str bodyLabel = freshLabel();
                    lowerCondition(stmt.cond, bodyLabel, endLabel, stmt.pos, out);
                    out.push_back(labelStmt(bodyLabel, stmt.pos));
                } else {
                    out.push_back(condGotoStmt(StmtKind::IfFalse, stmt.cond, endLabel, stmt.pos));
                }
                appendBody(std::move(bodyOut), stmt.pos, out);
                out.push_back(gotoStmt(condLabel, stmt.pos));
                out.push_back(labelStmt(endLabel, stmt.pos));
                (void) ctx;
            }

            void lowerSwitch(const Stmt &stmt, const Ctx &ctx, List<StmtPtr> &out) {
                // The subject is hoisted so it is evaluated exactly once, as a
                // C++ `switch` subject would be.
                const int subjectId = nextId();
                const Str subject = Str("simse_sw_") + std::to_string(subjectId);
                const Str endLabel = labelName(nextId());
                List<Str> armLabels;
                for (const ast::SwitchCase &switchCase: stmt.cases) {
                    armLabels.push_back(labelName(nextId()));
                }

                StmtPtr subjectDecl = make(StmtKind::VarDecl, stmt.pos);
                subjectDecl->name = subject;
                subjectDecl->init = stmt.cond;
                out.push_back(subjectDecl);

                for (int i = 0; i < (int) stmt.cases.size(); i++) {
                    const ast::SwitchCase &switchCase = stmt.cases[i];
                    if (switchCase.isDefault || !switchCase.label) continue;
                    out.push_back(condGotoStmt(StmtKind::IfTrue,
                                               equalsExpr(nameExpr(subject, stmt.pos),
                                                          switchCase.label, switchCase.pos),
                                               armLabels[i], stmt.pos));
                }
                // No case matched: fall through to the default arm, or leave the
                // switch. The default arm keeps its source position, so an arm
                // before it still falls into it.
                Str fallback = endLabel;
                for (int i = 0; i < (int) stmt.cases.size(); i++) {
                    if (stmt.cases[i].isDefault) fallback = armLabels[i];
                }
                out.push_back(gotoStmt(fallback, stmt.pos));

                for (int i = 0; i < (int) stmt.cases.size(); i++) {
                    const ast::SwitchCase &switchCase = stmt.cases[i];
                    out.push_back(labelStmt(armLabels[i], switchCase.pos));
                    Ctx armCtx;
                    armCtx.breakTo = endLabel;
                    armCtx.continueTo = ctx.continueTo;
                    List<StmtPtr> armOut;
                    lowerStmts(switchCase.body, armCtx, armOut);
                    appendBody(std::move(armOut), switchCase.pos, out);
                }
                out.push_back(labelStmt(endLabel, stmt.pos));
            }
        };
    }

    Lowered lowerBody(const List<StmtPtr> &body) {
        Lowerer lowerer;
        Lowered result;
        result.body = lowerer.run(body);
        result.changed = lowerer.changed;
        return result;
    }

    bool isSlotName(const Str &name) {
        // `compare` rather than a `startsWith` helper: the RTL's `Str` is either
        // backing, and both have it. The lengths are the prefixes' own (8 and 9).
        return name.compare(0, 8, "_sm_expr") == 0 || name.compare(0, 9, "simse_sw_") == 0;
    }

    List<StmtPtr> lowerForEmission(const List<StmtPtr> &body) {
        List<StmtPtr> current = body;
        bool canChange = true;
        int guard = 0;
        // A round only removes statements (it never adds any), so the loop below
        // always terminates; the guard is there to bound a bug, not the work.
        while (canChange && guard < 256) {
            guard++;
            // The rewriting stages feed each other (`=>` is one stage):
            //
            //   linearize => simplify => extract expressions => linearize => ...
            //
            // and the round is over when none of them has anything left to do.
            bool canExtract = true;
            while (canExtract) {
                canExtract = false;
                Lowered lowered = lowerBody(current);
                current = std::move(lowered.body);
                canExtract = canExtract || lowered.changed;
                lowered = simplifyBody(current);
                current = std::move(lowered.body);
                canExtract = canExtract || lowered.changed;
                lowered = lowerExprs(current);
                current = std::move(lowered.body);
                canExtract = canExtract || lowered.changed;
            }
            // Folding blocks is the last step of every round: it is what hands the
            // next round a flatter body, which is the only way the stages above see
            // work again (a jump a block used to hide is a jump the peephole can
            // fold, and a folded jump can free a label).
            Lowered flattened = flattenBlocks(current);
            current = std::move(flattened.body);
            canChange = flattened.changed;
        }
        return current;
    }
}
