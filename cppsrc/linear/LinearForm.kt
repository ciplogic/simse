// LinearForm.kt
//
// The linear IL, per body: control flow is labels and jumps, so a pass or a backend reads
// one instruction at a time without knowing about scopes (impl_specs/linear-il.md). The
// tables hold what instructions refer to by index: `types`, `vars` (the frame), `pool`,
// `methods`, `labels`, `ops`, `lines` (each op's line). A type is an `AstXmlNode` subtree.

package linear

import common
import sema

// `Expression` is the lowering's own storage (`_sm_expr<n>`, `simse_sw_<n>`); `Temp` is the
// extractor's, for a position the statements did not hold in a slot. A `Temp` the type rules
// can name hoists with the frame; one they cannot stays untyped and its single use folds it.
enum class IlVarKind {
    Argument,
    Local,
    Expression,
    Temp
}

// The shape of a call: the backend resolves the symbol from the name and the operand types.
enum class IlMethodKind {
    Function,
    Method,
    Constructor
}

// `Var` is a slot - a destination, or a place read; `Value` is a slot or a constant. The
// opcodes say nothing about types; the frame does.
enum class IlOperandKind {
    Var,
    Value,
    Text,
    Type,
    Method,
    Label,
    None
}

// The instruction set: one opcode per IL instruction. The order matches
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
    // The fused concatenation (MergeConcat.kt): the parts of a `+` chain over `Str`, or of
    // an `fmtStr`, whose lengths are summed once and whose bytes are appended once
    // (`ilConcatStatements`, cppsrc/codegen/IlCodeGen.kt). The lowerer never builds one;
    // the fusion does.
    Concat,
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
    var argTypes: List<Int>,

// Whether a `Method`'s receiver is one the emitter passes as `T* self` - a value or a raw-pointer
// receiver. A counted reference (`&T`, `PList`) is the receiver the emitter passes as the handle
// itself, so `linear/PromoteRefs.kt` must not turn such a receiver into a raw pointer.
    var recvIsValue: Bool
)

// One instruction. An operand's meaning is its opcode's operand kind: an index into a table,
// or, for a negative operand in a `Value` position, a literal naming `pool[-1-n]`.
data class IlOp(var kind: IlOpKind, var operands: List<Int>)

data class IlBody(
    var file: Str, var line: Int, var symbol: Str, var signature: Str, var types: List<Str>,

// The type node behind each `types` entry, when the extractor had one.
    var typeNodes: List<AstXmlNode>,

// What the *type pass* proved for every name in this body, more than the frame's slots
// carry: a machine-typed (`..T`) slot stays typed from this without a statement tree
// (impl_specs/for.md).
    var inferredTypes: Dictionary<Str, AstXmlNode>,
    var vars: List<IlVar>,
    var pool: List<Str>,
    var methods: List<IlMethod>,
    var labels: List<Str>,
    var ops: List<IlOp>,
    var lines: List<Int>
)

// What the extractor needs to know about the body's function. A lambda (or machine-method)
// body has no declaration; its captures are fields of its class, not frame slots. The last
// three fields feed a lambda's own type pass (its frame is not the enclosing function's).
data class IlFunction(
    var decl: AstXmlNode,

    var receiver: AstXmlNode,
    var symbol: Str,
    var statics: Dictionary<Str, Str>,
    // The class this body is a method of, when the *lowering* built it (a state machine).
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

// A lambda: a class with one field per captured variable and one method.
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

// One entry per opcode: its operands' kinds in order (`"Var,Text,Var,Var"`). A trailing
// `...` means the kind before it repeats; rows are in `IlOpKind` order.
data class IlSignature(
    var kind: IlOpKind,

    var operands: Str
)

var ilSignatureTable: List<IlSignature> = makeIlSignatures()

// One body into instructions. The frame comes from `IlFunction`; the tables are filled in
// first-touch order, and every instruction carries the line of its statement.
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
    // Synthesized slots that carry a type, declared at the top of the instruction list.
    var hoisted: List<Int>,
    var hoistedLines: List<Int>,
    // The caches the type questions read (`typeContext`/`frameNames`, each with a
    // stale flag). Borrowed storage of the caller's: a `SemBody` field would be incomplete here.
    var typeContext: *SemBody,
    var typeContextReady: Bool,
    var frameNames: Dictionary<Str, AstXmlNode>,
    var frameNamesStale: Bool
) {

}


// A lambda's parameter names, from the comma-separated `Params` attribute.
fun ilSplitParams(text: Str): List<Str> {
    var out: List<Str> = List<Str>()
    if (text.isEmpty()) {
        return out
    }
    var current: Str
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
// with a single-expression body rewritten to the `return` it stands for.
fun ilLambdaLower(body: *List<AstXmlNode>): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    if (body.size() == 1 && xmlKind(body[0]) == AstNodeCategory.StmtExprStmt) {
        val expr: *AstXmlNode = xmlChildPtr(body[0], AstNodeKind.Expr)
        if (!xmlIsEmpty(expr)) {
            var ret: AstXmlNode = linStmt(
                AstNodeCategory.StmtReturn, xmlLine(body[0]),
                xmlColumn(body[0])
            )
            var value: AstXmlNode = expr
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

// The type node of a frame slot, or empty when the extractor could not name it.
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

// The empty semantic context an extractor's cache starts as; `bodyContext` fills it in on
// first use, so only the "not built yet" flag is read before then.
fun ilEmptySemantics(): SemBody {
    return SemBody(
        xmlEmptyNode(), List<Str>(), xmlEmptyNode(), xmlEmptyNode(),
        List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
    )
}

// The opcode for a `StmtIfTrue`/`StmtIfFalse` category (the lowering's two jump forms).
fun ilCategoryName(kind: AstNodeCategory): IlOpKind {
    if (kind == AstNodeCategory.StmtIfTrue) {
        return IlOpKind.IfTrue
    }
    return IlOpKind.IfFalse
}

// The IL of one body: the body plus the lambdas it constructs. Closure symbols are numbered
// per *unit*, so a lambda inside a lambda still gets its own name.
fun ilExtractUnit(fn: *IlFunction, body: *List<AstXmlNode>, file: *Str): IlUnit {
    var unit: IlUnit = IlUnit(ilEmptyBody(), List<IlBody>(), List<IlClosure>())
    var counter: Int = 1
    // Type context the extractor fills in on first use, borrowed as `unit` and `counter` are.
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

// `--showLinearRepresentation`: the emitter forms the IL of every body and writes the dump
// to stderr (impl_specs/linear-il.md). Off unless the driver asks.
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

// The whole dump of one body, ready to write: a blank line before it.
fun ilDumpBody(body: *IlBody): Str {
    return "\n" + printIlBody(body)
}

// One table of the dump: the label, then its entries separated by three spaces.
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

