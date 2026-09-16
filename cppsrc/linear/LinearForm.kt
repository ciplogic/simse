// LinearForm.kt
//
// The linear IL, in the Simse ring: the mirror of cppsrc/linear/LinearForm.cpp
// (impl_specs/linear-il.md). The lowering leaves a body that is *almost* an
// instruction list already - control flow is labels and jumps, every value position
// is one operation deep, and the lowering's own storage is declared once at the top -
// and this is the same data with the last trees removed, so a pass or a backend can
// read one instruction at a time without knowing anything about scopes.
//
// The tables are per body and hold what instructions refer to by index:
//
//   types    every type the body mentions (the frame's slot types included)
//   vars     the frame: name, type index, and what the slot is for
//   pool     text: string literals, field names, operators
//   methods  every callee the body calls, with enough of its shape to verify a call
//   labels   label names; a label instruction carries one, a jump names one
//   ops      the instructions, in order
//   lines    the source line of each instruction, parallel to `ops`
//
// Nothing here is *inferred*: the frame is typed by the type pass before this runs,
// and the backend decides spelling from the slot types. The dump this file prints is
// byte-comparable with the C++ ring's - that is the oracle the port is checked with.
//
// The types of the *language* are `AstXmlNode` subtrees here (an empty node is "no
// type"), so `ilTypeText` spells what the C++ ring spells through `ast::TypeExpr`.

package linear

import common
import sema

// `Expression` is the lowering's own storage (`_sm_expr<n>`, `simse_sw_<n>`):
// declared, so a backend declares it where the hoisting put it. `Temp` is the extractor's:
// a slot it synthesised for a position the statements did not hold in a slot of its own.
// It is a declared slot like any other when the type rules can name it (the frame carries
// the type, and the declaration goes to the top of the instruction list with the rest of
// the frame); a slot whose type they cannot name has no declaration to print, so a backend
// folds its single use into the instruction that reads it.
enum class IlVarKind {
    Argument,
    Local,
    Expression,
    Temp
}

// What the *shape* of a call is: the backend resolves the symbol from the name and the
// operand types it finds in the frame, so the IL stays free of anything but the
// program's own spelling.
enum class IlMethodKind {
    Function,
    Method,
    Constructor
}

// `Var` is a **slot** - a destination, or a place read (`GetField`'s base); `Value` is
// a slot *or* a constant, which is what most operand positions are. The opcodes say
// nothing about types: the frame does.
enum class IlOperandKind {
    Var,
    Value,
    Text,
    Type,
    Method,
    Label,
    None
}

// The instruction set: one opcode per IL instruction. An enum rather than the
// opcode's *name*, because every pass and the backend dispatch on it - an `Int`
// compare instead of a string compare, and an instruction carrying four bytes of
// opcode instead of a `Str` (which is what `IlOp` used to be). The order matches
// `makeIlSignatures()`'s, so an opcode's signature is the table row at `kind.toInt()`.
enum class IlOpKind {
    Label,
    Goto,
    IfTrue,
    IfFalse,
    Declare,
    DeclareInit,
    SetVar,
    SetVar_Null,
    BinaryOp,
    UnaryOp,
    Cast,
    Box,
    Deref,
    CopyValue,
    Store,
    GetField,
    SetField,
    GetIndex,
    SetIndex,
    FieldAddr,
    IndexAddr,
    GetStatic,
    GetStaticAddr,
    SetStatic,
    Call,
    CallVoid,
    CallIndirect,
    CallIndirectVoid,
    CallCtor,
    Pack,
    Return,
    ReturnVoid,
    Lambda,
    Unsupported
}

data class IlVar(var name: Str, var typeIndex: Int, var kind: IlVarKind)

data class IlMethod(
    var name: Str,
    var kind: IlMethodKind,
    var argCount: Int,
    var staticBase: Int,
    var returnType: Int,
    var argTypes: List<Int>
)

// One instruction. An operand is an `Int` whose meaning is the opcode's operand kind
// at that position: an index into a table, or a literal. A **negative** operand in a
// `Value` position is a literal: it names `pool[-1-n]`, whose text a backend prints
// verbatim (`0`, `"abc"`, `true`) - which is what keeps a constant inside the
// instruction that uses it instead of a slot that would have to be declared.
data class IlOp(var kind: IlOpKind, var operands: List<Int>)

data class IlBody(
    var file: Str, var line: Int, var symbol: Str, var signature: Str, var types: List<Str>,

// The type node behind each `types` entry, when the extractor had one: the dump
// only needs the text, but a backend spells C++ from these.
    var typeNodes: List<AstXmlNode>,

// What the *type pass* proved for every name in this body (`sema::inferTypes`),
// which is more than the frame's slots carry: a name holding a state machine is
// typed `..T`, and a declaration is never written with that (the emitted C++ uses
// `auto`, and `linear/Yield.cpp` relies on the declaration staying untyped). The
// backend still needs it, because `for` wraps what it iterates in `smToYield()` and,
// on a machine, that wrap is the identity - a decision only the receiver's type can
// make (impl_specs/for.md). Seeding a frame from this is how a machine-typed slot
// stays typed without a statement tree.
    var inferredTypes: Dictionary<Str, AstXmlNode>,
    var vars: List<IlVar>,
    var pool: List<Str>,
    var methods: List<IlMethod>,
    var labels: List<Str>,
    var ops: List<IlOp>,
    var lines: List<Int>
)

// What the extractor needs to know about the body's function. A *lambda* body (and a
// state machine's method) has no declaration: its parameters, the class it is the
// `invoke` of, and the names it captures (which are fields of that class, not slots of
// the frame). All of them are empty for a function.
//
// The last three are what a *lambda* body needs to run its own type pass: the program
// facts (the extractor types a lambda body itself, because a lambda's frame is not the
// enclosing function's), the type parameters in scope there, and the flat record the
// enclosing body's pass produced (the top-level body's frame).
data class IlFunction(
    var decl: AstXmlNode,

    var receiver: AstXmlNode,
    var symbol: Str,
    var statics: Dictionary<Str, Str>,
    // The class this body is a method of, when the *lowering* built it (a state machine):
    // its fields are what `this.<name>` reaches, and the type rules need them
    // (`SemBody.selfDecl`).
    var selfDecl: AstXmlNode,
    var paramNames: List<Str>,
    var paramTypes: List<AstXmlNode>,
    var closureSymbol: Str,
    var captures: Dictionary<Str, Bool>,
    var captureTypes: Dictionary<Str, AstXmlNode>,
    var facts: *SemFacts,
    var typeParams: List<Str>,
    var inferredTypes: *Dictionary<Str, AstXmlNode>
)

// A lambda, as the language's model says it is: a class with one field per captured
// variable and one method, so a callable value is an *instance* of it.
data class IlClosure(
    var symbol: Str,

    var signature: Str,
    var captures: List<Str>,
    var captureTypes: List<AstXmlNode>,
    var params: List<IlVar>,
    var bodyIndex: Int
)

// One function-like body and the lambdas it constructs.
data class IlUnit(
    var body: IlBody,

    var lambdas: List<IlBody>,
    var closures: List<IlClosure>
)

// One entry per opcode, with its operands' kinds in order (`"Var,Text,Var,Var"`; a
// trailing `...` means "the kind before it repeats here"). The printer and the backend
// read this table, so it is the one place the IL's shape is written down. The rows are
// in `IlOpKind` order.
data class IlSignature(
    var kind: IlOpKind,

    var operands: Str
)

// ---- the instruction set ---------------------------------------------------

var ilSignatureTable: List<IlSignature> = makeIlSignatures()

fun makeIlSignatures(): List<IlSignature> {
    var table: List<IlSignature> = listOf<IlSignature>(
        IlSignature(IlOpKind.Label, "Label"),
        IlSignature(IlOpKind.Goto, "Label"),
        IlSignature(IlOpKind.IfTrue, "Value,Label"),
        IlSignature(IlOpKind.IfFalse, "Value,Label"),
        IlSignature(IlOpKind.Declare, "Var"),
        // A declaration the instruction *after* it initialises: the two are one line of
        // C++. The distinction is real because the hoisting turns a declaration's
        // initializer into a separate assignment, which is a bare `Declare`.
        IlSignature(IlOpKind.DeclareInit, "Var"),
        // One assignment op for every kind of value: the destination slot's type is what a
        // backend spells from, so the opcode says nothing about it - `SetVar x, y` copies a
        // slot, `SetVar x, 5` carries the constant in the operand.
        IlSignature(IlOpKind.SetVar, "Var,Value"),
        // ... except `null`, whose spelling comes from the destination's type
        // (`Opt<T>()` vs `nullptr`) rather than from a literal.
        IlSignature(IlOpKind.SetVar_Null, "Var"),
        IlSignature(IlOpKind.BinaryOp, "Var,Text,Value,Value"),
        IlSignature(IlOpKind.UnaryOp, "Var,Text,Value"),
        IlSignature(IlOpKind.Cast, "Var,Value"),     // Enum.toInt(): the one cast
        IlSignature(IlOpKind.Box, "Var,Var"),        // &x -> a counted handle
        IlSignature(IlOpKind.Deref, "Var,Var"),      // *x -> a borrow, a `.get()`,
        //                                             or a load of a `*T`
        IlSignature(IlOpKind.CopyValue, "Var,Var"),  // copy(x)
        IlSignature(IlOpKind.Store, "Var,Value"),    // *p = v
        IlSignature(IlOpKind.GetField, "Var,Var,Text"),
        IlSignature(IlOpKind.SetField, "Var,Text,Value"),
        IlSignature(IlOpKind.GetIndex, "Var,Var,Value"),
        IlSignature(IlOpKind.SetIndex, "Var,Value,Value"),
        IlSignature(IlOpKind.FieldAddr, "Var,Var,Text"),
        IlSignature(IlOpKind.IndexAddr, "Var,Var,Value"),
        IlSignature(IlOpKind.GetStatic, "Var,Text"),
        IlSignature(IlOpKind.GetStaticAddr, "Var,Text"),
        IlSignature(IlOpKind.SetStatic, "Text,Value"),
        IlSignature(IlOpKind.Call, "Var,Method,Value..."),
        IlSignature(IlOpKind.CallVoid, "Method,Value..."),
        IlSignature(IlOpKind.CallIndirect, "Var,Var,Value..."),
        IlSignature(IlOpKind.CallIndirectVoid, "Var,Value..."),
        IlSignature(IlOpKind.CallCtor, "Var,Type,Value..."),
        // A container built from values in one instruction - the IL's
        // `newarr`/`fill-array-data`: `List<T>{v1, v2, ...}`. The destination's type says
        // which container it is, and the values are elements (not fields), which is what
        // lets a call to a function whose last parameter is a list pack its trailing
        // arguments (`specs/functions.md`).
        IlSignature(IlOpKind.Pack, "Var,Value..."),
        IlSignature(IlOpKind.Return, "Value"),
        IlSignature(IlOpKind.ReturnVoid, ""),
        IlSignature(IlOpKind.Lambda, "Var"),
        IlSignature(IlOpKind.Unsupported, "Var,Text")
    )
    return table
}

// The opcode's spelling, in `IlOpKind` order (the dump reads it; the backend switches
// on the enum and never needs the text).
var ilOpKindTexts: List<Str> = makeIlOpKindTexts()

fun makeIlOpKindTexts(): List<Str> {
    var texts: List<Str> = listOf<Str>(
        "Label",
        "Goto",
        "IfTrue",
        "IfFalse",
        "Declare",
        "DeclareInit",
        "SetVar",
        "SetVar_Null",
        "BinaryOp",
        "UnaryOp",
        "Cast",
        "Box",
        "Deref",
        "CopyValue",
        "Store",
        "GetField",
        "SetField",
        "GetIndex",
        "SetIndex",
        "FieldAddr",
        "IndexAddr",
        "GetStatic",
        "GetStaticAddr",
        "SetStatic",
        "Call",
        "CallVoid",
        "CallIndirect",
        "CallIndirectVoid",
        "CallCtor",
        "Pack",
        "Return",
        "ReturnVoid",
        "Lambda",
        "Unsupported"
    )
    return texts
}

fun ilOpKindText(kind: IlOpKind): Str {
    val index: Int = kind.toInt()
    if (index < 0 || index >= ilOpKindTexts.size()) {
        return "?"
    }
    return ilOpKindTexts[index]
}

// The signature of an opcode. The table is in `IlOpKind` order, so the row *is* the
// opcode: no search, which matters because a backend asks for one per operand.
fun ilSignature(kind: IlOpKind): Opt<IlSignature> {
    val index: Int = kind.toInt()
    if (index < 0 || index >= ilSignatureTable.size()) {
        return Opt<IlSignature>.none()
    }
    return Opt<IlSignature>.some(ilSignatureTable[index])
}

fun ilVarKindText(kind: IlVarKind): Str {
    when (kind) {
        IlVarKind.Argument -> {
            return "Argument"
        }

        IlVarKind.Local -> {
            return "Local"
        }

        IlVarKind.Expression -> {
            return "Expression"
        }

        IlVarKind.Temp -> {
            return "Temp"
        }
    }
    return "?"
}

fun ilMethodKindText(kind: IlMethodKind): Str {
    when (kind) {
        IlMethodKind.Function -> {
            return "Function"
        }

        IlMethodKind.Method -> {
            return "Method"
        }

        IlMethodKind.Constructor -> {
            return "Constructor"
        }
    }
    return "?"
}

fun ilJoinList(parts: *List<Str>, separator: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < parts.size()) {
        if (i > 0) {
            out = out + separator
        }
        out = out + parts[i]
        i = i + 1
    }
    return out
}

