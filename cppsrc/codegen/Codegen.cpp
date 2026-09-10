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
        };

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

            // Per-function name kinds.
            Dictionary<Str, NameKind> nameKinds;
            NameKind selfKind = NameKind::Value;

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

            void addFunction(const ast::Decl *decl, ast::TypePtr receiver, const Str &file) {
                Fn fn;
                fn.decl = decl;
                fn.receiver = receiver;
                fn.file = file;
                functions.push_back(fn);
                if (receiver) {
                    receiverFnNames[decl->name] = true;
                }
            }

            void collect() {
                for (const Input &input: inputs) {
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind == DeclKind::Function) {
                            addFunction(decl.get(),
                                        decl->hasReceiver ? decl->receiverType : nullptr,
                                        input.fileName);
                            continue;
                        }
                        types[decl->name] = decl.get();
                        if (decl->kind == DeclKind::Enum) {
                            enumNames[decl->name] = true;
                        }
                        if (decl->kind == DeclKind::DataClass) {
                            ast::TypePtr receiver = namedTypeExpr(decl->name);
                            for (const ast::DeclPtr &method: decl->methods) {
                                addFunction(method.get(), receiver, input.fileName);
                            }
                        }
                    }
                }
            }

            // ---- type mapping ---------------------------------------------

            Str typeName(const Str &name, const common::SourcePos &pos) {
                if (name == "Unit") return "void";
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
                    case TypeKind::Generic: {
                        Str base = typeName(typeExpr.name, typeExpr.pos);
                        List<Str> args;
                        for (const ast::TypePtr &arg: typeExpr.typeArgs) {
                            args.push_back(type(*arg));
                        }
                        return base + "<" + join(args, ", ") + ">";
                    }
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
                line(0, "struct " + decl.name + " {");
                for (const ast::Field &field: decl.fields) {
                    line(1, type(*field.type) + " " + field.name + ";");
                }
                if (!decl.fields.empty()) {
                    line(1, decl.name + "(" + join(params, ", ") + ") : " + join(inits, ", ") + " {}");
                }
                line(0, "};");
            }

            void emitEnum(const ast::Decl &decl) {
                sourceComment(decl.pos);
                line(0, "enum class " + decl.name + " {");
                for (const ast::EnumMember &member: decl.members) {
                    Str text = member.name;
                    if (member.hasValue) text += " = " + std::to_string(member.value);
                    line(1, text + ",");
                }
                line(0, "};");
            }

            void emitTypeAlias(const ast::Decl &decl) {
                if (!decl.typeParams.empty()) {
                    fail(decl.pos, "unsupported: generic typealias '" + decl.name + "'");
                    return;
                }
                if (!decl.targetType) {
                    fail(decl.pos, "unsupported: typealias '" + decl.name + "' without a target type");
                    return;
                }
                Str target = type(*decl.targetType);
                if (failed) return;
                sourceComment(decl.pos);
                line(0, "using " + decl.name + " = " + target + ";");
            }

            void emitFunctions(bool prototypeOnly) {
                for (const Fn &fn: functions) {
                    curFile = fn.file;
                    emitFunction(fn, prototypeOnly);
                    if (failed) return;
                }
            }

            void beginScope(const Fn &fn, bool hasSelf, NameKind selfK) {
                nameKinds.clear();
                selfKind = selfK;
                if (fn.receiver) {
                    nameKinds["self"] = selfK;
                }
                for (const ast::Param &param: fn.decl->params) {
                    if (param.type) {
                        nameKinds[param.name] = kindOf(*param.type);
                    }
                }
            }

            void emitFunction(const Fn &fn, bool prototypeOnly) {
                const ast::Decl &decl = *fn.decl;
                if (decl.isNative) {
                    fail(decl.pos, "unsupported: native function '" + decl.name + "'");
                    return;
                }

                bool isMain = !fn.receiver && decl.name == "main";
                if (isMain && prototypeOnly) return;
                if (isMain && !decl.params.empty()) {
                    fail(decl.pos, "unsupported: main with parameters");
                    return;
                }

                Str ret = isMain ? "int" : (decl.returnType ? type(*decl.returnType) : Str("void"));
                if (failed) return;

                List<Str> params;
                bool hasSelf = false;
                NameKind selfK = NameKind::Value;
                if (fn.receiver) {
                    params.push_back(type(*fn.receiver) + " self");
                    hasSelf = true;
                    selfK = kindOf(*fn.receiver);
                    if (failed) return;
                }
                for (const ast::Param &param: decl.params) {
                    if (!param.type) {
                        fail(param.pos, "unsupported: parameter '" + param.name + "' without a type");
                        return;
                    }
                    if (param.name == "this" && !hasSelf) {
                        params.push_back(type(*param.type) + " self");
                        hasSelf = true;
                        selfK = kindOf(*param.type);
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
                if (prototypeOnly) {
                    line(0, signature + ";");
                    return;
                }
                if (!decl.hasBody) {
                    return;
                }

                sourceComment(decl.pos);
                line(0, signature + " {");
                beginScope(fn, hasSelf, selfK);
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
                        } else if (stmt.init) {
                            nameKinds[stmt.name] = stmt.init->kind == ExprKind::Ref
                                                       ? NameKind::Shared
                                                       : (stmt.init->kind == ExprKind::Deref
                                                              ? NameKind::Pointer
                                                              : NameKind::Value);
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

            Str memberAccess(const ast::Expr &base, const Str &name) {
                bool arrow = false;
                if (base.kind == ExprKind::Name) {
                    if (base.text == "this") {
                        arrow = selfKind != NameKind::Value;
                    } else {
                        auto it = nameKinds.find(base.text);
                        if (it != nameKinds.end() && it->second == NameKind::Shared) {
                            arrow = true;
                        }
                    }
                }
                return expr(base, 9) + (arrow ? "->" : ".") + name;
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
                    case ExprKind::Index:
                        return expr(*e.lhs, 9) + "[" + expr(*e.rhs, 0) + "]";
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
                        Str operand = expr(*e.lhs, 0);
                        return "std::make_shared<std::remove_cvref_t<decltype((" + operand + "))>>("
                               + operand + ")";
                    }
                    case ExprKind::Deref: {
                        Str operand = expr(*e.lhs, 7);
                        if (operandKind(*e.lhs) == NameKind::Shared) {
                            return "(" + operand + ").get()";
                        }
                        return "*" + operand;
                    }
                    case ExprKind::Copy: {
                        Str operand = expr(*e.lhs, 0);
                        NameKind kind = operandKind(*e.lhs);
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
                    fail(e.pos, "unsupported: generic-qualified call '" + callee.text + "<...>'");
                    return "/*unsupported*/";
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
                    return callee.text + "(" + join(args, ", ") + ")";
                }
                if (callee.kind == ExprKind::Member) {
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    if (receiverFnNames.count(callee.text) > 0) {
                        // A lowered extension/method function: the receiver is the
                        // first argument.
                        Str receiver = expr(*callee.lhs, 9);
                        Str all = receiver;
                        for (const Str &arg: args) {
                            all += ", " + arg;
                        }
                        return callee.text + "(" + all + ")";
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
