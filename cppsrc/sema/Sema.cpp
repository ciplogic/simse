#include "Sema.h"

#include <string>

using ast::DeclKind;
using ast::ExprKind;
using ast::StmtKind;
using ast::TypeKind;

namespace sema {
    namespace {
        struct ValueBinding {
            bool isMutable = true;
            bool checkAssign = false;
            // Declared or inferred type, when known; null otherwise.
            ast::TypePtr type;
        };

        bool isBuiltinType(const Str &name) {
            static const List<Str> builtins = {
                "Int", "Int8", "Int16", "Int32", "Int64",
                "Float32", "Float64", "Char", "Str", "Bool", "Unit",
                "List", "Array", "RawArray", "Opt", "Res",
                "Dictionary", "SmallVector", "PList"
            };
            for (const Str &builtin: builtins) {
                if (builtin == name) return true;
            }
            return false;
        }

        // Number of type parameters a built-in generic expects, or -1 when the
        // name is not a built-in generic.
        int builtinGenericArity(const Str &name) {
            if (name == "List" || name == "Array" || name == "RawArray"
                || name == "Opt" || name == "Res" || name == "PList") {
                return 1;
            }
            if (name == "Dictionary" || name == "SmallVector") {
                return 2;
            }
            return -1;
        }

        class Analyzer {
        public:
            Analyzer(const ast::Module &module, const Str &fileName)
                : module(module), file(fileName), hasImports(!module.imports.empty()) {
            }

            List<Str> diags;

            void run() {
                collect();
                for (const ast::DeclPtr &decl: module.declarations) {
                    analyzeDecl(*decl);
                }
            }

        private:
            const ast::Module &module;
            Str file;
            bool hasImports;

            // Top-level symbol tables.
            Dictionary<Str, const ast::Decl *> types;
            Dictionary<Str, List<const ast::Decl *>> functions;

            // Scopes. Value bindings and visible type parameters.
            List<Dictionary<Str, ValueBinding>> scopes;
            List<List<Str>> typeScopes;
            int loopDepth = 0;
            // `break` is valid in a loop or a switch; `continue` only in a loop.
            int breakDepth = 0;

            void diag(const common::SourcePos &pos, const Str &message) {
                diags.push_back(file + ":" + std::to_string(pos.line) + ":" + std::to_string(pos.column)
                                + ": " + message);
            }

            // ---- symbol collection ----------------------------------------

            void collect() {
                for (const ast::DeclPtr &decl: module.declarations) {
                    bool isFunction = decl->kind == DeclKind::Function;
                    bool nameTaken = types.count(decl->name) > 0 || functions.count(decl->name) > 0;
                    if (isFunction) {
                        if (types.count(decl->name) > 0) {
                            diag(decl->pos, "duplicate declaration '" + decl->name + "'");
                        } else {
                            functions[decl->name].push_back(decl.get());
                        }
                    } else {
                        if (nameTaken) {
                            diag(decl->pos, "duplicate declaration '" + decl->name + "'");
                        } else {
                            types[decl->name] = decl.get();
                        }
                    }
                }
            }

            // ---- scopes ---------------------------------------------------

            void pushScope() { scopes.emplace_back(); }
            void popScope() { scopes.pop_back(); }

            void declareValue(const Str &name, bool isMutable, bool checkAssign,
                              const ast::TypePtr &type = nullptr) {
                if (scopes.empty()) return;
                scopes.back()[name] = ValueBinding{isMutable, checkAssign, type};
            }

            const ValueBinding *lookupValue(const Str &name) {
                for (int i = (int) scopes.size() - 1; i >= 0; i--) {
                    auto it = scopes[i].find(name);
                    if (it != scopes[i].end()) return &it->second;
                }
                return nullptr;
            }

            void pushTypeScope() { typeScopes.emplace_back(); }
            void popTypeScope() { typeScopes.pop_back(); }

            void declareType(const Str &name) {
                if (typeScopes.empty()) return;
                typeScopes.back().push_back(name);
            }

            bool typeParamVisible(const Str &name) const {
                for (const List<Str> &scope: typeScopes) {
                    for (const Str &candidate: scope) {
                        if (candidate == name) return true;
                    }
                }
                return false;
            }

