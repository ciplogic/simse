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
// declared, so a backend declares it where the hoisting put it. `Temp` is the
// extractor's: a slot that exists only because a value position held more than a
// name, which a backend *folds* into the instruction that reads it.
enum IlVarKind {
    Argument,
    Local,
    Expression,
    Temp
}

// What the *shape* of a call is: the backend resolves the symbol from the name and the
// operand types it finds in the frame, so the IL stays free of anything but the
// program's own spelling.
enum IlMethodKind {
    Function,
    Method,
    Constructor
}

// `Var` is a **slot** - a destination, or a place read (`GetField`'s base); `Value` is
// a slot *or* a constant, which is what most operand positions are. The opcodes say
// nothing about types: the frame does.
enum IlOperandKind {
    Var,
    Value,
    Text,
    Type,
    Method,
    Label,
    None
}

data class IlVar(var name: Str;

var typeIndex: Int;
var kind: IlVarKind)

data class IlMethod(
    var name: Str;

var kind: IlMethodKind;
var argCount: Int;
var staticBase: Int;
var returnType: Int;
var argTypes: List<Int>
)

// One instruction. An operand is an `Int` whose meaning is the opcode's operand kind
// at that position: an index into a table, or a literal. A **negative** operand in a
// `Value` position is a literal: it names `pool[-1-n]`, whose text a backend prints
// verbatim (`0`, `"abc"`, `true`) - which is what keeps a constant inside the
// instruction that uses it instead of a slot that would have to be declared.
data class IlOp(var name: Str;

var operands: List<Int>)

data class IlBody(
    var file: Str;

var line: Int;
var symbol: Str;
var signature: Str;
var types: List<Str>;

// The type node behind each `types` entry, when the extractor had one: the dump
// only needs the text, but a backend spells C++ from these.
var typeNodes: List<AstXmlNode>;

// What the *type pass* proved for every name in this body (`sema::inferTypes`),
// which is more than the frame's slots carry: a name holding a state machine is
// typed `..T`, and a declaration is never written with that (the emitted C++ uses
// `auto`, and `linear/Yield.cpp` relies on the declaration staying untyped). The
// backend still needs it, because `for` wraps what it iterates in `smToYield()` and,
// on a machine, that wrap is the identity - a decision only the receiver's type can
// make (impl_specs/for.md). Seeding a frame from this is how a machine-typed slot
// stays typed without a statement tree.
var inferredTypes: Dictionary<Str, AstXmlNode>;
var vars: List<IlVar>;
var pool: List<Str>;
var methods: List<IlMethod>;
var labels: List<Str>;
var ops: List<IlOp>;
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
    var decl: AstXmlNode;

var receiver: AstXmlNode;
var symbol: Str;
var statics: Dictionary<Str, Str>;
var paramNames: List<Str>;
var paramTypes: List<AstXmlNode>;
var closureSymbol: Str;
var captures: Dictionary<Str, Bool>;
var captureTypes: Dictionary<Str, AstXmlNode>;
var facts: *SemFacts;
var typeParams: List<Str>;
var inferredTypes: *Dictionary<Str, AstXmlNode>
)

// A lambda, as the language's model says it is: a class with one field per captured
// variable and one method, so a callable value is an *instance* of it.
data class IlClosure(
    var symbol: Str;

var signature: Str;
var captures: List<Str>;
var captureTypes: List<AstXmlNode>;
var params: List<IlVar>;
var bodyIndex: Int
)

// One function-like body and the lambdas it constructs.
data class IlUnit(
    var body: IlBody;

var lambdas: List<IlBody>;
var closures: List<IlClosure>
)

// One entry per opcode, with its operands' kinds in order (`"Var,Text,Var,Var"`; a
// trailing `...` means "the kind before it repeats here"). The printer and the backend
// read this table, so it is the one place the IL's shape is written down.
data class IlSignature(var name: Str;

var operands: Str)

// ---- the instruction set ---------------------------------------------------

var ilSignatureTable: List<IlSignature> = makeIlSignatures()

fun makeIlSignatures(): List<IlSignature> {
    var table: List<IlSignature> = List<IlSignature>()
    table.append(IlSignature("Label", "Label"))
    table.append(IlSignature("Goto", "Label"))
    table.append(IlSignature("IfTrue", "Value,Label"))
    table.append(IlSignature("IfFalse", "Value,Label"))
    table.append(IlSignature("Declare", "Var"))
    // A declaration the instruction *after* it initialises: the two are one line of
    // C++. The distinction is real because the hoisting turns a declaration's
    // initializer into a separate assignment, which is a bare `Declare`.
    table.append(IlSignature("DeclareInit", "Var"))
    // One assignment op for every kind of value: the destination slot's type is what a
    // backend spells from, so the opcode says nothing about it - `SetVar x, y` copies a
    // slot, `SetVar x, 5` carries the constant in the operand.
    table.append(IlSignature("SetVar", "Var,Value"))
    // ... except `null`, whose spelling comes from the destination's type
    // (`Opt<T>()` vs `nullptr`) rather than from a literal.
    table.append(IlSignature("SetVar_Null", "Var"))
    table.append(IlSignature("BinaryOp", "Var,Text,Value,Value"))
    table.append(IlSignature("UnaryOp", "Var,Text,Value"))
    table.append(IlSignature("Cast", "Var,Value"))       // Enum.toInt(): the one cast
    table.append(IlSignature("Box", "Var,Var"))          // &x -> a counted handle
    table.append(IlSignature("Deref", "Var,Var"))        // *x -> a borrow, a `.get()`,
    //                                                      or a load of a `*T`
    table.append(IlSignature("CopyValue", "Var,Var"))    // copy(x)
    table.append(IlSignature("Store", "Var,Value"))      // *p = v
    table.append(IlSignature("GetField", "Var,Var,Text"))
    table.append(IlSignature("SetField", "Var,Text,Value"))
    table.append(IlSignature("GetIndex", "Var,Var,Value"))
    table.append(IlSignature("SetIndex", "Var,Value,Value"))
    table.append(IlSignature("FieldAddr", "Var,Var,Text"))
    table.append(IlSignature("IndexAddr", "Var,Var,Value"))
    table.append(IlSignature("GetStatic", "Var,Text"))
    table.append(IlSignature("SetStatic", "Text,Value"))
    table.append(IlSignature("Call", "Var,Method,Value..."))
    table.append(IlSignature("CallVoid", "Method,Value..."))
    table.append(IlSignature("CallIndirect", "Var,Var,Value..."))
    table.append(IlSignature("CallIndirectVoid", "Var,Value..."))
    table.append(IlSignature("CallCtor", "Var,Type,Value..."))
    table.append(IlSignature("Return", "Value"))
    table.append(IlSignature("ReturnVoid", ""))
    table.append(IlSignature("Lambda", "Var"))
    table.append(IlSignature("Unsupported", "Var,Text"))
    return table
}