fun ilIntText(value: Int): Str {
    return value.toString()
}

// The IL's spelling of a type: the language's own (`List<Str>`, `&Int`, `Box<T>`,
// `(Int, Int) -> Bool`), unqualified. The dump is read by a person; a backend prints
// its own text from the slots' types.
//
// A type the extractor could not name is an empty node, which spells `?` - the same
// marker the C++ ring prints for a null `ast::TypeExpr`.
fun ilTypeText(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return "?"
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return xmlAttr(typeNode, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            return xmlAttr(typeNode, AstNodeAttributeKind.Name)
        }

        AstNodeCategory.TypeGeneric -> {
            var args: List<Str> = List<Str>()
            val typeArgs: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*typeArg in typeArgs) {
                args.append(ilTypeText(typeArg))
            }
            return xmlAttr(typeNode, AstNodeAttributeKind.Name) + "<" + ilJoinList(args, ", ") + ">"
        }

        AstNodeCategory.TypeReference -> {
            return "&" + ilTypeText(xmlChild(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            return "*" + ilTypeText(xmlChild(typeNode, AstNodeKind.Inner))
        }
        // A `..T` (and anything else) spells `?`: the C++ ring's `ilTypeText` switches on
        // Named/IntLit/Generic/Reference/Pointer/Function only, and the two dumps have to
        // agree byte for byte. A machine is not a value type, so a body that *is* one shows
        // its return type as `?` in both rings.
        AstNodeCategory.TypeFunction -> {
            var params: List<Str> = List<Str>()
            val paramTypes: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            for (*paramType in paramTypes) {
                params.append(ilTypeText(paramType))
            }
            return "(" + ilJoinList(params, ", ") + ") -> "
            +ilTypeText(xmlChild(typeNode, AstNodeKind.ReturnType))
        }
    }
    return "?"
}

// The type a receiver slot has in the frame: `*T self` for a value receiver, `&T` for a
// handle, `*T` for an explicit pointer receiver.
fun ilReceiverTypeText(typeNode: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeReference -> {
            return "&" + ilTypeText(xmlChild(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            return "*" + ilTypeText(xmlChild(typeNode, AstNodeKind.Inner))
        }
    }
    return "*" + ilTypeText(typeNode)
}

// Whether an op's first `Var` operand is its *destination* - the rule the table leaves
// implicit: "the first `Var` operand of an op that produces a value is where the value
// goes". A backend folds a `Declare` into the instruction that writes the slot it
// declared when this is true and the two are adjacent.
fun ilWritesDestination(kind: IlOpKind): Bool {
    // Written out rather than derived, because deriving it means reading the operands
    // *and* knowing which of them produce a value, which is the thing being stated.
    when (kind) {
        IlOpKind.SetVar -> {
            return true
        }

        IlOpKind.SetVar_Null -> {
            return true
        }

        IlOpKind.BinaryOp -> {
            return true
        }

        IlOpKind.UnaryOp -> {
            return true
        }

        IlOpKind.Cast -> {
            return true
        }

        IlOpKind.Box -> {
            return true
        }

        IlOpKind.Deref -> {
            return true
        }

        IlOpKind.CopyValue -> {
            return true
        }

        IlOpKind.GetField -> {
            return true
        }

        IlOpKind.GetIndex -> {
            return true
        }

        IlOpKind.FieldAddr -> {
            return true
        }

        IlOpKind.IndexAddr -> {
            return true
        }

        IlOpKind.GetStatic -> {
            return true
        }

        IlOpKind.GetStaticAddr -> {
            return true
        }

        IlOpKind.Call -> {
            return true
        }

        IlOpKind.CallIndirect -> {
            return true
        }

        IlOpKind.CallCtor -> {
            return true
        }

        IlOpKind.Pack -> {
            return true
        }

        IlOpKind.Lambda -> {
            return true
        }

        IlOpKind.Unsupported -> {
            return true
        }
    }
    return false
}

// ---- reading operands back -------------------------------------------------

// `"Var,Method,Var..."` -> its tokens. Spelling a signature once per read is fine: the
// dump is a debug aid, not a pass.
fun ilOperandTokens(signature: *IlSignature): List<Str> {
    var tokens: List<Str> = List<Str>()
    var current: Str = Str()
    val spec: Str = signature.operands
    var i: Int = 0
    while (i < spec.size()) {
        if (spec[i] == ',') {
            tokens.append(current)
            current = Str()
            i = i + 1
            continue
        }
        current = current + spec[i]
        i = i + 1
    }
    if (!current.isEmpty()) {
        tokens.append(current)
    }
    return tokens
}

// Whether a token is the repeating one (`Var...`): the `...` is not part of its kind.
fun ilTokenRepeats(token: Str): Bool {
    return token.size() > 3 && token[token.size() - 1] == '.'
            && token[token.size() - 2] == '.' && token[token.size() - 3] == '.'
}

fun ilKindOfToken(token: Str): IlOperandKind {
    var base: Str = token
    if (ilTokenRepeats(base)) {
        base = base.substr(0, base.size() - 3)
    }
    when (base) {
        "Var" -> {
            return IlOperandKind.Var
        }

        "Value" -> {
            return IlOperandKind.Value
        }

        "Text" -> {
            return IlOperandKind.Text
        }

        "Type" -> {
            return IlOperandKind.Type
        }

        "Method" -> {
            return IlOperandKind.Method
        }

        "Label" -> {
            return IlOperandKind.Label
        }
    }
    return IlOperandKind.None
}

fun ilOperandKindAt(tokens: *List<Str>, index: Int): IlOperandKind {
    if (tokens.size() == 0) {
        return IlOperandKind.None
    }
    val last: Int = tokens.size() - 1
    if (index < last) {
        return ilKindOfToken(tokens[index])
    }
    if (index == last || ilTokenRepeats(tokens[last])) {
        return ilKindOfToken(tokens[last])
    }
    return IlOperandKind.None
}

// What operand `index` of `op` is, resolved from the signature table. A verifier, a
// printer and a backend all read the operands through this rather than counting them.
fun ilOperandKind(op: *IlOp, index: Int): IlOperandKind {
    val signature: Opt<IlSignature> = ilSignature(op.kind)
    if (!signature.hasValue()) {
        return IlOperandKind.None
    }
    val found: IlSignature = signature.value()
    val tokens: List<Str> = ilOperandTokens(found)
    return ilOperandKindAt(tokens, index)
}

// An operand the dump reads without trusting the instruction's arity: an out-of-range
// read shows up as -1, which the renderers spell as a `?` marker.
fun ilOperandAt(operands: *List<Int>, index: Int): Int {
    if (index < 0 || index >= operands.size()) {
        return -1
    }
    return operands[index]
}

fun ilPoolText(body: *IlBody, index: Int): Str {
    if (index < 0 || index >= body.pool.size()) {
        return "?p" + ilIntText(index)
    }
    return body.pool[index]
}

// A `Var`-position operand as the dump shows it: the slot's name, or - when the operand
// is a literal (a negative index) - the literal's own text. The two cannot be confused
// because a destination is never a literal, and every other `Var` position can be
// either.
fun ilVarName(body: *IlBody, index: Int): Str {
    if (index < 0) {
        return ilPoolText(body, -1 - index)
    }
    if (index >= body.vars.size()) {
        return "?v" + ilIntText(index)
    }
    return body.vars[index].name
}

fun ilTypeName(body: *IlBody, index: Int): Str {
    if (index < 0 || index >= body.types.size()) {
        return "?t" + ilIntText(index)
    }
    return body.types[index]
}

// The language spelling of a slot's type, for the dump's `Declare` comments.
fun ilVarTypeName(body: *IlBody, slot: Int): Str {
    if (slot < 0 || slot >= body.vars.size()) {
        return "?"
    }
    return ilTypeName(body, body.vars[slot].typeIndex)
}

fun ilLabelName(body: *IlBody, index: Int): Str {
    if (index < 0 || index >= body.labels.size()) {
        return "?L" + ilIntText(index)
    }
    return body.labels[index]
}

// A pool entry as the dump shows it: a literal already carries its own quotes; a name
// or an operator gets them so the two are told apart.
fun ilPoolAsText(text: Str): Str {
    if (text.isEmpty()) {
        return "\"\""
    }
    val first: Char = text[0]
    val literal: Bool = first == '\"' || first == '\''
            || (first >= '0' && first <= '9')
    if (literal) {
        return text
    }
    return "\"" + text + "\""
}

// The operand as the reader wants it: the name from the table it indexes, with the raw
// index only in the `?` fallbacks.
fun ilRenderOperand(body: *IlBody, kind: IlOperandKind, value: Int): Str {
    when (kind) {
        IlOperandKind.Var, IlOperandKind.Value -> {
            return ilVarName(body, value)
        }

        IlOperandKind.Text -> {
            return ilPoolAsText(ilPoolText(body, value))
        }

        IlOperandKind.Type -> {
            return ilTypeName(body, value)
        }

        IlOperandKind.Method -> {
            if (value < 0 || value >= body.methods.size()) {
                return "?m" + ilIntText(value)
            }
            return body.methods[value].name
        }

        IlOperandKind.Label -> {
            return ilLabelName(body, value)
        }
    }
    return "?"
}

// The operands of a call, from `first` on, as a comma-separated list.
fun ilArgList(body: *IlBody, operands: *List<Int>, first: Int): Str {
    var args: List<Str> = List<Str>()
    var i: Int = first
    while (i < operands.size()) {
        args.append(ilVarName(body, operands[i]))
        i = i + 1
    }
    return ilJoinList(args, ", ")
}

fun ilPadRight(text: Str, width: Int): Str {
    var out: Str = text
    while (out.size() < width) {
        out.append(' ')
    }
    return out
}

// ---- the dump --------------------------------------------------------------

// What the instruction means, spelled the way the language would write it. This is the
// dump's reason to exist: reading instructions, not trees.
//
// Every read of an operand is bounds-checked: the dump must survive an instruction the
// extractor built with the wrong arity (it then shows `?` markers instead of taking the
// compiler down).
fun ilOpComment(body: *IlBody, op: *IlOp): Str {
    val kind: IlOpKind = op.kind
    val operands: *List<Int> = *op.operands

    when (kind) {
        IlOpKind.Declare -> {
            val slot: Int = ilOperandAt(operands, 0)
            return "var " + ilVarName(body, slot) + ": " + ilVarTypeName(body, slot)
        }

        IlOpKind.Label -> {
            return ilLabelName(body, ilOperandAt(operands, 0)) + ":"
        }

        IlOpKind.Goto -> {
            return "goto " + ilLabelName(body, ilOperandAt(operands, 0))
        }

        IlOpKind.IfTrue, IlOpKind.IfFalse -> {
            val condition: Str = ilVarName(body, ilOperandAt(operands, 0))
            val target: Str = ilLabelName(body, ilOperandAt(operands, 1))
            if (kind == IlOpKind.IfTrue) {
                return "if (" + condition + ") goto " + target
            }
            return "if (!" + condition + ") goto " + target
        }

        IlOpKind.SetVar -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.SetVar_Null -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = null"
        }

        IlOpKind.BinaryOp -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 2)) + " "
            +ilPoolText(body, ilOperandAt(operands, 1)) + " "
            +ilVarName(body, ilOperandAt(operands, 3))
        }

        IlOpKind.UnaryOp -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilPoolText(body, ilOperandAt(operands, 1))
            +ilVarName(body, ilOperandAt(operands, 2))
        }

        IlOpKind.Cast -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = cast "
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.Box -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.Deref -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = *"
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.CopyValue -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = copy("
            +ilVarName(body, ilOperandAt(operands, 1)) + ")"
        }

        IlOpKind.Store -> {
            return "*" + ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.GetField -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 1)) + "."
            +ilPoolText(body, ilOperandAt(operands, 2))
        }

        IlOpKind.SetField -> {
            return ilVarName(body, ilOperandAt(operands, 1)) + "."
            +ilPoolText(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 2))
        }

        IlOpKind.GetIndex -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 1)) + "["
            +ilVarName(body, ilOperandAt(operands, 2)) + "]"
        }

        IlOpKind.SetIndex -> {
            return ilVarName(body, ilOperandAt(operands, 1)) + "["
            +ilVarName(body, ilOperandAt(operands, 2)) + "] = "
            +ilVarName(body, ilOperandAt(operands, 3))
        }

        IlOpKind.FieldAddr -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
            +ilVarName(body, ilOperandAt(operands, 1)) + "."
            +ilPoolText(body, ilOperandAt(operands, 2))
        }

        IlOpKind.IndexAddr -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
            +ilVarName(body, ilOperandAt(operands, 1)) + "["
            +ilVarName(body, ilOperandAt(operands, 2)) + "]"
        }

        IlOpKind.GetStatic -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = "
            +ilPoolText(body, ilOperandAt(operands, 1))
        }

        IlOpKind.GetStaticAddr -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
            +ilPoolText(body, ilOperandAt(operands, 1))
        }

        IlOpKind.SetStatic -> {
            return ilPoolText(body, ilOperandAt(operands, 0)) + " = "
            +ilVarName(body, ilOperandAt(operands, 1))
        }

        IlOpKind.Call, IlOpKind.CallVoid -> {
            val hasDst: Bool = kind == IlOpKind.Call
            var dst: Str = Str()
            if (hasDst) {
                dst = ilVarName(body, ilOperandAt(operands, 0)) + " = "
            }
            var methodOp: Int = ilOperandAt(operands, 0)
            var first: Int = 1
            if (hasDst) {
                methodOp = ilOperandAt(operands, 1)
                first = 2
            }
            if (methodOp < 0 || methodOp >= body.methods.size()) {
                return dst + "?m" + ilIntText(methodOp) + "(" + ilArgList(body, operands, first) + ")"
            }
            val method: IlMethod = body.methods[methodOp]
            if (method.kind == IlMethodKind.Method && first < operands.size()) {
                return dst + ilVarName(body, operands[first]) + "." + method.name + "("
                +ilArgList(body, operands, first + 1) + ")"
            }
            return dst + method.name + "(" + ilArgList(body, operands, first) + ")"
        }

        IlOpKind.CallIndirect, IlOpKind.CallIndirectVoid -> {
            val hasDst2: Bool = kind == IlOpKind.CallIndirect
            var dst2: Str = Str()
            var calleeAt: Int = 0
            if (hasDst2) {
                dst2 = ilVarName(body, ilOperandAt(operands, 0)) + " = "
                calleeAt = 1
            }
            return dst2 + ilVarName(body, ilOperandAt(operands, calleeAt)) + "("
            +ilArgList(body, operands, calleeAt + 1) + ")"
        }

        IlOpKind.Pack -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = ["
            +ilArgList(body, operands, 1) + "]"
        }

        IlOpKind.CallCtor -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = new "
            +ilTypeName(body, ilOperandAt(operands, 1)) + "("
            +ilArgList(body, operands, 2) + ")"
        }

        IlOpKind.Return -> {
            return "return " + ilVarName(body, ilOperandAt(operands, 0))
        }

        IlOpKind.ReturnVoid -> {
            return "return"
        }

        IlOpKind.Lambda -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = <lambda>"
        }

        IlOpKind.Unsupported -> {
            return "<unsupported: " + ilPoolText(body, ilOperandAt(operands, 1)) + ">"
        }
    }
    return Str()
}