            // ---- type resolution ------------------------------------------

            void checkTypeName(const Str &name, const common::SourcePos &pos) {
                if (isBuiltinType(name) || types.count(name) > 0 || typeParamVisible(name)) {
                    return;
                }
                // Import contents are not modeled, so a name that might come from
                // an import is not reported (imports are conservative by design).
                if (hasImports) return;
                diag(pos, "unknown type '" + name + "'");
            }

            // Reports a mismatch between the number of type arguments written at
            // an instantiation and the declaration's type-parameter count. Unknown
            // names are skipped (conservative).
            void checkInstantiationArity(const Str &name, int argCount, const common::SourcePos &pos) {
                int expected = -1;
                auto it = types.find(name);
                if (it != types.end()) {
                    expected = (int) it->second->typeParams.size();
                } else {
                    expected = builtinGenericArity(name);
                }
                if (expected >= 0 && argCount != expected) {
                    diag(pos, "'" + name + "' expects " + std::to_string(expected)
                              + " type argument(s) but got " + std::to_string(argCount));
                }
            }

            void resolveType(const ast::TypeExpr &type) {
                switch (type.kind) {
                    case TypeKind::IntLit:
                        return;
                    case TypeKind::Named:
                        checkTypeName(type.name, type.pos);
                        return;
                    case TypeKind::Generic:
                        checkTypeName(type.name, type.pos);
                        checkInstantiationArity(type.name, (int) type.typeArgs.size(), type.pos);
                        for (const ast::TypePtr &arg: type.typeArgs) {
                            resolveType(*arg);
                        }
                        return;
                    case TypeKind::Reference:
                    case TypeKind::Pointer:
                        if (type.inner) resolveType(*type.inner);
                        return;
                    case TypeKind::Function:
                        for (const ast::TypePtr &param: type.paramTypes) {
                            resolveType(*param);
                        }
                        if (type.returnType) resolveType(*type.returnType);
                        return;
                }
            }

            // ---- declaration analysis -------------------------------------

            void analyzeDecl(const ast::Decl &decl) {
                switch (decl.kind) {
                    case DeclKind::DataClass:
                        pushTypeScope();
                        for (const Str &param: decl.typeParams) {
                            declareType(param);
                        }
                        pushScope();
                        declareValue("this", true, false);
                        for (const ast::Field &field: decl.fields) {
                            if (field.type) resolveType(*field.type);
                        }
                        for (const ast::DeclPtr &method: decl.methods) {
                            analyzeFunction(*method);
                        }
                        popScope();
                        popTypeScope();
                        return;
                    case DeclKind::Enum:
                        return;
                    case DeclKind::TypeAlias:
                        pushTypeScope();
                        for (const Str &param: decl.typeParams) {
                            declareType(param);
                        }
                        if (decl.targetType) resolveType(*decl.targetType);
                        popTypeScope();
                        return;
                    case DeclKind::Function:
                        analyzeFunction(decl);
                        return;
                }
            }

            void analyzeFunction(const ast::Decl &decl) {
                pushTypeScope();
                for (const Str &param: decl.functionTypeParams) {
                    declareType(param);
                }
                pushScope();
                declareValue("this", true, false);
                if (decl.hasReceiver && decl.receiverType) {
                    resolveType(*decl.receiverType);
                }
                for (const ast::Param &param: decl.params) {
                    if (param.type) resolveType(*param.type);
                    // Parameters are not `val` declarations, so reassigning one is
                    // never reported (under-report rather than risk a false hit).
                    declareValue(param.name, true, false, param.type);
                }
                if (decl.returnType) {
                    resolveType(*decl.returnType);
                }

                int savedLoopDepth = loopDepth;
                loopDepth = 0;
                for (const ast::StmtPtr &stmt: decl.body) {
                    analyzeStmt(*stmt);
                }
                loopDepth = savedLoopDepth;

                popScope();
                popTypeScope();
            }

            // ---- statement analysis ---------------------------------------

