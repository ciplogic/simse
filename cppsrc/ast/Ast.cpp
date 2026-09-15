#include "Ast.h"

#include "../rtl/simse.hpp"

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
                    case StmtKind::Label:
                        line(indent, "Label " + s.name + " @" + posStr(s.pos));
                        break;
                    case StmtKind::Goto:
                        line(indent, "Goto " + s.name + " @" + posStr(s.pos));
                        break;
                    case StmtKind::IfTrue:
                    case StmtKind::IfFalse:
                        line(indent, (s.kind == StmtKind::IfTrue ? "IfTrue " : "IfFalse ") + s.name
                                      + " @" + posStr(s.pos));
                        dumpChild(indent + 1, "Cond", s.cond);
                        break;
                    case StmtKind::Block:
                        line(indent, "Block @" + posStr(s.pos));
                        for (const StmtPtr &child: s.body) {
                            dumpStmt(*child, indent + 1);
                        }
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
                    case DeclKind::Var: {
                        Str text = Str("Var ") + (d.isVar ? "var " : "val ") + d.name;
                        if (d.type) text += ": " + typeToString(*d.type);
                        text += " @" + posStr(d.pos);
                        line(indent, text);
                        if (d.init) {
                            line(indent + 1, "Init");
                            dumpExpr(*d.init, indent + 2);
                        }
                        break;
                    }
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
        dumper.line(1, "Package " + joinPath(module.package) + " @" + posStr(module.packagePos));
        for (const Import &import: module.imports) {
            dumper.line(1, "Import " + joinPath(import.path) + " @" + posStr(import.pos));
        }
        for (const DeclPtr &decl: module.declarations) {
            dumper.dumpDecl(*decl, 1);
        }
        return dumper.out;
    }

    // ---- AST -> AstXmlNode (impl_specs/ast-xmlnode.md) ------------------------

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

        AstXmlNode makeNode(const AstNodeKind name, const AstNodeCategory kind,
                            const List<AstNodeAttribute> &attrs) {
            AstXmlNode node;
            node.name = name;
            node.kind = kind;
            node.attributes = attrs;
            // No children yet: the shared empty array, so a leaf node allocates
            // nothing (specs/xml-node.md).
            node.Children = Array<AstXmlNode>();
            return node;
        }

        // Appends one child: `Children` is an `Array<AstXmlNode>` (fixed length), so
        // this replaces the node's handle with a block one element longer through
        // the list round trip the array API specifies.
        void addChild(AstXmlNode &parent, const AstXmlNode &child) {
            List<AstXmlNode> children = simse_array_toList(parent.Children);
            children.push_back(child);
            parent.Children = simse_list_toArray(children);
        }

        void addPos(List<AstNodeAttribute> &attrs, const SourcePos &pos) {
            attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Line, std::to_string(pos.line)));
            attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Column, std::to_string(pos.column)));
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
                case StmtKind::Label: return "Label";
                case StmtKind::Goto: return "Goto";
                case StmtKind::IfTrue: return "IfTrue";
                case StmtKind::IfFalse: return "IfFalse";
                case StmtKind::Block: return "Block";
                case StmtKind::Yield: return "Yield";
            }
            return "?";
        }

        // The node category an expression/statement/type kind maps to. The text
        // helpers above spell the same values with the `Expr.`/`Stmt.`/`Type.`
        // prefix the schema uses for the `kind` attribute's *text*; the dump prints
        // that through `astNodeCategoryText`.
        AstNodeCategory exprCategory(ExprKind kind) {
            switch (kind) {
                case ExprKind::IntLit: return AstNodeCategory::ExprIntLit;
                case ExprKind::FloatLit: return AstNodeCategory::ExprFloatLit;
                case ExprKind::StrLit: return AstNodeCategory::ExprStrLit;
                case ExprKind::CharLit: return AstNodeCategory::ExprCharLit;
                case ExprKind::BoolLit: return AstNodeCategory::ExprBoolLit;
                case ExprKind::NullLit: return AstNodeCategory::ExprNullLit;
                case ExprKind::Name: return AstNodeCategory::ExprName;
                case ExprKind::GenericName: return AstNodeCategory::ExprGenericName;
                case ExprKind::Member: return AstNodeCategory::ExprMember;
                case ExprKind::Call: return AstNodeCategory::ExprCall;
                case ExprKind::Index: return AstNodeCategory::ExprIndex;
                case ExprKind::Unary: return AstNodeCategory::ExprUnary;
                case ExprKind::Binary: return AstNodeCategory::ExprBinary;
                case ExprKind::Lambda: return AstNodeCategory::ExprLambda;
                case ExprKind::Ref: return AstNodeCategory::ExprRef;
                case ExprKind::Deref: return AstNodeCategory::ExprDeref;
                case ExprKind::Copy: return AstNodeCategory::ExprCopy;
            }
            return AstNodeCategory::None;
        }

        AstNodeCategory stmtCategory(StmtKind kind) {
            switch (kind) {
                case StmtKind::VarDecl: return AstNodeCategory::StmtVarDecl;
                case StmtKind::Assign: return AstNodeCategory::StmtAssign;
                case StmtKind::If: return AstNodeCategory::StmtIf;
                case StmtKind::While: return AstNodeCategory::StmtWhile;
                case StmtKind::Switch: return AstNodeCategory::StmtSwitch;
                case StmtKind::Return: return AstNodeCategory::StmtReturn;
                case StmtKind::Break: return AstNodeCategory::StmtBreak;
                case StmtKind::Continue: return AstNodeCategory::StmtContinue;
                case StmtKind::ExprStmt: return AstNodeCategory::StmtExprStmt;
                case StmtKind::Label: return AstNodeCategory::StmtLabel;
                case StmtKind::Goto: return AstNodeCategory::StmtGoto;
                case StmtKind::IfTrue: return AstNodeCategory::StmtIfTrue;
                case StmtKind::IfFalse: return AstNodeCategory::StmtIfFalse;
                case StmtKind::Block: return AstNodeCategory::StmtBlock;
                case StmtKind::Yield: return AstNodeCategory::StmtYield;
            }
            return AstNodeCategory::None;
        }

        AstNodeCategory typeCategory(TypeKind kind) {
            switch (kind) {
                case TypeKind::IntLit: return AstNodeCategory::TypeIntLit;
                case TypeKind::Named: return AstNodeCategory::TypeNamed;
                case TypeKind::Generic: return AstNodeCategory::TypeGeneric;
                case TypeKind::Reference: return AstNodeCategory::TypeReference;
                case TypeKind::Pointer: return AstNodeCategory::TypePointer;
                case TypeKind::Function: return AstNodeCategory::TypeFunction;
                case TypeKind::Yield: return AstNodeCategory::TypeYield;
            }
            return AstNodeCategory::None;
        }

        Str declKindName(DeclKind kind) {
            switch (kind) {
                case DeclKind::DataClass: return "DataClass";
                case DeclKind::Enum: return "Enum";
                case DeclKind::TypeAlias: return "TypeAlias";
                case DeclKind::Function: return "Function";
                case DeclKind::Var: return "Var";
            }
            return "?";
        }

        // The same mapping for the node's category: the schema's `kind` value. The
        // text (`*KindName`) is what the dump prints, the enum is what the tree
        // carries and what every test compares.
        AstNodeCategory declCategory(DeclKind kind) {
            switch (kind) {
                case DeclKind::DataClass: return AstNodeCategory::DataClass;
                case DeclKind::Enum: return AstNodeCategory::Enum;
                case DeclKind::TypeAlias: return AstNodeCategory::TypeAlias;
                case DeclKind::Function: return AstNodeCategory::Function;
                case DeclKind::Var: return AstNodeCategory::Var;
            }
            return AstNodeCategory::None;
        }

        // The same mapping into the AST's role enum (the node's `name`): the text
        // is what the schema's `kind` attribute carries, the enum is the role.
        AstNodeKind declNodeKind(DeclKind kind) {
            switch (kind) {
                case DeclKind::DataClass: return AstNodeKind::DataClass;
                case DeclKind::Enum: return AstNodeKind::Enum;
                case DeclKind::TypeAlias: return AstNodeKind::TypeAlias;
                case DeclKind::Function: return AstNodeKind::Function;
                case DeclKind::Var: return AstNodeKind::Var;
            }
            return AstNodeKind::None;
        }

        AstXmlNode typeToXml(const AstNodeKind role, const TypeExpr &type);
        AstXmlNode exprToXml(const AstNodeKind role, const Expr &expr);
        AstXmlNode stmtToXml(const Stmt &stmt);
        AstXmlNode declToXml(const Decl &decl);

        AstXmlNode typeToXml(const AstNodeKind role, const TypeExpr &type) {
            List<AstNodeAttribute> attrs;
            addPos(attrs, type.pos);
            if (type.kind == TypeKind::Named || type.kind == TypeKind::Generic) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, type.name));
            } else if (type.kind == TypeKind::IntLit) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Text, type.text));
            }
            AstXmlNode node = makeNode(role, typeCategory(type.kind), attrs);
            if (type.kind == TypeKind::Reference || type.kind == TypeKind::Pointer
                || type.kind == TypeKind::Yield) {
                if (type.inner) addChild(node, typeToXml(AstNodeKind::Inner, *type.inner));
            } else if (type.kind == TypeKind::Generic) {
                for (const TypePtr &arg: type.typeArgs) addChild(node, typeToXml(AstNodeKind::TypeArg, *arg));
            } else if (type.kind == TypeKind::Function) {
                for (const TypePtr &param: type.paramTypes) {
                    addChild(node, typeToXml(AstNodeKind::ParamType, *param));
                }
                if (type.returnType) addChild(node, typeToXml(AstNodeKind::ReturnType, *type.returnType));
            }
            return node;
        }

        AstXmlNode exprToXml(const AstNodeKind role, const Expr &expr) {
            List<AstNodeAttribute> attrs;
            addPos(attrs, expr.pos);
            switch (expr.kind) {
                case ExprKind::IntLit:
                case ExprKind::FloatLit:
                case ExprKind::StrLit:
                case ExprKind::CharLit:
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Text, expr.text));
                    break;
                case ExprKind::BoolLit:
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Value, boolStr(expr.boolValue)));
                    break;
                case ExprKind::Name:
                case ExprKind::GenericName:
                case ExprKind::Member:
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, expr.text));
                    break;
                case ExprKind::Unary:
                case ExprKind::Binary:
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Op, expr.text));
                    break;
                case ExprKind::Lambda: {
                    Str names;
                    for (int i = 0; i < (int) expr.paramNames.size(); i++) {
                        if (i > 0) names += ",";
                        names += expr.paramNames[i];
                    }
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Params, names));
                    break;
                }
                default:
                    break;
            }
            AstXmlNode node = makeNode(role, exprCategory(expr.kind), attrs);
            switch (expr.kind) {
                case ExprKind::GenericName:
                    for (const TypePtr &arg: expr.typeArgs) addChild(node, typeToXml(AstNodeKind::TypeArg, *arg));
                    break;
                case ExprKind::Member:
                    if (expr.lhs) addChild(node, exprToXml(AstNodeKind::Receiver, *expr.lhs));
                    break;
                case ExprKind::Call:
                    if (expr.lhs) addChild(node, exprToXml(AstNodeKind::Callee, *expr.lhs));
                    for (const ExprPtr &arg: expr.args) addChild(node, exprToXml(AstNodeKind::Arg, *arg));
                    break;
                case ExprKind::Index:
                    if (expr.lhs) addChild(node, exprToXml(AstNodeKind::Receiver, *expr.lhs));
                    if (expr.rhs) addChild(node, exprToXml(AstNodeKind::Index, *expr.rhs));
                    break;
                case ExprKind::Unary:
                case ExprKind::Ref:
                case ExprKind::Deref:
                case ExprKind::Copy:
                    if (expr.lhs) addChild(node, exprToXml(AstNodeKind::Operand, *expr.lhs));
                    break;
                case ExprKind::Binary:
                    if (expr.lhs) addChild(node, exprToXml(AstNodeKind::Lhs, *expr.lhs));
                    if (expr.rhs) addChild(node, exprToXml(AstNodeKind::Rhs, *expr.rhs));
                    break;
                case ExprKind::Lambda: {
                    for (const TypePtr &paramType: expr.paramTypes) {
                        if (paramType) addChild(node, typeToXml(AstNodeKind::ParamType, *paramType));
                    }
                    AstXmlNode body = makeNode(AstNodeKind::Body, AstNodeCategory::None, List<AstNodeAttribute>());
                    for (const StmtPtr &s: expr.body) addChild(body, stmtToXml(*s));
                    addChild(node, body);
                    break;
                }
                default:
                    break;
            }
            return node;
        }

        AstXmlNode stmtToXml(const Stmt &stmt) {
            List<AstNodeAttribute> attrs;
            addPos(attrs, stmt.pos);
            if (stmt.kind == StmtKind::VarDecl) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, stmt.name));
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::IsVar, boolStr(stmt.isVar)));
            } else if (stmt.kind == StmtKind::Assign) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Op, stmt.op));
            } else if (stmt.kind == StmtKind::Label || stmt.kind == StmtKind::Goto
                       || stmt.kind == StmtKind::IfTrue || stmt.kind == StmtKind::IfFalse) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, stmt.name));
            }
            AstXmlNode node = makeNode(AstNodeKind::Stmt, stmtCategory(stmt.kind), attrs);
            switch (stmt.kind) {
                case StmtKind::VarDecl:
                    if (stmt.type) addChild(node, typeToXml(AstNodeKind::Type, *stmt.type));
                    if (stmt.init) addChild(node, exprToXml(AstNodeKind::Init, *stmt.init));
                    break;
                case StmtKind::Assign:
                    if (stmt.target) addChild(node, exprToXml(AstNodeKind::Target, *stmt.target));
                    if (stmt.value) addChild(node, exprToXml(AstNodeKind::Value, *stmt.value));
                    break;
                case StmtKind::If: {
                    if (stmt.cond) addChild(node, exprToXml(AstNodeKind::Cond, *stmt.cond));
                    AstXmlNode thenBlock = makeNode(AstNodeKind::Then, AstNodeCategory::None, List<AstNodeAttribute>());
                    for (const StmtPtr &s: stmt.thenBody) addChild(thenBlock, stmtToXml(*s));
                    addChild(node, thenBlock);
                    if (stmt.hasElse) {
                        AstXmlNode elseBlock = makeNode(AstNodeKind::Else, AstNodeCategory::None, List<AstNodeAttribute>());
                        for (const StmtPtr &s: stmt.elseBody) addChild(elseBlock, stmtToXml(*s));
                        addChild(node, elseBlock);
                    }
                    break;
                }
                case StmtKind::While: {
                    if (stmt.cond) addChild(node, exprToXml(AstNodeKind::Cond, *stmt.cond));
                    AstXmlNode body = makeNode(AstNodeKind::Body, AstNodeCategory::None, List<AstNodeAttribute>());
                    for (const StmtPtr &s: stmt.body) addChild(body, stmtToXml(*s));
                    addChild(node, body);
                    break;
                }
                case StmtKind::Switch: {
                    if (stmt.cond) addChild(node, exprToXml(AstNodeKind::Cond, *stmt.cond));
                    for (const SwitchCase &switchCase: stmt.cases) {
                        List<AstNodeAttribute> caseAttrs;
                        caseAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::IsDefault, boolStr(switchCase.isDefault)));
                        addPos(caseAttrs, switchCase.pos);
                        AstXmlNode caseNode = makeNode(AstNodeKind::Case, AstNodeCategory::None, caseAttrs);
                        if (!switchCase.isDefault && switchCase.label) {
                            addChild(caseNode, exprToXml(AstNodeKind::Label, *switchCase.label));
                        }
                        for (const StmtPtr &s: switchCase.body) addChild(caseNode, stmtToXml(*s));
                        addChild(node, caseNode);
                    }
                    break;
                }
                case StmtKind::Return:
                    if (stmt.returnValue) addChild(node, exprToXml(AstNodeKind::Value, *stmt.returnValue));
                    break;
                case StmtKind::Yield:
                    // The value the machine hands out, in the role `return` uses: only
                    // the lowering knows what a yield *does* (impl_specs/yield.md).
                    if (stmt.expr) addChild(node, exprToXml(AstNodeKind::Value, *stmt.expr));
                    break;
                case StmtKind::ExprStmt:
                    if (stmt.expr) addChild(node, exprToXml(AstNodeKind::Expr, *stmt.expr));
                    break;
                case StmtKind::IfTrue:
                case StmtKind::IfFalse:
                    if (stmt.cond) addChild(node, exprToXml(AstNodeKind::Cond, *stmt.cond));
                    break;
                case StmtKind::Block: {
                    AstXmlNode body = makeNode(AstNodeKind::Body, AstNodeCategory::None, List<AstNodeAttribute>());
                    for (const StmtPtr &s: stmt.body) addChild(body, stmtToXml(*s));
                    addChild(node, body);
                    break;
                }
                default:
                    break;
            }
            return node;
        }

        AstXmlNode declToXml(const Decl &decl) {
            List<AstNodeAttribute> attrs;
            addPos(attrs, decl.pos);
            attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, decl.name));
            if (decl.kind == DeclKind::Function) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::IsNative, boolStr(decl.isNative)));
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::HasBody, boolStr(decl.hasBody)));
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::HasReceiver, boolStr(decl.hasReceiver)));
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::HasNativeSymbol, boolStr(decl.hasNativeSymbol)));
                if (decl.hasNativeSymbol) {
                    attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::NativeSymbol, decl.nativeSymbol));
                }
            } else if (decl.kind == DeclKind::Var) {
                attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::IsVar, boolStr(decl.isVar)));
            }
            AstXmlNode node = makeNode(declNodeKind(decl.kind), declCategory(decl.kind), attrs);
            switch (decl.kind) {
                case DeclKind::DataClass:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode(AstNodeKind::TypeParam, AstNodeCategory::None, List<AstNodeAttribute>{AstNodeAttribute(AstNodeAttributeKind::Name, param)}));
                    }
                    for (const Field &field: decl.fields) {
                        List<AstNodeAttribute> fieldAttrs;
                        fieldAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, field.name));
                        fieldAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::IsVar, boolStr(field.isVar)));
                        addPos(fieldAttrs, field.pos);
                        AstXmlNode fieldNode = makeNode(AstNodeKind::Field, AstNodeCategory::None, fieldAttrs);
                        if (field.type) addChild(fieldNode, typeToXml(AstNodeKind::Type, *field.type));
                        addChild(node, fieldNode);
                    }
                    for (const DeclPtr &method: decl.methods) addChild(node, declToXml(*method));
                    break;
                case DeclKind::Enum:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode(AstNodeKind::TypeParam, AstNodeCategory::None, List<AstNodeAttribute>{AstNodeAttribute(AstNodeAttributeKind::Name, param)}));
                    }
                    for (const EnumMember &member: decl.members) {
                        List<AstNodeAttribute> memberAttrs;
                        memberAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, member.name));
                        memberAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::HasValue, boolStr(member.hasValue)));
                        memberAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Value, std::to_string(member.value)));
                        addPos(memberAttrs, member.pos);
                        addChild(node, makeNode(AstNodeKind::EnumMember, AstNodeCategory::None, memberAttrs));
                    }
                    break;
                case DeclKind::TypeAlias:
                    for (const Str &param: decl.typeParams) {
                        addChild(node, makeNode(AstNodeKind::TypeParam, AstNodeCategory::None, List<AstNodeAttribute>{AstNodeAttribute(AstNodeAttributeKind::Name, param)}));
                    }
                    if (decl.targetType) addChild(node, typeToXml(AstNodeKind::TargetType, *decl.targetType));
                    break;
                case DeclKind::Function:
                    if (decl.hasReceiver && decl.receiverType) {
                        addChild(node, typeToXml(AstNodeKind::Receiver, *decl.receiverType));
                    }
                    for (const Str &param: decl.functionTypeParams) {
                        addChild(node, makeNode(AstNodeKind::TypeParam, AstNodeCategory::None, List<AstNodeAttribute>{AstNodeAttribute(AstNodeAttributeKind::Name, param)}));
                    }
                    for (const Param &param: decl.params) {
                        List<AstNodeAttribute> paramAttrs;
                        paramAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Name, param.name));
                        addPos(paramAttrs, param.pos);
                        AstXmlNode paramNode = makeNode(AstNodeKind::Param, AstNodeCategory::None, paramAttrs);
                        if (param.type) addChild(paramNode, typeToXml(AstNodeKind::Type, *param.type));
                        addChild(node, paramNode);
                    }
                    if (decl.returnType) addChild(node, typeToXml(AstNodeKind::ReturnType, *decl.returnType));
                    if (decl.hasBody) {
                        AstXmlNode body = makeNode(AstNodeKind::Body, AstNodeCategory::None, List<AstNodeAttribute>());
                        for (const StmtPtr &s: decl.body) addChild(body, stmtToXml(*s));
                        addChild(node, body);
                    }
                    break;
                case DeclKind::Var:
                    if (decl.type) addChild(node, typeToXml(AstNodeKind::Type, *decl.type));
                    if (decl.init) addChild(node, exprToXml(AstNodeKind::Init, *decl.init));
                    break;
            }
            return node;
        }

        void dumpXmlRec(const AstXmlNode &node, int depth, Str &out) {
            out.append((size_t) depth * 2, ' ');
            out += astNodeKindText(node.name);
            // The category is a field, not an attribute, but the dump prints it in
            // its schema position: first, as `kind='...'`.
            if (node.kind != AstNodeCategory::None) {
                out += " kind='";
                out += astNodeCategoryText(node.kind);
                out += "'";
            }
            for (const AstNodeAttribute &attribute: node.attributes) {
                out += " ";
                out += astNodeAttributeText(attribute.name);
                out += "='";
                out += xmlEscape(attribute.value);
                out += "'";
            }
            out += '\n';
            for (int i = 0; i < node.Children.count(); i++) {
                dumpXmlRec(node.Children[i], depth + 1, out);
            }
        }
    }

    // The schema's spelling of a role and of an attribute key: what the dump
    // prints, and the only place the enums turn back into text
    // (impl_specs/ast-xmlnode.md).
    const char *astNodeKindText(AstNodeKind kind) {
        switch (kind) {
            case AstNodeKind::None: return "";
            case AstNodeKind::Module: return "Module";
            case AstNodeKind::Import: return "Import";
            case AstNodeKind::DataClass: return "DataClass";
            case AstNodeKind::Enum: return "Enum";
            case AstNodeKind::TypeAlias: return "TypeAlias";
            case AstNodeKind::Function: return "Function";
            case AstNodeKind::Var: return "Var";
            case AstNodeKind::TypeParam: return "TypeParam";
            case AstNodeKind::Field: return "Field";
            case AstNodeKind::Param: return "Param";
            case AstNodeKind::EnumMember: return "EnumMember";
            case AstNodeKind::Type: return "Type";
            case AstNodeKind::Inner: return "Inner";
            case AstNodeKind::TypeArg: return "TypeArg";
            case AstNodeKind::ParamType: return "ParamType";
            case AstNodeKind::ReturnType: return "ReturnType";
            case AstNodeKind::TargetType: return "TargetType";
            case AstNodeKind::Receiver: return "Receiver";
            case AstNodeKind::Stmt: return "Stmt";
            case AstNodeKind::Expr: return "Expr";
            case AstNodeKind::Cond: return "Cond";
            case AstNodeKind::Then: return "Then";
            case AstNodeKind::Else: return "Else";
            case AstNodeKind::Body: return "Body";
            case AstNodeKind::Case: return "Case";
            case AstNodeKind::Label: return "Label";
            case AstNodeKind::Init: return "Init";
            case AstNodeKind::Value: return "Value";
            case AstNodeKind::Target: return "Target";
            case AstNodeKind::Operand: return "Operand";
            case AstNodeKind::Lhs: return "Lhs";
            case AstNodeKind::Rhs: return "Rhs";
            case AstNodeKind::Index: return "Index";
            case AstNodeKind::Callee: return "Callee";
            case AstNodeKind::Arg: return "Arg";
        }
        return "";
    }

    const char *astNodeAttributeText(AstNodeAttributeKind name) {
        switch (name) {
            case AstNodeAttributeKind::Line: return "line";
            case AstNodeAttributeKind::Column: return "column";
            case AstNodeAttributeKind::Name: return "name";
            case AstNodeAttributeKind::IsVar: return "isVar";
            case AstNodeAttributeKind::IsNative: return "isNative";
            case AstNodeAttributeKind::HasBody: return "hasBody";
            case AstNodeAttributeKind::HasReceiver: return "hasReceiver";
            case AstNodeAttributeKind::HasNativeSymbol: return "hasNativeSymbol";
            case AstNodeAttributeKind::NativeSymbol: return "nativeSymbol";
            case AstNodeAttributeKind::Package: return "package";
            case AstNodeAttributeKind::Path: return "path";
            case AstNodeAttributeKind::Params: return "params";
            case AstNodeAttributeKind::Op: return "op";
            case AstNodeAttributeKind::Value: return "value";
            case AstNodeAttributeKind::Text: return "text";
            case AstNodeAttributeKind::HasValue: return "hasValue";
            case AstNodeAttributeKind::IsDefault: return "isDefault";
        }
        return "";
    }

    // The schema's `kind` text for a category ("Stmt.If", "Type.Generic", ...):
    // the same strings the old kind *attribute* carried, so the dump is unchanged.
    const char *astNodeCategoryText(AstNodeCategory kind) {
        switch (kind) {
            case AstNodeCategory::None: return "";
            case AstNodeCategory::Module: return "Module";
            case AstNodeCategory::DataClass: return "DataClass";
            case AstNodeCategory::Enum: return "Enum";
            case AstNodeCategory::TypeAlias: return "TypeAlias";
            case AstNodeCategory::Function: return "Function";
            case AstNodeCategory::Var: return "Var";
            case AstNodeCategory::StmtVarDecl: return "Stmt.VarDecl";
            case AstNodeCategory::StmtAssign: return "Stmt.Assign";
            case AstNodeCategory::StmtIf: return "Stmt.If";
            case AstNodeCategory::StmtWhile: return "Stmt.While";
            case AstNodeCategory::StmtSwitch: return "Stmt.Switch";
            case AstNodeCategory::StmtReturn: return "Stmt.Return";
            case AstNodeCategory::StmtBreak: return "Stmt.Break";
            case AstNodeCategory::StmtContinue: return "Stmt.Continue";
            case AstNodeCategory::StmtExprStmt: return "Stmt.ExprStmt";
            case AstNodeCategory::StmtLabel: return "Stmt.Label";
            case AstNodeCategory::StmtGoto: return "Stmt.Goto";
            case AstNodeCategory::StmtIfTrue: return "Stmt.IfTrue";
            case AstNodeCategory::StmtIfFalse: return "Stmt.IfFalse";
            case AstNodeCategory::StmtBlock: return "Stmt.Block";
            case AstNodeCategory::StmtYield: return "Stmt.Yield";
            case AstNodeCategory::ExprIntLit: return "Expr.IntLit";
            case AstNodeCategory::ExprFloatLit: return "Expr.FloatLit";
            case AstNodeCategory::ExprStrLit: return "Expr.StrLit";
            case AstNodeCategory::ExprCharLit: return "Expr.CharLit";
            case AstNodeCategory::ExprBoolLit: return "Expr.BoolLit";
            case AstNodeCategory::ExprNullLit: return "Expr.NullLit";
            case AstNodeCategory::ExprName: return "Expr.Name";
            case AstNodeCategory::ExprGenericName: return "Expr.GenericName";
            case AstNodeCategory::ExprMember: return "Expr.Member";
            case AstNodeCategory::ExprCall: return "Expr.Call";
            case AstNodeCategory::ExprIndex: return "Expr.Index";
            case AstNodeCategory::ExprUnary: return "Expr.Unary";
            case AstNodeCategory::ExprBinary: return "Expr.Binary";
            case AstNodeCategory::ExprLambda: return "Expr.Lambda";
            case AstNodeCategory::ExprRef: return "Expr.Ref";
            case AstNodeCategory::ExprDeref: return "Expr.Deref";
            case AstNodeCategory::ExprCopy: return "Expr.Copy";
            case AstNodeCategory::TypeIntLit: return "Type.IntLit";
            case AstNodeCategory::TypeNamed: return "Type.Named";
            case AstNodeCategory::TypeGeneric: return "Type.Generic";
            case AstNodeCategory::TypeReference: return "Type.Reference";
            case AstNodeCategory::TypePointer: return "Type.Pointer";
            case AstNodeCategory::TypeFunction: return "Type.Function";
            case AstNodeCategory::TypeYield: return "Type.Yield";
        }
        return "";
    }

    AstXmlNode toXmlNode(const Module &module) {
        List<AstNodeAttribute> attrs;
        addPos(attrs, module.pos);
        // Every file declares exactly one package; the attribute is always
        // present. A programmatically built module with no package emits an
        // empty value.
        attrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Package, joinPath(module.package)));
        // The children are built in one list and frozen into the node's array in
        // one allocation, rather than appended to the node one by one.
        List<AstXmlNode> children;
        for (const Import &import: module.imports) {
            List<AstNodeAttribute> importAttrs;
            importAttrs.push_back(AstNodeAttribute(AstNodeAttributeKind::Path, joinPath(import.path)));
            addPos(importAttrs, import.pos);
            children.push_back(makeNode(AstNodeKind::Import, AstNodeCategory::None, importAttrs));
        }
        for (const DeclPtr &decl: module.declarations) children.push_back(declToXml(*decl));
        AstXmlNode root = makeNode(AstNodeKind::Module, AstNodeCategory::Module, attrs);
        root.Children = simse_list_toArray(children);
        return root;
    }

    Str dumpXmlNode(const AstXmlNode &node) {
        Str out;
        dumpXmlRec(node, 0, out);
        return out;
    }
}