// The dump: the tables, then one line per instruction, with the operands resolved for
// the reader. Deterministic - it can be compared byte for byte with the C++ ring's.
fun printIlBody(body: *IlBody): Str {
    var out: Str = Str()
    out = out + "# " + body.file + ":" + ilIntText(body.line) + "  " + body.symbol + " "
    +body.signature + "\n"

    var types: List<Str> = List<Str>()
    var i: Int = 0
    while (i < body.types.size()) {
        types.append(ilIntText(i) + " " + body.types[i])
        i = i + 1
    }
    ilAppendTable(out, "types:   ", types)

    var vars: List<Str> = List<Str>()
    i = 0
    while (i < body.vars.size()) {
        val slot: IlVar = body.vars[i]
        vars.append(
            ilIntText(i) + " " + slot.name + ":" + ilIntText(slot.typeIndex) + ":"
                    + ilVarKindText(slot.kind)
        )
        i = i + 1
    }
    ilAppendTable(out, "vars:    ", vars)

    var pool: List<Str> = List<Str>()
    i = 0
    while (i < body.pool.size()) {
        pool.append(ilIntText(i) + " " + ilPoolAsText(body.pool[i]))
        i = i + 1
    }
    ilAppendTable(out, "pool:    ", pool)

    var methods: List<Str> = List<Str>()
    i = 0
    while (i < body.methods.size()) {
        val method: IlMethod = body.methods[i]
        var text: Str = ilIntText(i) + " " + method.name + ":" + ilMethodKindText(method.kind) + ":"
        +ilIntText(method.argCount)
        if (method.staticBase >= 0) {
            text = text + ":static=" + ilTypeName(body, method.staticBase)
        }
        if (method.returnType >= 0) {
            text = text + ":ret=" + ilTypeName(body, method.returnType)
        }
        methods.append(text)
        i = i + 1
    }
    ilAppendTable(out, "methods: ", methods)

    var labels: List<Str> = List<Str>()
    i = 0
    while (i < body.labels.size()) {
        labels.append(ilIntText(i) + " " + body.labels[i])
        i = i + 1
    }
    ilAppendTable(out, "labels:  ", labels)

    i = 0
    while (i < body.ops.size()) {
        val op: IlOp = body.ops[i]
        val signature: Opt<IlSignature> = ilSignature(op.kind)
        var tokens: List<Str> = List<Str>()
        if (signature.hasValue()) {
            val found: IlSignature = signature.value()
            tokens = ilOperandTokens(found)
        }

        var rendered: List<Str> = List<Str>()
        var j: Int = 0
        while (j < op.operands.size()) {
            rendered.append(ilRenderOperand(body, ilOperandKindAt(tokens, j), op.operands[j]))
            j = j + 1
        }

        var text: Str = ilPadRight(ilIntText(i), 4) + ",  " + ilPadRight(ilOpKindText(op.kind), 16)
        +ilJoinList(rendered, ", ")
        val comment: Str = ilOpComment(body, op)
        if (!comment.isEmpty()) {
            text = ilPadRight(text, 74) + "# " + comment
        }
        var sourceLine: Int = 0
        if (i < body.lines.size()) {
            sourceLine = body.lines[i]
        }
        if (sourceLine > 0) {
            text = text + "  (line " + ilIntText(sourceLine) + ")"
        }
        out = out + text + "\n"
        i = i + 1
    }
    return out
}

// The dump of a whole unit: the body, then a section per lambda.
fun printIlUnit(unit: *IlUnit): Str {
    var out: Str = printIlBody(unit.body)
    var i: Int = 0
    while (i < unit.closures.size()) {
        val closure: IlClosure = unit.closures[i]
        out = out + "\n## closure " + closure.symbol + "  captures ("
        +ilJoinList(closure.captures, ", ") + ")  " + closure.signature + "\n"
        if (closure.bodyIndex >= 0 && closure.bodyIndex < unit.lambdas.size()) {
            out = out + printIlBody(unit.lambdas[closure.bodyIndex])
        }
        i = i + 1
    }
    return out
}

// ---- the closure of a lambda -----------------------------------------------
//
// The names a lambda body reads from *outside* itself: every read that is not one of its
// parameters and not a name it declares. That set is the closure - what the class's
// fields are - and it is computed, not guessed. Order is first-read, because the fields'
// order (and so every table the IL builds) has to be reproducible.

fun ilCollectExprNames(node: *AstXmlNode, order: *List<Str>, seen: *Dictionary<Str, Bool>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (name != "this" && !seen.has(name)) {
            seen.insert(name, true)
            order.append(name)
        }
        return
    }
    // A nested lambda is its own closure: its free names are resolved against *its*
    // frame when it is extracted, so walking into it here would collect the wrong set.
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return
    }
    for (*child in node.Children) {
        ilCollectExprNames(child, order, seen)
    }
}

// The names a statement sequence reads, in the order the C++ ring walks them (the
// statement's own expressions, then the statements of its containers). A name it declares
// counts as declared, wherever in the sequence the declaration stands.
fun ilCollectStmtNames(
    stmts: *List<AstXmlNode>, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            val declaredName: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            if (!declaredName.isEmpty()) {
                declared.insert(declaredName, true)
            }
        }
        ilCollectStmtExprs(stmt, AstNodeKind.Cond, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Target, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Value, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Init, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Expr, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Body, declared, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Then, declared, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Else, declared, order, seen)
    }
}

// One expression child of a statement, when the statement has it.
fun ilCollectStmtExprs(
    stmt: *AstXmlNode, role: AstNodeKind, order: *List<Str>,
    seen: *Dictionary<Str, Bool>
): Unit {
    val child: AstXmlNode = xmlChild(stmt, role)
    if (!xmlIsEmpty(child)) {
        ilCollectExprNames(child, order, seen)
    }
}

// The statements of one container child (`Body`, `Then`, `Else`), when it has one.
fun ilCollectStmtNamesIn(
    stmt: *AstXmlNode, role: AstNodeKind, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    val container: AstXmlNode = xmlChild(stmt, role)
    if (xmlIsEmpty(container)) {
        return
    }
    var i: Int = 0
    val children: Array<AstXmlNode> = container.Children
    var body: List<AstXmlNode> = List<AstXmlNode>()
    while (i < children.count()) {
        body.append(children[i])
        i = i + 1
    }
    ilCollectStmtNames(body, declared, order, seen)
}

// ---- operand lists ---------------------------------------------------------

fun ilOps1(a: Int): List<Int> {
    return listOf<Int>(a)
}

fun ilOps2(a: Int, b: Int): List<Int> {
    return listOf<Int>(a, b)
}

fun ilOps3(a: Int, b: Int, c: Int): List<Int> {
    return listOf<Int>(a, b, c)
}

fun ilOps4(a: Int, b: Int, c: Int, d: Int): List<Int> {
    return listOf<Int>(a, b, c, d)
}

// A type node built for a synthesized slot: the language's types are nodes, so a receiver
// slot's `*T` is a pointer node around the receiver's own node (a copy, like the C++
// ring's `receiverTypeNode`).
fun ilPointerNode(inner: *AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypePointer,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var renamed: AstXmlNode = copy(inner)
    // The child's role is what the backend's `type()` looks up (`Inner`), so a synthesized
    // `*T` has to carry it like a parsed one does.
    renamed.name = AstNodeKind.Inner
    xmlAddChild(node, renamed)
    return node
}

// A `*x` node around an expression, the shape the parser builds (the operand under the
// `Operand` role): the extractor makes one when a call argument's address is what a
// `*T` parameter wants, so the address is spelled by the *same* code an explicit `*`
// reaches (`into`).
fun ilDerefNode(operand: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprDeref,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var renamed: AstXmlNode = copy(operand)
    renamed.name = AstNodeKind.Operand
    xmlAddChild(node, renamed)
    return node
}

// Whether an assignment's operator is one of the compound forms (`+=`, `-=`, `*=`, `/=`,
// `%=`). The step forms (`i++`, `i--`) are the parser's `+= 1` and `-= 1`, so the
// lowering sees one shape for all of them.
fun isCompoundAssignOp(op: Str): Bool {
    return op == "+=" || op == "-=" || op == "*=" || op == "/=" || op == "%="
}

// The binary operation a compound assignment's operator names: `+=` is `x = x + v`.
fun compoundBinaryOp(op: Str): Str {
    when (op) {
        "+=" -> {
            return "+"
        }

        "-=" -> {
            return "-"
        }

        "*=" -> {
            return "*"
        }

        "/=" -> {
            return "/"
        }
    }
    return "%"
}

fun ilNamedTypeNode(name: Str): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypeNamed,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// The type a receiver slot is: already a pointer or a handle when the source said so,
// otherwise a pointer to the value (a value receiver is `T* self`).
fun ilReceiverTypeNode(typeNode: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return copy(typeNode)
    }
    return ilPointerNode(typeNode)
}

// Whether a type is reached through a handle (`&T`, `*T`, or the `PList<T>` alias of
// `&List<T>`): the C++ ring's `sema::isHandleType`, which this ring spells on the emitter
// (`Emitter.isHandleType`). The extractor is not the emitter, so it reads the same rule
// here.
fun ilIsHandleType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList") {
        return true
    }
    return false
}

// ---- the extractor ---------------------------------------------------------

