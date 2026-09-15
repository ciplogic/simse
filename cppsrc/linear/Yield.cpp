#include "Yield.h"

#include "Linear.h"

#include <string>

namespace linear {
    using ast::Expr;
    using ast::ExprKind;
    using ast::ExprPtr;
    using ast::Stmt;
    using ast::StmtKind;
    using ast::StmtPtr;

    namespace {
        // ---- node builders ------------------------------------------------------

        ExprPtr nameNode(const Str &text) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::Name;
            node->text = text;
            return node;
        }

        ExprPtr memberNode(const ExprPtr &base, const Str &field) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::Member;
            node->text = field;
            node->lhs = base;
            return node;
        }

        ExprPtr thisMember(const Str &field) {
            return memberNode(nameNode("this"), field);
        }

        ExprPtr intLiteral(int value) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::IntLit;
            node->text = std::to_string(value);
            return node;
        }

        ExprPtr boolLiteral(bool value) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::BoolLit;
            node->boolValue = value;
            node->text = value ? "true" : "false";
            return node;
        }

        ExprPtr binaryNode(const Str &op, const ExprPtr &lhs, const ExprPtr &rhs) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::Binary;
            node->text = op;
            node->lhs = lhs;
            node->rhs = rhs;
            return node;
        }

        ExprPtr derefNode(const ExprPtr &inner) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::Deref;
            node->lhs = inner;
            return node;
        }

        ExprPtr callNode(const ExprPtr &callee, const List<ExprPtr> &args) {
            auto node = std::make_shared<Expr>();
            node->kind = ExprKind::Call;
            node->lhs = callee;
            node->args = args;
            return node;
        }

        // `Opt<T>.some(value)` / `Opt<T>.none()`: a static call on the RTL's optional,
        // which the emitter already spells (`Opt<Int>::some(...)`).
        ExprPtr optionalCall(const ast::TypePtr &elementType, const Str &method,
                             const List<ExprPtr> &args) {
            auto base = std::make_shared<Expr>();
            base->kind = ExprKind::GenericName;
            base->text = "Opt";
            base->typeArgs.push_back(elementType);
            return callNode(memberNode(base, method), args);
        }

        ast::TypePtr namedType(const Str &text) {
            auto node = std::make_shared<ast::TypeExpr>();
            node->kind = ast::TypeKind::Named;
            node->name = text;
            return node;
        }

        StmtPtr labelStmt(const Str &text) {
            auto node = std::make_shared<Stmt>();
            node->kind = StmtKind::Label;
            node->name = text;
            return node;
        }

        StmtPtr jumpWhen(const ExprPtr &cond, const Str &text) {
            auto node = std::make_shared<Stmt>();
            node->kind = StmtKind::IfTrue;
            node->cond = cond;
            node->name = text;
            return node;
        }

        StmtPtr assignStmt(const ExprPtr &target, const ExprPtr &value) {
            auto node = std::make_shared<Stmt>();
            node->kind = StmtKind::Assign;
            node->op = "=";
            node->target = target;
            node->value = value;
            return node;
        }

        StmtPtr exprStmt(const ExprPtr &value) {
            auto node = std::make_shared<Stmt>();
            node->kind = StmtKind::ExprStmt;
            node->expr = value;
            return node;
        }

        StmtPtr returnStmt(const ExprPtr &value) {
            auto node = std::make_shared<Stmt>();
            node->kind = StmtKind::Return;
            node->returnValue = value;
            return node;
        }

        // The labels this pass makes, kept apart from the lowering's `L<n>`.
        Str yieldLabel(int branch) {
            return Str("LY") + std::to_string(branch);
        }
        const char *kEndLabel = "LYend";
        const char *kBranchField = "branch";
        // The receiver of an extension function lives in the instance under this name
        // (`linear::yieldReceiverField` is how the emitter learns it).
        const char *kReceiverField = "_sm_self";

        // The type of the machine's receiver field: it holds exactly what the emitted
        // function's `self` parameter holds - a *value* receiver arrives as a pointer
        // (`T* self`), a handle as itself.
        ast::TypePtr receiverFieldType(const ast::TypeExpr &type) {
            if (type.kind == ast::TypeKind::Reference || type.kind == ast::TypeKind::Pointer) {
                return std::make_shared<ast::TypeExpr>(type);
            }
            auto pointer = std::make_shared<ast::TypeExpr>();
            pointer->kind = ast::TypeKind::Pointer;
            pointer->inner = std::make_shared<ast::TypeExpr>(type);
            return pointer;
        }

        // Whether a receiver is a *handle*: a bare `this` is then already the value the
        // caller passed, while a value receiver's `this` has to be read back out of the
        // pointer the machine holds.
        bool receiverIsHandle(const ast::TypeExpr &type) {
            return type.kind == ast::TypeKind::Reference || type.kind == ast::TypeKind::Pointer;
        }

        // ---- the machine --------------------------------------------------------

        class Machinery {
        public:
            Machinery(const ast::Decl &decl, const ast::TypePtr &elementType,
                      const Str &valueTypeText)
                : decl(decl), elementType(elementType), valueTypeText(valueTypeText) {}

            Yielded run(const List<StmtPtr> &body) {
                Yielded yielded;
                collectFields(body, yielded);
                if (!error.empty()) {
                    yielded.error = error;
                    return yielded;
                }
                // `next()`: the optional form. `advance()`: the same machine without the
                // copy - it writes through the caller's pointer and says whether there
                // was a value.
                yielded.methods.push_back(method("next", false, body));
                if (!valueTypeText.empty()) {
                    yielded.methods.push_back(method("advance", true, body));
                }
                if (!error.empty()) yielded.error = error;
                return yielded;
            }

        private:
            const ast::Decl &decl;
            ast::TypePtr elementType;
            Str valueTypeText; // the type of `advance`'s value parameter
            Dictionary<Str, ast::TypePtr> fieldTypes;
            List<Str> fieldOrder;
            int yields = 0;
            Str error;

            void fail(const Str &message) {
                if (error.empty()) error = message;
            }

            // The fields: `branch`, the receiver (an extension function's `this` has to
            // cross a yield like anything else), the parameters, and every local the body
            // declares that is not the lowering's own storage (those are per-statement and
            // are re-initialised on every entry, so they stay locals of the method).
            void collectFields(const List<StmtPtr> &body, Yielded &yielded) {
                ast::Field branch;
                branch.name = kBranchField;
                branch.isVar = true;
                branch.type = namedType("Int");
                fieldTypes[branch.name] = branch.type;
                fieldOrder.push_back(branch.name);

                if (decl.receiverType) {
                    fieldTypes[kReceiverField] = receiverFieldType(*decl.receiverType);
                    fieldOrder.push_back(kReceiverField);
                }

                for (const ast::Param &param : decl.params) {
                    if (param.name == "this") {
                        // A receiver written as a parameter *is* the `this` of the body,
                        // and `this` cannot name a C++ member: the receiver-form spelling
                        // (`fun T.name`) is the one that can yield for now.
                        fail("yield: a `this` parameter cannot be a field; write the "
                             "receiver before the name (`fun T.name`) instead");
                        return;
                    }
                    if (fieldTypes.count(param.name) > 0) continue;
                    if (!param.type) {
                        fail("yield: the parameter '" + param.name + "' has no type");
                        return;
                    }
                    fieldTypes[param.name] = param.type;
                    fieldOrder.push_back(param.name);
                }
                collectLocals(body);
                if (!error.empty()) return;
                for (const Str &field : fieldOrder) {
                    ast::Field entry;
                    entry.name = field;
                    entry.isVar = true;
                    entry.type = fieldTypes[field];
                    yielded.fields.push_back(entry);
                }
            }

            void collectLocals(const List<StmtPtr> &body) {
                for (const StmtPtr &stmtPtr : body) {
                    if (!stmtPtr) continue;
                    const Stmt &stmt = *stmtPtr;
                    if (stmt.kind == StmtKind::VarDecl && !isSlotName(stmt.name)) {
                        if (fieldTypes.count(stmt.name) == 0) {
                            if (!stmt.type) {
                                // A local that lives across a yield must be a field, and a
                                // field needs a type - the type pass spells it, so an
                                // untyped one here is a gap in the body, not in this pass.
                                // One gap has a name of its own: the machine a `for`
                                // iterates is created by a call, and a machine type is the
                                // class that call's own function got (`..T` is not a value
                                // type), so a nested loop would need the machine's class
                                // to survive a yield - which is the one thing a field
                                // cannot be named from here.
                                if (stmt.name.compare(0, 7, "_sm_for") == 0
                                    || stmt.name.compare(0, 8, "_sm_step") == 0) {
                                    fail("yield: a `for` over a machine cannot cross a yield "
                                         "(the machine a call creates has no type to make a "
                                         "field of); collect the values into a `List` first");
                                    return;
                                }
                                fail("yield: the local '" + stmt.name
                                     + "' has no type to make a field of");
                                return;
                            }
                            fieldTypes[stmt.name] = stmt.type;
                            fieldOrder.push_back(stmt.name);
                        }
                    }
                    collectLocals(stmt.body);
                    collectLocals(stmt.thenBody);
                    collectLocals(stmt.elseBody);
                }
            }

            // One method of the machine. The body is the same statements either way:
            // only what a yield *does* with the value differs.
            YieldMethod method(const Str &name, bool byReference, const List<StmtPtr> &body) {
                YieldMethod method;
                method.name = name;
                byReferenceMethod = byReference;
                if (byReference) {
                    ast::Param param;
                    param.name = "value";
                    auto pointer = std::make_shared<ast::TypeExpr>();
                    pointer->kind = ast::TypeKind::Pointer;
                    pointer->inner = elementType;
                    param.type = pointer;
                    method.params.push_back(param);
                }
                yields = 0;
                List<StmtPtr> rewritten;
                for (const StmtPtr &stmt : body) statements(stmt, rewritten);
                // The hoisted storage first - the declarations the lowering moved to the top
                // of the body, which stay locals of the method (they are per-statement, so
                // they never have to survive a call). They have to precede the dispatcher:
                // a jump that skips a declaration is what C++ refuses (C2362), and for a
                // generic function a declaration is `T`, i.e. non-trivial for `Str` and
                // friends. So the dispatcher goes *after* them.
                int first = 0;
                while (first < (int) rewritten.size() && isLocalDeclaration(rewritten[first])) {
                    method.body.push_back(rewritten[first]);
                    first++;
                }
                method.body.push_back(jumpWhen(binaryNode("==", thisMember(kBranchField),
                                                          intLiteral(-1)),
                                               kEndLabel));
                for (int i = 1; i <= yields; i++) {
                    method.body.push_back(
                            jumpWhen(binaryNode("==", thisMember(kBranchField), intLiteral(i)),
                                     yieldLabel(i)));
                }
                for (int i = first; i < (int) rewritten.size(); i++) {
                    method.body.push_back(rewritten[i]);
                }
                method.body.push_back(labelStmt(kEndLabel));
                method.body.push_back(assignStmt(thisMember(kBranchField), intLiteral(-1)));
                method.body.push_back(returnStmt(finishValue()));
                method.yieldCount = yields;
                return method;
            }

            // Whether a statement is one of the method's own declarations: the lowering's
            // storage (`_sm_expr<n>`), declared without an initializer at the top of the
            // body (`isSlotName` is the one place that is stated). A user's local became a
            // field, so this is exactly the set that stays with the method.
            static bool isLocalDeclaration(const StmtPtr &stmt) {
                if (!stmt || stmt->kind != StmtKind::VarDecl) return false;
                if (!isSlotName(stmt->name)) return false;
                return stmt->init == nullptr;
            }

            // What a finished machine answers: an empty optional, or `false` when the
            // value is handed back through the caller's pointer.
            ExprPtr finishValue() const {
                return byReferenceMethod ? boolLiteral(false)
                                         : optionalCall(elementType, "none", {});
            }

            void statements(const StmtPtr &stmtPtr, List<StmtPtr> &out) {
                if (!stmtPtr || !error.empty()) return;
                const Stmt &stmt = *stmtPtr;
                switch (stmt.kind) {
                    case StmtKind::Yield: {
                        const int branch = ++yields;
                        if (byReferenceMethod) {
                            // `*value = e; return true;`
                            out.push_back(assignStmt(derefNode(nameNode("value")), expr(stmt.expr)));
                            out.push_back(assignStmt(thisMember(kBranchField), intLiteral(branch)));
                            out.push_back(returnStmt(boolLiteral(true)));
                        } else {
                            out.push_back(assignStmt(thisMember(kBranchField), intLiteral(branch)));
                            out.push_back(returnStmt(
                                    optionalCall(elementType, "some", {expr(stmt.expr)})));
                        }
                        out.push_back(labelStmt(yieldLabel(branch)));
                        return;
                    }
                    case StmtKind::Return: {
                        // A `return` in a yielding body is `yield break`: the machine is
                        // finished. A value (which the `..T` signature does not allow)
                        // is still evaluated, so nothing silently disappears.
                        if (stmt.returnValue) out.push_back(exprStmt(expr(stmt.returnValue)));
                        out.push_back(assignStmt(thisMember(kBranchField), intLiteral(-1)));
                        out.push_back(returnStmt(finishValue()));
                        return;
                    }
                    case StmtKind::VarDecl: {
                        // The lowering's own storage stays a local of the method: it is
                        // per-statement, so it is re-initialised on every entry and never
                        // has to survive a yield.
                        if (isSlotName(stmt.name)) {
                            auto copy = std::make_shared<Stmt>(stmt);
                            copy->init = expr(stmt.init);
                            out.push_back(copy);
                            return;
                        }
                        // Everything else is a field now; its initializer runs where it
                        // was, which is on the way to the first yield (a machine that
                        // resumes past it does not run it again).
                        if (stmt.init) {
                            out.push_back(assignStmt(thisMember(stmt.name), expr(stmt.init)));
                        }
                        return;
                    }
                    case StmtKind::Assign: {
                        out.push_back(copyWith(stmtPtr, expr(stmt.target), expr(stmt.value)));
                        return;
                    }
                    case StmtKind::ExprStmt: {
                        auto copy = std::make_shared<Stmt>(stmt);
                        copy->expr = expr(stmt.expr);
                        out.push_back(copy);
                        return;
                    }
                    case StmtKind::IfTrue:
                    case StmtKind::IfFalse: {
                        auto copy = std::make_shared<Stmt>(stmt);
                        copy->cond = expr(stmt.cond);
                        out.push_back(copy);
                        return;
                    }
                    case StmtKind::Block: {
                        auto copy = std::make_shared<Stmt>(stmt);
                        List<StmtPtr> inner;
                        for (const StmtPtr &child : stmt.body) statements(child, inner);
                        copy->body = inner;
                        out.push_back(copy);
                        return;
                    }
                    default:
                        // Labels and gotos are the control flow, and `break`/`continue`
                        // are gone by now.
                        out.push_back(stmtPtr);
                        return;
                }
            }

            static StmtPtr copyWith(const StmtPtr &stmt, const ExprPtr &target,
                                    const ExprPtr &value) {
                auto copy = std::make_shared<Stmt>(*stmt);
                copy->target = target;
                copy->value = value;
                return copy;
            }

            // A name that is a field is read and written as a field of the machine, so
            // the values live in the instance across calls. Everything else (the
            // lowering's temporaries, statics, calls) is left as it was.
            //
            // `base` says the node is the receiver of a member or an index (or a callee),
            // where the *field* is what is wanted: the spelling helpers dereference a
            // pointer field where they have to (`this._sm_self->size()`,
            // `(*this._sm_self)[i]`). Anywhere else the language means the object behind
            // the receiver, so a value receiver's `this` is read back out of it.
            ExprPtr expr(const ExprPtr &node, bool base = false) {
                if (!node || !error.empty()) return node;
                if (node->kind == ExprKind::Name) {
                    if (node->text == "this") {
                        ExprPtr self = thisMember(kReceiverField);
                        if (!base && decl.receiverType && !receiverIsHandle(*decl.receiverType)) {
                            return derefNode(self);
                        }
                        return self;
                    }
                    if (fieldTypes.count(node->text) > 0) {
                        return memberNode(nameNode("this"), node->text);
                    }
                    return node;
                }
                auto copy = std::make_shared<Expr>(*node);
                // A member's or an index's receiver is a *place*, not a value: `this`
                // there stays the field (see above).
                const bool bases = node->kind == ExprKind::Member || node->kind == ExprKind::Index;
                copy->lhs = expr(node->lhs, bases);
                copy->rhs = expr(node->rhs);
                for (int i = 0; i < (int) node->args.size(); i++) {
                    copy->args[i] = expr(node->args[i]);
                }
                return copy;
            }

            bool byReferenceMethod = false;
        };
    }

    bool hasYield(const List<ast::StmtPtr> &body) {
        for (const StmtPtr &stmtPtr : body) {
            if (!stmtPtr) continue;
            const Stmt &stmt = *stmtPtr;
            if (stmt.kind == StmtKind::Yield) return true;
            if (hasYield(stmt.body) || hasYield(stmt.thenBody) || hasYield(stmt.elseBody)) {
                return true;
            }
        }
        return false;
    }

    Yielded lowerYield(const ast::Decl &decl, const ast::TypePtr &elementType,
                       const List<ast::StmtPtr> &linearBody, const Str &valueTypeText) {
        Machinery machinery(decl, elementType, valueTypeText);
        return machinery.run(linearBody);
    }

    Str yieldReceiverField() {
        return Str(kReceiverField);
    }
}
