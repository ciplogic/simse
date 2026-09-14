#include "ExpressionLowering.h"

#include <string>
#include <utility>

namespace linear {
    using ast::ExprKind;
    using ast::ExprPtr;
    using ast::SourcePos;
    using ast::Stmt;
    using ast::StmtKind;
    using ast::StmtPtr;

    namespace {
        // A literal, a name, a qualified name (`Res<T>`), or a lambda. A lambda's
        // own body is a separate body: the same passes lower it when it is emitted.
        bool isSimple(const ExprPtr &e) {
            switch (e->kind) {
                case ExprKind::IntLit:
                case ExprKind::FloatLit:
                case ExprKind::StrLit:
                case ExprKind::CharLit:
                case ExprKind::BoolLit:
                case ExprKind::NullLit:
                case ExprKind::Name:
                case ExprKind::GenericName:
                case ExprKind::Lambda:
                    return true;
                default:
                    return false;
            }
        }

        // An lvalue path: never bound to a value temporary (see the header). `Deref`
        // counts even when its pointer is a temporary: it still names a place the
        // emitter passes on as a reference.
        bool isPlace(const ExprPtr &e) {
            switch (e->kind) {
                case ExprKind::Name:
                case ExprKind::GenericName:
                case ExprKind::Deref:
                    return true;
                case ExprKind::Member:
                case ExprKind::Index:
                    return e->lhs && isPlace(e->lhs);
                default:
                    return false;
            }
        }

        // `&&`, `||` (and a future `?:`): operands that may not even be evaluated.
        bool isShortCircuit(const ExprPtr &e) {
            return e->kind == ExprKind::Binary && (e->text == "&&" || e->text == "||");
        }

        // Where an expression sits, which decides how much of it survives:
        // `Root` is the statement's own expression, `Value` is an operand, and
        // `Path` is a position that must stay a place (an assignment target, the
        // operand of `&`/`*`).
        enum class Slot { Root, Value, Path };

        class Flattener {
        public:
            List<StmtPtr> run(const List<StmtPtr> &stmts) {
                List<StmtPtr> out;
                walkStmts(stmts, out);
                return out;
            }

        private:
            // Per body, like the label counter in the linear pass.
            int next = 1;
            // The temporaries the statement being rewritten needs, in evaluation
            // order; they are emitted in front of it.
            List<StmtPtr> temps;

            Str freshTemp() { return Str("_sm_expr") + std::to_string(next++); }

            static ExprPtr nameExpr(const Str &name, const SourcePos &pos) {
                auto expr = std::make_shared<ast::Expr>();
                expr->kind = ExprKind::Name;
                expr->pos = pos;
                expr->text = name;
                return expr;
            }

            static StmtPtr tempDecl(const Str &name, const ExprPtr &init, const SourcePos &pos) {
                auto stmt = std::make_shared<Stmt>();
                stmt->kind = StmtKind::VarDecl;
                stmt->pos = pos;
                stmt->isVar = false;
                stmt->name = name;
                stmt->init = init;
                return stmt;
            }

            static StmtPtr blockStmt(List<StmtPtr> body, const SourcePos &pos) {
                auto stmt = std::make_shared<Stmt>();
                stmt->kind = StmtKind::Block;
                stmt->pos = pos;
                stmt->body = std::move(body);
                return stmt;
            }

            // Binds a value expression to a fresh temporary and returns its name.
            ExprPtr bind(const ExprPtr &e) {
                const Str name = freshTemp();
                temps.push_back(tempDecl(name, e, e->pos));
                return nameExpr(name, e->pos);
            }

            List<ExprPtr> flatList(const List<ExprPtr> &exprs) {
                List<ExprPtr> out;
                for (const ExprPtr &expr: exprs) out.push_back(flat(expr, Slot::Value));
                return out;
            }

            // A callee keeps its shape: `f`, `Res<T>.ok`, and a member call's
            // receiver path (`a[i].f(...)` stays a call on `a[i]`).
            ExprPtr flatCallee(const ExprPtr &e) {
                if (!e) return e;
                if (e->kind == ExprKind::Member) return flat(e, Slot::Path);
                if (e->kind == ExprKind::Name || e->kind == ExprKind::GenericName) return e;
                return flat(e, Slot::Value);
            }

            // A place stays a place; anything that produces a value is flattened as
            // a value (and bound if it is deeper than one operation).
            ExprPtr pathOrValue(const ExprPtr &e) {
                if (!e) return e;
                return isPlace(e) ? flat(e, Slot::Path) : flat(e, Slot::Value);
            }

