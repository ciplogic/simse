// LinearFormSig.kt
//
// The IL's signature table and operand spellings: what each op kind is, which operands are
// writes, and the text a dump shows.

package linear
import compiler

import common
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
        IlSignature(IlOpKind.Unsupported, "Var,Text")
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
            return fmtStr("|<|>", name, joinStrs(args, ", "))
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
    }
    return "*" + ilTypeText(typeNode)
}

// The rule the table leaves implicit: the first `Var` operand of an op that produces a
// value is where the value goes.
fun ilWritesDestination(kind: IlOpKind): Bool {
    // Written out rather than derived: deriving it needs to know which operands produce a value.
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
    return fmtStr("\"|\"", text)
}

// The operand as the reader wants it: the name from the table it indexes.
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
    return joinStrs(args, ", ")
}

fun ilPadRight(text: Str, width: Int): Str {
    var out: Str = text
    while (out.size() < width) {
        out.append(' ')
    }
    return out
}

