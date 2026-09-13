#pragma once

#include "../common/common.h"

#include <memory>

// The Simse AST. Each node category is one struct with a `kind` enum, mirroring
// how SkeletonNode avoids an inheritance hierarchy. Recursive children are held
// by std::shared_ptr so whole subtrees can be shared cheaply. Every node carries
// the position of its first token.

namespace ast {
    using common::SourcePos;

    enum class TypeKind {
        IntLit, // an integer used as a type argument, e.g. SmallVector<4, T>
        Named,
        Generic,
        Reference, // &T
        Pointer,   // *T
        Function,  // (A, B) -> R
    };

    struct TypeExpr;
    using TypePtr = std::shared_ptr<TypeExpr>;

    struct TypeExpr {
        TypeKind kind = TypeKind::Named;
        SourcePos pos{};
        Str name;                 // Named, Generic
        Str text;                 // IntLit
        TypePtr inner;            // Reference, Pointer
        List<TypePtr> typeArgs;   // Generic (types, or IntLit for integer args)
        List<TypePtr> paramTypes; // Function
        TypePtr returnType;       // Function
    };

    enum class ExprKind {
        IntLit,
        FloatLit,
        StrLit,
        CharLit,
        BoolLit,
        NullLit,
        Name,
        GenericName, // Name<T> in an expression, e.g. Res<Token> or List<Int>
        Member,
        Call,
        Index,
        Unary,
        Binary,
        Lambda,
        Ref,   // &expr
        Deref, // *expr
        Copy,  // copy(expr)
    };

    struct Expr;
    using ExprPtr = std::shared_ptr<Expr>;

    struct Stmt;
    using StmtPtr = std::shared_ptr<Stmt>;

    // One arm of a `switch`: a `case CONST:` or `default:` label plus the
    // statements that follow it (up to the next label or the closing brace).
    struct SwitchCase {
        SourcePos pos{};
        bool isDefault = false;
        ExprPtr label; // null for `default`
        List<StmtPtr> body;
    };

    struct Expr {
        ExprKind kind = ExprKind::Name;
        SourcePos pos{};
        Str text;                 // literal text, name, member name, or operator
        ExprPtr lhs;              // operand / receiver / binary left / callee
        ExprPtr rhs;              // binary right / index
        List<ExprPtr> args;       // Call arguments
        List<TypePtr> typeArgs;   // GenericName type arguments
        List<Str> paramNames;     // Lambda
        List<TypePtr> paramTypes; // Lambda
        List<StmtPtr> body;       // Lambda
        bool boolValue = false;   // BoolLit
    };

    enum class StmtKind {
        VarDecl,
        Assign,
        If,
        While,
        Switch,
        Return,
        Break,
        Continue,
        ExprStmt,
        // Post-lowering forms (impl_specs/linear-lowering.md). The parser never
        // produces them: the linear pass replaces If/While/Switch/Break/Continue
        // with labels, jumps and blocks before the C++ emitter runs.
        Label,   // name: label definition
        Goto,    // name: unconditional jump target
        IfTrue,  // name + cond: jump when cond is true
        IfFalse, // name + cond: jump when cond is false
        Block,   // body: `{ ... }` scope wrapper
    };

    struct Stmt {
        StmtKind kind = StmtKind::ExprStmt;
        SourcePos pos{};

        // VarDecl
        bool isVar = false;
        Str name;
        TypePtr type;
        ExprPtr init;

        // Assign
        ExprPtr target;
        Str op;
        ExprPtr value;

        // If / While
        ExprPtr cond;
        List<StmtPtr> thenBody; // If
        List<StmtPtr> elseBody; // If
        bool hasElse = false;
        List<StmtPtr> body; // While

        // Switch
        List<SwitchCase> cases;

        // Return
        ExprPtr returnValue;

        // ExprStmt
        ExprPtr expr;
    };

    struct Field {
        SourcePos pos{};
        bool isVar = true;
        Str name;
        TypePtr type;
    };

    struct EnumMember {
        SourcePos pos{};
        Str name;
        bool hasValue = false;
        int value = 0;
    };

    struct Param {
        SourcePos pos{};
        Str name;
        TypePtr type;
    };

    enum class DeclKind {
        DataClass,
        Enum,
        TypeAlias,
        Function,
        Var,
    };

    struct Decl;
    using DeclPtr = std::shared_ptr<Decl>;

    struct Decl {
        DeclKind kind = DeclKind::Function;
        SourcePos pos{};
        Str name;

        // DataClass
        List<Field> fields;
        List<DeclPtr> methods;

        // Enum
        List<EnumMember> members;

        // DataClass / Enum / TypeAlias type parameters
        List<Str> typeParams;
        TypePtr targetType;

        // Var: a file-level `var`/`val` (static storage, specs/statics.md). The
        // shape mirrors a `Stmt` VarDecl so emitters have one variable form; the
        // initializer is optional.
        bool isVar = false;
        TypePtr type;
        ExprPtr init;

        // Function
        List<Str> functionTypeParams;
        bool hasReceiver = false;
        TypePtr receiverType;
        List<Param> params;
        TypePtr returnType;
        List<StmtPtr> body;
        bool hasBody = false;
        bool isNative = false;
        bool hasNativeSymbol = false;
        Str nativeSymbol;
    };

    struct Import {
        SourcePos pos{};
        List<Str> path;
    };

    struct Module {
        SourcePos pos{};
        // The declared package (`package a.b.c`), empty for the root package. It
        // is namespacing only: imports select declarations by package, and there
        // is no visibility/access-control meaning.
        List<Str> package;
        SourcePos packagePos{};
        List<Import> imports;
        List<DeclPtr> declarations;
    };

    // Renders a type in source-like form, e.g. "List<TokenMatcher>" or
    // "(*T) -> Unit".
    Str typeToString(const TypeExpr& type);

    // Deterministic, indented, line-per-node dump for golden files. Contains no
    // file paths or addresses, so it is stable across machines.
    Str dumpModule(const Module& module);

    // Converts the AST into an `AstXmlNode` tree using the schema in
    // impl_specs/ast-xmlnode.md: each node carries its AST kind as its category
    // and its structural role as the node's `name`; scalars are string
    // attributes. `dumpXmlNode` renders that tree deterministically, and
    // `astNodeKindText`/`astNodeCategoryText`/`astNodeAttributeText` are the
    // schema's spelling of a role, a kind and an attribute key.
    AstXmlNode toXmlNode(const Module& module);
    Str dumpXmlNode(const AstXmlNode& node);
    const char* astNodeKindText(AstNodeKind kind);
    const char* astNodeCategoryText(AstNodeCategory kind);
    const char* astNodeAttributeText(AstNodeAttributeKind name);
}