// The signature of an opcode, or an empty node-like `Opt` when the name is not one.
fun ilSignature(name: Str): Opt<IlSignature> {
    var i: Int = 0
    while (i < ilSignatureTable.size()) {
        if (ilSignatureTable[i].name == name) {
            return Opt<IlSignature>.some(ilSignatureTable[i])
        }
        i = i + 1
    }
    return Opt<IlSignature>.none()
}

fun ilVarKindText(kind: IlVarKind): Str {
    if (kind == IlVarKind.Argument) {
        return "Argument"
    }
    if (kind == IlVarKind.Local) {
        return "Local"
    }
    if (kind == IlVarKind.Expression) {
        return "Expression"
    }
    if (kind == IlVarKind.Temp) {
        return "Temp"
    }
    return "?"
}

fun ilMethodKindText(kind: IlMethodKind): Str {
    if (kind == IlMethodKind.Function) {
        return "Function"
    }
    if (kind == IlMethodKind.Method) {
        return "Method"
    }
    if (kind == IlMethodKind.Constructor) {
        return "Constructor"
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
    if (kind == AstNodeCategory.TypeIntLit) {
        return xmlAttr(typeNode, AstNodeAttributeKind.Text)
    }
    if (kind == AstNodeCategory.TypeNamed) {
        return xmlAttr(typeNode, AstNodeAttributeKind.Name)
    }
    if (kind == AstNodeCategory.TypeGeneric) {
        var args: List<Str> = List<Str>()
        val typeArgs: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
        var i: Int = 0
        while (i < typeArgs.size()) {
            args.append(ilTypeText(*typeArgs[i]))
            i = i + 1
        }
        return xmlAttr(typeNode, AstNodeAttributeKind.Name) + "<" + ilJoinList(*args, ", ") + ">"
    }
    if (kind == AstNodeCategory.TypeReference) {
        return "&" + ilTypeText(*xmlChild(typeNode, AstNodeKind.Inner))
    }
    if (kind == AstNodeCategory.TypePointer) {
        return "*" + ilTypeText(*xmlChild(typeNode, AstNodeKind.Inner))
    }
    // A `..T` (and anything else) spells `?`: the C++ ring's `ilTypeText` switches on
    // Named/IntLit/Generic/Reference/Pointer/Function only, and the two dumps have to
    // agree byte for byte. A machine is not a value type, so a body that *is* one shows
    // its return type as `?` in both rings.
    if (kind == AstNodeCategory.TypeFunction) {
        var params: List<Str> = List<Str>()
        val paramTypes: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
        var i: Int = 0
        while (i < paramTypes.size()) {
            params.append(ilTypeText(*paramTypes[i]))
            i = i + 1
        }
        return "(" + ilJoinList(*params, ", ") + ") -> "
        +ilTypeText(*xmlChild(typeNode, AstNodeKind.ReturnType))
    }
    return "?"
}

// The type a receiver slot has in the frame: `*T self` for a value receiver, `&T` for a
// handle, `*T` for an explicit pointer receiver.
fun ilReceiverTypeText(typeNode: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference) {
        return "&" + ilTypeText(*xmlChild(typeNode, AstNodeKind.Inner))
    }
    if (kind == AstNodeCategory.TypePointer) {
        return "*" + ilTypeText(*xmlChild(typeNode, AstNodeKind.Inner))
    }
    return "*" + ilTypeText(typeNode)
}

