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

            void declareValue(const Str &name, bool isMutable, bool checkAssign) {
                if (scopes.empty()) return;
                scopes.back()[name] = ValueBinding{isMutable, checkAssign};
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

            void resolveType(const ast::TypeExpr &type) {
                switch (type.kind) {
                    case TypeKind::IntLit:
                        return;
                    case TypeKind::Named:
                        checkTypeName(type.name, type.pos);
                        return;
                    case TypeKind::Generic:
                        checkTypeName(type.name, type.pos);
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
                    declareValue(param.name, true, false);
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
                    case StmtKind::VarDecl:
                        if (stmt.init) analyzeExpr(*stmt.init);
                        if (stmt.type) resolveType(*stmt.type);
                        declareValue(stmt.name, stmt.isVar, true);
                        return;
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
                        for (const ast::StmtPtr &child: stmt.body) {
                            analyzeStmt(*child);
                        }
                        loopDepth--;
                        popScope();
                        return;
                    case StmtKind::Return:
                        if (stmt.returnValue) analyzeExpr(*stmt.returnValue);
                        return;
                    case StmtKind::Break:
                        if (loopDepth == 0) diag(stmt.pos, "'break' outside a loop");
                        return;
                    case StmtKind::Continue:
                        if (loopDepth == 0) diag(stmt.pos, "'continue' outside a loop");
                        return;
                    case StmtKind::ExprStmt:
                        if (stmt.expr) analyzeExpr(*stmt.expr);
                        return;
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
                            if (i < (int) expr.paramTypes.size() && expr.paramTypes[i]) {
                                resolveType(*expr.paramTypes[i]);
                            }
                            declareValue(expr.paramNames[i], true, false);
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

            void checkCallArity(const ast::Expr &call) {
                if (!call.lhs || call.lhs->kind != ExprKind::Name) return;
                const Str &name = call.lhs->text;
                if (lookupValue(name) != nullptr) return; // shadowed by a local/param
                auto it = functions.find(name);
                if (it == functions.end()) return;
                int argCount = (int) call.args.size();
                for (const ast::Decl *function: it->second) {
                    if ((int) function->params.size() == argCount) return;
                }
                diag(call.pos, "no overload of '" + name + "' takes "
                               + std::to_string(argCount) + " argument(s)");
            }
        };
    }

    List<Str> analyze(const ast::Module &module, const Str &fileName) {
        Analyzer analyzer(module, fileName);
        analyzer.run();
        return analyzer.diags;
    }
}