// One body into instructions. The frame comes from `IlFunction` (a declaration's
// parameters and receiver, or a lambda's / a machine method's parameters and class), the
// tables are filled in first-touch order, and every instruction carries the line of the
// statement it came from.
data class IlExtractor(
    var fn: IlFunction,

    var unit: *IlUnit,
    var closureCounter: *Int,
    var out: IlBody,
    var varAt: Dictionary<Str, Int>,
    var typeAt: Dictionary<Str, Int>,
    var poolAt: Dictionary<Str, Int>,
    var methodAt: Dictionary<Str, Int>,
    var labelAt: Dictionary<Str, Int>,
    var nextBase: Int,
    var line: Int,
    // The synthesized slots that carry a type: their declarations go to the top of the
    // instruction list (with the line that needed them, for the dump).
    var hoisted: List<Int>,
    var hoistedLines: List<Int>,
    // The caches the type questions read: the context `semTypeOfExpr` is given and the
    // frame as it wants to see it (`typeContext`/`frameNames`), plus the flag that says
    // whether each is still valid (`typeContextReady`/`frameNamesStale`). The context is
    // *borrowed* storage of the caller's - the way `unit` and `closureCounter` are - because
    // a `SemBody` field here would be an incomplete type where this class is defined.
    var typeContext: *SemBody,
    var typeContextReady: Bool,
    var frameNames: Dictionary<Str, AstXmlNode>,
    var frameNamesStale: Bool
) {

    // ---- tables -----------------------------------------------------------

    fun addVar(name: Str, typeText: Str, kind: IlVarKind, typeNode: AstXmlNode): Int {
        this.out.vars.append(IlVar(name, this.typeIndex(typeText, typeNode), kind))
        this.varAt.insert(name, this.out.vars.size() - 1)
        this.frameChanged()
        return this.out.vars.size() - 1
    }

    // Give a slot a type after the fact: the destination of a list literal is the call's
    // own slot, and the literal knows the type it is building even when the position it
    // stands in would only have inferred it.
    fun setSlotType(slot: Int, typeNode: AstXmlNode): Unit {
        if (slot < 0 || slot >= this.out.vars.size() || xmlIsEmpty(typeNode)) {
            return
        }
        this.out.vars[slot].typeIndex = this.typeIndex(ilTypeText(typeNode), typeNode)
        this.frameChanged()
    }

    // The type table: text for the dump, the node (when there is one) for a backend. The
    // first node seen for a text wins, so the table does not depend on which mention
    // happened to carry it.
    fun typeIndex(text: Str, node: AstXmlNode): Int {
        if (this.typeAt.has(text)) {
            val index: Int = this.typeAt.get(text).value()
            if (!xmlIsEmpty(node) && index < this.out.typeNodes.size()) {
                val existing: AstXmlNode = this.out.typeNodes[index]
                if (xmlIsEmpty(existing)) {
                    this.out.typeNodes[index] = node
                }
            }
            return index
        }
        this.out.types.append(text)
        this.out.typeNodes.append(node)
        this.typeAt.insert(text, this.out.types.size() - 1)
        return this.out.types.size() - 1
    }

    fun typeIndexText(text: Str): Int {
        return this.typeIndex(text, xmlEmptyNode())
    }

    fun poolIndex(text: Str): Int {
        if (this.poolAt.has(text)) {
            return this.poolAt.get(text).value()
        }
        this.out.pool.append(text)
        this.poolAt.insert(text, this.out.pool.size() - 1)
        return this.out.pool.size() - 1
    }

    fun labelIndex(name: Str): Int {
        if (this.labelAt.has(name)) {
            return this.labelAt.get(name).value()
        }
        this.out.labels.append(name)
        this.labelAt.insert(name, this.out.labels.size() - 1)
        return this.out.labels.size() - 1
    }

    // A method's identity is its name, its kind, the type it is reached through (a static
    // call), and the types it is passed: the same name over two receivers is two entries.
    fun methodIndex(
        name: Str, kind: IlMethodKind, staticBase: Int, returnType: Int,
        argTypes: List<Int>
    ): Int {
        var key: Str = name + "|" + ilMethodKindText(kind) + "|" + ilIntText(staticBase)
        var i: Int = 0
        while (i < argTypes.size()) {
            key = key + "|" + ilIntText(argTypes[i])
            i = i + 1
        }
        if (this.methodAt.has(key)) {
            return this.methodAt.get(key).value()
        }
        this.out.methods.append(IlMethod(name, kind, argTypes.size(), staticBase, returnType, argTypes))
        this.methodAt.insert(key, this.out.methods.size() - 1)
        return this.out.methods.size() - 1
    }

    fun hasVar(name: Str): Bool {
        return this.varAt.has(name)
    }

    fun varIndex(name: Str): Int {
        if (this.varAt.has(name)) {
            return this.varAt.get(name).value()
        }
        return -1
    }

    fun freshSlot(typeText: Str, typeNode: AstXmlNode): Int {
        val slot: Int = this.addVar(
            "_sm_base" + ilIntText(this.nextBase), typeText,
            IlVarKind.Temp, typeNode
        )
        this.nextBase = this.nextBase + 1
        if (!xmlIsEmpty(typeNode)) {
            this.hoisted.append(slot)
            this.hoistedLines.append(this.line)
        } else {
            // A slot the type rules could not name: the instruction list has to say where
            // it comes from *here*, in front of the instruction that first writes it, and
            // the backend declares it with `auto`.
            this.emit(IlOpKind.Declare, ilOps1(slot))
        }
        return slot
    }

    fun freshSlotText(typeText: Str): Int {
        return this.freshSlot(typeText, xmlEmptyNode())
    }

    // ---- the types of what the extractor synthesizes ----------------------

    // The context `semTypeOfExpr` reads, and the frame as it wants to see it. Both are
    // constant for a body except when a slot is added, so they are built once and reused:
    // every question used to rebuild them (a dictionary of every slot, and a `SemBody` with
    // its lists copied), which cost the compiler's own transpile about a third of its time
    // once the extractor began asking about *call* arguments as well as about the slots it
    // synthesizes.
    fun frameChanged(): Unit {
        this.frameNamesStale = true
    }

    // The context, borrowed: the same one every question about this body is asked with.
    // The storage is the caller's, the way the extractor's own frame tables are filled in
    // place; it is written once, on the first question.
    fun bodyContext(): *SemBody {
        if (this.typeContextReady) {
            return this.typeContext
        }
        this.typeContext.decl = this.fn.decl
        this.typeContext.selfType = this.fn.receiver
        this.typeContext.selfDecl = this.fn.selfDecl
        this.typeContext.typeParams = this.fn.typeParams
        this.typeContext.paramNames = this.fn.paramNames
        this.typeContext.paramTypes = this.fn.paramTypes
        this.typeContext.captures = this.fn.captureTypes
        if (xmlIsEmpty(this.typeContext.selfType) && !this.fn.closureSymbol.isEmpty()) {
            // A machine method or a lambda body: `this` is the instance of the class the
            // lowering built (a value receiver reads through it).
            this.typeContext.selfType = ilNamedTypeNode(this.fn.closureSymbol)
        }
        this.typeContextReady = true
        return this.typeContext
    }

    // The frame is a scope: every slot's name and its type. That is exactly the shape
    // `semTypeOfExpr` takes, and the reason the extractor can give a type to a slot the
    // statements never declared (a place, a value position the lowering left inline) - a
    // slot without one cannot be declared at the top of the body, and then the instruction
    // that reads it has to inline the whole expression it stands for. Borrowed, and rebuilt
    // only when the frame changed.
    fun frameTypes(): *Dictionary<Str, AstXmlNode> {
        if (this.frameNamesStale) {
            this.frameNames.clear()
            var i: Int = 0
            while (i < this.out.vars.size()) {
                val typeNode: AstXmlNode = ilVarType(this.out, i)
                if (!xmlIsEmpty(typeNode)) {
                    this.frameNames.insert(this.out.vars[i].name, typeNode)
                }
                i = i + 1
            }
            this.frameNamesStale = false
        }
        return * this.frameNames
    }

    // The type of an expression, from the rules the *type pass* applies to a whole body -
    // asked here about one node, with this body's frame in scope. An empty node when the
    // rules cannot name it (or when there are no facts to ask); the caller then leaves the
    // slot untyped.
    fun exprType(e: AstXmlNode): AstXmlNode {
        if (this.fn.facts == null) {
            return xmlEmptyNode()
        }
        return semTypeOfExpr(e, this.fn.facts, this.bodyContext(), this.frameTypes())
    }

    // The type of the *value* an instruction writes for this expression. It is the type
    // rules' answer, unchanged: they already describe what the emitter spells (`*x` on a
    // value is the address, on `&T` the `.get()`, on `*T` the load through it).
    fun valueType(e: AstXmlNode): AstXmlNode {
        return this.exprType(e)
    }

    // The type of a slot to synthesise for `e`: its own type, spelled the way the frame
    // spells types, or `?` when there is none.
    fun slotTypeText(typeNode: AstXmlNode): Str {
        if (xmlIsEmpty(typeNode)) {
            return "?"
        }
        return ilTypeText(typeNode)
    }

    fun emit(kind: IlOpKind, operands: List<Int>): Unit {
        this.out.ops.append(IlOp(kind, operands))
        this.out.lines.append(this.line)
    }

    fun unsupported(what: Str): Unit {
        this.emit(IlOpKind.Unsupported, ilOps2(this.freshSlotText("?"), this.poolIndex(what)))
    }

    fun literalOperand(text: Str): Int {
        return -1 - this.poolIndex(text)
    }

    // ---- the frame --------------------------------------------------------

    fun begin(file: Str): Unit {
        this.out.file = file
        if (xmlIsEmpty(this.fn.decl)) {
            this.out.line = 0
        } else {
            this.out.line = xmlLine(this.fn.decl)
        }
        this.out.symbol = this.fn.symbol
        // The types the enclosing pass proved, so the frame carries the slots a
        // declaration could not name (a `..T` machine). The extractor still adds each
        // slot's own declared type as it goes; both are keyed by name.
        val declaredTypes: List<Str> = this.fn.inferredTypes.keys()
        var ti: Int = 0
        while (ti < declaredTypes.size()) {
            this.out.inferredTypes.insert(
                declaredTypes[ti], this.fn.inferredTypes.get(declaredTypes[ti]).value()
            )
            ti = ti + 1
        }
        this.buildFrame()
        this.out.signature = this.signatureText()
    }

    fun run(body: List<AstXmlNode>): IlBody {
        this.stmts(body)
        // The extractor's own slots are the body's registers: a slot with a type is
        // declared once, at the top of the instruction list, exactly where `hoistSlots` put
        // the lowering's own slots - so the frame is flat, no jump can cross a declaration,
        // and nothing has to be scoped. A slot without a type (a shape the type rules
        // cannot name) keeps its declaration in front of the instruction that first writes
        // it, which the backend then pairs with that instruction as `auto`.
        if (this.hoisted.size() > 0) {
            var ops: List<IlOp> = List<IlOp>()
            var lines: List<Int> = List<Int>()
            var i: Int = 0
            while (i < this.hoisted.size()) {
                ops.append(IlOp(IlOpKind.Declare, ilOps1(this.hoisted[i])))
                lines.append(this.hoistedLines[i])
                i = i + 1
            }
            i = 0
            while (i < this.out.ops.size()) {
                ops.append(this.out.ops[i])
                i = i + 1
            }
            i = 0
            while (i < this.out.lines.size()) {
                lines.append(this.out.lines[i])
                i = i + 1
            }
            this.out.ops = ops
            this.out.lines = lines
        }
        return this.out
    }

    fun buildFrame(): Unit {
        if (!this.fn.closureSymbol.isEmpty()) {
            // A lambda (or a machine's method): the receiver is the class instance - whose
            // fields the captures are - and the parameter list is the body's own.
            this.addVar(
                "self", "*" + this.fn.closureSymbol, IlVarKind.Argument,
                ilPointerNode(ilNamedTypeNode(this.fn.closureSymbol))
            )
            var i: Int = 0
            while (i < this.fn.paramNames.size()) {
                var paramType: AstXmlNode = xmlEmptyNode()
                if (i < this.fn.paramTypes.size()) {
                    paramType = this.fn.paramTypes[i]
                }
                var text: Str = "?"
                if (!xmlIsEmpty(paramType)) {
                    text = ilTypeText(paramType)
                }
                this.addVar(this.fn.paramNames[i], text, IlVarKind.Argument, paramType)
                i = i + 1
            }
            return
        }
        var hasSelf: Bool = false
        if (!xmlIsEmpty(this.fn.receiver)) {
            this.addVar(
                "self", ilReceiverTypeText(this.fn.receiver), IlVarKind.Argument,
                ilReceiverTypeNode(this.fn.receiver)
            )
            hasSelf = true
        }
        val params: List<AstXmlNode> = xmlChildren(this.fn.decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            val param: *AstXmlNode = *params[i]
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            val typeNode: AstXmlNode = xmlChild(param, AstNodeKind.Type)
            if (name == "this" && !hasSelf) {
                var selfText: Str = "?"
                if (!xmlIsEmpty(typeNode)) {
                    selfText = ilReceiverTypeText(typeNode)
                }
                this.addVar("self", selfText, IlVarKind.Argument, ilReceiverTypeNode(typeNode))
                hasSelf = true
                i = i + 1
                continue
            }
            var text: Str = "?"
            if (!xmlIsEmpty(typeNode)) {
                text = ilTypeText(typeNode)
            }
            this.addVar(name, text, IlVarKind.Argument, typeNode)
            i = i + 1
        }
    }

    fun signatureText(): Str {
        var params: List<Str> = List<Str>()
        var i: Int = 0
        while (i < this.out.vars.size()) {
            val slot: IlVar = this.out.vars[i]
            if (slot.kind == IlVarKind.Argument) {
                params.append(this.out.types[slot.typeIndex] + " " + slot.name)
            }
            i = i + 1
        }
        // A lambda's result is whatever its body returns, which C++ deduces; a function's
        // is its declared type.
        var retText: Str = "?"
        if (!xmlIsEmpty(this.fn.decl)) {
            val declared: AstXmlNode = xmlChild(this.fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(declared)) {
                retText = ilTypeText(declared)
            }
        }
        return "(" + ilJoinList(params, ", ") + ") -> " + retText
    }

    // ---- statements -------------------------------------------------------

    fun stmts(list: *List<AstXmlNode>): Unit {
        for (*stmt in list) {
            this.statement(stmt)
        }
    }

    fun statement(stmt: *AstXmlNode): Unit {
        this.line = xmlLine(stmt)
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtLabel -> {
                this.emit(IlOpKind.Label, ilOps1(this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))))
                return
            }

            AstNodeCategory.StmtGoto -> {
                this.emit(IlOpKind.Goto, ilOps1(this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))))
                return
            }

            AstNodeCategory.StmtIfTrue, AstNodeCategory.StmtIfFalse -> {
                val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
                this.emit(
                    ilCategoryName(kind), ilOps2(
                        this.operandOf(cond),
                        this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))
                    )
                )
                return
            }

            AstNodeCategory.StmtVarDecl -> {
                val typeNode: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
                var typeText: Str = "?"
                if (!xmlIsEmpty(typeNode)) {
                    typeText = ilTypeText(typeNode)
                }
                val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                var slotKind: IlVarKind = IlVarKind.Local
                if (linIsSlotName(name)) {
                    slotKind = IlVarKind.Expression
                }
                val slot: Int = this.addVar(name, typeText, slotKind, typeNode)
                val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
                if (xmlIsEmpty(init)) {
                    this.emit(IlOpKind.Declare, ilOps1(slot))
                } else {
                    this.emit(IlOpKind.DeclareInit, ilOps1(slot))
                    this.into(slot, init)
                }
                return
            }

            AstNodeCategory.StmtAssign -> {
                this.assign(stmt, xmlChild(stmt, AstNodeKind.Target), xmlChild(stmt, AstNodeKind.Value))
                return
            }

            AstNodeCategory.StmtReturn -> {
                val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
                if (xmlIsEmpty(value)) {
                    this.emit(IlOpKind.ReturnVoid, List<Int>())
                } else {
                    this.emit(IlOpKind.Return, ilOps1(this.operandOf(value)))
                }
                return
            }

            AstNodeCategory.StmtExprStmt -> {
                val expr: AstXmlNode = xmlChild(stmt, AstNodeKind.Expr)
                if (xmlKind(expr) == AstNodeCategory.ExprCall) {
                    this.call(-1, expr)
                    return
                }
                // A read with no destination: keep it visible rather than dropping it.
                this.unsupported("expression statement")
                return
            }

            AstNodeCategory.StmtBlock -> {
                val container: AstXmlNode = xmlChild(stmt, AstNodeKind.Body)
                var i: Int = 0
                val children: Array<AstXmlNode> = container.Children
                var body: List<AstXmlNode> = List<AstXmlNode>()
                while (i < children.count()) {
                    body.append(children[i])
                    i = i + 1
                }
                this.stmts(body)
                return
            }

            AstNodeCategory.StmtIf, AstNodeCategory.StmtWhile,
            AstNodeCategory.StmtBreak, AstNodeCategory.StmtContinue -> {
                this.unsupported("structured statement reached the IL")
                return
            }
        }
        this.unsupported("statement")
    }

    fun assign(stmt: *AstXmlNode, target: *AstXmlNode, value: *AstXmlNode): Unit {
        val op: Str = xmlAttr(stmt, AstNodeAttributeKind.Op)
        if (isCompoundAssignOp(op)) {
            this.assignCompound(target, compoundBinaryOp(op), value)
            return
        }
        val targetKind: AstNodeCategory = xmlKind(target)
        when (targetKind) {
            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                // A write to a captured variable writes the closure's field.
                if (this.fn.captures.has(name)) {
                    this.emit(
                        IlOpKind.SetField, ilOps3(
                            this.varIndex("self"), this.poolIndex(name),
                            this.operandOf(value)
                        )
                    )
                    return
                }
                val slot: Int = this.varIndex(name)
                if (slot >= 0) {
                    this.into(slot, value)
                    return
                }
                this.emit(IlOpKind.SetStatic, ilOps2(this.poolIndex(name), this.operandOf(value)))
                return
            }

            AstNodeCategory.ExprMember -> {
                val lhs: AstXmlNode = xmlChild(target, AstNodeKind.Receiver)
                val fieldName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                if (this.isTypeBase(lhs)) {
                    this.emit(
                        IlOpKind.SetStatic, ilOps2(
                            this.poolIndex(this.baseText(lhs) + "." + fieldName),
                            this.operandOf(value)
                        )
                    )
                    return
                }
                this.emit(
                    IlOpKind.SetField, ilOps3(
                        this.receiverOf(lhs), this.poolIndex(fieldName),
                        this.operandOf(value)
                    )
                )
                return
            }

            AstNodeCategory.ExprIndex -> {
                this.emit(
                    IlOpKind.SetIndex, ilOps3(
                        this.receiverOf(xmlChild(target, AstNodeKind.Receiver)),
                        this.operandOf(xmlChild(target, AstNodeKind.Index)),
                        this.operandOf(value)
                    )
                )
                return
            }

            AstNodeCategory.ExprDeref -> {
                // `*p = v`: the emitter writes through the pointer's type.
                this.emit(
                    IlOpKind.Store, ilOps2(
                        this.operandOf(xmlChild(target, AstNodeKind.Operand)),
                        this.operandOf(value)
                    )
                )
                return
            }
        }
        this.unsupported("assignment target")
    }

    // `x op= v` - and `x++`/`x--`, which are the parser's `x += 1` and `x -= 1`: the
    // target's *place* is located once, its current value is read out of that same place,
    // folded with the operation, and written back through it. So `a[i] += 1` evaluates `a`
    // and `i` once, `*p += 1` is one load, one fold and one store, and a write can never
    // land in a copy of what the target names.
    fun assignCompound(target: *AstXmlNode, op: Str, value: *AstXmlNode): Unit {
        val targetKind: AstNodeCategory = xmlKind(target)
        when (targetKind) {
            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                // A captured variable lives in the closure's object: read and write its
                // field, so the fold sees what the target holds.
                if (this.fn.captures.has(name)) {
                    val base: Int = this.varIndex("self")
                    val field: Int = this.poolIndex(name)
                    val current: Int = this.readField(target, base, field)
                    this.emit(
                        IlOpKind.SetField,
                        ilOps3(base, field, this.fold(current, op, value, target))
                    )
                    return
                }
                // A local slot *is* the place: `i = i + 1` is one instruction, and there is
                // no address to take.
                val slot: Int = this.varIndex(name)
                if (slot >= 0) {
                    this.emit(
                        IlOpKind.BinaryOp, ilOps4(
                            slot, this.poolIndex(op), slot, this.operandOf(value)
                        )
                    )
                    return
                }
                // A file-level `var` lives in static storage: read, fold, write back - the
                // name is the same place both times.
                val staticName: Int = this.poolIndex(name)
                val current: Int = this.readStatic(target, staticName)
                this.emit(
                    IlOpKind.SetStatic,
                    ilOps2(staticName, this.fold(current, op, value, target))
                )
                return
            }

            AstNodeCategory.ExprMember -> {
                val lhs: AstXmlNode = xmlChild(target, AstNodeKind.Receiver)
                val fieldName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                if (this.isTypeBase(lhs)) {
                    val field: Int = this.poolIndex(this.baseText(lhs) + "." + fieldName)
                    val current: Int = this.readStatic(target, field)
                    this.emit(IlOpKind.SetStatic, ilOps2(field, this.fold(current, op, value, target)))
                    return
                }
                // The base is the reference the whole expression is reached through: it is
                // located once, and both the read and the write name the field on it.
                val base: Int = this.receiverOf(lhs)
                val field: Int = this.poolIndex(fieldName)
                val current: Int = this.readField(target, base, field)
                this.emit(IlOpKind.SetField, ilOps3(base, field, this.fold(current, op, value, target)))
                return
            }

            AstNodeCategory.ExprIndex -> {
                // The base and the index are each evaluated once, into the operands the read
                // and the write share - so an index with a side effect runs once.
                val base: Int = this.receiverOf(xmlChild(target, AstNodeKind.Receiver))
                val index: Int = this.operandOf(xmlChild(target, AstNodeKind.Index))
                val current: Int = this.freshValueSlot(target)
                this.emit(IlOpKind.GetIndex, ilOps3(current, base, index))
                this.emit(IlOpKind.SetIndex, ilOps3(base, index, this.fold(current, op, value, target)))
                return
            }

            AstNodeCategory.ExprDeref -> {
                // `*p += 1`: the pointer *is* the place, so the load and the store both go
                // through it and the pointee is written in place.
                val pointer: Int = this.operandOf(xmlChild(target, AstNodeKind.Operand))
                val current: Int = this.freshValueSlot(target)
                this.emit(IlOpKind.Deref, ilOps2(current, pointer))
                this.emit(IlOpKind.Store, ilOps2(pointer, this.fold(current, op, value, target)))
                return
            }
        }
        this.unsupported("compound assignment target")
    }

    // The current value of a field, as one instruction: the base is the one the write will
    // use, so the place is not located twice.
    fun readField(whole: *AstXmlNode, base: Int, field: Int): Int {
        val slot: Int = this.freshValueSlot(whole)
        this.emit(IlOpKind.GetField, ilOps3(slot, base, field))
        return slot
    }

    fun readStatic(whole: *AstXmlNode, name: Int): Int {
        val slot: Int = this.freshValueSlot(whole)
        this.emit(IlOpKind.GetStatic, ilOps2(slot, name))
        return slot
    }

    // `current <op> value`, in a slot of the target's own type - the value the write puts
    // back.
    fun fold(current: Int, op: Str, value: *AstXmlNode, whole: *AstXmlNode): Int {
        val slot: Int = this.freshValueSlot(whole)
        this.emit(IlOpKind.BinaryOp, ilOps4(slot, this.poolIndex(op), current, this.operandOf(value)))
        return slot
    }

    // A slot for the value of `e`, typed the way `valueOf` types the slots it synthesizes.
    fun freshValueSlot(e: *AstXmlNode): Int {
        val typeNode: AstXmlNode = this.valueType(e)
        return this.freshSlot(this.slotTypeText(typeNode), typeNode)
    }

    // ---- values -----------------------------------------------------------

    // The value `expr` produces, as an operand: a frame slot, a literal (a negative
    // operand, see `literalOperand`), or - when the position holds more than a name - a
    // slot the extractor synthesizes for the place.
    fun operandOf(expr: *AstXmlNode): Int {
        val kind: AstNodeCategory = xmlKind(expr)
        when (kind) {
            AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit,
            AstNodeCategory.ExprStrLit, AstNodeCategory.ExprCharLit -> {
                // A literal in a value position rides the instruction as an operand: the pool
                // holds the token's own text, so a backend prints exactly what the statement
                // path printed - `i > 0`, not `_sm_base1 = 0; ... i > _sm_base1`.
                return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Text))
            }

            AstNodeCategory.ExprBoolLit -> {
                return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Value))
            }

            AstNodeCategory.ExprNullLit -> {
                // `null`'s spelling depends on the expected type (`Opt<T>()` vs `nullptr`),
                // which only the destination slot's type states for certain - so it is
                // materialised. It is the one value with no type of its own, so the slot stays
                // untyped and the backend inlines it where it is read.
                val slot: Int = this.freshSlotText("?")
                this.emit(IlOpKind.SetVar_Null, ilOps1(slot))
                return slot
            }

            AstNodeCategory.ExprName -> {
                return this.nameOf(expr)
            }

            AstNodeCategory.ExprMember, AstNodeCategory.ExprIndex,
            AstNodeCategory.ExprCall, AstNodeCategory.ExprBinary,
            AstNodeCategory.ExprUnary, AstNodeCategory.ExprRef,
            AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
                // A value the body does not hold in a slot: the extractor makes one, and the
                // *type rules* type it - so the instruction that reads it names a declared slot
                // instead of inlining the whole expression it stands for.
                val typeNode: AstXmlNode = this.valueType(expr)
                val slot: Int = this.freshSlot(this.slotTypeText(typeNode), typeNode)
                this.into(slot, expr)
                return slot
            }

            AstNodeCategory.ExprLambda -> {
                return this.lambdaOf(expr, -1)
            }

            AstNodeCategory.ExprGenericName -> {
                this.unsupported("type name in a value position")
                return this.freshSlotText("?")
            }
        }
        this.unsupported("expression")
        return this.freshSlotText("?")
    }

    // Writes `e` into `slot`, one instruction per operation: every value the lowering
    // produces is one operation deep, so one case here is one instruction.
    fun into(slot: Int, e: AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit,
            AstNodeCategory.ExprStrLit, AstNodeCategory.ExprCharLit -> {
                this.emit(
                    IlOpKind.SetVar,
                    ilOps2(slot, this.literalOperand(xmlAttr(e, AstNodeAttributeKind.Text)))
                )
                return
            }

            AstNodeCategory.ExprBoolLit -> {
                this.emit(
                    IlOpKind.SetVar,
                    ilOps2(slot, this.literalOperand(xmlAttr(e, AstNodeAttributeKind.Value)))
                )
                return
            }

            AstNodeCategory.ExprNullLit -> {
                this.emit(IlOpKind.SetVar_Null, ilOps1(slot))
                return
            }

            AstNodeCategory.ExprName -> {
                this.emit(IlOpKind.SetVar, ilOps2(slot, this.nameOf(e)))
                return
            }

            AstNodeCategory.ExprMember -> {
                val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
                val fieldName: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (this.isTypeBase(lhs)) {
                    this.emit(
                        IlOpKind.GetStatic,
                        ilOps2(slot, this.poolIndex(this.baseText(lhs) + "." + fieldName))
                    )
                    return
                }
                this.emit(IlOpKind.GetField, ilOps3(slot, this.receiverOf(lhs), this.poolIndex(fieldName)))
                return
            }

            AstNodeCategory.ExprIndex -> {
                this.emit(
                    IlOpKind.GetIndex, ilOps3(
                        slot, this.receiverOf(xmlChild(e, AstNodeKind.Receiver)),
                        this.operandOf(xmlChild(e, AstNodeKind.Index))
                    )
                )
                return
            }

            AstNodeCategory.ExprBinary -> {
                this.emit(
                    IlOpKind.BinaryOp, ilOps4(
                        slot, this.poolIndex(xmlAttr(e, AstNodeAttributeKind.Op)),
                        this.operandOf(xmlChild(e, AstNodeKind.Lhs)),
                        this.operandOf(xmlChild(e, AstNodeKind.Rhs))
                    )
                )
                return
            }

            AstNodeCategory.ExprUnary -> {
                this.emit(
                    IlOpKind.UnaryOp, ilOps3(
                        slot, this.poolIndex(xmlAttr(e, AstNodeAttributeKind.Op)),
                        this.operandOf(xmlChild(e, AstNodeKind.Operand))
                    )
                )
                return
            }

            AstNodeCategory.ExprRef -> {
                this.emit(IlOpKind.Box, ilOps2(slot, this.operandOf(xmlChild(e, AstNodeKind.Operand))))
                return
            }

            AstNodeCategory.ExprDeref -> {
                // `*x` is the address of what `x` denotes: of a value's own storage (the place
                // itself, or `&name`), of a counted reference's pointee (`.get()`), or the load
                // through a raw pointer - the backend reads which from the operand's slot type.
                // A place that is a chain already *is* its address, so it is copied; a name, a
                // handle and a call's result are read and the address is taken from the value.
                val operand: AstXmlNode = xmlChild(e, AstNodeKind.Operand)
                val operandName: Str = xmlAttr(operand, AstNodeAttributeKind.Name)
                if (xmlKind(operand) == AstNodeCategory.ExprName && this.isStaticName(operandName)) {
                    // A file-level static is not a slot of the frame, so its address is its own
                    // instruction (writing the value into a slot first would take the address of
                    // a copy).
                    this.emit(IlOpKind.GetStaticAddr, ilOps2(slot, this.poolIndex(operandName)))
                    return
                }
                if ((xmlKind(operand) == AstNodeCategory.ExprMember
                            || xmlKind(operand) == AstNodeCategory.ExprIndex)
                    && !this.isHandleExpr(operand)
                ) {
                    // A chain that is a place: the address *is* the place slot. (A call's result
                    // is not a place - its address is taken at the call, which is what `Deref`
                    // spells for a value.)
                    this.emit(IlOpKind.SetVar, ilOps2(slot, this.receiverOf(operand)))
                    return
                }
                this.emit(IlOpKind.Deref, ilOps2(slot, this.valueOf(operand)))
                return
            }

            AstNodeCategory.ExprCopy -> {
                this.emit(IlOpKind.CopyValue, ilOps2(slot, this.operandOf(xmlChild(e, AstNodeKind.Operand))))
                return
            }

            AstNodeCategory.ExprCall -> {
                this.call(slot, e)
                return
            }

            AstNodeCategory.ExprLambda -> {
                // A lambda whose value is dropped still constructs its class.
                this.lambdaOf(e, slot)
                return
            }

            AstNodeCategory.ExprGenericName -> {
                this.unsupported("type name in a value position")
                return
            }
        }
        this.unsupported("expression")
    }

    // A read of a place: the value behind it, as a slot. The place itself is read through
    // `receiverOf` - so reading `a[i].f` borrows the element rather than copying it into a
    // temporary of the extractor's own.
    fun valueOf(e: AstXmlNode): Int {
        if (xmlKind(e) == AstNodeCategory.ExprName) {
            return this.nameOf(e)
        }
        val typeNode: AstXmlNode = this.valueType(e)
        val slot: Int = this.freshSlot(this.slotTypeText(typeNode), typeNode)
        this.into(slot, e)
        return slot
    }

    // Whether an expression's *value* is already a handle (`&T`/`*T`/`PList`). Such a value
    // denotes storage on its own, so it *is* the place: taking its address would take the
    // address of the pointer.
    fun isHandleExpr(e: AstXmlNode): Bool {
        val typeNode: AstXmlNode = this.exprType(e)
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        return ilIsHandleType(typeNode)
    }

    // Whether a base needs an explicit address instruction: it is an *inline* value, of a
    // type the rules can name. A handle value already denotes the storage, and an
    // unnameable one keeps the value form - the address of something whose type is unknown
    // is not a thing the emitted C++ can spell.
    fun needsPlace(e: AstXmlNode): Bool {
        val typeNode: AstXmlNode = this.exprType(e)
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        return !ilIsHandleType(typeNode)
    }

    // The address of an inline value's storage, as one instruction: the slot holds the
    // pointer, typed from the type rules, so a backend can declare it and resolve a call
    // reached through it. A place is never a copy.
    fun place(
        kind: IlOpKind, baseExpr: AstXmlNode, whole: AstXmlNode, field: Str,
        indexExpr: AstXmlNode
    ): Int {
        val pointee: AstXmlNode = this.exprType(whole)
        var typeNode: AstXmlNode = xmlEmptyNode()
        if (!xmlIsEmpty(pointee)) {
            typeNode = ilPointerNode(pointee)
        }
        var typeText: Str = "*?"
        if (!xmlIsEmpty(typeNode)) {
            typeText = ilTypeText(typeNode)
        }
        val slot: Int = this.freshSlot(typeText, typeNode)
        val base: Int = this.receiverOf(baseExpr)
        var operands: List<Int> = ilOps2(slot, base)
        if (kind == IlOpKind.FieldAddr) {
            operands.append(this.poolIndex(field))
        } else {
            operands.append(this.operandOf(indexExpr))
        }
        this.emit(kind, operands)
        return slot
    }

    // The address of a file-level static, as one instruction: the base a call or a write
    // through the static needs (`&ns1_names`), never a copy of it.
    fun staticAddr(e: AstXmlNode): Int {
        val pointee: AstXmlNode = this.exprType(e)
        var typeNode: AstXmlNode = xmlEmptyNode()
        if (!xmlIsEmpty(pointee)) {
            typeNode = ilPointerNode(pointee)
        }
        var typeText: Str = "*?"
        if (!xmlIsEmpty(typeNode)) {
            typeText = ilTypeText(typeNode)
        }
        val slot: Int = this.freshSlot(typeText, typeNode)
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        this.emit(IlOpKind.GetStaticAddr, ilOps2(slot, this.poolIndex(name)))
        return slot
    }

    // A base through which something is reached: a call's receiver, a read's base, an
    // assignment target's base. A plain name *is* that base; a handle value is one too (it
    // already points at the storage); an *inline* value has to have its address taken -
    // which is what `FieldAddr`/`IndexAddr` are. That is what keeps a read from copying an
    // aggregate (the C++ of a field read is `p->f`, not a copy of a struct) and a write from
    // being lost in a copy.
    fun receiverOf(e: AstXmlNode): Int {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprName -> {
                // A file-level static is not a slot of the frame: as a base it is its own
                // *address*, so a call that mutates it reaches the storage and not a copy of it
                // (`names.append(x)` appends to `names`).
                if (this.isStaticName(xmlAttr(e, AstNodeAttributeKind.Name))) {
                    return this.staticAddr(e)
                }
                return this.nameOf(e)
            }

            AstNodeCategory.ExprMember -> {
                val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
                if (this.isTypeBase(lhs)) {
                    return this.valueOf(e)
                }
                if (!this.needsPlace(e)) {
                    return this.valueOf(e)
                }
                return this.place(
                    IlOpKind.FieldAddr, lhs, e,
                    xmlAttr(e, AstNodeAttributeKind.Name), xmlEmptyNode()
                )
            }

            AstNodeCategory.ExprIndex -> {
                if (!this.needsPlace(e)) {
                    return this.valueOf(e)
                }
                return this.place(
                    IlOpKind.IndexAddr, xmlChild(e, AstNodeKind.Receiver), e, Str(),
                    xmlChild(e, AstNodeKind.Index)
                )
            }

            AstNodeCategory.ExprDeref -> {
                return this.operandOf(xmlChild(e, AstNodeKind.Operand))
            }
        }
        return this.valueOf(e)
    }

    // ---- names and types --------------------------------------------------

    fun nameOf(e: AstXmlNode): Int {
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        // A captured variable is a *field* of the closure, not a slot of this frame:
        // reading it reads through `self`.
        if (this.fn.captures.has(name)) {
            var text: Str = "?"
            if (this.fn.captureTypes.has(name)) {
                text = ilTypeText(this.fn.captureTypes.get(name).value())
            }
            val slot: Int = this.freshSlot(text, this.fn.captureTypes.get(name).value())
            this.emit(IlOpKind.GetField, ilOps3(slot, this.varIndex("self"), this.poolIndex(name)))
            return slot
        }
        if (name == "this") {
            // Not `self`: a receiver's own slot is `self` in the emitted C++ (T47), so a
            // local of that name would shadow it in this method's body.
            val selfSlot: Int = this.varIndex("self")
            if (selfSlot >= 0) {
                return selfSlot
            }
        }
        val slot: Int = this.varIndex(name)
        if (slot >= 0) {
            return slot
        }
        // A file-level static the extractor was told about, or a name only the backend can
        // resolve (`GetStatic` in both cases). The type rules know the statics the program
        // declares, so the slot carries a type and the backend declares it with the frame
        // instead of inlining the read.
        val typeNode: AstXmlNode = this.exprType(e)
        var typeText: Str = "?"
        if (xmlIsEmpty(typeNode)) {
            if (this.fn.statics.has(name)) {
                typeText = this.fn.statics.get(name).value()
            }
        } else {
            typeText = ilTypeText(typeNode)
        }
        val fresh: Int = this.freshSlot(typeText, typeNode)
        this.emit(IlOpKind.GetStatic, ilOps2(fresh, this.poolIndex(name)))
        return fresh
    }

    fun isTypeBase(e: AstXmlNode): Bool {
        val kind: AstNodeCategory = xmlKind(e)
        if (kind == AstNodeCategory.ExprGenericName) {
            return true
        }
        if (kind != AstNodeCategory.ExprName) {
            return false
        }
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        if (name == "this" || this.hasVar(name)) {
            return false
        }
        if (this.fn.captures.has(name)) {
            return false
        }
        return !this.fn.statics.has(name)
    }

    // A name that is file-level static storage rather than a slot of the frame.
    fun isStaticName(name: Str): Bool {
        if (this.hasVar(name)) {
            return false
        }
        return this.fn.statics.has(name)
    }

    fun baseText(e: AstXmlNode): Str {
        if (xmlKind(e) == AstNodeCategory.ExprName) {
            return xmlAttr(e, AstNodeAttributeKind.Name)
        }
        return ilTypeText(this.calleeToType(e))
    }

    // The `GenericName` node carries its type arguments; the IL wants the type as a node,
    // so a fresh generic type node is built from the name and those arguments.
    fun calleeToType(e: AstXmlNode): AstXmlNode {
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        if (xmlKind(e) == AstNodeCategory.ExprGenericName) {
            var node: AstXmlNode = AstXmlNode(
                AstNodeKind.Type, AstNodeCategory.TypeGeneric,
                List<AstNodeAttribute>(), Array<AstXmlNode>()
            )
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            val args: List<AstXmlNode> = xmlChildren(e, AstNodeKind.TypeArg)
            for (*typeArg in args) {
                var arg: AstXmlNode = copy(typeArg)
                arg.name = AstNodeKind.TypeArg
                xmlAddChild(node, arg)
            }
            return node
        }
        return ilNamedTypeNode(name)
    }

    // A literal expression's type, as the frame spells it: a literal operand is a *value*
    // with a type like any other, and the method table's `argTypes` should read the same
    // whether the argument was a slot or a constant.
    fun literalTypeText(e: AstXmlNode): Str {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprIntLit -> {
                return "Int"
            }

            AstNodeCategory.ExprFloatLit -> {
                return "Float64"
            }

            AstNodeCategory.ExprStrLit -> {
                return "Str"
            }

            AstNodeCategory.ExprCharLit -> {
                return "Char"
            }

            AstNodeCategory.ExprBoolLit -> {
                return "Bool"
            }
        }
        return "?"
    }

    fun operandType(slot: Int, expr: AstXmlNode): Int {
        if (slot >= 0) {
            return this.out.vars[slot].typeIndex
        }
        if (xmlIsEmpty(expr)) {
            return this.typeIndexText("?")
        }
        return this.typeIndexText(this.literalTypeText(expr))
    }

    // ---- calls ------------------------------------------------------------

    // The symbol a `native("...")` declaration names, without its quotes.
    fun ilNativeSymbolOf(decl: AstXmlNode): Str {
        val text: Str = xmlAttr(decl, AstNodeAttributeKind.NativeSymbol)
        if (text.size() >= 2 && text.substr(0, 1) == "\"" && text.substr(text.size() - 1, 1) == "\"") {
            return text.substr(1, text.size() - 2)
        }
        return text
    }

    // Whether a call's target is the prelude's list literal (`rtl.kt`'s `listOf<T>`): the
    // one native whose *call* is not a call.
    fun ilIsListOf(target: AstXmlNode): Bool {
        return xmlAttr(target, AstNodeAttributeKind.IsNative) == "true"
                && xmlAttr(target, AstNodeAttributeKind.HasNativeSymbol) == "true"
                && this.ilNativeSymbolOf(target) == "simse_listOf"
    }

    // The `List<T>` a `listOf<T>(...)` names: the type argument it was written with, or -
    // when it was written without one, which the language infers (`specs/functions.md`) -
    // the type of its first element.
    fun ilListOfType(callee: AstXmlNode, args: List<AstXmlNode>, from: Int): AstXmlNode {
        if (xmlKind(callee) == AstNodeCategory.ExprGenericName
            && xmlCount(callee, AstNodeKind.TypeArg) == 1
        ) {
            return semGenericType("List", xmlChildren(callee, AstNodeKind.TypeArg))
        }
        if (from < args.size()) {
            val element: AstXmlNode = this.exprType(args[from])
            if (!xmlIsEmpty(element)) {
                var typeArgs: List<AstXmlNode> = List<AstXmlNode>()
                typeArgs.append(semReRole(element, AstNodeKind.TypeArg))
                return semGenericType("List", typeArgs)
            }
        }
        return xmlEmptyNode()
    }

    // The declaration a call resolves to, when the extractor can name it: the same rule
    // the checker applies - an exact parameter count first, then a last parameter a call
    // may pack into - so a stage that needs more than the name does not have to guess. A
    // member call also resolves by its *receiver*: two types may each have a method of the
    // same name (`Analyzer.exprType` and `IlExtractor.exprType` take different parameters),
    // and a name is not a declaration. The receiver's type is asked for only when there is
    // more than one declaration to choose between. An ambiguous name resolves to nothing -
    // no packing, no conversion - which is exactly the call it was before either existed.
    // Empty when the facts are not available (a body with no facts, a callee that is a
    // local, a static call's type).
    fun callTarget(callee: AstXmlNode, receiver: AstXmlNode, argCount: Int, member: Bool): AstXmlNode {
        if (this.fn.facts == null) {
            return xmlEmptyNode()
        }
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        // The candidates are *indices* into the facts, not the facts themselves: a
        // `SemFnFact` carries its declaration node and its attribute list, so a copy per
        // candidate per call would be work the C++ ring - which holds `const FnFact*` -
        // never does.
        var candidates: List<Int> = List<Int>()
        var i: Int = 0
        while (i < this.fn.facts.functions.size()) {
            val index: Int = i
            i = i + 1
            val fact: *SemFnFact = *this.fn.facts.functions[index]
            if (xmlIsEmpty(fact.decl)
                || xmlAttr(fact.decl, AstNodeAttributeKind.Name) != name
            ) {
                continue
            }
            val factMember: Bool = !xmlIsEmpty(fact.receiver)
            if (factMember != member) {
                continue
            }
            candidates.append(index)
        }
        if (candidates.size() > 1 && member && !xmlIsEmpty(receiver)) {
            val receiverType: AstXmlNode = this.exprType(receiver)
            val recv: AstXmlNode = semPointeeOf(receiverType)
            var matching: List<Int> = List<Int>()
            var m: Int = 0
            while (m < candidates.size()) {
                val index: Int = candidates[m]
                m = m + 1
                val fact: *SemFnFact = *this.fn.facts.functions[index]
                if (!xmlIsEmpty(recv) && !xmlIsEmpty(fact.receiver)
                    && !semUnifyType(fact.receiver, recv, fact.templateParams)
                ) {
                    continue // another type's method of the same name
                }
                matching.append(index)
            }
            candidates = matching
        }
        var exact: AstXmlNode = xmlEmptyNode()
        var pack: AstXmlNode = xmlEmptyNode()
        var exactCount: Int = 0
        var packCount: Int = 0
        var k: Int = 0
        while (k < candidates.size()) {
            val fact: *SemFnFact = *this.fn.facts.functions[candidates[k]]
            k = k + 1
            val params: List<AstXmlNode> = xmlChildren(fact.decl, AstNodeKind.Param)
            val paramCount: Int = params.size()
            if (paramCount == argCount) {
                exact = fact.decl
                exactCount = exactCount + 1
                continue
            }
            if (paramCount > 0
                && semIsPackTarget(xmlChild(params[paramCount - 1], AstNodeKind.Type))
                && argCount >= paramCount - 1
            ) {
                pack = fact.decl
                packCount = packCount + 1
            }
        }
        if (exactCount > 0) {
            if (exactCount == 1) {
                return exact
            }
            return xmlEmptyNode()
        }
        if (packCount == 1) {
            return pack
        }
        return xmlEmptyNode()
    }

    // The type of an *argument*, which the pack and the handle conversions both ask for: a
    // name is a slot of this frame and already carries one, so the common argument costs a
    // dictionary lookup; anything else has to be asked of the type rules, which is the
    // expensive question and the reason it is asked as rarely as the rules allow.
    fun argumentType(e: AstXmlNode): AstXmlNode {
        if (xmlKind(e) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (this.varAt.has(name)) {
                return ilVarType(this.out, this.varAt.get(name).value())
            }
        }
        return this.exprType(e)
    }

    // Where the *pack* starts in an argument list, or -1 when the arguments are passed as
    // they are. One argument per parameter is still the list itself when that argument
    // *is* a list, whatever its handle form - which is what tells `addAll(*xs)` from
    // `addAll(1)`, and `Format(template, items)` from `Format(template, "world")`. An
    // argument whose type the rules cannot name leaves the call exactly as it was:
    // nothing is inferred, nothing is packed.
    fun packStart(target: AstXmlNode, args: List<AstXmlNode>): Int {
        if (xmlIsEmpty(target)) {
            return -1
        }
        val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
        if (params.size() == 0) {
            return -1
        }
        val wanted: AstXmlNode = xmlChild(params[params.size() - 1], AstNodeKind.Type)
        if (!semIsPackTarget(wanted)) {
            return -1
        }
        if (args.size() == params.size() && args.size() > 0) {
            val last: AstXmlNode = this.argumentType(args[args.size() - 1])
            if (xmlIsEmpty(last) || !xmlIsEmpty(semListTypeOf(last))) {
                return -1
            }
        }
        return params.size() - 1
    }

    // The trailing arguments as one list, built by one `Pack` instruction: the list's
    // type is the parameter's and the elements are the arguments as values. A
    // `*List<T>` parameter gets the *address* of the fresh list - one element copy each,
    // no copy of the list at all, and the slot outlives the call, since it is a slot of
    // this body.
    fun packArguments(target: AstXmlNode, args: List<AstXmlNode>, from: Int): Int {
        val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
        var wanted: AstXmlNode = xmlEmptyNode()
        if (params.size() > 0) {
            wanted = xmlChild(params[params.size() - 1], AstNodeKind.Type)
        }
        val list: AstXmlNode = semListTypeOf(wanted)
        val slot: Int = this.freshSlot(this.slotTypeText(list), list)
        var operands: List<Int> = List<Int>()
        operands.append(slot)
        var i: Int = from
        while (i < args.size()) {
            operands.append(this.operandOf(args[i]))
            i = i + 1
        }
        this.emit(IlOpKind.Pack, operands)
        if (xmlIsEmpty(wanted) || xmlKind(wanted) == AstNodeCategory.TypeGeneric) {
            return slot // by value
        }
        val handle: AstXmlNode = copy(wanted)
        val bound: Int = this.freshSlot(ilTypeText(handle), handle)
        this.emit(IlOpKind.Deref, ilOps2(bound, slot)) // `*List<T>`: the borrow of a fresh slot
        return bound
    }

    // A value read out of a handle, as a slot of the pointee's type: what a by-value
    // parameter needs when the argument is a borrow or a counted reference. (`CopyValue` is
    // the language's `copy`, which reads through a handle - the emitter spells it `*(x)`.)
    fun readThrough(pointeeType: *AstXmlNode, from: Int): Int {
        val typeNode: AstXmlNode = copy(pointeeType)
        val slot: Int = this.freshSlot(ilTypeText(typeNode), typeNode)
        this.emit(IlOpKind.CopyValue, ilOps2(slot, from))
        return slot
    }

    // The argument a call passes for one parameter, converted the way the two types require
    // (`specs/functions.md`):
    //
    // | parameter | argument |                            |
    // | --------- | -------- | -------------------------- |
    // | `*T`      | `T`      | the place's address (`&x`) |
    // | `*T`      | `&T`     | the handle's pointee (`x.get()`) |
    // | `T`       | `*T`/`&T`| a copy of the pointee      |
    // | `&T`      | `T`      | a *boxed copy* - `&x` means exactly that |
    // | `&T`      | `*T`     | **nothing**: the checker reports it (a pointer cannot
    // |           |          | become a counted reference in place - `Sema.cpp`) |
    //
    // Nothing is converted when the two are not the same type to begin with: a `List<Int>`
    // argument is not a `*List<Str>`, and the call is the type error it always was. This is
    // why a parameter can change from `List<T>` to `*List<T>` (a borrow, no copy) without
    // every caller having to spell the `*`, and why the reverse change still compiles. A
    // `*T` *binding* (`val p: *T = x`) is a different question - a pointer that outlives
    // the expression it points into has to be asked for, so the writer writes it.
    fun convertArgument(target: AstXmlNode, index: Int, arg: AstXmlNode): Int {
        val slot: Int = this.operandOf(arg)
        if (xmlIsEmpty(target)) {
            return slot
        }
        val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
        if (index >= params.size()) {
            return slot
        }
        val param: AstXmlNode = xmlChild(params[index], AstNodeKind.Type)
        if (xmlIsEmpty(param)) {
            return slot
        }
        val wantPointer: Bool = xmlKind(param) == AstNodeCategory.TypePointer
        val wantShared: Bool = !wantPointer && ilIsHandleType(param)
        // The argument's type, cheaply: a place is a slot of this frame and already
        // carries one, so the common argument costs a lookup. Anything else is read into a
        // slot of its own - which is the value the call passes - and only an untyped slot
        // (a `for` machine, a bare `null`) has to be asked of the type rules, the
        // expensive question.
        var actual: AstXmlNode = ilVarType(this.out, slot)
        if (xmlIsEmpty(actual)) {
            actual = this.exprType(arg)
        }
        if (xmlIsEmpty(actual)) {
            return slot
        }
        val wanted: AstXmlNode = semPointeeOf(param)
        val given: AstXmlNode = semPointeeOf(actual)
        if (xmlIsEmpty(wanted) || xmlIsEmpty(given)) {
            return slot
        }
        if (ilTypeText(wanted) != ilTypeText(given)) {
            return slot // not the same type
        }
        val havePointer: Bool = xmlKind(actual) == AstNodeCategory.TypePointer
        val haveShared: Bool = !havePointer && ilIsHandleType(actual)
        val haveValue: Bool = !havePointer && !haveShared
        if ((wantPointer && havePointer) || (wantShared && haveShared)) {
            return slot
        }
        if (wantPointer) {
            // The address, spelled exactly as an explicit `*` spells it (`into`'s `Deref`
            // arm is the definition): a member/index chain *is* its own address (`&a.f`,
            // never the address of a copy of `a.f` - a callee that writes through the
            // pointer has to write into the caller's object), a static name is its
            // `GetStaticAddr`, and a name or a temporary is `&x` / the address taken at the
            // call.
            val handle: AstXmlNode = copy(param)
            val bound: Int = this.freshSlot(ilTypeText(handle), handle)
            this.into(bound, ilDerefNode(arg))
            return bound
        }
        if (wantShared) {
            if (!haveValue) {
                return slot // `&T` from a pointer: the checker reports it
            }
            val box: AstXmlNode = copy(param)
            val held: Int = this.freshSlot(ilTypeText(box), box)
            this.emit(IlOpKind.Box, ilOps2(held, slot)) // `&x` boxes a copy
            return held
        }
        if (haveValue) {
            return slot
        }
        return this.readThrough(wanted, slot)
    }

    // `dst < 0` means the result is dropped (`CallVoid`).
    fun call(dst: Int, e: AstXmlNode): Unit {
        val callee: AstXmlNode = xmlChild(e, AstNodeKind.Callee)
        val hasDst: Bool = dst >= 0
        var returnType: Int = -1
        if (hasDst) {
            returnType = this.out.vars[dst].typeIndex
        }
        val member: Bool = xmlKind(callee) == AstNodeCategory.ExprMember
        val lhs: AstXmlNode = xmlChild(callee, AstNodeKind.Receiver)
        var staticCall: Bool = false
        if (member) {
            staticCall = this.isTypeBase(lhs)
        }
        val receiverCall: Bool = member && !staticCall

        // The receiver is evaluated first - it is the leftmost thing the C++ call reads -
        // and a `Method` call carries it as its first argument, so the count of `Var`
        // operands after the callee is exactly what `IlMethod::argCount` says.
        var recvSlot: Int = -1
        if (receiverCall) {
            recvSlot = this.receiverOf(lhs)
        }

        val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        val argNodes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)

        // The trailing arguments may pack into a last parameter that is a list
        // (`fun addAll(values: *List<Int>)` called as `addAll(1, 2, 3)`, or
        // `"Hello {0}".Format("world")`). A static call (`Res<Str>.ok(x)`) is not looked
        // up: its callee is a type, and the same name may be a plain function of another
        // shape.
        var target: AstXmlNode = xmlEmptyNode()
        if (!staticCall) {
            var receiverNode: AstXmlNode = xmlEmptyNode()
            if (receiverCall) {
                receiverNode = lhs
            }
            target = this.callTarget(callee, receiverNode, argNodes.size(), receiverCall)
        }
        val packFrom: Int = this.packStart(target, argNodes)

        // `listOf<T>(a, b, c)` is the language's list literal: the same `Pack` instruction
        // a packed call builds, written out. It is not a call at all - the native it names
        // exists so the checker and the type rules have a signature to read - so a backend
        // that sees one of these writes the construction and never the function.
        if (hasDst && packFrom >= 0 && !xmlIsEmpty(target) && this.ilIsListOf(target)) {
            val listType: AstXmlNode = this.ilListOfType(callee, argNodes, packFrom)
            if (!xmlIsEmpty(listType)) {
                this.setSlotType(dst, listType)
                var packing: List<Int> = List<Int>()
                packing.append(dst)
                var p: Int = packFrom
                while (p < argNodes.size()) {
                    packing.append(this.operandOf(argNodes[p]))
                    p = p + 1
                }
                this.emit(IlOpKind.Pack, packing)
                return
            }
        }

        var args: List<Int> = List<Int>()
        var argTypes: List<Int> = List<Int>()
        var plain: Int = argNodes.size()
        if (packFrom >= 0) {
            plain = packFrom
        }
        var i: Int = 0
        while (i < plain) {
            val slot: Int = this.convertArgument(target, i, argNodes[i])
            args.append(slot)
            argTypes.append(this.operandType(slot, argNodes[i]))
            i = i + 1
        }
        if (packFrom >= 0 && !xmlIsEmpty(target)) {
            val slot: Int = this.packArguments(target, argNodes, packFrom)
            args.append(slot)
            argTypes.append(this.out.vars[slot].typeIndex)
        }
        var fullTypes: List<Int> = List<Int>()
        if (recvSlot >= 0) {
            fullTypes.append(this.out.vars[recvSlot].typeIndex)
        }
        i = 0
        while (i < argTypes.size()) {
            fullTypes.append(argTypes[i])
            i = i + 1
        }

        var operands: List<Int> = List<Int>()
        if (hasDst) {
            operands.append(dst)
        }

        if (receiverCall) {
            operands.append(this.methodIndex(calleeName, IlMethodKind.Method, -1, returnType, fullTypes))
            operands.append(recvSlot)
            i = 0
            while (i < args.size()) {
                operands.append(args[i])
                i = i + 1
            }
            this.emitCall(hasDst, operands)
            return
        }
        if (staticCall) {
            // A static call: `Res<Str>.ok(x)`, `Color.fromInt(v)`. The type is part of the
            // method's identity, and the node lets a backend spell its type arguments as
            // the source wrote them.
            operands.append(
                this.methodIndex(
                    calleeName, IlMethodKind.Function,
                    this.typeIndex(this.baseText(lhs), this.calleeToType(lhs)),
                    returnType, argTypes
                )
            )
            i = 0
            while (i < args.size()) {
                operands.append(args[i])
                i = i + 1
            }
            this.emitCall(hasDst, operands)
            return
        }
        if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
            // A construction: `Point(1, 2)`, `List<Str>()`. The type operand is the *node*
            // the callee was, so a backend can spell the type arguments the way the source
            // wrote them.
            if (!hasDst) {
                this.unsupported("constructor call with no destination")
                return
            }
            // A `List<T>()` with no arguments is the empty list, and `List<T>(n)`
            // / `List<T>(n, v)` are the RTL's *count* constructions - a literal
            // is `listOf<T>(a, b, c)`, which is the `Pack` above, so the two can
            // never be confused.
            operands.append(this.typeIndex(this.baseText(callee), this.calleeToType(callee)))
            i = 0
            while (i < args.size()) {
                operands.append(args[i])
                i = i + 1
            }
            this.emit(IlOpKind.CallCtor, operands)
            return
        }
        // A plain function (or a native - the backend resolves the symbol).
        operands.append(this.methodIndex(calleeName, IlMethodKind.Function, -1, returnType, argTypes))
        i = 0
        while (i < args.size()) {
            operands.append(args[i])
            i = i + 1
        }
        this.emitCall(hasDst, operands)
    }

    fun emitCall(hasDst: Bool, operands: List<Int>): Unit {
        if (hasDst) {
            this.emit(IlOpKind.Call, operands)
        } else {
            this.emit(IlOpKind.CallVoid, operands)
        }
    }

    // ---- lambdas ----------------------------------------------------------

    // The symbol prefix a synthesized class takes: the body's own emitted name, so two
    // bodies never spell the same class and the dump says where it came from.
    fun ownerSymbol(): Str {
        if (this.fn.symbol.isEmpty()) {
            return "_closure_owner"
        }
        return this.fn.symbol
    }

    // A lambda, projected the way the language models it: a class with one field per
    // *captured* variable and one method. `dst < 0` means the value goes nowhere (the
    // class is still constructed).
    fun lambdaOf(e: *AstXmlNode, dst: Int): Int {
        val counter: Int = this.closureCounter[0]
        this.closureCounter[0] = counter + 1
        val symbol: Str = this.ownerSymbol() + "_closure" + ilIntText(counter)

        var read: List<Str> = List<Str>()
        var readSeen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var declared: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        val paramNames: List<Str> = ilSplitParams(xmlAttr(e, AstNodeAttributeKind.Params))
        val paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
        var p: Int = 0
        while (p < paramNames.size()) {
            declared.insert(paramNames[p], true)
            p = p + 1
        }
        val bodyContainer: AstXmlNode = xmlChild(e, AstNodeKind.Body)
        var bodyList: List<AstXmlNode> = List<AstXmlNode>()
        var bi: Int = 0
        while (bi < bodyContainer.Children.count()) {
            bodyList.append(bodyContainer.Children[bi])
            bi = bi + 1
        }
        ilCollectStmtNames(bodyList, declared, read, readSeen)

        // The closure: what the body reads that the *enclosing* frame holds. A name that is
        // neither (a static, a type, a function) is not captured - the enclosing `nameOf`
        // resolves it the same way it always did.
        var captured: List<Str> = List<Str>()
        var ri: Int = 0
        while (ri < read.size()) {
            val name: Str = read[ri]
            ri = ri + 1
            if (declared.has(name)) {
                continue
            }
            if (name == "this") {
                continue
            }
            if (!this.hasVar(name)) {
                continue
            }
            captured.append(name)
        }

        // Filled by the lambda's own type pass, below; `begin` copies it into the body.
        var lambdaTypes: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        var info: IlFunction = IlFunction(
            xmlEmptyNode(), xmlEmptyNode(), symbol, this.fn.statics, xmlEmptyNode(),
            paramNames, paramTypes, symbol,
            Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
            this.fn.facts, this.fn.typeParams, *lambdaTypes
        )
        var ci: Int = 0
        while (ci < captured.size()) {
            val name: Str = captured[ci]
            info.captures.insert(name, true)
            // The field's type is the enclosing slot's: the class's field has it, so a read
            // of it in the body is typed without inference.
            val slotType: AstXmlNode = ilVarTypeNode(this.out, this.varIndex(name))
            if (!xmlIsEmpty(slotType)) {
                info.captureTypes.insert(name, slotType)
            }
            ci = ci + 1
        }

        // The lambda's body has a frame of its own, so it is lowered here, the way the
        // emitter lowers it - including the single-expression body, which is the `return`
        // it stands for - and it is *typed* here too, with a frame of its own: parameters
        // and captures. Without this pass the body's own declarations stay untyped, and a
        // slot the frame cannot name sends every spelling decision that needs a type the
        // wrong way (`v.toString()` picks the `StrView` overload; a `..T` receiver hides
        // the `smToYield` identity).
        var lowered: List<AstXmlNode> = ilLambdaLower(bodyList)
        val lambdaSemantics: SemBody = SemBody(
            xmlEmptyNode(), this.fn.typeParams, ilNamedTypeNode(symbol), xmlEmptyNode(),
            paramNames, paramTypes, info.captureTypes
        )
        lowered = semInferTypes(lowered, this.fn.facts, lambdaSemantics, lambdaTypes)
        lowered = linFinishForEmission(lowered, paramNames)

        // The extractor's type context: storage of this frame's, borrowed by the inner
        // extractor the way `unit` and `closureCounter` are.
        var innerContext: SemBody = ilEmptySemantics()
        var inner: IlExtractor = IlExtractor(
            info, this.unit, this.closureCounter,
            ilEmptyBody(), Dictionary<Str, Int>(),
            Dictionary<Str, Int>(), Dictionary<Str, Int>(),
            Dictionary<Str, Int>(), Dictionary<Str, Int>(), 1, 0,
            List<Int>(), List<Int>(),
            *innerContext, false, Dictionary<Str, AstXmlNode>(), true
        )
        inner.begin(this.out.file)
        val innerBody: IlBody = inner.run(lowered)

        var closure: IlClosure = IlClosure(
            symbol, "", captured, List<AstXmlNode>(),
            List<IlVar>(), this.unit.lambdas.size()
        )
        ci = 0
        while (ci < captured.size()) {
            if (info.captureTypes.has(captured[ci])) {
                closure.captureTypes.append(info.captureTypes.get(captured[ci]).value())
            } else {
                closure.captureTypes.append(xmlEmptyNode())
            }
            ci = ci + 1
        }
        var vi: Int = 0
        while (vi < innerBody.vars.size()) {
            val slot: IlVar = innerBody.vars[vi]
            if (slot.kind == IlVarKind.Argument && slot.name != "self") {
                closure.params.append(slot)
            }
            vi = vi + 1
        }
        closure.signature = "(" + ilJoinList(captured, ", ") + ") " + innerBody.signature
        this.unit.lambdas.append(innerBody)
        this.unit.closures.append(closure)

        // The value: a construction of the class, with the captured values.
        val classType: AstXmlNode = ilNamedTypeNode(symbol)
        var target: Int = dst
        if (dst < 0) {
            target = this.freshSlot(symbol, classType)
        }
        var operands: List<Int> = listOf<Int>(target, this.typeIndex(symbol, classType))
        ci = 0
        while (ci < captured.size()) {
            // The capture is a slot of *this* frame (that is what put it in the closure), so
            // the construction passes its value by index.
            operands.append(this.varIndex(captured[ci]))
            ci = ci + 1
        }
        this.emit(IlOpKind.CallCtor, operands)
        return target
    }
}

