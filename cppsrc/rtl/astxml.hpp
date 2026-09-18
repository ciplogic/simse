#pragma once

#include <utility>

#include "containers.hpp"
#include "types.hpp"

// The compiler's AST node type (impl_specs/ast-xmlnode.md). It is the `XmlNode`
// schema with the two stringly-typed parts replaced by enums:
//
//   * the node's role (`name`) is an `AstNodeKind` - the schema's fixed set of
//     structural roles - instead of a `Str`, so "what kind of node is this?" is
//     an integer compare instead of a string compare;
//   * an attribute's key is an `AstNodeAttributeKind`, so finding an attribute
//     compares integers instead of scanning keys as text. The *values* stay `Str`:
//     the schema is stringly typed by design (numbers are decimal text, booleans
//     are "true"/"false", `kind` is a category tag such as "Stmt.If"), and the
//     emit path depends on that text.
//
// Everything else is the `XmlNode` shape: children are one ref-counted
// `Array<AstXmlNode>` (the shared empty array for a leaf node, so a leaf costs no
// allocation), attributes are a value `List<AstNodeAttribute>`, and an absent
// optional child is the `AstNodeKind::None` node. Copying a node shares the
// children block and deep-copies the name/attribute list.
//
// The language-level `XmlNode` (`xml.hpp`) stays the general tree a *program*
// builds (specs/xml-node.md); this type is what the compiler pipeline passes
// around. Both rings share this definition through the RTL: the Simse side sees
// the same declarations from the prelude (`astxml.simse`).
//
// The text of an enum member - the role and attribute names of the schema - is
// the dump's business (`ast::astNodeKindText` / `ast::astNodeAttributeText` in
// cppsrc/ast/Ast.cpp), not the type's: nothing else needs the spelling back.
enum class AstNodeKind : Int {
    None = 0, // the absent sentinel: the schema has no null, so a missing
              // optional child is a `None` node (an empty role)
    Module,
    Import,
    DataClass,
    Enum,
    TypeAlias,
    Function,
    Var,
    TypeParam,
    Field,
    Param,
    EnumMember,
    Type,
    Inner,
    TypeArg,
    ParamType,
    ReturnType,
    TargetType,
    Receiver,
    Stmt,
    Expr,
    // Child roles (a container or a named sub-node of a statement/expression).
    Cond,
    Then,
    Else,
    Body,
    Label,
    Init,
    Value,
    Target,
    Operand,
    Lhs,
    Rhs,
    Index,
    Callee,
    Arg,
};

// An attribute's key. The schema's attribute names, in a fixed order. The
// category (`kind`) is not here: it is a field of the node (specs/statics.md's
// sibling, impl_specs/ast-xmlnode.md), not an attribute.
enum class AstNodeAttributeKind : Int {
    Line = 0,
    Column,
    Name,
    IsVar,
    IsNative,
    HasBody,
    HasReceiver,
    HasNativeSymbol,
    NativeSymbol,
    // An `@SmGen` attribute (specs/attributes.md, impl_specs/generators.md): the
    // attribute's own name (`SmGen`, and later the sugar `Json`), the generator it names
    // - the attribute's *first* argument, `cpp` for `native(...)` - and the generator's
    // remaining arguments in order, joined by `,`, a string literal without its quotes.
    // `native(...)` is the `cpp`/`defined-in-headers` form, so a `native` declaration
    // carries these too.
    Attribute,
    Generator,
    GeneratorArgs,
    Package,
    Path,
    Params,
    Op,
    Value,
    Text,
    HasValue,
};

// The schema's `kind` attribute: what a node **is** (its category), as opposed to
// its role (`AstNodeKind`, where it sits). The two are independent - `attach`
// re-roots a node under a new role and the category travels with it - so a node
// carries both, and the category is a field rather than an attribute: every test
// on it is an integer compare, and the dump renders it back as `kind='...'` in
// its schema position (first). `None` is "no kind attribute" (`Import`, `Field`,
// `Param`, `EnumMember`, `TypeParam`, and the container roles).
enum class AstNodeCategory : Int {
    None = 0,
    // declarations and the module
    Module,
    DataClass,
    Enum,
    TypeAlias,
    Function,
    Var,
    // statements
    StmtVarDecl,
    StmtAssign,
    StmtIf,
    StmtWhile,
    StmtReturn,
    StmtBreak,
    StmtContinue,
    StmtExprStmt,
    StmtLabel,
    StmtGoto,
    StmtIfTrue,
    StmtIfFalse,
    StmtBlock,
    // `yield e` (impl_specs/yield.md): the value a state machine hands out.
    StmtYield,
    // expressions
    ExprIntLit,
    ExprFloatLit,
    ExprStrLit,
    ExprCharLit,
    ExprBoolLit,
    ExprNullLit,
    ExprName,
    ExprGenericName,
    ExprMember,
    ExprCall,
    ExprIndex,
    ExprUnary,
    ExprBinary,
    ExprLambda,
    ExprRef,
    ExprDeref,
    ExprCopy,
    // types
    TypeIntLit,
    TypeNamed,
    TypeGeneric,
    TypeReference,
    TypePointer,
    TypeFunction,
    // `..T`: the body yields `T`, so the function builds a state machine.
    TypeYield,
};

SIMSE_PACK_PUSH
struct AstNodeAttribute {
    AstNodeAttributeKind name = AstNodeAttributeKind::Line;
    Str value;

    AstNodeAttribute() = default;
    AstNodeAttribute(AstNodeAttributeKind n, Str v) : name(n), value(std::move(v)) {}
};

struct AstXmlNode {
    AstNodeKind name = AstNodeKind::None;
    AstNodeCategory kind = AstNodeCategory::None;
    List<AstNodeAttribute> attributes;
    Array<AstXmlNode> Children;

    AstXmlNode() = default;
    AstXmlNode(AstNodeKind n, AstNodeCategory k, List<AstNodeAttribute> attrs, Array<AstXmlNode> children)
        : name(n), kind(k), attributes(std::move(attrs)), Children(std::move(children)) {}
};
SIMSE_PACK_POP
