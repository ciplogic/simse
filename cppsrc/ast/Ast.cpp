#include "Ast.h"

#include <string>

namespace ast {
    namespace {
        Str posStr(const SourcePos &pos) {
            return std::to_string(pos.line) + ":" + std::to_string(pos.column);
        }

        // Escapes the four whitespace-ish bytes so every node stays on one line.
        Str escapeText(const Str &text) {
            Str escaped;
            for (char ch: text) {
                switch (ch) {
                    case '\\': escaped += "\\\\"; break;
                    case '\n': escaped += "\\n"; break;
                    case '\r': escaped += "\\r"; break;
                    case '\t': escaped += "\\t"; break;
                    default: escaped += ch; break;
                }
            }
            return escaped;
        }

        Str joinNames(const List<Str> &names) {
            Str out;
            for (int i = 0; i < (int) names.size(); i++) {
                if (i > 0) out += ", ";
                out += names[i];
            }
            return out;
        }

        Str joinPath(const List<Str> &parts) {
            Str out;
            for (int i = 0; i < (int) parts.size(); i++) {
                if (i > 0) out += ".";
                out += parts[i];
            }
            return out;
        }

        Str joinTypes(const List<TypePtr> &types) {
            Str out;
            for (int i = 0; i < (int) types.size(); i++) {
                if (i > 0) out += ", ";
                out += typeToString(*types[i]);
            }
            return out;
        }

        struct Dumper {
            Str out;

            void line(int indent, const Str &text) {
                out += Str(indent * 2, ' ');
                out += text;
                out += '\n';
            }

            void dumpChild(int indent, const Str &label, const ExprPtr &child) {
                if (!child) return;
                line(indent, label);
                dumpExpr(*child, indent + 1);
            }

            void dumpExpr(const Expr &e, int indent) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                        line(indent, "IntLit " + escapeText(e.text) + " @" + posStr(e.pos));
                        break;
                    case ExprKind::FloatLit:
                        line(indent, "FloatLit " + escapeText(e.text) + " @" + posStr(e.pos));
                        break;
                    case ExprKind::StrLit:
                        line(indent, "StrLit " + escapeText(e.text) + " @" + posStr(e.pos));
                        break;
                    case ExprKind::CharLit:
                        line(indent, "CharLit " + escapeText(e.text) + " @" + posStr(e.pos));
                        break;
                    case ExprKind::BoolLit:
                        line(indent, Str("BoolLit ") + (e.boolValue ? "true" : "false") + " @" + posStr(e.pos));
                        break;
                    case ExprKind::NullLit:
                        line(indent, "NullLit @" + posStr(e.pos));
                        break;
                    case ExprKind::Name:
                        line(indent, "Name " + e.text + " @" + posStr(e.pos));
                        break;
                    case ExprKind::GenericName:
                        line(indent, "GenericName " + e.text + "<" + joinTypes(e.typeArgs) + "> @" + posStr(e.pos));
                        break;
                    case ExprKind::Member:
                        line(indent, "Member ." + e.text + " @" + posStr(e.pos));
                        dumpChild(indent + 1, "Receiver", e.lhs);
                        break;
                    case ExprKind::Call:
                        line(indent, "Call @" + posStr(e.pos));
                        dumpChild(indent + 1, "Callee", e.lhs);
                        for (const ExprPtr &arg: e.args) {
                            dumpChild(indent + 1, "Arg", arg);
                        }
                        break;
                    case ExprKind::Index:
                        line(indent, "Index @" + posStr(e.pos));
                        dumpChild(indent + 1, "Receiver", e.lhs);
                        dumpChild(indent + 1, "Index", e.rhs);
                        break;
                    case ExprKind::Unary:
                        line(indent, "Unary " + e.text + " @" + posStr(e.pos));
                        dumpChild(indent + 1, "Operand", e.lhs);
                        break;
                    case ExprKind::Binary:
                        line(indent, "Binary " + e.text + " @" + posStr(e.pos));
                        dumpChild(indent + 1, "Lhs", e.lhs);
                        dumpChild(indent + 1, "Rhs", e.rhs);
                        break;
                    case ExprKind::Lambda:
                        line(indent, "Lambda @" + posStr(e.pos));
                        line(indent + 1, "Params " + joinNames(e.paramNames));
                        line(indent + 1, "Body");
                        for (const StmtPtr &s: e.body) {
                            dumpStmt(*s, indent + 2);
                        }
                        break;
                    case ExprKind::Ref:
                        line(indent, "Ref @" + posStr(e.pos));
                        dumpChild(indent + 1, "Operand", e.lhs);
                        break;
                    case ExprKind::Deref:
                        line(indent, "Deref @" + posStr(e.pos));
                        dumpChild(indent + 1, "Operand", e.lhs);
                        break;
                    case ExprKind::Copy:
                        line(indent, "Copy @" + posStr(e.pos));
                        dumpChild(indent + 1, "Operand", e.lhs);
                        break;
                }
            }

