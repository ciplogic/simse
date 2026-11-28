// LinearFormSig.kt
//
// The IL's signature table and operand spellings: what each op kind is, which operands are
// writes, and the text a dump shows.

package linear
import compiler

import common
import parser
import sema


fun makeIlSignatures(): List<IlSignature> {
    var table: List<IlSignature> = listOf<IlSignature>(
        IlSignature(IlOpKind.Label, "Label"),
        IlSignature(IlOpKind.Goto, "Label"),
        IlSignature(IlOpKind.IfTrue, "Value,Label"),
        IlSignature(IlOpKind.IfFalse, "Value,Label"),
        IlSignature(IlOpKind.Declare, "Var"),
        // A declaration the instruction *after* it initialises - the hoisting splits an
        // initializer into a separate assignment, which is a bare `Declare`.
        IlSignature(IlOpKind.DeclareInit, "Var"),
        // One assignment op for every kind of value - the destination slot's type says which.
        IlSignature(IlOpKind.SetVar, "Var,Value"),
        // Except `null`, whose spelling comes from the destination's type.
        IlSignature(IlOpKind.SetVar_Null, "Var"),
        IlSignature(IlOpKind.BinaryOp, "Var,Text,Value,Value"),
        IlSignature(IlOpKind.UnaryOp, "Var,Text,Value"),
        IlSignature(IlOpKind.Cast, "Var,Value"),     // Enum.toInt(): the one cast
        IlSignature(IlOpKind.Box, "Var,Var"),        // &x -> a counted handle
        IlSignature(IlOpKind.Deref, "Var,Var"),      // *x -> a borrow, `.get()`, or a `*T` load
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
        // A container built from values in one instruction: `List<T>{v1, ...}`. The
        // destination's type says which container; the values are elements, which is what
        // the pack builds on.
        IlSignature(IlOpKind.Pack, "Var,Value..."),
        IlSignature(IlOpKind.Concat, "Var,Value..."),
        IlSignature(IlOpKind.Return, "Value"),
        IlSignature(IlOpKind.ReturnVoid, ""),
        IlSignature(IlOpKind.Lambda, "Var"),
        IlSignature(IlOpKind.Unsupported, "Var,Text"),
        // The address of a slot, one operation (`impl_specs/linear-form-plan.md`, step 3b):
        // what the conversion to a `*T` parameter spelled through a `Deref` over a value.
        IlSignature(IlOpKind.Addr, "Var,Var")
    )
    return table
}

// The opcode's spelling, in `IlOpKind` order (the dump reads it).
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
        "Concat",
        "Return",
        "ReturnVoid",
        "Lambda",
        "Unsupported",
        "Addr"
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

// An opcode's signature: the table is in `IlOpKind` order, so the row *is* the opcode.
fun ilSignature(kind: IlOpKind): Opt<IlSignature> {
    val index: Int = kind.toInt()
    if (index < 0 || index >= ilSignatureTable.size()) {
        return ()
    }
    return (ilSignatureTable[index])
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

fun ilIntText(value: Int): Str {
    return value.toString()
}

// The IL's spelling of a type, in the language's own form and unqualified. An empty node
// (a type the extractor could not name) spells `?`.
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
            val name: *Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
            val joined: Str = joinStrs(args, ", ")
            var out: Str
            out.reserve(name.size() + joined.size() + 2)
            out.appendStrPtr(name)
            out.append('<')
            out.appendStr(joined)
            out.append('>')
            return out
        }

        AstNodeCategory.TypeReference -> {
            return "&" + ilTypeText(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            if (xmlIsRawPtrType(typeNode)) {
                return "RawPtr"
            }
            return "*" + ilTypeText(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeYield -> {
            // A machine (`..T`): `?` when nothing named the class, the class and its type
            // arguments otherwise.
            val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
            if (name == "") {
                return "?"
            }
            var args: List<Str> = List<Str>()
            val typeArgs: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*typeArg in typeArgs) {
                args.append(ilTypeText(typeArg))
            }
            if (args.size() == 0) {
                return name
            }
            val joinStrsText: Str = joinStrs(args, ", ")
            return `@name<@joinStrsText>`
        }

        AstNodeCategory.TypeFunction -> {
            var params: List<Str> = List<Str>()
            val paramTypes: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            for (*paramType in paramTypes) {
                params.append(ilTypeText(paramType))
            }
            val joined: Str = joinStrs(params, ", ")
            val ret: Str = ilTypeText(xmlChildPtr(typeNode, AstNodeKind.ReturnType))
            var out: Str
            out.reserve(joined.size() + ret.size() + 8)
            out.append('(')
            out.appendStr(joined)
            out.appendStr(") -> ")
            out.appendStr(ret)
            return out
        }
    }
    return "?"
}