            // The same node with its operands flattened. The copy is shallow and the
            // original is never touched.
            ExprPtr rebuild(const ExprPtr &e) {
                auto fresh = std::make_shared<ast::Expr>(*e);
                switch (e->kind) {
                    case ExprKind::Member:
                        fresh->lhs = pathOrValue(e->lhs);
                        break;
                    case ExprKind::Index:
                        fresh->lhs = pathOrValue(e->lhs);
                        fresh->rhs = flat(e->rhs, Slot::Value);
                        break;
                    case ExprKind::Call:
                        fresh->lhs = flatCallee(e->lhs);
                        fresh->args = flatList(e->args);
                        break;
                    case ExprKind::Binary:
                        fresh->lhs = flat(e->lhs, Slot::Value);
                        fresh->rhs = flat(e->rhs, Slot::Value);
                        break;
                    case ExprKind::Unary:
                    case ExprKind::Copy:
                        fresh->lhs = flat(e->lhs, Slot::Value);
                        break;
                    case ExprKind::Ref:
                    case ExprKind::Deref:
                        // `&x` boxes and `*x` borrows: both keep a place in place, or
                        // the address of a temporary would be taken.
                        fresh->lhs = pathOrValue(e->lhs);
                        break;
                    default:
                        break;
                }
                return fresh;
            }

            ExprPtr flat(const ExprPtr &e, Slot slot) {
                if (!e) return e;
                if (isSimple(e) || isShortCircuit(e)) return e;
                const ExprPtr built = rebuild(e);
                if (slot != Slot::Value) return built;
                if (isSimple(built) || isPlace(built)) return built;
                return bind(built);
            }

            // The statement plus the temporaries its expressions needed, scoped so a
            // jump can never cross one of them.
            StmtPtr withTemps(const StmtPtr &stmt) {
                if (temps.empty()) return stmt;
                temps.push_back(stmt);
                List<StmtPtr> body = std::move(temps);
                temps.clear();
                return blockStmt(std::move(body), stmt->pos);
            }

            void walkStmts(const List<StmtPtr> &stmts, List<StmtPtr> &out) {
                for (const StmtPtr &stmt: stmts) walkStmt(stmt, out);
            }

            void walkStmt(const StmtPtr &stmt, List<StmtPtr> &out) {
                if (!stmt) return;
                switch (stmt->kind) {
                    case StmtKind::Block: {
                        List<StmtPtr> inner;
                        walkStmts(stmt->body, inner);
                        out.push_back(blockStmt(std::move(inner), stmt->pos));
                        return;
                    }
                    case StmtKind::VarDecl: {
                        // The temporaries stay in this scope: the declared name is
                        // visible for the rest of the region. A region that declares
                        // anything is already a C++ block (`linear::lowerBody`),
                        // which is what keeps a jump from crossing them.
                        temps.clear();
                        auto fresh = std::make_shared<Stmt>(*stmt);
                        fresh->init = flat(stmt->init, Slot::Root);
                        for (const StmtPtr &temp: temps) out.push_back(temp);
                        out.push_back(fresh);
                        return;
                    }
                    case StmtKind::IfTrue:
                    case StmtKind::IfFalse: {
                        temps.clear();
                        const ExprPtr cond = flat(stmt->cond, Slot::Root);
                        auto fresh = std::make_shared<Stmt>(*stmt);
                        fresh->cond = cond;
                        out.push_back(withTemps(fresh));
                        return;
                    }
                    case StmtKind::Assign: {
                        temps.clear();
                        auto fresh = std::make_shared<Stmt>(*stmt);
                        fresh->target = flat(stmt->target, Slot::Path);
                        fresh->value = flat(stmt->value, Slot::Root);
                        out.push_back(withTemps(fresh));
                        return;
                    }
                    case StmtKind::Return: {
                        temps.clear();
                        auto fresh = std::make_shared<Stmt>(*stmt);
                        fresh->returnValue = flat(stmt->returnValue, Slot::Root);
                        out.push_back(withTemps(fresh));
                        return;
                    }
                    case StmtKind::ExprStmt: {
                        temps.clear();
                        auto fresh = std::make_shared<Stmt>(*stmt);
                        fresh->expr = flat(stmt->expr, Slot::Root);
                        out.push_back(withTemps(fresh));
                        return;
                    }
                    default:
                        // Label, Goto: no expressions. If/While/Switch/Break/Continue
                        // cannot appear here (linear::lowerBody removed them).
                        out.push_back(stmt);
                        return;
                }
            }
        };
    }

    List<StmtPtr> lowerExprs(const List<StmtPtr> &body) {
        Flattener flattener;
        return flattener.run(body);
    }
}