// A lambda's parameter names, from the `Params` attribute the parser writes (comma
// separated). An empty attribute is no parameters.
fun ilSplitParams(text: Str): List<Str> {
    var out: List<Str> = List<Str>()
    if (text.isEmpty()) {
        return out
    }
    var current: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] == ',') {
            out.append(current)
            current = Str()
            i = i + 1
            continue
        }
        current = current + text[i]
        i = i + 1
    }
    out.append(current)
    return out
}

// A lambda's body, lowered the way the emitter lowers it (impl_specs/linear-lowering.md),
// with the single-expression body rewritten to the `return` it stands for. `params` is what
// the body's own C++ scope already declares (its parameters), so a hoisted declaration that
// would collide with one is renamed.
fun ilLambdaLower(body: *List<AstXmlNode>): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    if (body.size() == 1 && xmlKind(body[0]) == AstNodeCategory.StmtExprStmt) {
        val expr: AstXmlNode = xmlChild(body[0], AstNodeKind.Expr)
        if (!xmlIsEmpty(expr)) {
            var ret: AstXmlNode = linStmt(
                AstNodeCategory.StmtReturn, xmlLine(body[0]),
                xmlColumn(body[0])
            )
            var value: AstXmlNode = copy(expr)
            value.name = AstNodeKind.Value
            xmlAddChild(ret, value)
            out.append(ret)
            return linLowerForEmission(out)
        }
    }
    var i: Int = 0
    while (i < body.size()) {
        out.append(body[i])
        i = i + 1
    }
    return linLowerForEmission(out)
}