// A receiver slot's frame type: `*T` for a value or pointer receiver, `&T` for a handle.
fun ilReceiverTypeText(typeNode: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeReference -> {
            return "&" + ilTypeText(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            if (xmlIsRawPtrType(typeNode)) {
                return "RawPtr"
            }
            return "*" + ilTypeText(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeYield -> {
            // A receiver spelled through a protocol (`fun Yieldable<*T>.toList<T>()`): the
            // bare pattern has no class to name (`*?`), but the protocol's spelling does -
            // the machine contract exactly as the author wrote it.
            val protocolName: Str = xmlAttr(typeNode, AstNodeAttributeKind.MachineProtocol)
            if (protocolName != "") {
                val elementText: Str = ilTypeText(xmlChildPtr(typeNode, AstNodeKind.Inner))
                return `@protocolName<@elementText>`
            }
        }
    }
    return "*" + ilTypeText(typeNode)
}

// The rule the table leaves implicit: the first `Var` operand of an op that produces a
// value is where the value goes.
fun ilWritesDestination(kind: IlOpKind): Bool {
    // Written out rather than derived: deriving it needs to know which operands produce a value.
    when (kind) {
        IlOpKind.Addr -> {
            return true
        }

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

        IlOpKind.Concat -> {
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

// `"Var,Method,Var..."` -> its tokens.
fun ilOperandTokens(signature: *IlSignature): List<Str> {
    var tokens: List<Str> = List<Str>()
    var current: Str
    val spec: Str = signature.operands
    var i: Int = 0
    while (i < spec.size()) {
        if (spec[i] == ',') {
            tokens.append(current)
            current = Str()
            i++
            continue
        }
        current = current + spec[i]
        i++
    }
    if (!current.isEmpty()) {
        tokens.append(current)
    }
    return tokens
}

fun ilTokenRepeats(token: *Str): Bool {
    return token.size() > 3 && token[token.size() - 1] == '.'
            && token[token.size() - 2] == '.' && token[token.size() - 3] == '.'
}

fun ilKindOfToken(token: *Str): IlOperandKind {
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

// What operand `index` of `op` is, from the signature table.
fun ilOperandKind(op: *IlOp, index: Int): IlOperandKind {
    val signature: Opt<IlSignature> = ilSignature(op.kind)
    if (!signature.hasValue()) {
        return IlOperandKind.None
    }
    val found: IlSignature = signature.value()
    val tokens: List<Str> = ilOperandTokens(found)
    return ilOperandKindAt(tokens, index)
}

// Whether `writes` - a method record's comma-separated positions - names `position`.
fun ilWritesHas(writes: *Str, position: Int): Bool {
    if (position < 0 || writes.isEmpty()) {
        return false
    }
    var start: Int = 0
    var i: Int = 0
    while (i <= writes.size()) {
        if (i == writes.size() || writes[i] == ',') {
            val piece: Str = writes.substr(start, i - start)
            val at: Opt<Int> = piece.toInt()
            if (at.hasValue() && at.value() == position) {
                return true
            }
            start = i + 1
        }
        i++
    }
    return false
}

// The argument positions a callee writes, comma-separated, the receiver at 0: the mask a
// method record carries (`IlMethod.writes`). The borrow fixpoint's table is the source -
// proved bodies and the author's `mutates` marks (`bpWritesNames`, the receiver seeded at
// 0 exactly as this mask spells it). A name the table does not carry - a machine's
// generated step, a native without a mark, a body the fixpoint could not attribute -
// answers with the callee's own signature: a position whose declared parameter is a handle
// is written (the machine contract's `tryAdvance(current: *TItem)` writes its out slot
// whatever the argument's own slot type spells), then narrowed by the marks - a `mutates`
// mark to the receiver, and a callee proved to write nothing to none. No declaration at
// all, the argument types are what is left: a handle-typed position is written.
fun ilWritesOf(name: *Str, argTypes: *List<Int>, il: *IlBody, target: *AstXmlNode): Str {
    val proved: *Str = bpWritesNames.getPtr(name)
    if (proved != null) {
        return *proved
    }
    if (bpTrustedNames.has(name) || bpResultTrusted.has(name)) {
        return ""
    }
    if (bpMutatesNames.has(name)) {
        if (argTypes.size() > 0) {
            return "0"
        }
        return ""
    }
    var fromDecl: Str = ""
    if (!xmlIsEmpty(target)) {
        // The receiver is position 0 over a `Method` record; the parameters follow. The
        // declaration's own parameter type is the callee's word on what it writes through.
        var position: Int = 0
        val receiver: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Receiver)
        if (!xmlIsEmpty(receiver)) {
            val typeNode: *AstXmlNode = xmlChildPtr(receiver, AstNodeKind.Type)
            if (!xmlIsEmpty(typeNode) && ilDeclParamIsHandle(typeNode)) {
                fromDecl = "0"
            }
            position = 1
        } else if (semReceiverParams(target) > 0) {
            // A `this:`-first receiver: the first Param child is the receiver.
            val first: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Param)
            if (!xmlIsEmpty(first)) {
                val typeNode: *AstXmlNode = xmlChildPtr(first, AstNodeKind.Type)
                if (!xmlIsEmpty(typeNode) && ilDeclParamIsHandle(typeNode)) {
                    fromDecl = "0"
                }
            }
            position = 1
        }
        val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
        var p: Int = semReceiverParams(target)
        while (p < params.size()) {
            val typeNode: *AstXmlNode = xmlChildPtr(params[p], AstNodeKind.Type)
            if (!xmlIsEmpty(typeNode) && ilDeclParamIsHandle(typeNode)) {
                if (!fromDecl.isEmpty()) {
                    fromDecl.append(',')
                }
                fromDecl.appendStr(position.toString())
            }
            p++
            position++
        }
        if (!fromDecl.isEmpty()) {
            return fromDecl
        }
    }
    var mask: Str = ""
    var i: Int = 0
    while (i < argTypes.size()) {
        val text: Str = ilTypeName(il, argTypes[i])
        if (text.size() > 0 && (text[0] == '*' || text[0] == '&')) {
            if (!mask.isEmpty()) {
                mask.append(',')
            }
            mask.appendStr(i.toString())
        }
        i++
    }
    return mask
}

// Whether a declaration's parameter type is a handle: the callee may write through it.
fun ilDeclParamIsHandle(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    return kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference
}

// Whether this call may move the buffer of what it writes: the resolved declaration's own
// `grows` mark (`bpGrowsMark`), where there is one - the Str `initByValue` carries it and
// the union's does not, a distinction the name-level table cannot make - else the
// name-level closure (`bpGrowsNames`), which a name shared with a growing declaration
// answers yes to.
fun IlExtractor.ilCallGrows(name: *Str, target: *AstXmlNode): Bool {
    if (!xmlIsEmpty(target)) {
        return bpGrowsMark(target)
    }
    return bpGrowsNames.has(name)
}

// The method record a call-like op names, or null (`CallIndirect` names a slot, and a
// construction names a type - neither carries a mask).
fun ilMethodOf(il: *IlBody, op: *IlOp): *IlMethod {
    if (op.kind != IlOpKind.Call && op.kind != IlOpKind.CallVoid) {
        return null
    }
    val at: Int = ilReuseMethodIndex(op)
    if (at < 0 || at >= il.methods.size()) {
        return null
    }
    return *il.methods[at]
}

// Whether operand `index` of `op` is an `Out` position: a call argument whose position the
// method record's mask names. The receiver of a `Method` record is position 0, and the
// record's `argTypes` index operands the same way (`fullTypes` appends the receiver first),
// so an argument's position is its distance from the first one.
fun ilOperandIsOut(il: *IlBody, op: *IlOp, index: Int): Bool {
    val method: *IlMethod = ilMethodOf(il, op)
    if (method == null) {
        return false
    }
    val base: Int = ilReuseArgBase(op.kind)
    if (index < base) {
        return false
    }
    return ilWritesHas(method.writes, index - base)
}

// What operand `index` of `op` is, the mask consulted: a `Value` position the callee
// writes is `Out` (impl_specs/linear-form-plan.md, step 3). Everything else is the
// signature table's answer.
fun ilOperandKindIn(il: *IlBody, op: *IlOp, index: Int): IlOperandKind {
    if (ilOperandIsOut(il, op, index)) {
        return IlOperandKind.Out
    }
    return ilOperandKind(op, index)
}

// An operand read without trusting the arity: out of range shows up as -1.
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

// A `Var`-position operand as the dump shows it: the slot's name, or a literal's text when
// the operand is a negative index.
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

// A pool entry as the dump shows it: a literal already has quotes; a name is quoted.
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
    return `"@text"`
}

// The operand as the reader wants it: the name from the table it indexes.
fun ilRenderOperand(body: *IlBody, kind: IlOperandKind, value: Int): Str {
    when (kind) {
        IlOperandKind.Var, IlOperandKind.Value, IlOperandKind.Out -> {
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
        i++
    }
    return joinStrs(args, ", ")
}

fun ilPadRight(text: Str, width: Int): Str {
    var out: Str = text
    while (out.size() < width) {
        out.append(' ')
    }
    return out
}

