#include "Codegen.h"

#include <string>

using ast::DeclKind;
using ast::ExprKind;
using ast::StmtKind;
using ast::TypeKind;

namespace codegen {
    namespace {
        // How a name's storage is reached, used to pick `.` vs `->`, `*x` vs
        // `x.get()`, and the `copy` lowering. We only track the cases the v1
        // subset needs; anything unknown is treated as a plain value.
        enum class NameKind { Value, Shared, Pointer };

        Str join(const List<Str> &parts, const Str &separator) {
            Str out;
            for (int i = 0; i < (int) parts.size(); i++) {
                if (i > 0) out += separator;
                out += parts[i];
            }
            return out;
        }

        struct Fn {
            const ast::Decl *decl = nullptr;
            ast::TypePtr receiver; // null for plain top-level functions
            Str file;
            List<Str> templateParams; // C++ template type parameters
            bool prelude = false;    // resolved but never emitted
        };

        // A native function declaration to emit at the top of the amalgamated
        // file (and to call by symbol).
        struct NativeDecl {
            const ast::Decl *decl = nullptr;
            Str file;
            Str symbol;
            bool prelude = false; // comes from the RTL; not emitted
        };

        // A native extension method, e.g. `native("sym") fun append<T>(this:
        // List<T>, value: T)`. The receiver pattern picks the right overload when
        // several extensions share a name (`append` on `List<T>` vs on `Str`).
        struct NativeExt {
            Str symbol;
            ast::TypePtr receiver;
            ast::TypePtr returnType;
            List<Str> typeParams;
        };

        // Strips the surrounding quotes from a string token's raw text.
        Str unquote(const Str &text) {
            if (text.length() >= 2 && text.front() == '"' && text.back() == '"') {
                return text.substr(1, text.length() - 2);
            }
            return text;
        }

        bool isRtlTypeName(const Str &name) {
            static const List<Str> names = {
                "Int", "Int8", "Int16", "Int32", "Int64",
                "Float32", "Float64", "Char", "Bool", "Str",
                "List", "Array", "RawArray", "Opt", "Res",
                "Dictionary", "SmallVector", "PList",
            };
            for (const Str &candidate: names) {
                if (candidate == name) return true;
            }
            return false;
        }

        class Emitter {
        public:
            explicit Emitter(const List<Input> &inputs) : inputs(inputs) {
            }

            Res<Str> run() {
                collect();
                prelude();
                emitNativeDeclarations();
                if (failed) return resError<Str>(error);
                emitTypes();
                if (failed) return resError<Str>(error);
                emitFunctions(true);
                if (failed) return resError<Str>(error);
                emitFunctions(false);
                if (failed) return resError<Str>(error);
                return ok(out);
            }

        private:
            const List<Input> &inputs;

            Str out;
            bool failed = false;
            Str error;
            Str curFile;

            Dictionary<Str, const ast::Decl *> types;
            Dictionary<Str, bool> enumNames;
            List<Fn> functions;
            Dictionary<Str, bool> receiverFnNames;
            List<NativeDecl> nativeDecls;
            Dictionary<Str, Str> nativeSymbols; // Simse name -> C++ symbol
            // Explicit-`this` native extensions: method name -> overloads.
            Dictionary<Str, List<NativeExt>> nativeExtensions;

            // C++ type parameters currently in scope (for generic declarations).
            Dictionary<Str, bool> activeTypeParams;

            // Per-function name kinds and declared/inferred types.
            Dictionary<Str, NameKind> nameKinds;
            Dictionary<Str, ast::TypePtr> localTypes;
            NameKind selfKind = NameKind::Value;
            ast::TypePtr selfType;

            // ---- diagnostics ----------------------------------------------

            void fail(const common::SourcePos &pos, const Str &message) {
                if (failed) return;
                failed = true;
                error = curFile + ":" + std::to_string(pos.line) + ":" + std::to_string(pos.column)
                        + ": " + message;
            }

            void line(int level, const Str &text) {
                out += Str(level * 4, ' ');
                out += text;
                out += '\n';
            }

            void sourceComment(const common::SourcePos &pos) {
                line(0, "// " + curFile + ":" + std::to_string(pos.line));
            }

            // ---- symbol collection ----------------------------------------

            ast::TypePtr namedTypeExpr(const Str &name) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Named;
                type->name = name;
                return type;
            }

            // The receiver type of a class method: `Name<A, B>` for a generic
            // class, `Name` otherwise.
            ast::TypePtr classReceiver(const ast::Decl &decl) {
                if (decl.typeParams.empty()) {
                    return namedTypeExpr(decl.name);
                }
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Generic;
                type->name = decl.name;
                for (const Str &param: decl.typeParams) {
                    type->typeArgs.push_back(namedTypeExpr(param));
                }
                return type;
            }