// The type node of a frame slot, or an empty node when the extractor could not name it.
fun ilVarTypeNode(body: *IlBody, slot: Int): AstXmlNode {
    if (slot < 0 || slot >= body.vars.size()) {
        return xmlEmptyNode()
    }
    val typeIndex: Int = body.vars[slot].typeIndex
    if (typeIndex < 0 || typeIndex >= body.typeNodes.size()) {
        return xmlEmptyNode()
    }
    return body.typeNodes[typeIndex]
}

fun ilEmptyBody(): IlBody {
    return IlBody(
        "", 0, "", "", List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>(),
        List<IlVar>(), List<Str>(),
        List<IlMethod>(), List<Str>(), List<IlOp>(), List<Int>()
    )
}

// The semantic context an extractor's cache starts as: the C++ ring's default-constructed
// `sema::Body`, empty - `bodyContext` fills it in from the function on first use, so only
// the flag that says "not built yet" (`typeContextReady`) is ever read before then.
fun ilEmptySemantics(): SemBody {
    return SemBody(
        xmlEmptyNode(), List<Str>(), xmlEmptyNode(), xmlEmptyNode(),
        List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
    )
}

// The names the schema gives a jump category, as the opcode's own name (`IfTrue`,
// `IfFalse`): the instruction set spells them that way.
// The opcode for a `StmtIfTrue`/`StmtIfFalse` category (the lowering's two jump forms,
// which the extractor sees as statements).
fun ilCategoryName(kind: AstNodeCategory): IlOpKind {
    if (kind == AstNodeCategory.StmtIfTrue) {
        return IlOpKind.IfTrue
    }
    return IlOpKind.IfFalse
}

