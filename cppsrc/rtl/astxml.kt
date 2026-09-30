// astxml.kt
//
// The compiler's AST node type (impl_specs/ast-xmlnode.md): the `XmlNode` schema with its
// two stringly-typed parts - a node's role and an attribute's key - replaced by enums.
// The emitter generates the structs, the enums and their conversions from these declarations.
//
// `AstNodeKind.None` is the absent sentinel: a missing optional child has role `None`.

package rtl

// The structural role of a node - the schema's fixed set.
enum class AstNodeKind {
    None,
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
    Arg
}

// An attribute's key: the schema's attribute names.
enum class AstNodeAttributeKind {
    Line,
    Column,
    Name,
    IsVar,
    IsNative,
    HasBody,
    HasReceiver,
    HasNativeSymbol,
    NativeSymbol,

    // An `@SmGen` attribute (specs/attributes.md): `Attribute` is its own name, `Generator`
    // its first argument, `GeneratorArgs` the rest, joined by `,`. A string literal is
    // stored without its quotes, because a generator reads a name or a symbol.
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

    // `data fun` (a *pure* function): no side effects, the result a function of its receiver
    // and arguments. The compiler trusts the mark - it is the only source of truth for a
    // body-less (`@SmGen`) function - and uses it to reuse a repeated call of an unchanged
    // argument (`linear/ReusePure.kt`). Appended last so the values already in use do not move.
    IsPure,

    // `suspend fun` (impl_specs/async.md): the declaration's body may wait, so the lowering
    // turns it into a ref-counted task and a call to it is a suspension. It is a modifier on
    // the declaration, not a type - the signature stays the plain one. Appended last, like
    // `IsPure`.
    IsSuspend,

    // `borrow fun`: the body reads its receiver and parameters and never writes through them, so
    // a caller may hand it a pointer (`impl_specs/escape-analysis.md`, the auto-borrow proof).
    // Weaker than `data`: it constrains what the function does to its arguments, not what it
    // answers, so a read-only function returning a fresh value may carry it without its calls
    // being folded. Appended last, like `IsPure`.
    IsBorrow
}

// What a node is - the schema's `kind` - as against its role (`AstNodeKind`, where it
// sits). The two are independent, and `None` means the node has no `kind`.
enum class AstNodeCategory {
    None,
    Module,
    DataClass,
    Enum,
    TypeAlias,
    Function,
    Var,
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
    TypeIntLit,
    TypeNamed,
    TypeGeneric,
    TypeReference,
    TypePointer,
    TypeFunction,

    // `..T`: the body yields `T`, so the function builds a state machine.
    TypeYield,

    // `x!!`: the success payload of a `Res`, or the failure returned out of the enclosing
    // function. A rewrite like `for` (cppsrc/parser/Propagate.kt), so nothing past the
    // parser sees the operator.
    ExprPropagate
}

// One attribute: a schema key and its text value (numbers as decimal text, booleans as
// "true"/"false"), so a node's scalars stay stringly typed.
data class AstNodeAttribute(
    var name: AstNodeAttributeKind,

    var value: Str
)

// One AST node: role, category, attributes, children. `Children` is one ref-counted
// `Array<AstXmlNode>`; a leaf holds the shared empty array.
data class AstXmlNode(
    var name: AstNodeKind,

    var kind: AstNodeCategory,
    var attributes: List<AstNodeAttribute>,
    var Children: Array<AstXmlNode>
)