// Whether an op's first `Var` operand is its *destination* - the rule the table leaves
// implicit: "the first `Var` operand of an op that produces a value is where the value
// goes". A backend folds a `Declare` into the instruction that writes the slot it
// declared when this is true and the two are adjacent.
fun ilWritesDestination(name: Str): Bool {
    // Written out rather than derived, because deriving it means reading the operands
    // *and* knowing which of them produce a value, which is the thing being stated.
    if (name == "SetVar") {
        return true
    }
    if (name == "SetVar_Null") {
        return true
    }
    if (name == "BinaryOp") {
        return true
    }
    if (name == "UnaryOp") {
        return true
    }
    if (name == "Cast") {
        return true
    }
    if (name == "Box") {
        return true
    }
    if (name == "Deref") {
        return true
    }
    if (name == "CopyValue") {
        return true
    }
    if (name == "GetField") {
        return true
    }
    if (name == "GetIndex") {
        return true
    }
    if (name == "FieldAddr") {
        return true
    }
    if (name == "IndexAddr") {
        return true
    }
    if (name == "GetStatic") {
        return true
    }
    if (name == "Call") {
        return true
    }
    if (name == "CallIndirect") {
        return true
    }
    if (name == "CallCtor") {
        return true
    }
    if (name == "Lambda") {
        return true
    }
    if (name == "Unsupported") {
        return true
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
    if (base == "Var") {
        return IlOperandKind.Var
    }
    if (base == "Value") {
        return IlOperandKind.Value
    }
    if (base == "Text") {
        return IlOperandKind.Text
    }
    if (base == "Type") {
        return IlOperandKind.Type
    }
    if (base == "Method") {
        return IlOperandKind.Method
    }
    if (base == "Label") {
        return IlOperandKind.Label
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
    val signature: Opt<IlSignature> = ilSignature(op.name)
    if (!signature.hasValue()) {
        return IlOperandKind.None
    }
    val found: IlSignature = signature.value()
    val tokens: List<Str> = ilOperandTokens(*found)
    return ilOperandKindAt(*tokens, index)
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
    if (kind == IlOperandKind.Var || kind == IlOperandKind.Value) {
        return ilVarName(body, value)
    }
    if (kind == IlOperandKind.Text) {
        return ilPoolAsText(ilPoolText(body, value))
    }
    if (kind == IlOperandKind.Type) {
        return ilTypeName(body, value)
    }
    if (kind == IlOperandKind.Method) {
        if (value < 0 || value >= body.methods.size()) {
            return "?m" + ilIntText(value)
        }
        return body.methods[value].name
    }
    if (kind == IlOperandKind.Label) {
        return ilLabelName(body, value)
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
    return ilJoinList(*args, ", ")
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
    val name: Str = op.name
    val operands: *List<Int> = *op.operands

    if (name == "Declare") {
        val slot: Int = ilOperandAt(operands, 0)
        return "var " + ilVarName(body, slot) + ": " + ilVarTypeName(body, slot)
    }
    if (name == "Label") {
        return ilLabelName(body, ilOperandAt(operands, 0)) + ":"
    }
    if (name == "Goto") {
        return "goto " + ilLabelName(body, ilOperandAt(operands, 0))
    }
    if (name == "IfTrue" || name == "IfFalse") {
        val condition: Str = ilVarName(body, ilOperandAt(operands, 0))
        val target: Str = ilLabelName(body, ilOperandAt(operands, 1))
        if (name == "IfTrue") {
            return "if (" + condition + ") goto " + target
        }
        return "if (!" + condition + ") goto " + target
    }
    if (name == "SetVar") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "SetVar_Null") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = null"
    }
    if (name == "BinaryOp") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 2)) + " "
        +ilPoolText(body, ilOperandAt(operands, 1)) + " "
        +ilVarName(body, ilOperandAt(operands, 3))
    }
    if (name == "UnaryOp") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilPoolText(body, ilOperandAt(operands, 1))
        +ilVarName(body, ilOperandAt(operands, 2))
    }
    if (name == "Cast") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = cast "
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "Box") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "Deref") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = *"
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "CopyValue") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = copy("
        +ilVarName(body, ilOperandAt(operands, 1)) + ")"
    }
    if (name == "Store") {
        return "*" + ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "GetField") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 1)) + "."
        +ilPoolText(body, ilOperandAt(operands, 2))
    }
    if (name == "SetField") {
        return ilVarName(body, ilOperandAt(operands, 1)) + "."
        +ilPoolText(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 2))
    }
    if (name == "GetIndex") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 1)) + "["
        +ilVarName(body, ilOperandAt(operands, 2)) + "]"
    }
    if (name == "SetIndex") {
        return ilVarName(body, ilOperandAt(operands, 1)) + "["
        +ilVarName(body, ilOperandAt(operands, 2)) + "] = "
        +ilVarName(body, ilOperandAt(operands, 3))
    }
    if (name == "FieldAddr") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
        +ilVarName(body, ilOperandAt(operands, 1)) + "."
        +ilPoolText(body, ilOperandAt(operands, 2))
    }
    if (name == "IndexAddr") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = &"
        +ilVarName(body, ilOperandAt(operands, 1)) + "["
        +ilVarName(body, ilOperandAt(operands, 2)) + "]"
    }
    if (name == "GetStatic") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = "
        +ilPoolText(body, ilOperandAt(operands, 1))
    }
    if (name == "SetStatic") {
        return ilPoolText(body, ilOperandAt(operands, 0)) + " = "
        +ilVarName(body, ilOperandAt(operands, 1))
    }
    if (name == "Call" || name == "CallVoid") {
        val hasDst: Bool = name == "Call"
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
    if (name == "CallIndirect" || name == "CallIndirectVoid") {
        val hasDst2: Bool = name == "CallIndirect"
        var dst2: Str = Str()
        var calleeAt: Int = 0
        if (hasDst2) {
            dst2 = ilVarName(body, ilOperandAt(operands, 0)) + " = "
            calleeAt = 1
        }
        return dst2 + ilVarName(body, ilOperandAt(operands, calleeAt)) + "("
        +ilArgList(body, operands, calleeAt + 1) + ")"
    }
    if (name == "CallCtor") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = new "
        +ilTypeName(body, ilOperandAt(operands, 1)) + "("
        +ilArgList(body, operands, 2) + ")"
    }
    if (name == "Return") {
        return "return " + ilVarName(body, ilOperandAt(operands, 0))
    }
    if (name == "ReturnVoid") {
        return "return"
    }
    if (name == "Lambda") {
        return ilVarName(body, ilOperandAt(operands, 0)) + " = <lambda>"
    }
    if (name == "Unsupported") {
        return "<unsupported: " + ilPoolText(body, ilOperandAt(operands, 1)) + ">"
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
    ilAppendTable(*out, "types:   ", *types)

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
    ilAppendTable(*out, "vars:    ", *vars)

    var pool: List<Str> = List<Str>()
    i = 0
    while (i < body.pool.size()) {
        pool.append(ilIntText(i) + " " + ilPoolAsText(body.pool[i]))
        i = i + 1
    }
    ilAppendTable(*out, "pool:    ", *pool)

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
    ilAppendTable(*out, "methods: ", *methods)

    var labels: List<Str> = List<Str>()
    i = 0
    while (i < body.labels.size()) {
        labels.append(ilIntText(i) + " " + body.labels[i])
        i = i + 1
    }
    ilAppendTable(*out, "labels:  ", *labels)

    i = 0
    while (i < body.ops.size()) {
        val op: IlOp = body.ops[i]
        val signature: Opt<IlSignature> = ilSignature(op.name)
        var tokens: List<Str> = List<Str>()
        if (signature.hasValue()) {
            val found: IlSignature = signature.value()
            tokens = ilOperandTokens(*found)
        }

        var rendered: List<Str> = List<Str>()
        var j: Int = 0
        while (j < op.operands.size()) {
            rendered.append(ilRenderOperand(body, ilOperandKindAt(*tokens, j), op.operands[j]))
            j = j + 1
        }

        var text: Str = ilPadRight(ilIntText(i), 4) + ",  " + ilPadRight(op.name, 16)
        +ilJoinList(*rendered, ", ")
        val comment: Str = ilOpComment(body, *op)
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
    var out: Str = printIlBody(*unit.body)
    var i: Int = 0
    while (i < unit.closures.size()) {
        val closure: IlClosure = unit.closures[i]
        out = out + "\n## closure " + closure.symbol + "  captures ("
        +ilJoinList(*closure.captures, ", ") + ")  " + closure.signature + "\n"
        if (closure.bodyIndex >= 0 && closure.bodyIndex < unit.lambdas.size()) {
            out = out + printIlBody(*unit.lambdas[closure.bodyIndex])
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
    var i: Int = 0
    while (i < node.Children.count()) {
        ilCollectExprNames(*node.Children[i], order, seen)
        i = i + 1
    }
}

// The names a statement sequence reads, in the order the C++ ring walks them (the
// statement's own expressions, then the statements of its containers). A name it declares
// counts as declared, wherever in the sequence the declaration stands.
fun ilCollectStmtNames(
    stmts: *List<AstXmlNode>, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
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
        val cases: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Case)
        var c: Int = 0
        while (c < cases.size()) {
            val arm: List<AstXmlNode> = xmlChildren(*cases[c], AstNodeKind.Label)
            if (arm.size() > 0) {
                ilCollectExprNames(*arm[0], order, seen)
            }
            var body: List<AstXmlNode> = List<AstXmlNode>()
            var k: Int = 0
            val armChildren: Array<AstXmlNode> = cases[c].Children
            while (k < armChildren.count()) {
                if (armChildren[k].name != AstNodeKind.Label) {
                    body.append(armChildren[k])
                }
                k = k + 1
            }
            ilCollectStmtNames(*body, declared, order, seen)
            c = c + 1
        }
        i = i + 1
    }
}

// One expression child of a statement, when the statement has it.
fun ilCollectStmtExprs(
    stmt: *AstXmlNode, role: AstNodeKind, order: *List<Str>,
    seen: *Dictionary<Str, Bool>
): Unit {
    val child: AstXmlNode = xmlChild(stmt, role)
    if (!xmlIsEmpty(*child)) {
        ilCollectExprNames(*child, order, seen)
    }
}

// The statements of one container child (`Body`, `Then`, `Else`), when it has one.
fun ilCollectStmtNamesIn(
    stmt: *AstXmlNode, role: AstNodeKind, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    val container: AstXmlNode = xmlChild(stmt, role)
    if (xmlIsEmpty(*container)) {
        return
    }
    var i: Int = 0
    val children: Array<AstXmlNode> = container.Children
    var body: List<AstXmlNode> = List<AstXmlNode>()
    while (i < children.count()) {
        body.append(children[i])
        i = i + 1
    }
    ilCollectStmtNames(*body, declared, order, seen)
}

// ---- operand lists ---------------------------------------------------------

fun ilOps1(a: Int): List<Int> {
    var ops: List<Int> = List<Int>()
    ops.append(a)
    return ops
}

fun ilOps2(a: Int, b: Int): List<Int> {
    var ops: List<Int> = List<Int>()
    ops.append(a)
    ops.append(b)
    return ops
}

fun ilOps3(a: Int, b: Int, c: Int): List<Int> {
    var ops: List<Int> = List<Int>()
    ops.append(a)
    ops.append(b)
    ops.append(c)
    return ops
}

fun ilOps4(a: Int, b: Int, c: Int, d: Int): List<Int> {
    var ops: List<Int> = List<Int>()
    ops.append(a)
    ops.append(b)
    ops.append(c)
    ops.append(d)
    return ops
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
    xmlAddChild(*node, renamed)
    return node
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

// ---- the extractor ---------------------------------------------------------

// One body into instructions. The frame comes from `IlFunction` (a declaration's
// parameters and receiver, or a lambda's / a machine method's parameters and class), the
// tables are filled in first-touch order, and every instruction carries the line of the
// statement it came from.
data class IlExtractor(
    var fn: IlFunction;

var unit: *IlUnit;
var closureCounter: *Int;
var out: IlBody;
var varAt: Dictionary<Str, Int>;
var typeAt: Dictionary<Str, Int>;
var poolAt: Dictionary<Str, Int>;
var methodAt: Dictionary<Str, Int>;
var labelAt: Dictionary<Str, Int>;
var nextBase: Int;
var line: Int
) {

    // ---- tables -----------------------------------------------------------

    fun addVar(name: Str, typeText: Str, kind: IlVarKind, typeNode: AstXmlNode): Int {
        this.out.vars.append(IlVar(name, this.typeIndex(typeText, typeNode), kind))
        this.varAt.insert(name, this.out.vars.size() - 1)
        return this.out.vars.size() - 1
    }

    // The type table: text for the dump, the node (when there is one) for a backend. The
    // first node seen for a text wins, so the table does not depend on which mention
    // happened to carry it.
    fun typeIndex(text: Str, node: AstXmlNode): Int {
        if (this.typeAt.has(text)) {
            val index: Int = this.typeAt.get(text).value()
            if (!xmlIsEmpty(*node) && index < this.out.typeNodes.size()) {
                val existing: AstXmlNode = this.out.typeNodes[index]
                if (xmlIsEmpty(*existing)) {
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
        // A slot the extractor synthesised exists nowhere in the statements, so the
        // instruction list has to say where it comes from: here, in front of the
        // instruction that first writes it.
        this.emit("Declare", ilOps1(slot))
        return slot
    }

    fun freshSlotText(typeText: Str): Int {
        return this.freshSlot(typeText, xmlEmptyNode())
    }

    fun emit(name: Str, operands: List<Int>): Unit {
        this.out.ops.append(IlOp(name, operands))
        this.out.lines.append(this.line)
    }

    fun unsupported(what: Str): Unit {
        this.emit("Unsupported", ilOps2(this.freshSlotText("?"), this.poolIndex(what)))
    }

    fun literalOperand(text: Str): Int {
        return -1 - this.poolIndex(text)
    }

    // ---- the frame --------------------------------------------------------

    fun begin(file: Str): Unit {
        this.out.file = file
        if (xmlIsEmpty(*this.fn.decl)) {
            this.out.line = 0
        } else {
            this.out.line = xmlLine(*this.fn.decl)
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
        this.stmts(*body)
        return this.out
    }

    fun buildFrame(): Unit {
        if (!this.fn.closureSymbol.isEmpty()) {
            // A lambda (or a machine's method): the receiver is the class instance - whose
            // fields the captures are - and the parameter list is the body's own.
            this.addVar(
                "self", "*" + this.fn.closureSymbol, IlVarKind.Argument,
                ilPointerNode(*ilNamedTypeNode(this.fn.closureSymbol))
            )
            var i: Int = 0
            while (i < this.fn.paramNames.size()) {
                var paramType: AstXmlNode = xmlEmptyNode()
                if (i < this.fn.paramTypes.size()) {
                    paramType = this.fn.paramTypes[i]
                }
                var text: Str = "?"
                if (!xmlIsEmpty(*paramType)) {
                    text = ilTypeText(*paramType)
                }
                this.addVar(this.fn.paramNames[i], text, IlVarKind.Argument, paramType)
                i = i + 1
            }
            return
        }
        var hasSelf: Bool = false
        if (!xmlIsEmpty(*this.fn.receiver)) {
            this.addVar(
                "self", ilReceiverTypeText(*this.fn.receiver), IlVarKind.Argument,
                ilReceiverTypeNode(*this.fn.receiver)
            )
            hasSelf = true
        }
        val params: List<AstXmlNode> = xmlChildren(*this.fn.decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            val param: *AstXmlNode = *params[i]
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            val typeNode: AstXmlNode = xmlChild(param, AstNodeKind.Type)
            if (name == "this" && !hasSelf) {
                var selfText: Str = "?"
                if (!xmlIsEmpty(*typeNode)) {
                    selfText = ilReceiverTypeText(*typeNode)
                }
                this.addVar("self", selfText, IlVarKind.Argument, ilReceiverTypeNode(*typeNode))
                hasSelf = true
                i = i + 1
                continue
            }
            var text: Str = "?"
            if (!xmlIsEmpty(*typeNode)) {
                text = ilTypeText(*typeNode)
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
        if (!xmlIsEmpty(*this.fn.decl)) {
            val declared: AstXmlNode = xmlChild(*this.fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(*declared)) {
                retText = ilTypeText(*declared)
            }
        }
        return "(" + ilJoinList(*params, ", ") + ") -> " + retText
    }

    // ---- statements -------------------------------------------------------

    fun stmts(list: *List<AstXmlNode>): Unit {
        var i: Int = 0
        while (i < list.size()) {
            this.statement(*list[i])
            i = i + 1
        }
    }

    fun statement(stmt: *AstXmlNode): Unit {
        this.line = xmlLine(stmt)
        val kind: AstNodeCategory = xmlKind(stmt)
        if (kind == AstNodeCategory.StmtLabel) {
            this.emit("Label", ilOps1(this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))))
            return
        }
        if (kind == AstNodeCategory.StmtGoto) {
            this.emit("Goto", ilOps1(this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))))
            return
        }
        if (kind == AstNodeCategory.StmtIfTrue || kind == AstNodeCategory.StmtIfFalse) {
            val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
            this.emit(
                ilCategoryName(kind), ilOps2(
                    this.operandOf(*cond),
                    this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))
                )
            )
            return
        }
        if (kind == AstNodeCategory.StmtVarDecl) {
            val typeNode: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
            var typeText: Str = "?"
            if (!xmlIsEmpty(*typeNode)) {
                typeText = ilTypeText(*typeNode)
            }
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            var slotKind: IlVarKind = IlVarKind.Local
            if (linIsSlotName(name)) {
                slotKind = IlVarKind.Expression
            }
            val slot: Int = this.addVar(name, typeText, slotKind, typeNode)
            val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
            if (xmlIsEmpty(*init)) {
                this.emit("Declare", ilOps1(slot))
            } else {
                this.emit("DeclareInit", ilOps1(slot))
                this.into(slot, init)
            }
            return
        }
        if (kind == AstNodeCategory.StmtAssign) {
            this.assign(*xmlChild(stmt, AstNodeKind.Target), *xmlChild(stmt, AstNodeKind.Value))
            return
        }
        if (kind == AstNodeCategory.StmtReturn) {
            val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
            if (xmlIsEmpty(*value)) {
                this.emit("ReturnVoid", List<Int>())
            } else {
                this.emit("Return", ilOps1(this.operandOf(*value)))
            }
            return
        }
        if (kind == AstNodeCategory.StmtExprStmt) {
            val expr: AstXmlNode = xmlChild(stmt, AstNodeKind.Expr)
            if (xmlKind(*expr) == AstNodeCategory.ExprCall) {
                this.call(-1, expr)
                return
            }
            // A read with no destination: keep it visible rather than dropping it.
            this.unsupported("expression statement")
            return
        }
        if (kind == AstNodeCategory.StmtBlock) {
            val container: AstXmlNode = xmlChild(stmt, AstNodeKind.Body)
            var i: Int = 0
            val children: Array<AstXmlNode> = container.Children
            var body: List<AstXmlNode> = List<AstXmlNode>()
            while (i < children.count()) {
                body.append(children[i])
                i = i + 1
            }
            this.stmts(*body)
            return
        }
        if (kind == AstNodeCategory.StmtIf || kind == AstNodeCategory.StmtWhile
            || kind == AstNodeCategory.StmtSwitch || kind == AstNodeCategory.StmtBreak
            || kind == AstNodeCategory.StmtContinue
        ) {
            this.unsupported("structured statement reached the IL")
            return
        }
        this.unsupported("statement")
    }

    fun assign(target: *AstXmlNode, value: *AstXmlNode): Unit {
        val targetKind: AstNodeCategory = xmlKind(target)
        if (targetKind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            // A write to a captured variable writes the closure's field.
            if (this.fn.captures.has(name)) {
                this.emit(
                    "SetField", ilOps3(
                        this.varIndex("self"), this.poolIndex(name),
                        this.operandOf(value)
                    )
                )
                return
            }
            val slot: Int = this.varIndex(name)
            if (slot >= 0) {
                this.into(slot, *value)
                return
            }
            this.emit("SetStatic", ilOps2(this.poolIndex(name), this.operandOf(value)))
            return
        }
        if (targetKind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(target, AstNodeKind.Receiver)
            val fieldName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            if (this.isTypeBase(lhs)) {
                this.emit(
                    "SetStatic", ilOps2(
                        this.poolIndex(this.baseText(lhs) + "." + fieldName),
                        this.operandOf(value)
                    )
                )
                return
            }
            this.emit(
                "SetField", ilOps3(
                    this.receiverOf(lhs), this.poolIndex(fieldName),
                    this.operandOf(value)
                )
            )
            return
        }
        if (targetKind == AstNodeCategory.ExprIndex) {
            this.emit(
                "SetIndex", ilOps3(
                    this.receiverOf(xmlChild(target, AstNodeKind.Receiver)),
                    this.operandOf(*xmlChild(target, AstNodeKind.Index)),
                    this.operandOf(value)
                )
            )
            return
        }
        if (targetKind == AstNodeCategory.ExprDeref) {
            // `*p = v`: the emitter writes through the pointer's type.
            this.emit(
                "Store", ilOps2(
                    this.operandOf(*xmlChild(target, AstNodeKind.Operand)),
                    this.operandOf(value)
                )
            )
            return
        }
        this.unsupported("assignment target")
    }

    // ---- values -----------------------------------------------------------

    // The value `expr` produces, as an operand: a frame slot, a literal (a negative
    // operand, see `literalOperand`), or - when the position holds more than a name - a
    // slot the extractor synthesizes for the place.
    fun operandOf(expr: *AstXmlNode): Int {
        val kind: AstNodeCategory = xmlKind(expr)
        if (kind == AstNodeCategory.ExprIntLit || kind == AstNodeCategory.ExprFloatLit
            || kind == AstNodeCategory.ExprStrLit || kind == AstNodeCategory.ExprCharLit
        ) {
            // A literal in a value position rides the instruction as an operand: the pool
            // holds the token's own text, so a backend prints exactly what the statement
            // path printed - `i > 0`, not `_sm_base1 = 0; ... i > _sm_base1`.
            return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Text))
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Value))
        }
        if (kind == AstNodeCategory.ExprNullLit) {
            // `null`'s spelling depends on the expected type (`Opt<T>()` vs `nullptr`),
            // which only the destination slot's type states for certain - so it is
            // materialised.
            val slot: Int = this.freshSlotText("?")
            this.emit("SetVar_Null", ilOps1(slot))
            return slot
        }
        if (kind == AstNodeCategory.ExprName) {
            return this.nameOf(*expr)
        }
        if (kind == AstNodeCategory.ExprMember || kind == AstNodeCategory.ExprIndex
            || kind == AstNodeCategory.ExprCall || kind == AstNodeCategory.ExprBinary
            || kind == AstNodeCategory.ExprUnary || kind == AstNodeCategory.ExprRef
            || kind == AstNodeCategory.ExprDeref || kind == AstNodeCategory.ExprCopy
        ) {
            val slot: Int = this.freshSlotText("?")
            this.into(slot, *expr)
            return slot
        }
        if (kind == AstNodeCategory.ExprLambda) {
            return this.lambdaOf(expr, -1)
        }
        if (kind == AstNodeCategory.ExprGenericName) {
            this.unsupported("type name in a value position")
            return this.freshSlotText("?")
        }
        this.unsupported("expression")
        return this.freshSlotText("?")
    }

    // Writes `e` into `slot`, one instruction per operation: every value the lowering
    // produces is one operation deep, so one case here is one instruction.
    fun into(slot: Int, e: AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(*e)
        if (kind == AstNodeCategory.ExprIntLit || kind == AstNodeCategory.ExprFloatLit
            || kind == AstNodeCategory.ExprStrLit || kind == AstNodeCategory.ExprCharLit
        ) {
            this.emit("SetVar", ilOps2(slot, this.literalOperand(xmlAttr(*e, AstNodeAttributeKind.Text))))
            return
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            this.emit("SetVar", ilOps2(slot, this.literalOperand(xmlAttr(*e, AstNodeAttributeKind.Value))))
            return
        }
        if (kind == AstNodeCategory.ExprNullLit) {
            this.emit("SetVar_Null", ilOps1(slot))
            return
        }
        if (kind == AstNodeCategory.ExprName) {
            this.emit("SetVar", ilOps2(slot, this.nameOf(e)))
            return
        }
        if (kind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(*e, AstNodeKind.Receiver)
            val fieldName: Str = xmlAttr(*e, AstNodeAttributeKind.Name)
            if (this.isTypeBase(lhs)) {
                this.emit("GetStatic", ilOps2(slot, this.poolIndex(this.baseText(lhs) + "." + fieldName)))
                return
            }
            this.emit("GetField", ilOps3(slot, this.valueOf(lhs), this.poolIndex(fieldName)))
            return
        }
        if (kind == AstNodeCategory.ExprIndex) {
            this.emit(
                "GetIndex", ilOps3(
                    slot, this.valueOf(xmlChild(*e, AstNodeKind.Receiver)),
                    this.operandOf(*xmlChild(*e, AstNodeKind.Index))
                )
            )
            return
        }
        if (kind == AstNodeCategory.ExprBinary) {
            this.emit(
                "BinaryOp", ilOps4(
                    slot, this.poolIndex(xmlAttr(*e, AstNodeAttributeKind.Op)),
                    this.operandOf(*xmlChild(*e, AstNodeKind.Lhs)),
                    this.operandOf(*xmlChild(*e, AstNodeKind.Rhs))
                )
            )
            return
        }
        if (kind == AstNodeCategory.ExprUnary) {
            this.emit(
                "UnaryOp", ilOps3(
                    slot, this.poolIndex(xmlAttr(*e, AstNodeAttributeKind.Op)),
                    this.operandOf(*xmlChild(*e, AstNodeKind.Operand))
                )
            )
            return
        }
        if (kind == AstNodeCategory.ExprRef) {
            this.emit("Box", ilOps2(slot, this.operandOf(*xmlChild(*e, AstNodeKind.Operand))))
            return
        }
        if (kind == AstNodeCategory.ExprDeref) {
            // `*x` is a borrow, `.get()`, or a load depending on what `x` is - the
            // backend reads that from the operand's slot type.
            this.emit("Deref", ilOps2(slot, this.operandOf(*xmlChild(*e, AstNodeKind.Operand))))
            return
        }
        if (kind == AstNodeCategory.ExprCopy) {
            this.emit("CopyValue", ilOps2(slot, this.operandOf(*xmlChild(*e, AstNodeKind.Operand))))
            return
        }
        if (kind == AstNodeCategory.ExprCall) {
            this.call(slot, e)
            return
        }
        if (kind == AstNodeCategory.ExprLambda) {
            // A lambda whose value is dropped still constructs its class.
            this.lambdaOf(*e, slot)
            return
        }
        if (kind == AstNodeCategory.ExprGenericName) {
            this.unsupported("type name in a value position")
            return
        }
        this.unsupported("expression")
    }

    // A read of a place: the value behind it, as a slot.
    fun valueOf(e: AstXmlNode): Int {
        if (xmlKind(*e) == AstNodeCategory.ExprName) {
            return this.nameOf(e)
        }
        val slot: Int = this.freshSlotText("?")
        this.into(slot, e)
        return slot
    }

    // A base that is a *place*: a call's receiver, or an assignment target's base. A
    // plain name is that slot; anything deeper becomes an **address** slot
    // (`FieldAddr`/`IndexAddr`), so a call that mutates its receiver reaches the original
    // and not a copy.
    fun receiverOf(e: AstXmlNode): Int {
        val kind: AstNodeCategory = xmlKind(*e)
        if (kind == AstNodeCategory.ExprName) {
            return this.nameOf(e)
        }
        if (kind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(*e, AstNodeKind.Receiver)
            if (this.isTypeBase(lhs)) {
                return this.valueOf(e)
            }
            val slot: Int = this.freshSlotText("*?")
            this.emit(
                "FieldAddr", ilOps3(
                    slot, this.receiverOf(lhs),
                    this.poolIndex(xmlAttr(*e, AstNodeAttributeKind.Name))
                )
            )
            return slot
        }
        if (kind == AstNodeCategory.ExprIndex) {
            val slot: Int = this.freshSlotText("*?")
            this.emit(
                "IndexAddr", ilOps3(
                    slot, this.receiverOf(xmlChild(*e, AstNodeKind.Receiver)),
                    this.operandOf(*xmlChild(*e, AstNodeKind.Index))
                )
            )
            return slot
        }
        if (kind == AstNodeCategory.ExprDeref) {
            return this.operandOf(*xmlChild(*e, AstNodeKind.Operand))
        }
        return this.valueOf(e)
    }

    // ---- names and types --------------------------------------------------

    fun nameOf(e: AstXmlNode): Int {
        val name: Str = xmlAttr(*e, AstNodeAttributeKind.Name)
        // A captured variable is a *field* of the closure, not a slot of this frame:
        // reading it reads through `self`.
        if (this.fn.captures.has(name)) {
            var text: Str = "?"
            if (this.fn.captureTypes.has(name)) {
                text = ilTypeText(*this.fn.captureTypes.get(name).value())
            }
            val slot: Int = this.freshSlot(text, this.fn.captureTypes.get(name).value())
            this.emit("GetField", ilOps3(slot, this.varIndex("self"), this.poolIndex(name)))
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
        // resolve (`GetStatic` in both cases).
        var typeText: Str = "?"
        if (this.fn.statics.has(name)) {
            typeText = this.fn.statics.get(name).value()
        }
        val fresh: Int = this.freshSlotText(typeText)
        this.emit("GetStatic", ilOps2(fresh, this.poolIndex(name)))
        return fresh
    }

    fun isTypeBase(e: AstXmlNode): Bool {
        val kind: AstNodeCategory = xmlKind(*e)
        if (kind == AstNodeCategory.ExprGenericName) {
            return true
        }
        if (kind != AstNodeCategory.ExprName) {
            return false
        }
        val name: Str = xmlAttr(*e, AstNodeAttributeKind.Name)
        if (name == "this" || this.hasVar(name)) {
            return false
        }
        if (this.fn.captures.has(name)) {
            return false
        }
        return !this.fn.statics.has(name)
    }

    fun baseText(e: AstXmlNode): Str {
        if (xmlKind(*e) == AstNodeCategory.ExprName) {
            return xmlAttr(*e, AstNodeAttributeKind.Name)
        }
        return ilTypeText(*this.calleeToType(e))
    }

    // The `GenericName` node carries its type arguments; the IL wants the type as a node,
    // so a fresh generic type node is built from the name and those arguments.
    fun calleeToType(e: AstXmlNode): AstXmlNode {
        val name: Str = xmlAttr(*e, AstNodeAttributeKind.Name)
        if (xmlKind(*e) == AstNodeCategory.ExprGenericName) {
            var node: AstXmlNode = AstXmlNode(
                AstNodeKind.Type, AstNodeCategory.TypeGeneric,
                List<AstNodeAttribute>(), Array<AstXmlNode>()
            )
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            var i: Int = 0
            val args: List<AstXmlNode> = xmlChildren(*e, AstNodeKind.TypeArg)
            while (i < args.size()) {
                var arg: AstXmlNode = copy(*args[i])
                arg.name = AstNodeKind.TypeArg
                xmlAddChild(*node, arg)
                i = i + 1
            }
            return node
        }
        return ilNamedTypeNode(name)
    }

    // A literal expression's type, as the frame spells it: a literal operand is a *value*
    // with a type like any other, and the method table's `argTypes` should read the same
    // whether the argument was a slot or a constant.
    fun literalTypeText(e: AstXmlNode): Str {
        val kind: AstNodeCategory = xmlKind(*e)
        if (kind == AstNodeCategory.ExprIntLit) {
            return "Int"
        }
        if (kind == AstNodeCategory.ExprFloatLit) {
            return "Float64"
        }
        if (kind == AstNodeCategory.ExprStrLit) {
            return "Str"
        }
        if (kind == AstNodeCategory.ExprCharLit) {
            return "Char"
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            return "Bool"
        }
        return "?"
    }

    fun operandType(slot: Int, expr: AstXmlNode): Int {
        if (slot >= 0) {
            return this.out.vars[slot].typeIndex
        }
        if (xmlIsEmpty(*expr)) {
            return this.typeIndexText("?")
        }
        return this.typeIndexText(this.literalTypeText(expr))
    }

    // ---- calls ------------------------------------------------------------

    // `dst < 0` means the result is dropped (`CallVoid`).
    fun call(dst: Int, e: AstXmlNode): Unit {
        val callee: AstXmlNode = xmlChild(*e, AstNodeKind.Callee)
        val hasDst: Bool = dst >= 0
        var returnType: Int = -1
        if (hasDst) {
            returnType = this.out.vars[dst].typeIndex
        }
        val member: Bool = xmlKind(*callee) == AstNodeCategory.ExprMember
        val lhs: AstXmlNode = xmlChild(*callee, AstNodeKind.Receiver)
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

        var args: List<Int> = List<Int>()
        var argTypes: List<Int> = List<Int>()
        var i: Int = 0
        val argNodes: List<AstXmlNode> = xmlChildren(*e, AstNodeKind.Arg)
        while (i < argNodes.size()) {
            val slot: Int = this.operandOf(*argNodes[i])
            args.append(slot)
            argTypes.append(this.operandType(slot, argNodes[i]))
            i = i + 1
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
        val calleeName: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)

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
        if (xmlKind(*callee) == AstNodeCategory.ExprGenericName) {
            // A construction: `Point(1, 2)`, `List<Str>()`. The type operand is the *node*
            // the callee was, so a backend can spell the type arguments the way the source
            // wrote them.
            if (!hasDst) {
                this.unsupported("constructor call with no destination")
                return
            }
            operands.append(this.typeIndex(this.baseText(callee), this.calleeToType(callee)))
            i = 0
            while (i < args.size()) {
                operands.append(args[i])
                i = i + 1
            }
            this.emit("CallCtor", operands)
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
            this.emit("Call", operands)
        } else {
            this.emit("CallVoid", operands)
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
        ilCollectStmtNames(*bodyList, *declared, *read, *readSeen)

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
            xmlEmptyNode(), xmlEmptyNode(), symbol, this.fn.statics,
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
            val slotType: AstXmlNode = ilVarTypeNode(*this.out, this.varIndex(name))
            if (!xmlIsEmpty(*slotType)) {
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
        var lowered: List<AstXmlNode> = ilLambdaLower(*bodyList)
        val lambdaSemantics: SemBody = SemBody(
            xmlEmptyNode(), this.fn.typeParams, ilNamedTypeNode(symbol),
            paramNames, paramTypes, info.captureTypes
        )
        lowered = semInferTypes(*lowered, this.fn.facts, *lambdaSemantics, *lambdaTypes)
        lowered = linFinishForEmission(lowered, paramNames)

        var inner: IlExtractor = IlExtractor(
            info, this.unit, this.closureCounter,
            ilEmptyBody(), Dictionary<Str, Int>(),
            Dictionary<Str, Int>(), Dictionary<Str, Int>(),
            Dictionary<Str, Int>(), Dictionary<Str, Int>(), 1, 0
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
        closure.signature = "(" + ilJoinList(*captured, ", ") + ") " + innerBody.signature
        this.unit.lambdas.append(innerBody)
        this.unit.closures.append(closure)

        // The value: a construction of the class, with the captured values.
        val classType: AstXmlNode = ilNamedTypeNode(symbol)
        var target: Int = dst
        if (dst < 0) {
            target = this.freshSlot(symbol, classType)
        }
        var operands: List<Int> = List<Int>()
        operands.append(target)
        operands.append(this.typeIndex(symbol, classType))
        ci = 0
        while (ci < captured.size()) {
            // The capture is a slot of *this* frame (that is what put it in the closure), so
            // the construction passes its value by index.
            operands.append(this.varIndex(captured[ci]))
            ci = ci + 1
        }
        this.emit("CallCtor", operands)
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
    if (body.size() == 1 && xmlKind(*body[0]) == AstNodeCategory.StmtExprStmt) {
        val expr: AstXmlNode = xmlChild(*body[0], AstNodeKind.Expr)
        if (!xmlIsEmpty(*expr)) {
            var ret: AstXmlNode = linStmt(
                AstNodeCategory.StmtReturn, xmlLine(*body[0]),
                xmlColumn(*body[0])
            )
            var value: AstXmlNode = copy(*expr)
            value.name = AstNodeKind.Value
            xmlAddChild(*ret, value)
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

// The names the schema gives a jump category, as the opcode's own name (`IfTrue`,
// `IfFalse`): the instruction set spells them that way.
fun ilCategoryName(kind: AstNodeCategory): Str {
    if (kind == AstNodeCategory.StmtIfTrue) {
        return "IfTrue"
    }
    return "IfFalse"
}

// Builds the IL of one body: the body itself plus the lambdas it constructs. The closure
// symbols are numbered per *unit*, so a lambda inside a lambda still gets a name of its
// own.
fun ilExtractUnit(fn: IlFunction, body: List<AstXmlNode>, file: Str): IlUnit {
    var unit: IlUnit = IlUnit(ilEmptyBody(), List<IlBody>(), List<IlClosure>())
    var counter: Int = 1
    var extractor: IlExtractor = IlExtractor(
        fn, *unit, *counter, ilEmptyBody(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), 1, 0
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
