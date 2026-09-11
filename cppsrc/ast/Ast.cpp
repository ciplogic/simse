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
                    case StmtKind::Switch:
                        line(indent, "Switch @" + posStr(s.pos));
                        dumpChild(indent + 1, "Cond", s.cond);
                        for (const SwitchCase &switchCase: s.cases) {
                            if (switchCase.isDefault) {
                                line(indent + 1, "Default @" + posStr(switchCase.pos));
                            } else {
                                line(indent + 1, "Case @" + posStr(switchCase.pos));
                                if (switchCase.label) dumpExpr(*switchCase.label, indent + 2);
                            }
                            for (const StmtPtr &child: switchCase.body) {
                                dumpStmt(*child, indent + 2);
                            }
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
                        if (!d.typeParams.empty()) {
                            line(indent + 1, "TypeParams " + joinNames(d.typeParams));
                        }
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
                        if (!d.typeParams.empty()) {
                            line(indent + 1, "TypeParams " + joinNames(d.typeParams));
                        }
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
        if (!module.package.empty()) {
            dumper.line(1, "Package " + joinPath(module.package) + " @" + posStr(module.packagePos));
        }
        for (const Import &import: module.imports) {
            dumper.line(1, "Import " + joinPath(import.path) + " @" + posStr(import.pos));
        }
        for (const DeclPtr &decl: module.declarations) {
            dumper.dumpDecl(*decl, 1);
        }
        return dumper.out;
    }

    // ---- AST -> XmlNode (impl_specs/ast-xmlnode.md) ------------------------

    namespace {
        Str xmlEscape(const Str &text) {
            Str escaped;
            for (char ch: text) {
                switch (ch) {
                    case '\\': escaped += "\\\\"; break;
                    case '\n': escaped += "\\n"; break;
                    case '\r': escaped += "\\r"; break;
                    case '\t': escaped += "\\t"; break;
                    case '\'': escaped += "\\'"; break;
                    default: escaped += ch; break;
                }
            }
            return escaped;
        }

        Str boolStr(bool value) {
            return value ? "true" : "false";
        }

        XmlNode makeNode(const Str &name, const List<Attribute> &attrs) {
            XmlNode node;
            node.name = name;
            node.attributes = attrs;
            node.Children = makeList<XmlNode>();
            return node;
        }

        void addChild(XmlNode &parent, const XmlNode &child) {
            parent.Children->push_back(child);
        }

        void addPos(List<Attribute> &attrs, const SourcePos &pos) {
            attrs.push_back(Attribute("line", std::to_string(pos.line)));
            attrs.push_back(Attribute("column", std::to_string(pos.column)));
        }

        Str typeKindName(TypeKind kind) {
            switch (kind) {
                case TypeKind::IntLit: return "IntLit";
                case TypeKind::Named: return "Named";
                case TypeKind::Generic: return "Generic";
                case TypeKind::Reference: return "Reference";
                case TypeKind::Pointer: return "Pointer";
                case TypeKind::Function: return "Function";
            }
            return "?";
        }

        Str exprKindName(ExprKind kind) {
            switch (kind) {
                case ExprKind::IntLit: return "IntLit";
                case ExprKind::FloatLit: return "FloatLit";
                case ExprKind::StrLit: return "StrLit";
                case ExprKind::CharLit: return "CharLit";
                case ExprKind::BoolLit: return "BoolLit";
                case ExprKind::NullLit: return "NullLit";
                case ExprKind::Name: return "Name";
                case ExprKind::GenericName: return "GenericName";
                case ExprKind::Member: return "Member";
                case ExprKind::Call: return "Call";
                case ExprKind::Index: return "Index";
                case ExprKind::Unary: return "Unary";
                case ExprKind::Binary: return "Binary";
                case ExprKind::Lambda: return "Lambda";
                case ExprKind::Ref: return "Ref";
                case ExprKind::Deref: return "Deref";
                case ExprKind::Copy: return "Copy";
            }
            return "?";
        }

        Str stmtKindName(StmtKind kind) {
            switch (kind) {
                case StmtKind::VarDecl: return "VarDecl";
                case StmtKind::Assign: return "Assign";
                case StmtKind::If: return "If";
                case StmtKind::While: return "While";
                case StmtKind::Switch: return "Switch";
                case StmtKind::Return: return "Return";
                case StmtKind::Break: return "Break";
                case StmtKind::Continue: return "Continue";
                case StmtKind::ExprStmt: return "ExprStmt";
            }
            return "?";
        }

        Str declKindName(DeclKind kind) {
            switch (kind) {
                case DeclKind::DataClass: return "DataClass";
                case DeclKind::Enum: return "Enum";
                case DeclKind::TypeAlias: return "TypeAlias";
                case DeclKind::Function: return "Function";
            }
            return "?";
        }

        XmlNode typeToXml(const Str &role, const TypeExpr &type);
        XmlNode exprToXml(const Str &role, const Expr &expr);
        XmlNode stmtToXml(const Stmt &stmt);
        XmlNode declToXml(const Decl &decl);

        XmlNode typeToXml(const Str &role, const TypeExpr &type) {
            List<Attribute> attrs;
            attrs.push_back(Attribute("kind", Str("Type.") + typeKindName(type.kind)));
            addPos(attrs, type.pos);
            if (type.kind == TypeKind::Named || type.kind == TypeKind::Generic) {
                attrs.push_back(Attribute("name", type.name));
            } else if (type.kind == TypeKind::IntLit) {
                attrs.push_back(Attribute("text", type.text));
            }
            XmlNode node = makeNode(role, attrs);
            if (type.kind == TypeKind::Reference || type.kind == TypeKind::Pointer) {
                if (type.inner) addChild(node, typeToXml("Inner", *type.inner));
            } else if (type.kind == TypeKind::Generic) {
                for (const TypePtr &arg: type.typeArgs) addChild(node, typeToXml("TypeArg", *arg));
            } else if (type.kind == TypeKind::Function) {
                for (const TypePtr &param: type.paramTypes) {
                    addChild(node, typeToXml("ParamType", *param));
                }
                if (type.returnType) addChild(node, typeToXml("ReturnType", *type.returnType));
            }
            return node;
        }

        XmlNode exprToXml(const Str &role, const Expr &expr) {
            List<Attribute> attrs;
            attrs.push_back(Attribute("kind", Str("Expr.") + exprKindName(expr.kind)));
            addPos(attrs, expr.pos);
            switch (expr.kind) {
                case ExprKind::IntLit:
                case ExprKind::FloatLit:
                case ExprKind::StrLit:
                case ExprKind::CharLit:
                    attrs.push_back(Attribute("text", expr.text));
                    break;
                case ExprKind::BoolLit:
                    attrs.push_back(Attribute("value", boolStr(expr.boolValue)));
                    break;
                case ExprKind::Name:
                case ExprKind::GenericName:
                case ExprKind::Member:
                    attrs.push_back(Attribute("name", expr.text));
                    break;
                case ExprKind::Unary:
                case ExprKind::Binary:
                    attrs.push_back(Attribute("op", expr.text));
                    break;
                case ExprKind::Lambda: {
                    Str names;
                    for (int i = 0; i < (int) expr.paramNames.size(); i++) {
                        if (i > 0) names += ",";
                        names += expr.paramNames[i];
                    }
                    attrs.push_back(Attribute("params", names));
                    break;
                }
                default:
                    break;
            }
            XmlNode node = makeNode(role, attrs);
            switch (expr.kind) {
                case ExprKind::GenericName:
                    for (const TypePtr &arg: expr.typeArgs) addChild(node, typeToXml("TypeArg", *arg));
                    break;
                case ExprKind::Member:
                    if (expr.lhs) addChild(node, exprToXml("Receiver", *expr.lhs));
                    break;
                case ExprKind::Call:
                    if (expr.lhs) addChild(node, exprToXml("Callee", *expr.lhs));
                    for (const ExprPtr &arg: expr.args) addChild(node, exprToXml("Arg", *arg));
                    break;
                case ExprKind::Index:
                    if (expr.lhs) addChild(node, exprToXml("Receiver", *expr.lhs));
                    if (expr.rhs) addChild(node, exprToXml("Index", *expr.rhs));
                    break;
                case ExprKind::Unary:
                case ExprKind::Ref:
                case ExprKind::Deref:
                case ExprKind::Copy:
                    if (expr.lhs) addChild(node, exprToXml("Operand", *expr.lhs));
                    break;
                case ExprKind::Binary:
                    if (expr.lhs) addChild(node, exprToXml("Lhs", *expr.lhs));
                    if (expr.rhs) addChild(node, exprToXml("Rhs", *expr.rhs));
                    break;
                case ExprKind::Lambda: {
                    for (const TypePtr &paramType: expr.paramTypes) {
                        if (paramType) addChild(node, typeToXml("ParamType", *paramType));
                    }
                    XmlNode body = makeNode("Body", List<Attribute>());
                    for (const StmtPtr &s: expr.body) addChild(body, stmtToXml(*s));
                    addChild(node, body);
                    break;
                }
                default:
                    break;
            }
            return node;
        }

        XmlNode stmtToXml(const Stmt &stmt) {
            List<Attribute> attrs;
            attrs.push_back(Attribute("kind", Str("Stmt.") + stmtKindName(stmt.kind)));
            addPos(attrs, stmt.pos);
            if (stmt.kind == StmtKind::VarDecl) {
                attrs.push_back(Attribute("name", stmt.name));
                attrs.push_back(Attribute("isVar", boolStr(stmt.isVar)));
            } else if (stmt.kind == StmtKind::Assign) {
                attrs.push_back(Attribute("op", stmt.op));
            }
            XmlNode node = makeNode("Stmt", attrs);
            switch (stmt.kind) {
                case StmtKind::VarDecl:
                    if (stmt.type) addChild(node, typeToXml("Type", *stmt.type));
                    if (stmt.init) addChild(node, exprToXml("Init", *stmt.init));
                    break;
                case StmtKind::Assign:
                    if (stmt.target) addChild(node, exprToXml("Target", *stmt.target));
                    if (stmt.value) addChild(node, exprToXml("Value", *stmt.value));
                    break;
                case StmtKind::If: {
                    if (stmt.cond) addChild(node, exprToXml("Cond", *stmt.cond));
                    XmlNode thenBlock = makeNode("Then", List<Attribute>());
                    for (const StmtPtr &s: stmt.thenBody) addChild(thenBlock, stmtToXml(*s));
                    addChild(node, thenBlock);
                    if (stmt.hasElse) {
                        XmlNode elseBlock = makeNode("Else", List<Attribute>());
                        for (const StmtPtr &s: stmt.elseBody) addChild(elseBlock, stmtToXml(*s));
                        addChild(node, elseBlock);
                    }
                    break;
                }
                case StmtKind::While: {
                    if (stmt.cond) addChild(node, exprToXml("Cond", *stmt.cond));
                    XmlNode body = makeNode("Body", List<Attribute>());
                    for (const StmtPtr &s: stmt.body) addChild(body, stmtToXml(*s));
                    addChild(node, body);
                    break;
                }
                case StmtKind::Switch: {
                    if (stmt.cond) addChild(node, exprToXml("Cond", *stmt.cond));
                    for (const SwitchCase &switchCase: stmt.cases) {
                        List<Attribute> caseAttrs;
                        caseAttrs.push_back(Attribute("isDefault", boolStr(switchCase.isDefault)));
                        addPos(caseAttrs, switchCase.pos);
                        XmlNode caseNode = makeNode("Case", caseAttrs);
                        if (!switchCase.isDefault && switchCase.label) {
                            addChild(caseNode, exprToXml("Label", *switchCase.label));
                        }
                        for (const StmtPtr &s: switchCase.body) addChild(caseNode, stmtToXml(*s));
                        addChild(node, caseNode);
                    }
                    break;
                }
                case StmtKind::Return:
                    if (stmt.returnValue) addChild(node, exprToXml("Value", *stmt.returnValue));
                    break;
                case StmtKind::ExprStmt:
                    if (stmt.expr) addChild(node, exprToXml("Expr", *stmt.expr));
                    break;
                default:
                    break;
            }
            return node;
        }

        XmlNode declToXml(const Decl &decl) {
            List<Attribute> attrs;
            attrs.push_back(Attribute("kind", declKindName(decl.kind)));
            addPos(attrs, decl.pos);
            attrs.push_back(Attribute("name", decl.name));
            if (decl.kind == DeclKind::Function) {
                attrs.push_back(Attribute("isNative", boolStr(decl.isNative)));
                attrs.push_back(Attribute("hasBody", boolStr(decl.hasBody)));
                attrs.push_back(Attribute("hasReceiver", boolStr(decl.hasReceiver)));
                attrs.push_back(Attribute("hasNativeSymbol", boolStr(decl.hasNativeSymbol)));
                if (decl.hasNativeSymbol) {
                    attrs.push_back(Attribute("nativeSymbol", decl.nativeSymbol));
                }
            }
            XmlNode node = makeNode(declKindName(decl.kind), attrs);
            switch (decl.kind) {
                case DeclKind::DataClass:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode("TypeParam", List<Attribute>{Attribute("name", param)}));
                    }
                    for (const Field &field: decl.fields) {
                        List<Attribute> fieldAttrs;
                        fieldAttrs.push_back(Attribute("name", field.name));
                        fieldAttrs.push_back(Attribute("isVar", boolStr(field.isVar)));
                        addPos(fieldAttrs, field.pos);
                        XmlNode fieldNode = makeNode("Field", fieldAttrs);
                        if (field.type) addChild(fieldNode, typeToXml("Type", *field.type));
                        addChild(node, fieldNode);
                    }
                    for (const DeclPtr &method: decl.methods) addChild(node, declToXml(*method));
                    break;
                case DeclKind::Enum:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode("TypeParam", List<Attribute>{Attribute("name", param)}));
                    }
                    for (const EnumMember &member: decl.members) {
                        List<Attribute> memberAttrs;
                        memberAttrs.push_back(Attribute("name", member.name));
                        memberAttrs.push_back(Attribute("hasValue", boolStr(member.hasValue)));
                        memberAttrs.push_back(Attribute("value", std::to_string(member.value)));
                        addPos(memberAttrs, member.pos);
                        addChild(node, makeNode("EnumMember", memberAttrs));
                    }
                    break;
                case DeclKind::TypeAlias:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode("TypeParam", List<Attribute>{Attribute("name", param)}));
                    }
                    if (decl.targetType) addChild(node, typeToXml("TargetType", *decl.targetType));
                    break;
                case DeclKind::Function:
                    if (decl.hasReceiver && decl.receiverType) {
                        addChild(node, typeToXml("Receiver", *decl.receiverType));
                    }
                    for (const Str &param: decl.functionTypeParams) {
                        addChild(node, makeNode("TypeParam", List<Attribute>{Attribute("name", param)}));
                    }
                    for (const Param &param: decl.params) {
                        List<Attribute> paramAttrs;
                        paramAttrs.push_back(Attribute("name", param.name));
                        addPos(paramAttrs, param.pos);
                        XmlNode paramNode = makeNode("Param", paramAttrs);
                        if (param.type) addChild(paramNode, typeToXml("Type", *param.type));
                        addChild(node, paramNode);
                    }
                    if (decl.returnType) addChild(node, typeToXml("ReturnType", *decl.returnType));
                    if (decl.hasBody) {
                        XmlNode body = makeNode("Body", List<Attribute>());
                        for (const StmtPtr &s: decl.body) addChild(body, stmtToXml(*s));
                        addChild(node, body);
                    }
                    break;
            }
            return node;
        }

        void dumpXmlRec(const XmlNode &node, int depth, Str &out) {
            out.append((size_t) depth * 2, ' ');
            out += node.name;
            for (const Attribute &attribute: node.attributes) {
                out += " ";
                out += attribute.name;
                out += "='";
                out += xmlEscape(attribute.value);
                out += "'";
            }
            out += '\n';
            if (node.Children) {
                for (const XmlNode &child: *node.Children) {
                    dumpXmlRec(child, depth + 1, out);
                }
            }
        }
    }

    XmlNode toXmlNode(const Module &module) {
        List<Attribute> attrs;
        attrs.push_back(Attribute("kind", "Module"));
        addPos(attrs, module.pos);
        // The `package` attribute is present only when one is declared, so
        // package-less files keep their previous AST (and goldens).
        if (!module.package.empty()) {
            attrs.push_back(Attribute("package", joinPath(module.package)));
        }
        XmlNode root = makeNode("Module", attrs);
        for (const Import &import: module.imports) {
            List<Attribute> importAttrs;
            importAttrs.push_back(Attribute("path", joinPath(import.path)));
            addPos(importAttrs, import.pos);
            addChild(root, makeNode("Import", importAttrs));
        }
        for (const DeclPtr &decl: module.declarations) addChild(root, declToXml(*decl));
        return root;
    }

    Str dumpXmlNode(const XmlNode &node) {
        Str out;
        dumpXmlRec(node, 0, out);
        return out;
    }
}