            void analyzeStmt(const ast::Stmt &stmt) {
                switch (stmt.kind) {
                    case StmtKind::VarDecl: {
                        if (stmt.init) analyzeExpr(*stmt.init);
                        if (stmt.type) resolveType(*stmt.type);
                        ast::TypePtr type = stmt.type;
                        if (!type && stmt.init) type = exprType(*stmt.init);
                        declareValue(stmt.name, stmt.isVar, true, type);
                        return;
                    }
                    case StmtKind::Assign: {
                        if (stmt.target) analyzeExpr(*stmt.target);
                        if (stmt.value) analyzeExpr(*stmt.value);
                        if (stmt.target && stmt.target->kind == ExprKind::Name) {
                            const ValueBinding *binding = lookupValue(stmt.target->text);
                            if (binding && binding->checkAssign && !binding->isMutable) {
                                diag(stmt.target->pos, "cannot assign to val '" + stmt.target->text + "'");
                            }
                        }
                        return;
                    }
                    case StmtKind::If:
                        if (stmt.cond) analyzeExpr(*stmt.cond);
                        pushScope();
                        for (const ast::StmtPtr &child: stmt.thenBody) {
                            analyzeStmt(*child);
                        }
                        popScope();
                        if (stmt.hasElse) {
                            pushScope();
                            for (const ast::StmtPtr &child: stmt.elseBody) {
                                analyzeStmt(*child);
                            }
                            popScope();
                        }
                        return;
                    case StmtKind::While:
                        if (stmt.cond) analyzeExpr(*stmt.cond);
                        pushScope();
                        loopDepth++;
                        breakDepth++;
                        for (const ast::StmtPtr &child: stmt.body) {
                            analyzeStmt(*child);
                        }
                        breakDepth--;
                        loopDepth--;
                        popScope();
                        return;
                    case StmtKind::Switch: {
                        if (stmt.cond) analyzeExpr(*stmt.cond);
                        breakDepth++;
                        for (const ast::SwitchCase &switchCase: stmt.cases) {
                            if (!switchCase.isDefault && switchCase.label) {
                                analyzeExpr(*switchCase.label);
                                if (!isConstantExpr(*switchCase.label)) {
                                    diag(switchCase.label->pos,
                                         "case label must be a constant expression");
                                }
                            }
                            pushScope();
                            for (const ast::StmtPtr &child: switchCase.body) {
                                analyzeStmt(*child);
                            }
                            popScope();
                        }
                        breakDepth--;
                        return;
                    }
                    case StmtKind::Return:
                        if (stmt.returnValue) analyzeExpr(*stmt.returnValue);
                        return;
                    case StmtKind::Break:
                        if (breakDepth == 0) diag(stmt.pos, "'break' outside a loop or switch");
                        return;
                    case StmtKind::Continue:
                        if (loopDepth == 0) diag(stmt.pos, "'continue' outside a loop");
                        return;
                    case StmtKind::ExprStmt:
                        if (stmt.expr) analyzeExpr(*stmt.expr);
                        return;
                }
            }

            // A `case` label must be a compile-time constant. We accept literals,
            // names, enum-qualified members, and unary negation of those; anything
            // clearly dynamic (a call, index, or the like) is rejected.
            bool isConstantExpr(const ast::Expr &expr) const {
                switch (expr.kind) {
                    case ExprKind::IntLit:
                    case ExprKind::FloatLit:
                    case ExprKind::StrLit:
                    case ExprKind::CharLit:
                    case ExprKind::BoolLit:
                    case ExprKind::Name:
                    case ExprKind::Member:
                        return true;
                    case ExprKind::Unary:
                        return expr.lhs && isConstantExpr(*expr.lhs);
                    default:
                        return false;
                }
            }

            // ---- expression analysis --------------------------------------

