// astxml.kt
//
// The compiler's AST node type (impl_specs/ast-xmlnode.md): the `XmlNode` schema with its two
// stringly-typed parts - a node's role and an attribute's key - as enums, from which the
// emitter generates the structs and conversions. `AstNodeKind.None` is the absent sentinel,
// the role of a missing optional child.

package compiler

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

    // The implicit tag enum a `union class` carries, generated beside it and hoisted to the
    // module's declarations by the parser so sema and the emitter treat it as an ordinary
    // enum (name `Sm<Type>Types`, members `None` + the field names).
    UnionTag,
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

    // `operator fun get`/`set` (specs/functions.md, "Operator functions"): the index syntax
    // is the call - `x[i]` is `x.get(i)` and `x[i] = v` is `x.set(i, v)` - for a receiver
    // whose type declares the operator. Kotlin's convention is the spelling.
    IsOperator,

    // The `initByValue` construction convention's marker on a `var x = T(a)` declaration
    // (src/sema/TypeInfer.kt): the initializer constructs through `T.initByValue`, so the
    // declaration stays where it is and src/linear/LinearForm.kt routes it.
    InitByValue,

    // `native class` (specs/memory-model.md): the class's generated struct exists to mirror a
    // native layout, so the emitter does not wrap it in the language's 4-byte packing.
    IsNativeClass,

    // `ref class`: the second class word - parsed and recorded, and nothing reads it yet.
    IsRefClass,

    // `union class` (specs/declarations.md): a discriminated union - one field live at a
    // time, chosen by an implicit `Sm<Type>Types` tag enum. The class is otherwise a data
    // class (fields, methods), with generated tag accessors and per-field arms.
    IsUnionClass,

    // One of a `union class`'s generated members (`getTypeOf`, `isOfType`, `get<Field>`,
    // `set<Field>`, `setNone`, `initByValue`): a real declaration to sema, skipped by the
    // emitter's body pass because class emission writes the C++ by hand (`emitUnionClass`).
    IsUnionGenerated,

    // `protocol fun Name<T> T.method(...)` (specs/declarations.md, "Protocols"): the
    // declaration is a Function whose *signature* is the protocol - the attribute carries the
    // protocol's name (the method name when the declaration is unnamed). The name may precede
    // `fun` in the older spelling, still accepted while uses migrate. Nothing is emitted for
    // it; an implementation is any function that satisfies the signature. Appended last, so
    // the values already in use do not move.
    Protocol,

    // `fun f<T>(...) when T: Printable, Countable`: the type parameter's protocol
    // requirements, one `<param>:<protocol>` item per entry, joined by a comma (the parser
    // writes it; `sema.Protocols` reads it back). Appended last for the same reason.
    Protocols
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
    // function. A rewrite like `for` (src/parser/Propagate.kt), so nothing past the
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
