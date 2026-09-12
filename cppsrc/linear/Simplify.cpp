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

        bool jumpsTo(const List<StmtPtr> &stmts, const Str &name) {
            for (const StmtPtr &stmt: stmts) {
                if (stmt && (isGoto(stmt) || isCondJump(stmt)) && stmt->name == name) return true;
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
    }

    List<StmtPtr> simplifyBody(const List<StmtPtr> &body) {
        List<StmtPtr> current = body;
        bool changed = true;
        int guard = 0;
        while (changed && guard < 16) {
            guard++;
            changed = false;
            current = prunePass(current, changed);
            current = labelPass(current, changed);
        }
        return current;
    }
}