// Builds the IL of one body: the body itself plus the lambdas it constructs. The closure
// symbols are numbered per *unit*, so a lambda inside a lambda still gets a name of its
// own.
fun ilExtractUnit(fn: IlFunction, body: List<AstXmlNode>, file: Str): IlUnit {
    var unit: IlUnit = IlUnit(ilEmptyBody(), List<IlBody>(), List<IlClosure>())
    var counter: Int = 1
    // The type context the extractor fills in on its first question: storage of this
    // frame's, borrowed the way `unit` and `counter` are.
    var context: SemBody = ilEmptySemantics()
    var extractor: IlExtractor = IlExtractor(
        fn, *unit, *counter, ilEmptyBody(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), 1, 0, List<Int>(), List<Int>(),
        *context, false, Dictionary<Str, AstXmlNode>(), true
    )
    extractor.begin(file)
    val extracted: IlBody = extractor.run(body)
    unit.body = extracted
    return unit
}

// ---- the dump flag ---------------------------------------------------------

// `--showLinearRepresentation`: the emitter forms the IL of every body it emits and
// writes the dump to stderr, so the linear form can be read next to the code it produced
// (impl_specs/linear-il.md). Off unless the driver asks for it, and it never touches the
// emitted C++.
var ilShowFlag: Bool = false