            void analyzeExpr(const ast::Expr &expr) {
                switch (expr.kind) {
                    case ExprKind::IntLit:
                    case ExprKind::FloatLit:
                    case ExprKind::StrLit:
                    case ExprKind::CharLit:
                    case ExprKind::BoolLit:
                    case ExprKind::NullLit:
                    case ExprKind::Name:
                        return;
                    case ExprKind::GenericName:
                        checkGenericNameArity(expr);
                        for (const ast::TypePtr &arg: expr.typeArgs) {
                            resolveType(*arg);
                        }
                        return;
                    case ExprKind::Member:
                        if (expr.lhs) analyzeExpr(*expr.lhs);
                        return;
                    case ExprKind::Call:
                        if (expr.lhs) analyzeExpr(*expr.lhs);
                        for (const ast::ExprPtr &arg: expr.args) {
                            analyzeExpr(*arg);
                        }
                        checkCallArity(expr);
                        checkExtensionCallArity(expr);
                        return;
                    case ExprKind::Index:
                        if (expr.lhs) analyzeExpr(*expr.lhs);
                        if (expr.rhs) analyzeExpr(*expr.rhs);
                        return;
                    case ExprKind::Unary:
                    case ExprKind::Ref:
                    case ExprKind::Deref:
                    case ExprKind::Copy:
                        if (expr.lhs) analyzeExpr(*expr.lhs);
                        return;
                    case ExprKind::Binary:
                        if (expr.lhs) analyzeExpr(*expr.lhs);
                        if (expr.rhs) analyzeExpr(*expr.rhs);
                        return;
                    case ExprKind::Lambda: {
                        pushScope();
                        for (int i = 0; i < (int) expr.paramNames.size(); i++) {
                            ast::TypePtr type;
                            if (i < (int) expr.paramTypes.size() && expr.paramTypes[i]) {
                                type = expr.paramTypes[i];
                                resolveType(*type);
                            }
                            declareValue(expr.paramNames[i], true, false, type);
                        }
                        int savedLoopDepth = loopDepth;
                        loopDepth = 0;
                        for (const ast::StmtPtr &child: expr.body) {
                            analyzeStmt(*child);
                        }
                        loopDepth = savedLoopDepth;
                        popScope();
                        return;
                    }
                }
            }

            void checkGenericNameArity(const ast::Expr &expr) {
                int argCount = (int) expr.typeArgs.size();
                auto it = functions.find(expr.text);
                if (it != functions.end()) {
                    for (const ast::Decl *function: it->second) {
                        if ((int) function->functionTypeParams.size() == argCount) return;
                    }
                    diag(expr.pos, "no overload of '" + expr.text + "' takes "
                                   + std::to_string(argCount) + " type argument(s)");
                    return;
                }
                checkInstantiationArity(expr.text, argCount, expr.pos);
            }

            void checkCallArity(const ast::Expr &call) {
                if (!call.lhs) return;
                bool generic = call.lhs->kind == ExprKind::GenericName;
                if (call.lhs->kind != ExprKind::Name && !generic) return;
                const Str &name = call.lhs->text;
                if (lookupValue(name) != nullptr) return; // shadowed by a local/param
                int argCount = (int) call.args.size();

                // A call to a known data class is a constructor call: the argument
                // count must match the declared field count exactly (constructors
                // take one argument per field, in order).
                auto typeIt = types.find(name);
                if (typeIt != types.end() && typeIt->second->kind == DeclKind::DataClass) {
                    int fieldCount = (int) typeIt->second->fields.size();
                    if (fieldCount != argCount) {
                        diag(call.pos, "data class '" + name + "' expects "
                                       + std::to_string(fieldCount) + " field(s) but got "
                                       + std::to_string(argCount));
                    }
                    return;
                }

                auto it = functions.find(name);
                if (it == functions.end()) return;
                int typeArgCount = generic ? (int) call.lhs->typeArgs.size() : 0;
                for (const ast::Decl *function: it->second) {
                    if ((int) function->params.size() != argCount) continue;
                    if (generic && (int) function->functionTypeParams.size() != typeArgCount) continue;
                    return;
                }
                diag(call.pos, "no overload of '" + name + "' takes "
                               + std::to_string(argCount) + " argument(s)");
            }

            // ---- extension (receiver) call resolution ----------------------

            bool isTypeParam(const Str &name, const List<Str> &typeParams) const {
                for (const Str &param: typeParams) {
                    if (param == name) return true;
                }
                return false;
            }