            void dumpStmt(const Stmt &s, int indent) {
                switch (s.kind) {
                    case StmtKind::VarDecl: {
                        Str text = Str("VarDecl ") + (s.isVar ? "var " : "val ") + s.name;
                        if (s.type) text += ": " + typeToString(*s.type);
                        text += " @" + posStr(s.pos);
                        line(indent, text);
                        if (s.init) {
                            line(indent + 1, "Init");
                            dumpExpr(*s.init, indent + 2);
                        }
                        break;
                    }
                    case StmtKind::Assign:
                        line(indent, "Assign " + s.op + " @" + posStr(s.pos));
                        dumpChild(indent + 1, "Target", s.target);
                        dumpChild(indent + 1, "Value", s.value);
                        break;
                    case StmtKind::If:
                        line(indent, "If @" + posStr(s.pos));
                        dumpChild(indent + 1, "Cond", s.cond);
                        line(indent + 1, "Then");
                        for (const StmtPtr &child: s.thenBody) {
                            dumpStmt(*child, indent + 2);
                        }
                        if (s.hasElse) {
                            line(indent + 1, "Else");
                            for (const StmtPtr &child: s.elseBody) {
                                dumpStmt(*child, indent + 2);
                            }
                        }
                        break;
                    case StmtKind::While:
                        line(indent, "While @" + posStr(s.pos));
                        dumpChild(indent + 1, "Cond", s.cond);
                        line(indent + 1, "Body");
                        for (const StmtPtr &child: s.body) {
                            dumpStmt(*child, indent + 2);
                        }
                        break;
                    case StmtKind::Return:
                        line(indent, "Return @" + posStr(s.pos));
                        if (s.returnValue) {
                            dumpExpr(*s.returnValue, indent + 1);
                        }
                        break;
                    case StmtKind::Break:
                        line(indent, "Break @" + posStr(s.pos));
                        break;
                    case StmtKind::Continue:
                        line(indent, "Continue @" + posStr(s.pos));
                        break;
                    case StmtKind::ExprStmt:
                        line(indent, "ExprStmt @" + posStr(s.pos));
                        dumpExpr(*s.expr, indent + 1);
                        break;
                }
            }

            void dumpDecl(const Decl &d, int indent) {
                switch (d.kind) {
                    case DeclKind::DataClass:
                        line(indent, "DataClass " + d.name + " @" + posStr(d.pos));
                        for (const Field &field: d.fields) {
                            Str text = Str("Field ") + (field.isVar ? "var " : "val ") + field.name;
                            if (field.type) text += ": " + typeToString(*field.type);
                            text += " @" + posStr(field.pos);
                            line(indent + 1, text);
                        }
                        for (const DeclPtr &method: d.methods) {
                            dumpDecl(*method, indent + 1);
                        }
                        break;
                    case DeclKind::Enum:
                        line(indent, "Enum " + d.name + " @" + posStr(d.pos));
                        for (const EnumMember &member: d.members) {
                            Str text = "Member " + member.name;
                            if (member.hasValue) text += " = " + std::to_string(member.value);
                            text += " @" + posStr(member.pos);
                            line(indent + 1, text);
                        }
                        break;
                    case DeclKind::TypeAlias:
                        line(indent, "TypeAlias " + d.name + " @" + posStr(d.pos));
                        if (!d.typeParams.empty()) {
                            line(indent + 1, "TypeParams " + joinNames(d.typeParams));
                        }
                        if (d.targetType) {
                            line(indent + 1, "Target " + typeToString(*d.targetType));
                        }
                        break;
                    case DeclKind::Function:
                        line(indent, Str(d.isNative ? "NativeFunction " : "Function ") + d.name + " @" + posStr(d.pos));
                        if (d.hasReceiver && d.receiverType) {
                            line(indent + 1, "Receiver " + typeToString(*d.receiverType));
                        }
                        if (!d.functionTypeParams.empty()) {
                            line(indent + 1, "TypeParams " + joinNames(d.functionTypeParams));
                        }
                        for (const Param &param: d.params) {
                            Str text = "Param " + param.name;
                            text += param.type ? ": " + typeToString(*param.type) : ": <none>";
                            line(indent + 1, text);
                        }
                        if (d.returnType) {
                            line(indent + 1, "Return " + typeToString(*d.returnType));
                        }
                        if (d.hasNativeSymbol) {
                            line(indent + 1, "NativeSymbol " + escapeText(d.nativeSymbol));
                        }
                        if (d.hasBody) {
                            line(indent + 1, "Body");
                            for (const StmtPtr &stmt: d.body) {
                                dumpStmt(*stmt, indent + 2);
                            }
                        }
                        break;
                }
            }
        };
    }

    Str typeToString(const TypeExpr &type) {
        switch (type.kind) {
            case TypeKind::IntLit:
                return type.text;
            case TypeKind::Named:
                return type.name;
            case TypeKind::Generic:
                return type.name + "<" + joinTypes(type.typeArgs) + ">";
            case TypeKind::Reference:
                return "&" + (type.inner ? typeToString(*type.inner) : Str("?"));
            case TypeKind::Pointer:
                return "*" + (type.inner ? typeToString(*type.inner) : Str("?"));
            case TypeKind::Function: {
                Str out = "(" + joinTypes(type.paramTypes) + ") -> ";
                out += type.returnType ? typeToString(*type.returnType) : Str("?");
                return out;
            }
        }
        return "?";
    }

    Str dumpModule(const Module &module) {
        Dumper dumper;
        dumper.line(0, "Module @" + posStr(module.pos));
        for (const Import &import: module.imports) {
            dumper.line(1, "Import " + joinPath(import.path) + " @" + posStr(import.pos));
        }
        for (const DeclPtr &decl: module.declarations) {
            dumper.dumpDecl(*decl, 1);
        }
        return dumper.out;
    }
}