fun ilShow(): Bool {
    return ilShowFlag
}

fun ilSetShow(value: Bool): Unit {
    ilShowFlag = value
}

// The type behind a `types` entry, when the extractor had the node.
fun ilTypeNode(body: *IlBody, index: Int): AstXmlNode {
    if (index < 0 || index >= body.typeNodes.size()) {
        return xmlEmptyNode()
    }
    return body.typeNodes[index]
}

// The declared type of a slot: the node a backend spells C++ from.
fun ilVarType(body: *IlBody, slot: Int): AstXmlNode {
    if (slot < 0 || slot >= body.vars.size()) {
        return xmlEmptyNode()
    }
    return ilTypeNode(body, body.vars[slot].typeIndex)
}

// The whole dump of one body, ready to write: a blank line before it, like the framed
// form the stderr dump uses.
fun ilDumpBody(body: *IlBody): Str {
    return "\n" + printIlBody(body)
}

// One table of the dump: the label, then its entries separated by three spaces. An empty
// table is dropped, like the C++ ring does.
fun ilAppendTable(out: *Str, label: Str, entries: *List<Str>): Unit {
    if (entries.size() == 0) {
        return
    }
    out.appendStr(label)
    var i: Int = 0
    while (i < entries.size()) {
        if (i > 0) {
            out.appendStr("   ")
        }
        out.appendStr(entries[i])
        i = i + 1
    }
    out.appendStr("\n")
}