            // Simple structural unification of an extension receiver pattern
            // (which may mention the extension's type parameters) against the
            // actual receiver type. References/pointers on the receiver side are
            // auto-dereferenced, matching the List<T> member-call decision.
            bool unifyReceiver(const ast::TypeExpr &pattern, const ast::TypeExpr &actual,
                               const List<Str> &typeParams) {
                const ast::TypeExpr *actualPtr = &actual;
                if (pattern.kind != TypeKind::Reference && pattern.kind != TypeKind::Pointer) {
                    while ((actualPtr->kind == TypeKind::Reference
                            || actualPtr->kind == TypeKind::Pointer) && actualPtr->inner) {
                        actualPtr = actualPtr->inner.get();
                    }
                }
                const ast::TypeExpr &a = *actualPtr;
                switch (pattern.kind) {
                    case TypeKind::IntLit:
                        return a.kind == TypeKind::IntLit && a.text == pattern.text;
                    case TypeKind::Named:
                        if (isTypeParam(pattern.name, typeParams)) return true;
                        return a.kind == TypeKind::Named && a.name == pattern.name;
                    case TypeKind::Generic:
                        if (isTypeParam(pattern.name, typeParams)) return true;
                        if (a.kind != TypeKind::Generic || a.name != pattern.name) return false;
                        if (a.typeArgs.size() != pattern.typeArgs.size()) return false;
                        for (int i = 0; i < (int) a.typeArgs.size(); i++) {
                            if (!unifyReceiver(*pattern.typeArgs[i], *a.typeArgs[i], typeParams)) {
                                return false;
                            }
                        }
                        return true;
                    case TypeKind::Reference:
                        return a.kind == TypeKind::Reference && a.inner && pattern.inner
                               && unifyReceiver(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Pointer:
                        return a.kind == TypeKind::Pointer && a.inner && pattern.inner
                               && unifyReceiver(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Function:
                        return false;
                }
                return false;
            }

            // The receiver's type, when the checker tracks it (a local/parameter
            // name or a generic construction). Null when unknown.
            ast::TypePtr exprType(const ast::Expr &expr) {
                if (expr.kind == ExprKind::Name) {
                    const ValueBinding *binding = lookupValue(expr.text);
                    return binding ? binding->type : nullptr;
                }
                if (expr.kind == ExprKind::Call && expr.lhs
                    && expr.lhs->kind == ExprKind::GenericName) {
                    auto type = std::make_shared<ast::TypeExpr>();
                    type->kind = TypeKind::Generic;
                    type->name = expr.lhs->text;
                    type->typeArgs = expr.lhs->typeArgs;
                    return type;
                }
                return nullptr;
            }

            // Reports an arity mismatch only when a receiver-compatible extension
            // method exists but no overload takes the given value-argument count.
            // Unknown receiver types stay silent (conservative).
            void checkExtensionCallArity(const ast::Expr &call) {
                if (!call.lhs || call.lhs->kind != ExprKind::Member) return;
                const ast::Expr &callee = *call.lhs;
                auto it = functions.find(callee.text);
                if (it == functions.end()) return;
                ast::TypePtr actual = exprType(*callee.lhs);
                if (!actual) return;
                int argCount = (int) call.args.size();
                bool compatible = false;
                for (const ast::Decl *function: it->second) {
                    const ast::TypeExpr *receiver = nullptr;
                    int valueParamCount = 0;
                    if (function->hasReceiver && function->receiverType) {
                        receiver = function->receiverType.get();
                        valueParamCount = (int) function->params.size();
                    } else if (!function->params.empty() && function->params[0].name == "this"
                               && function->params[0].type) {
                        receiver = function->params[0].type.get();
                        valueParamCount = (int) function->params.size() - 1;
                    } else {
                        continue;
                    }
                    if (!unifyReceiver(*receiver, *actual, function->functionTypeParams)) continue;
                    compatible = true;
                    if (valueParamCount == argCount) return;
                }
                if (compatible) {
                    diag(call.pos, "no overload of '" + callee.text + "' takes "
                                   + std::to_string(argCount) + " argument(s)");
                }
            }
        };
    }

    List<Str> analyze(const ast::Module &module, const Str &fileName) {
        Analyzer analyzer(module, fileName);
        analyzer.run();
        return analyzer.diags;
    }
}