            void addFunction(const ast::Decl *decl, ast::TypePtr receiver, const Str &file,
                             const List<Str> &templateParams, bool prelude) {
                Fn fn;
                fn.decl = decl;
                fn.receiver = receiver;
                fn.file = file;
                fn.templateParams = templateParams;
                fn.prelude = prelude;
                functions.push_back(fn);
                if (receiver) {
                    receiverFnNames[decl->name] = true;
                }
            }

            void collect() {
                for (const Input &input: inputs) {
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind == DeclKind::Function) {
                            if (decl->isNative) {
                                NativeDecl native;
                                native.decl = decl.get();
                                native.file = input.fileName;
                                native.prelude = input.prelude;
                                native.symbol = decl->hasNativeSymbol
                                                    ? unquote(decl->nativeSymbol)
                                                    : decl->name;
                                nativeDecls.push_back(native);
                                nativeSymbols[decl->name] = native.symbol;
                                // A native with an explicit `this` first parameter is an
                                // extension method; member calls lower to symbol(receiver, ...).
                                if (!decl->params.empty() && decl->params[0].name == "this") {
                                    NativeExt ext;
                                    ext.symbol = native.symbol;
                                    ext.receiver = decl->params[0].type;
                                    ext.returnType = decl->returnType;
                                    ext.typeParams = decl->functionTypeParams;
                                    nativeExtensions[decl->name].push_back(ext);
                                }
                            }
                            addFunction(decl.get(),
                                        decl->hasReceiver ? decl->receiverType : nullptr,
                                        input.fileName, decl->functionTypeParams, input.prelude);
                            continue;
                        }
                        types[decl->name] = decl.get();
                        if (decl->kind == DeclKind::Enum) {
                            enumNames[decl->name] = true;
                        }
                        if (decl->kind == DeclKind::DataClass) {
                            ast::TypePtr receiver = classReceiver(*decl);
                            for (const ast::DeclPtr &method: decl->methods) {
                                List<Str> methodParams = decl->typeParams;
                                for (const Str &param: method->functionTypeParams) {
                                    methodParams.push_back(param);
                                }
                                addFunction(method.get(), receiver, input.fileName, methodParams,
                                            input.prelude);
                            }
                        }
                    }
                }
            }

            // ---- type mapping ---------------------------------------------

            void setActiveTypeParams(const List<Str> &params) {
                activeTypeParams.clear();
                for (const Str &param: params) {
                    activeTypeParams[param] = true;
                }
            }

            Str templateClause(const List<Str> &params) {
                if (params.empty()) return "";
                List<Str> parts;
                for (const Str &param: params) {
                    parts.push_back("class " + param);
                }
                return "template <" + join(parts, ", ") + ">";
            }

            // Renders the template argument list for a generic name, applying the
            // built-in parameter reorder (SmallVector<N, T> -> SmallVector<T, N>).
            Str typeArgsString(const Str &baseName, const List<ast::TypePtr> &args) {
                List<Str> rendered;
                for (const ast::TypePtr &arg: args) {
                    rendered.push_back(type(*arg));
                }
                if (baseName == "SmallVector" && rendered.size() == 2) {
                    Str first = rendered[0];
                    rendered[0] = rendered[1];
                    rendered[1] = first;
                }
                return join(rendered, ", ");
            }

            Str typeName(const Str &name, const common::SourcePos &pos) {
                if (name == "Unit") return "void";
                if (activeTypeParams.count(name) > 0) return name;
                if (isRtlTypeName(name)) return name;
                if (types.count(name) > 0) return name;
                fail(pos, "unsupported type '" + name + "'");
                return "/*unsupported*/";
            }

            Str type(const ast::TypeExpr &typeExpr) {
                switch (typeExpr.kind) {
                    case TypeKind::IntLit:
                        return typeExpr.text;
                    case TypeKind::Named:
                        return typeName(typeExpr.name, typeExpr.pos);
                    case TypeKind::Generic:
                        return typeName(typeExpr.name, typeExpr.pos)
                               + "<" + typeArgsString(typeExpr.name, typeExpr.typeArgs) + ">";
                    case TypeKind::Reference:
                        return "std::shared_ptr<"
                               + (typeExpr.inner ? type(*typeExpr.inner) : Str("void")) + ">";
                    case TypeKind::Pointer:
                        return (typeExpr.inner ? type(*typeExpr.inner) : Str("void")) + "*";
                    case TypeKind::Function: {
                        Str ret = typeExpr.returnType ? type(*typeExpr.returnType) : Str("void");
                        List<Str> params;
                        for (const ast::TypePtr &param: typeExpr.paramTypes) {
                            params.push_back(type(*param));
                        }
                        return "Func<" + ret + "(" + join(params, ", ") + ")>";
                    }
                }
                return "/*unsupported*/";
            }

            NameKind kindOf(const ast::TypeExpr &typeExpr) {
                if (typeExpr.kind == TypeKind::Reference) return NameKind::Shared;
                if (typeExpr.kind == TypeKind::Pointer) return NameKind::Pointer;
                return NameKind::Value;
            }

            // ---- declarations ---------------------------------------------

            void emitTypes() {
                for (const Input &input: inputs) {
                    if (input.prelude) continue;
                    curFile = input.fileName;
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind == DeclKind::DataClass) {
                            emitDataClass(*decl);
                        } else if (decl->kind == DeclKind::Enum) {
                            emitEnum(*decl);
                        } else if (decl->kind == DeclKind::TypeAlias) {
                            emitTypeAlias(*decl);
                        }
                        if (failed) return;
                    }
                }
            }

            void emitDataClass(const ast::Decl &decl) {
                setActiveTypeParams(decl.typeParams);
                List<Str> params;
                List<Str> inits;
                for (const ast::Field &field: decl.fields) {
                    if (!field.type) {
                        fail(field.pos, "unsupported: field '" + field.name + "' without a type");
                        return;
                    }
                    params.push_back(type(*field.type) + " " + field.name);
                    inits.push_back(field.name + "(" + field.name + ")");
                }
                if (failed) return;

                sourceComment(decl.pos);
                Str tmpl = templateClause(decl.typeParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "struct " + decl.name + " {");
                for (const ast::Field &field: decl.fields) {
                    line(1, type(*field.type) + " " + field.name + ";");
                }
                // A default constructor is emitted alongside the field constructor
                // so the type can be default-initialized where C++ needs it (for
                // example the payload of a failed Res<T>).
                line(1, decl.name + "() = default;");
                if (!decl.fields.empty()) {
                    line(1, decl.name + "(" + join(params, ", ") + ") : " + join(inits, ", ") + " {}");
                }
                line(0, "};");
            }

            void emitEnum(const ast::Decl &decl) {
                sourceComment(decl.pos);
                Str tmpl = templateClause(decl.typeParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "enum class " + decl.name + " {");
                for (const ast::EnumMember &member: decl.members) {
                    Str text = member.name;
                    if (member.hasValue) text += " = " + std::to_string(member.value);
                    line(1, text + ",");
                }
                line(0, "};");
            }

            void emitTypeAlias(const ast::Decl &decl) {
                if (!decl.targetType) {
                    fail(decl.pos, "unsupported: typealias '" + decl.name + "' without a target type");
                    return;
                }
                setActiveTypeParams(decl.typeParams);
                Str target = type(*decl.targetType);
                if (failed) return;
                sourceComment(decl.pos);
                Str tmpl = templateClause(decl.typeParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "using " + decl.name + " = " + target + ";");
            }

            // Native functions are declared once at the top of the file; their
            // symbols are hand-written C++ (impl_specs/native-interop.md).
            void emitNativeDeclarations() {
                setActiveTypeParams({});
                for (const NativeDecl &native: nativeDecls) {
                    if (native.prelude) continue; // provided by the RTL header
                    curFile = native.file;
                    const ast::Decl &decl = *native.decl;
                    setActiveTypeParams(decl.functionTypeParams);
                    if (native.symbol.find("::") != Str::npos) {
                        fail(decl.pos, "unsupported: namespaced native symbol '" + native.symbol
                                       + "' needs a global wrapper");
                        return;
                    }
                    Str ret = decl.returnType ? type(*decl.returnType) : Str("void");
                    if (failed) return;
                    List<Str> params;
                    for (const ast::Param &param: decl.params) {
                        if (!param.type) {
                            fail(param.pos, "unsupported: native parameter '" + param.name
                                            + "' without a type");
                            return;
                        }
                        Str mapped = type(*param.type);
                        if (failed) return;
                        // An explicit `this` receiver is emitted as `self` so the
                        // declaration is valid C++ and matches the call lowering.
                        Str name = param.name == "this" ? Str("self") : param.name;
                        if (param.type->kind == TypeKind::Pointer
                            || param.type->kind == TypeKind::Reference) {
                            params.push_back(mapped + " " + name);
                        } else {
                            params.push_back("const " + mapped + "& " + name);
                        }
                    }
                    sourceComment(decl.pos);
                    Str tmpl = templateClause(decl.functionTypeParams);
                    if (!tmpl.empty()) line(0, tmpl);
                    line(0, ret + " " + native.symbol + "(" + join(params, ", ") + ");");
                }
            }

            void emitFunctions(bool prototypeOnly) {
                for (const Fn &fn: functions) {
                    if (fn.prelude) continue;
                    curFile = fn.file;
                    emitFunction(fn, prototypeOnly);
                    if (failed) return;
                }
            }

            void beginScope(const Fn &fn, NameKind selfK, const ast::TypePtr &selfTypePtr) {
                nameKinds.clear();
                localTypes.clear();
                selfKind = selfK;
                selfType = selfTypePtr;
                if (fn.receiver) {
                    nameKinds["self"] = selfK;
                }
                for (const ast::Param &param: fn.decl->params) {
                    if (param.type) {
                        nameKinds[param.name] = kindOf(*param.type);
                        localTypes[param.name] = param.type;
                    }
                }
            }

            // The C++ parameter form of a receiver. Value receivers are taken by
            // reference so mutations through `this` reach the caller (data-class
            // methods like `setSource`/`advance` rely on this). Counted
            // references and raw pointers keep their handle form.
            Str receiverParam(const ast::TypePtr &receiverType) {
                Str mapped = type(*receiverType);
                if (receiverType->kind == TypeKind::Reference
                    || receiverType->kind == TypeKind::Pointer) {
                    return mapped + " self";
                }
                return mapped + "& self";
            }

            void emitFunction(const Fn &fn, bool prototypeOnly) {
                const ast::Decl &decl = *fn.decl;
                // Native functions are declared by emitNativeDeclarations, not here.
                if (decl.isNative) return;

                bool isMain = !fn.receiver && decl.name == "main";
                if (isMain && prototypeOnly) return;
                if (isMain && !decl.params.empty()) {
                    fail(decl.pos, "unsupported: main with parameters");
                    return;
                }

                setActiveTypeParams(fn.templateParams);
                Str ret = isMain ? "int" : (decl.returnType ? type(*decl.returnType) : Str("void"));
                if (failed) return;

                List<Str> params;
                bool hasSelf = false;
                NameKind selfK = NameKind::Value;
                ast::TypePtr selfTypePtr;
                if (fn.receiver) {
                    params.push_back(receiverParam(fn.receiver));
                    hasSelf = true;
                    selfK = kindOf(*fn.receiver);
                    selfTypePtr = fn.receiver;
                    if (failed) return;
                }
                for (const ast::Param &param: decl.params) {
                    if (!param.type) {
                        fail(param.pos, "unsupported: parameter '" + param.name + "' without a type");
                        return;
                    }
                    if (param.name == "this" && !hasSelf) {
                        params.push_back(receiverParam(param.type));
                        hasSelf = true;
                        selfK = kindOf(*param.type);
                        selfTypePtr = param.type;
                    } else {
                        params.push_back(type(*param.type) + " " + param.name);
                    }
                    if (failed) return;
                }
                // A bare `this` uses `self`; make sure it resolves even without an
                // explicit receiver declaration.
                if (!hasSelf) {
                    selfK = NameKind::Value;
                }

                Str signature = ret + " " + decl.name + "(" + join(params, ", ") + ")";
                Str tmpl = templateClause(fn.templateParams);
                if (prototypeOnly) {
                    if (!tmpl.empty()) line(0, tmpl);
                    line(0, signature + ";");
                    return;
                }
                if (!decl.hasBody) {
                    return;
                }

                sourceComment(decl.pos);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, signature + " {");
                beginScope(fn, selfK, selfTypePtr);
                emitStmts(decl.body, 1);
                if (failed) return;
                line(0, "}");
            }

            // ---- statements -----------------------------------------------

            void emitStmts(const List<ast::StmtPtr> &stmts, int level) {
                for (const ast::StmtPtr &stmt: stmts) {
                    emitStmt(*stmt, level);
                    if (failed) return;
                }
            }

            void emitStmt(const ast::Stmt &stmt, int level) {
                switch (stmt.kind) {
                    case StmtKind::VarDecl: {
                        Str typeText;
                        if (stmt.type) {
                            typeText = type(*stmt.type);
                        } else if (stmt.init) {
                            typeText = "auto";
                        } else {
                            fail(stmt.pos, "unsupported: '" + stmt.name
                                           + "' has neither a type nor an initializer");
                            return;
                        }
                        if (failed) return;
                        Str text = typeText + " " + stmt.name;
                        if (stmt.init) text += " = " + expr(*stmt.init, 0);
                        line(level, text + ";");

                        if (stmt.type) {
                            nameKinds[stmt.name] = kindOf(*stmt.type);
                            localTypes[stmt.name] = stmt.type;
                        } else if (stmt.init) {
                            nameKinds[stmt.name] = stmt.init->kind == ExprKind::Ref
                                                       ? NameKind::Shared
                                                       : (stmt.init->kind == ExprKind::Deref
                                                              ? NameKind::Pointer
                                                              : NameKind::Value);
                            ast::TypePtr inferred = inferType(*stmt.init);
                            if (inferred) localTypes[stmt.name] = inferred;
                        }
                        return;
                    }
                    case StmtKind::Assign:
                        if (stmt.op != "=") {
                            fail(stmt.pos, "unsupported: assignment operator '" + stmt.op + "'");
                            return;
                        }
                        line(level, expr(*stmt.target, 0) + " = " + expr(*stmt.value, 0) + ";");
                        return;
                    case StmtKind::If:
                        line(level, "if (" + expr(*stmt.cond, 0) + ") {");
                        emitStmts(stmt.thenBody, level + 1);
                        if (failed) return;
                        if (stmt.hasElse) {
                            line(level, "} else {");
                            emitStmts(stmt.elseBody, level + 1);
                            if (failed) return;
                        }
                        line(level, "}");
                        return;
                    case StmtKind::While:
                        line(level, "while (" + expr(*stmt.cond, 0) + ") {");
                        emitStmts(stmt.body, level + 1);
                        if (failed) return;
                        line(level, "}");
                        return;
                    case StmtKind::Return:
                        if (stmt.returnValue) {
                            line(level, "return " + expr(*stmt.returnValue, 0) + ";");
                        } else {
                            line(level, "return;");
                        }
                        return;
                    case StmtKind::Break:
                        line(level, "break;");
                        return;
                    case StmtKind::Continue:
                        line(level, "continue;");
                        return;
                    case StmtKind::ExprStmt:
                        line(level, expr(*stmt.expr, 0) + ";");
                        return;
                }
            }

            // ---- expressions ----------------------------------------------

            static int precedence(const ast::Expr &expr) {
                if (expr.kind == ExprKind::Binary) {
                    const Str &op = expr.text;
                    if (op == "||") return 1;
                    if (op == "&&") return 2;
                    if (op == "==" || op == "!=") return 3;
                    if (op == "<" || op == ">" || op == "<=" || op == ">=") return 4;
                    if (op == "+" || op == "-") return 5;
                    if (op == "*" || op == "/" || op == "%") return 6;
                    return 1;
                }
                switch (expr.kind) {
                    case ExprKind::Unary:
                    case ExprKind::Deref:
                    case ExprKind::Copy:
                        return 7;
                    case ExprKind::Ref:
                    case ExprKind::Call:
                    case ExprKind::Index:
                    case ExprKind::Member:
                        return 9;
                    default:
                        return 10;
                }
            }

            Str expr(const ast::Expr &e, int minPrecedence) {
                int p = precedence(e);
                Str s;
                if (p < minPrecedence) s += "(";
                s += exprInner(e);
                if (p < minPrecedence) s += ")";
                return s;
            }

            NameKind operandKind(const ast::Expr &e) {
                if (e.kind == ExprKind::Name) {
                    if (e.text == "this") return selfKind;
                    auto it = nameKinds.find(e.text);
                    if (it != nameKinds.end()) return it->second;
                }
                return NameKind::Value;
            }

            // ---- lightweight type inference ---------------------------------
            //
            // Codegen needs just enough type information to pick the right
            // lowering for a member/index/call receiver: `.` vs `->`, whether to
            // auto-dereference a `&T`/`*T` handle, which overloaded native
            // extension applies (`append` on `List<T>` vs on `Str`), and the
            // `Res<T>.value`/`.error` field spelling. It is deliberately best
            // effort: an unknown type yields null and the caller falls back to the
            // syntactic name-kind tracking.

            ast::TypePtr namedType(const Str &name) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Named;
                type->name = name;
                return type;
            }

            ast::TypePtr genericType(const Str &name, const List<ast::TypePtr> &args) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Generic;
                type->name = name;
                type->typeArgs = args;
                return type;
            }

            // The pointee after stripping any number of `&`/`*` handles.
            const ast::TypeExpr *pointee(const ast::TypePtr &type) {
                const ast::TypeExpr *current = type.get();
                while (current && (current->kind == TypeKind::Reference
                                   || current->kind == TypeKind::Pointer)
                       && current->inner) {
                    current = current->inner.get();
                }
                return current;
            }

            bool isTypeParamName(const Str &name, const List<Str> &typeParams) {
                for (const Str &param: typeParams) {
                    if (param == name) return true;
                }
                return false;
            }

            // Structural unification of an extension receiver pattern (which may
            // mention the extension's type parameters) against an actual receiver
            // type. Mirrors the sema checker's rule.
            bool unifyType(const ast::TypeExpr &pattern, const ast::TypeExpr &actual,
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
                        if (isTypeParamName(pattern.name, typeParams)) return true;
                        return a.kind == TypeKind::Named && a.name == pattern.name;
                    case TypeKind::Generic:
                        if (isTypeParamName(pattern.name, typeParams)) return true;
                        if (a.kind != TypeKind::Generic || a.name != pattern.name) return false;
                        if (a.typeArgs.size() != pattern.typeArgs.size()) return false;
                        for (int i = 0; i < (int) a.typeArgs.size(); i++) {
                            if (!unifyType(*pattern.typeArgs[i], *a.typeArgs[i], typeParams)) {
                                return false;
                            }
                        }
                        return true;
                    case TypeKind::Reference:
                        return a.kind == TypeKind::Reference && a.inner && pattern.inner
                               && unifyType(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Pointer:
                        return a.kind == TypeKind::Pointer && a.inner && pattern.inner
                               && unifyType(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Function:
                        return false;
                }
                return false;
            }

            ast::TypePtr functionReturn(const Str &name) {
                for (const Fn &fn: functions) {
                    if (fn.decl->name == name && fn.decl->returnType) {
                        return fn.decl->returnType;
                    }
                }
                return nullptr;
            }

            // The return type of a member call (`recv.name(...)`) when it resolves
            // to a known extension function or a built-in accessor.
            ast::TypePtr memberCallReturn(const ast::Expr &callee) {
                ast::TypePtr receiverType = inferType(*callee.lhs);
                const ast::TypeExpr *recv = pointee(receiverType);
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || !fn.receiver) continue;
                    if (fn.decl->name != callee.text) continue;
                    if (recv && unifyType(*fn.receiver, *recv, fn.templateParams)
                        && fn.decl->returnType) {
                        return fn.decl->returnType;
                    }
                }
                auto extensions = nativeExtensions.find(callee.text);
                if (extensions != nativeExtensions.end()) {
                    for (const NativeExt &ext: extensions->second) {
                        if (recv && ext.receiver && unifyType(*ext.receiver, *recv, ext.typeParams)
                            && ext.returnType) {
                            return ext.returnType;
                        }
                    }
                }
                if (recv && recv->kind == TypeKind::Generic) {
                    if (callee.text == "value" && recv->name == "Opt" && !recv->typeArgs.empty()) {
                        return recv->typeArgs[0];
                    }
                }
                if (recv && (callee.text == "size" || callee.text == "count")) {
                    if (recv->name == "List" || recv->name == "Str" || recv->name == "Array"
                        || recv->name == "Dictionary" || recv->name == "SmallVector") {
                        return namedType("Int");
                    }
                }
                if (callee.text == "isOk" || callee.text == "hasValue") {
                    return namedType("Bool");
                }
                return nullptr;
            }

            ast::TypePtr inferType(const ast::Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                        return namedType("Int");
                    case ExprKind::FloatLit:
                        return namedType("Float64");
                    case ExprKind::StrLit:
                        return namedType("Str");
                    case ExprKind::CharLit:
                        return namedType("Char");
                    case ExprKind::BoolLit:
                        return namedType("Bool");
                    case ExprKind::NullLit:
                        return nullptr;
                    case ExprKind::Name: {
                        if (e.text == "this") return selfType;
                        auto it = localTypes.find(e.text);
                        if (it != localTypes.end()) return it->second;
                        return nullptr;
                    }
                    case ExprKind::GenericName:
                        return genericType(e.text, e.typeArgs);
                    case ExprKind::Member: {
                        ast::TypePtr baseType = inferType(*e.lhs);
                        const ast::TypeExpr *base = pointee(baseType);
                        if (!base) return nullptr;
                        if (base->kind == TypeKind::Generic && base->name == "Res") {
                            if (e.text == "value" && !base->typeArgs.empty()) return base->typeArgs[0];
                            if (e.text == "error") return namedType("Str");
                        }
                        if (base->kind == TypeKind::Named || base->kind == TypeKind::Generic) {
                            auto it = types.find(base->name);
                            if (it != types.end() && it->second->kind == DeclKind::DataClass) {
                                for (const ast::Field &field: it->second->fields) {
                                    if (field.name == e.text) return field.type;
                                }
                            }
                        }
                        return nullptr;
                    }
                    case ExprKind::Call: {
                        const ast::Expr &callee = *e.lhs;
                        if (callee.kind == ExprKind::GenericName) {
                            if (types.count(callee.text) > 0 || isRtlTypeName(callee.text)) {
                                return genericType(callee.text, callee.typeArgs);
                            }
                            return functionReturn(callee.text);
                        }
                        if (callee.kind == ExprKind::Name) {
                            if (types.count(callee.text) > 0 || isRtlTypeName(callee.text)) {
                                return namedType(callee.text);
                            }
                            return functionReturn(callee.text);
                        }
                        if (callee.kind == ExprKind::Member) {
                            return memberCallReturn(callee);
                        }
                        return nullptr;
                    }
                    case ExprKind::Index: {
                        const ast::TypeExpr *base = pointee(inferType(*e.lhs));
                        if (!base) return nullptr;
                        if (base->kind == TypeKind::Named && base->name == "Str") return namedType("Char");
                        if (base->kind != TypeKind::Generic || base->typeArgs.empty()) return nullptr;
                        if (base->name == "SmallVector" && base->typeArgs.size() == 2) {
                            return base->typeArgs[1]; // <N, T>
                        }
                        if (base->name == "Dictionary" && base->typeArgs.size() == 2) {
                            return base->typeArgs[1];
                        }
                        return base->typeArgs[0];
                    }
                    case ExprKind::Ref: {
                        auto type = std::make_shared<ast::TypeExpr>();
                        type->kind = TypeKind::Reference;
                        type->inner = inferType(*e.lhs);
                        return type;
                    }
                    case ExprKind::Deref: {
                        auto type = std::make_shared<ast::TypeExpr>();
                        type->kind = TypeKind::Pointer;
                        type->inner = inferType(*e.lhs);
                        return type;
                    }
                    case ExprKind::Copy:
                    case ExprKind::Unary:
                        return inferType(*e.lhs);
                    case ExprKind::Binary: {
                        if (e.text == "==" || e.text == "!=" || e.text == "<" || e.text == ">"
                            || e.text == "<=" || e.text == ">=" || e.text == "&&" || e.text == "||") {
                            return namedType("Bool");
                        }
                        return inferType(*e.lhs);
                    }
                    case ExprKind::Lambda:
                        return nullptr;
                }
                return nullptr;
            }

            // The receiver argument for a lowered call, dereferencing a counted
            // reference or raw pointer when the callee's receiver is a value.
            Str receiverArg(const ast::TypePtr &pattern, const ast::Expr &recv) {
                if (pattern && (pattern->kind == TypeKind::Reference
                                || pattern->kind == TypeKind::Pointer)) {
                    return expr(recv, 9);
                }
                ast::TypePtr recvType = inferType(recv);
                if (recvType && (recvType->kind == TypeKind::Reference
                                 || recvType->kind == TypeKind::Pointer)) {
                    return "(*" + expr(recv, 9) + ")";
                }
                return expr(recv, 9);
            }

            // Finds a Simse-declared extension/method whose receiver matches the
            // given receiver expression's type. Returns null when unknown.
            const Fn *findExtensionFn(const Str &name, const ast::Expr &recvExpr) {
                const ast::TypeExpr *recv = pointee(inferType(recvExpr));
                if (!recv) return nullptr;
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || !fn.receiver) continue;
                    if (fn.decl->name != name) continue;
                    if (unifyType(*fn.receiver, *recv, fn.templateParams)) return &fn;
                }
                return nullptr;
            }

            const NativeExt *findNativeExt(const Str &name, const ast::Expr &recvExpr) {
                auto it = nativeExtensions.find(name);
                if (it == nativeExtensions.end()) return nullptr;
                const ast::TypeExpr *recv = pointee(inferType(recvExpr));
                if (!recv) return nullptr;
                for (const NativeExt &ext: it->second) {
                    if (ext.receiver && unifyType(*ext.receiver, *recv, ext.typeParams)) return &ext;
                }
                return nullptr;
            }

            Str memberAccess(const ast::Expr &base, const Str &name) {
                bool arrow = false;
                ast::TypePtr baseType = inferType(base);
                if (baseType) {
                    arrow = baseType->kind == TypeKind::Reference
                            || baseType->kind == TypeKind::Pointer;
                } else if (base.kind == ExprKind::Name) {
                    // Fallback for a receiver whose type we could not infer.
                    if (base.text == "this") {
                        arrow = selfKind != NameKind::Value;
                    } else {
                        auto it = nameKinds.find(base.text);
                        if (it != nameKinds.end() && it->second == NameKind::Shared) {
                            arrow = true;
                        }
                    }
                }
                // Res<T> exposes its payload as the `value`/`error` properties; the
                // RTL field spellings are Value/Error.
                Str field = name;
                const ast::TypeExpr *recv = pointee(baseType);
                if (recv && recv->kind == TypeKind::Generic && recv->name == "Res") {
                    if (name == "value") field = "Value";
                    else if (name == "error") field = "Error";
                }
                return expr(base, 9) + (arrow ? "->" : ".") + field;
            }

            Str exprInner(const ast::Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                    case ExprKind::FloatLit:
                    case ExprKind::StrLit:
                    case ExprKind::CharLit:
                        return e.text;
                    case ExprKind::BoolLit:
                        return e.boolValue ? "true" : "false";
                    case ExprKind::NullLit:
                        fail(e.pos, "unsupported: null literal");
                        return "/*unsupported*/";
                    case ExprKind::Name:
                        return e.text == "this" ? Str("self") : e.text;
                    case ExprKind::GenericName:
                        fail(e.pos, "unsupported: generic-qualified expression '" + e.text + "<...>'");
                        return "/*unsupported*/";
                    case ExprKind::Member:
                        if (e.lhs && e.lhs->kind == ExprKind::Name && enumNames.count(e.lhs->text) > 0) {
                            return e.lhs->text + "::" + e.text;
                        }
                        return memberAccess(*e.lhs, e.text);
                    case ExprKind::Call:
                        return call(e);
                    case ExprKind::Index: {
                        Str baseExpr = expr(*e.lhs, 9);
                        ast::TypePtr baseType = inferType(*e.lhs);
                        if (baseType && baseType->kind == TypeKind::Reference) {
                            // Auto-dereference a counted reference: `(*handle)[i]`.
                            return "(*" + baseExpr + ")[" + expr(*e.rhs, 0) + "]";
                        }
                        return baseExpr + "[" + expr(*e.rhs, 0) + "]";
                    }
                    case ExprKind::Unary:
                        return e.text + expr(*e.lhs, 7);
                    case ExprKind::Binary: {
                        int p = precedence(e);
                        return expr(*e.lhs, p) + " " + e.text + " " + expr(*e.rhs, p + 1);
                    }
                    case ExprKind::Lambda:
                        fail(e.pos, "unsupported: lambda expression");
                        return "/*unsupported*/";
                    case ExprKind::Ref: {
                        // `&List<T>()` is the counted empty-list construction.
                        if (e.lhs && e.lhs->kind == ExprKind::Call && e.lhs->lhs
                            && e.lhs->lhs->kind == ExprKind::GenericName
                            && e.lhs->lhs->text == "List" && e.lhs->args.empty()) {
                            return "makeList<" + typeArgsString("List", e.lhs->lhs->typeArgs) + ">()";
                        }
                        Str operand = expr(*e.lhs, 0);
                        return "std::make_shared<std::remove_cvref_t<decltype((" + operand + "))>>("
                               + operand + ")";
                    }
                    case ExprKind::Deref: {
                        Str operand = expr(*e.lhs, 7);
                        ast::TypePtr operandType = inferType(*e.lhs);
                        NameKind kind = operandType ? kindOf(*operandType) : operandKind(*e.lhs);
                        if (kind == NameKind::Shared) {
                            return "(" + operand + ").get()";
                        }
                        return "*" + operand;
                    }
                    case ExprKind::Copy: {
                        Str operand = expr(*e.lhs, 0);
                        ast::TypePtr operandType = inferType(*e.lhs);
                        NameKind kind = operandType ? kindOf(*operandType) : operandKind(*e.lhs);
                        if (kind == NameKind::Shared || kind == NameKind::Pointer) {
                            return "*(" + operand + ")";
                        }
                        return "(" + operand + ")";
                    }
                }
                return "/*unsupported*/";
            }

            Str call(const ast::Expr &e) {
                const ast::Expr &callee = *e.lhs;
                if (callee.kind == ExprKind::GenericName) {
                    // A generic type construction (`List<Int>()`) or a generic
                    // function call (`identity<Int>(x)`); both lower directly.
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    return callee.text + "<" + typeArgsString(callee.text, callee.typeArgs)
                           + ">(" + join(args, ", ") + ")";
                }
                if (callee.kind == ExprKind::Name) {
                    if (callee.text == "println" || callee.text == "print") {
                        Str arg = e.args.empty() ? Str("") : expr(*e.args[0], 0);
                        Str s = "std::cout << std::boolalpha << (" + arg + ")";
                        if (callee.text == "println") s += " << std::endl";
                        return s;
                    }
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    auto native = nativeSymbols.find(callee.text);
                    Str calleeName = native != nativeSymbols.end() ? native->second : callee.text;
                    return calleeName + "(" + join(args, ", ") + ")";
                }
                if (callee.kind == ExprKind::Member) {
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    // Generic-qualified static call: `Res<T>.ok(x)` lowers to
                    // `Res<T>::ok(x)` (RTL Opt/Res provide static constructors).
                    if (callee.lhs && callee.lhs->kind == ExprKind::GenericName) {
                        return callee.lhs->text + "<"
                               + typeArgsString(callee.lhs->text, callee.lhs->typeArgs)
                               + ">::" + callee.text + "(" + join(args, ", ") + ")";
                    }

                    const ast::TypeExpr *receiver = pointee(inferType(*callee.lhs));
                    if (receiver) {
                        // Receiver type is known: pick the extension (or native
                        // extension) overload whose receiver unifies with it.
                        const Fn *fn = findExtensionFn(callee.text, *callee.lhs);
                        if (fn) {
                            Str all = receiverArg(fn->receiver, *callee.lhs);
                            for (const Str &arg: args) all += ", " + arg;
                            return fn->decl->name + "(" + all + ")";
                        }
                        const NativeExt *ext = findNativeExt(callee.text, *callee.lhs);
                        if (ext) {
                            Str all = receiverArg(ext->receiver, *callee.lhs);
                            for (const Str &arg: args) all += ", " + arg;
                            return ext->symbol + "(" + all + ")";
                        }
                        // Known receiver but no Simse/native extension: an RTL
                        // member call such as List::size or Res::isOk.
                        return memberAccess(*callee.lhs, callee.text)
                               + "(" + join(args, ", ") + ")";
                    }

                    // Unknown receiver type: keep the name-based precedence.
                    if (receiverFnNames.count(callee.text) > 0) {
                        Str all = expr(*callee.lhs, 9);
                        for (const Str &arg: args) all += ", " + arg;
                        return callee.text + "(" + all + ")";
                    }
                    auto extension = nativeExtensions.find(callee.text);
                    if (extension != nativeExtensions.end() && !extension->second.empty()) {
                        Str all = expr(*callee.lhs, 9);
                        for (const Str &arg: args) all += ", " + arg;
                        return extension->second[0].symbol + "(" + all + ")";
                    }
                    return memberAccess(*callee.lhs, callee.text)
                           + "(" + join(args, ", ") + ")";
                }
                fail(e.pos, "unsupported: call target");
                return "/*unsupported*/";
            }

            // ---- prelude --------------------------------------------------

            void prelude() {
                out += "// Generated by simse_transpile. Do not edit.\n";
                out += "#include \"cppsrc/rtl/simse.hpp\"\n";
                out += "#include <iostream>\n";
                out += "#include <type_traits>\n";
                out += "\n";
            }
        };
    }

    Res<Str> emitProgram(const List<Input> &inputs) {
        Emitter emitter(inputs);
        return emitter.run();
    }
}
