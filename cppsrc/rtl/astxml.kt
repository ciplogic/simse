// astxml.kt
//
// The compiler's AST node type (impl_specs/ast-xmlnode.md): the `XmlNode` schema with its two
// stringly-typed parts - a node's role and an attribute's key - as enums, from which the
// emitter generates the structs and conversions. `AstNodeKind.None` is the absent sentinel,
// the role of a missing optional child.

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

    // `data fun`: pure - no side effects, the result a function of its arguments. The mark is
    // the only source of truth for a body-less (`@SmGen`) function, and a repeated call on an
    // unchanged argument is reused (`linear/ReusePure.kt`).
    IsPure,

    // `suspend fun` (impl_specs/async.md): the body may wait, so the lowering makes it a
    // ref-counted task and a call to it is a suspension. A modifier, not a type - the signature
    // stays plain.
    IsSuspend,

    // `borrow fun`: the body reads its receiver and parameters and never writes through them,
    // so a caller may hand it a pointer (`impl_specs/escape-analysis.md`). Weaker than `data` -
    // it constrains the arguments, not the result, so its calls are not folded.
    // The modifiers are appended last, so the values already in use do not move.
    IsBorrow,

    // The `initByValue` construction convention's marker on a `var x = T(a)` declaration
    // (cppsrc/sema/TypeInfer.kt): the initializer constructs through `T.initByValue`, so the
    // declaration stays where it is and cppsrc/linear/LinearForm.kt routes it.
    InitByValue
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
