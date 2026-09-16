#include "Sema.h"
#include "TypeInfer.h"

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
            explicit Analyzer(const List<Input> &inputs) : inputs(inputs) {
            }

            List<Str> diags;

            void run() {
                collectGlobal();
                for (const Input &input: inputs) {
                    file = input.fileName;
                    validateImports(*input.module);
                    buildVisible(*input.module);
                    for (const ast::DeclPtr &decl: input.module->declarations) {
                        analyzeDecl(*decl);
                    }
                    // `buildVisible` pushed the module scope the file's declarations -
                    // including its file-level statics - were analyzed in.
                    popScope();
                }
            }

        private:
            const List<Input> &inputs;
            Str file;

            // Global (whole-compilation) tables, keyed by "<package>|<name>".
            // Declarations are grouped by their file's package, so two files that
            // declare the same package share one scope.
            Dictionary<Str, const ast::Decl *> globalTypes;
            Dictionary<Str, List<const ast::Decl *>> globalFunctions;
            // File-level statics (`DeclKind::Var`, specs/statics.md): collected apart
            // from the types because they are values, not types.
            Dictionary<Str, const ast::Decl *> globalStatics;
            Dictionary<Str, List<const ast::Decl *>> packageDecls;
            Dictionary<Str, bool> declaredPackages;

            // The declarations visible (unqualified) in the current file: its own
            // package, every imported package, and the implicit `rtl` prelude.
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

            // "<a>.<b>" for a dotted path; empty for the root package.
            static Str packageName(const List<Str> &parts) {
                Str out;
                for (int i = 0; i < (int) parts.size(); i++) {
                    if (i > 0) out += ".";
                    out += parts[i];
                }
                return out;
            }

            // Collects every declaration into its package scope, reporting a
            // duplicate top-level name within one package (including across two
            // files that declare it). File-level statics (`Var`, specs/statics.md)
            // share the namespace with the other declarations but are collected
            // separately: they are values, not types.
            void collectGlobal() {
                for (const Input &input: inputs) {
                    file = input.fileName;
                    Str pkg = packageName(input.module->package);
                    declaredPackages[pkg] = true;
                    for (const ast::DeclPtr &decl: input.module->declarations) {
                        Str key = pkg + "|" + decl->name;
                        bool isFunction = decl->kind == DeclKind::Function;
                        bool isStatic = decl->kind == DeclKind::Var;
                        bool nameTaken = globalTypes.count(key) > 0
                                         || globalFunctions.count(key) > 0
                                         || globalStatics.count(key) > 0;
                        if (isFunction) {
                            if (globalTypes.count(key) > 0 || globalStatics.count(key) > 0) {
                                diag(decl->pos, "duplicate declaration '" + decl->name + "'");
                            } else {
                                globalFunctions[key].push_back(decl.get());
                            }
                        } else if (isStatic) {
                            if (nameTaken) {
                                diag(decl->pos, "duplicate declaration '" + decl->name + "'");
                            } else {
                                globalStatics[key] = decl.get();
                            }
                        } else {
                            if (nameTaken) {
                                diag(decl->pos, "duplicate declaration '" + decl->name + "'");
                            } else {
                                globalTypes[key] = decl.get();
                            }
                        }
                        packageDecls[pkg].push_back(decl.get());
                    }
                }
            }

            // Reports an import whose package no participating file declares.
            void validateImports(const ast::Module &module) {
                for (const ast::Import &import: module.imports) {
                    Str dotted = packageName(import.path);
                    if (declaredPackages.count(dotted) == 0) {
                        diag(import.pos, "cannot resolve import '" + dotted
                                         + "': no file declares package '" + dotted + "'");
                    }
                }
            }

            // Builds the unqualified scope of one file: its own package, then
            // each imported package, then the implicit `rtl` prelude. It also pushes
            // the module scope the file's declarations are analyzed in, where the
            // visible file-level statics live as values (specs/statics.md); `run`
            // pops it.
            void buildVisible(const ast::Module &module) {
                types.clear();
                functions.clear();
                List<Str> packages;
                packages.push_back(packageName(module.package));
                for (const ast::Import &import: module.imports) {
                    packages.push_back(packageName(import.path));
                }
                packages.push_back("rtl");

                pushScope();
                Dictionary<Str, bool> seen;
                for (const Str &pkg: packages) {
                    if (seen.count(pkg) > 0) continue;
                    seen[pkg] = true;
                    auto it = packageDecls.find(pkg);
                    if (it == packageDecls.end()) continue;
                    for (const ast::Decl *decl: it->second) {
                        if (decl->kind == DeclKind::Function) {
                            functions[decl->name].push_back(decl);
                        } else if (decl->kind == DeclKind::Var) {
                            declareValue(decl->name, decl->isVar, true, decl->type);
                        } else if (types.count(decl->name) == 0) {
                            types[decl->name] = decl;
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
                    case DeclKind::Var:
                        // Static storage: the type in the file's module scope, and the
                        // initializer as an ordinary expression. The initializer may name
                        // any hoisted declaration, including another static
                        // (specs/statics.md: the name is visible everywhere, the
                        // initialization order is not specified).
                        if (decl.type) resolveType(*decl.type);
                        if (decl.init) analyzeExpr(*decl.init);
                        return;
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

            // `for` is lowered in the parser into the declaration of the machine it
            // iterates (`_sm_for<n>`, impl_specs/for.md), whose initializer is the
            // invisible `smToYield()` / `smToYieldPtr()` wrap, so the checker sees the
            // template rather than the construct, and the template's names are the one
            // marker that says "this came from a `for`". Something is iterable when that
            // wrap resolves: a machine is (the identity, impl_specs/for.md), and anything
            // else needs the wrap in scope - the prelude has one per container, in both
            // flavours. Say so here, where the `for` still has a position: the C++ the
            // template would otherwise emit does not compile, and its error would name a
            // generated statement instead of the line the user wrote.
            void checkForIterable(const ast::Stmt &stmt) {
                if (!stmt.init || !isForTemplateName(stmt.name)) return;
                if (stmt.init->kind != ExprKind::Call || !stmt.init->lhs
                    || stmt.init->lhs->kind != ExprKind::Member || !stmt.init->lhs->lhs) {
                    return;
                }
                const Str &wrap = stmt.init->lhs->text;
                const ast::Expr &receiver = *stmt.init->lhs->lhs;
                ast::TypePtr receiverType = iteratedType(receiver);
                if (!receiverType) return; // unknown: the C++ compiler has the last word
                if (receiverType->kind == TypeKind::Yield) {
                    // A machine *is* the identity for `smToYield` - it hands out values,
                    // not places, so it has no pointer form.
                    if (wrap == "smToYield") return;
                    diag(stmt.pos, "a `for (*x in m)` needs a `smToYieldPtr`, and a machine "
                                   "yields values rather than places: iterate it with "
                                   "`for (x in m)`");
                    return;
                }
                if (hasWrap(wrap, *receiverType)) return;
                diag(stmt.pos, "a `for` iterates a machine (`..T`) or a type with a `" + wrap
                               + "`, and "
                               + ast::typeToString(*receiverType)
                               + " has neither; iterate a container with `while` and an index");
            }

            // Whether a wrap (`smToYield`, `smToYieldPtr`) takes this receiver: the
            // convention the parser's wrap calls through (`specs/functions.md`,
            // impl_specs/for.md). The receiver's *name* is what is compared - `List<T>`
            // takes any `List<...>`, and a pattern type parameter takes anything - which is
            // all the gate needs; the emitted call is resolved with the full unification (in
            // `codegen`), and a name this cannot decide stays silent (the C++ compiler gets
            // the last word, as everywhere else).
            bool hasWrap(const Str &wrap, const ast::TypeExpr &receiverType) {
                auto found = functions.find(wrap);
                if (found == functions.end()) return false;
                for (const ast::Decl *function: found->second) {
                    if (!function || !function->receiverType) continue;
                    if (receiverNameMatches(*function->receiverType, receiverType,
                                            function->functionTypeParams)) {
                        return true;
                    }
                }
                return false;
            }

            // The receiver's outer type, ignoring handles and type arguments: `*List<Int>`
            // and `List<Str>` are the same receiver for this purpose.
            static bool receiverNameMatches(const ast::TypeExpr &pattern,
                                            const ast::TypeExpr &actual,
                                            const List<Str> &typeParams) {
                const ast::TypeExpr *a = &actual;
                while ((a->kind == TypeKind::Reference || a->kind == TypeKind::Pointer)
                       && a->inner) {
                    a = a->inner.get();
                }
                if (pattern.kind == TypeKind::Named) {
                    return a->kind == TypeKind::Named && a->name == pattern.name;
                }
                if (pattern.kind == TypeKind::Generic) {
                    for (const Str &param: typeParams) {
                        if (param == pattern.name) return true;
                    }
                    return a->kind == TypeKind::Generic && a->name == pattern.name;
                }
                return false;
            }

            // Whether a name is one the `for` desugaring made. The generated names are
            // per-file counters (`_sm_for1`, `_sm_step1`, `_sm_index1`), like the
            // lowering's own slots: recognizable, and documented as not a user's to take.
            static bool isForTemplateName(const Str &name) {
                return name.compare(0, 7, "_sm_for") == 0;
            }

            // The type of the expression a `for` iterates, for the shapes the checker can
            // name without walking anything: a binding it tracks, a type construction,
            // or a call of a declared function. Anything else stays unknown, and unknown
            // stays silent - the C++ compiler gets the last word there, as it does for
            // any other member it resolves.
            ast::TypePtr iteratedType(const ast::Expr &expr) {
                if (expr.kind == ExprKind::Name) return exprType(expr);
                if (expr.kind != ExprKind::Call || !expr.lhs) return nullptr;
                if (expr.lhs->kind == ExprKind::GenericName) {
                    // `List<Int>()` builds a value of the name it calls, so it is the
                    // type; `f<Int>(x)` calls the function, and its signature answers.
                    if (types.count(expr.lhs->text) > 0) return exprType(expr);
                } else if (expr.lhs->kind != ExprKind::Name) {
                    return nullptr;
                }
                auto it = functions.find(expr.lhs->text);
                if (it == functions.end()) return nullptr;
                ast::TypePtr known;
                for (int i = 0; i < (int) it->second.size(); i++) {
                    const ast::Decl *function = it->second[i];
                    if (!function || !function->returnType) continue;
                    if (function->returnType->kind == TypeKind::Yield) return function->returnType;
                    if (!known) known = function->returnType;
                }
                return known;
            }

            // ---- statement analysis ---------------------------------------

            void analyzeStmt(const ast::Stmt &stmt) {
                switch (stmt.kind) {
                    case StmtKind::VarDecl: {
                        if (stmt.init) analyzeExpr(*stmt.init);
                        if (stmt.type) resolveType(*stmt.type);
                        ast::TypePtr type = stmt.type;
                        if (!type && stmt.init) type = exprType(*stmt.init);
                        checkForIterable(stmt);
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

            // What the checker has to say about one argument of an accepted call. One rule
            // so far: a **raw pointer cannot become a counted reference in place**. The
            // language shares a *box* (`&x` is the counted reference to a copy of `x`),
            // and a `*T` argument is a pointer to somebody's storage - the compiler would
            // have to guess whether the call wants a copy of that storage or a share of a
            // box that does not exist. So the writer says it, one line before the call:
            //
            //     var boxed: &Int = &v      // a reference to a copy of v
            //     printRef(boxed)
            //
            // A report rather than a silent copy, because the two spellings mean
            // different things. Every *other* handle conversion is inferred
            // (`convertArgument` in the extractor, `specs/functions.md`).
            void checkHandleArgument(const Str &callee, const ast::Decl &function, int index,
                                     const ast::Expr &arg) {
                if (index >= (int) function.params.size()) return;
                const ast::TypeExpr *param = function.params[index].type.get();
                if (param == nullptr) return;
                if (param->kind == ast::TypeKind::Pointer) return; // a borrow takes anything
                if (!sema::isHandleType(param)) return;            // a by-value parameter reads through
                const ast::TypePtr actual = exprType(arg);
                if (!actual || actual->kind != ast::TypeKind::Pointer) return;
                const ast::TypeExpr *pointee = sema::pointeeOf(param);
                diag(arg.pos, "'" + callee + "' takes a counted reference ('&"
                              + (pointee ? ast::typeToString(*pointee) : Str("T"))
                              + "') and the argument is a raw pointer: a pointer cannot become a"
                                " reference in place - make a reference variable one line before"
                                " the call (var ref: &"
                              + (pointee ? ast::typeToString(*pointee) : Str("T")) + " = &value)");
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
                    if (generic && (int) function->functionTypeParams.size() != typeArgCount) continue;
                    const int paramCount = (int) function->params.size();
                    if (paramCount == argCount) {
                        for (int i = 0; i < argCount; i++) {
                            checkHandleArgument(name, *function, i, *call.args[i]);
                        }
                        return;
                    }
                    // The trailing arguments may *pack* into a last parameter that is a
                    // list (`fun addAll(values: *List<Int>)` called as `addAll(1, 2, 3)`),
                    // so a call with more arguments than parameters is legal when the
                    // last parameter takes a pack - and one with fewer, which is the
                    // same call with no elements (`specs/functions.md`). A construction is
                    // not a call here: it takes one argument per field, always.
                    if (paramCount > 0
                        && sema::isPackTarget(function->params[paramCount - 1].type.get())
                        && argCount >= paramCount - 1) {
                        return;
                    }
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

    List<Str> analyze(const List<Input> &inputs) {
        Analyzer analyzer(inputs);
        analyzer.run();
        return analyzer.diags;
    }
}
