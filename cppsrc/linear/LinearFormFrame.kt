// LinearFormFrame.kt
//
// `IlExtractor`'s frame and tables: slots, types, methods, labels, the type context, and
// `buildFrame`/`run`. Extension methods on `IlExtractor` (LinearForm.kt).

package linear

import common
import sema

fun IlExtractor.addVar(name: *Str, typeText: *Str, kind: IlVarKind, typeNode: *AstXmlNode): Int {
    this.out.vars.append(IlVar(name, this.typeIndex(typeText, typeNode), kind))
    this.varAt.insert(name, this.out.vars.size() - 1)
    // The frame the type questions read is kept in place: it is only ever appended to.
    this.frameAdd(name, typeNode)
    return this.out.vars.size() - 1
}

// Give a slot a type after the fact: a list literal's destination knows the type it is
// building even when the position would only have inferred it.
fun IlExtractor.setSlotType(slot: Int, typeNode: AstXmlNode): Unit {
    if (slot < 0 || slot >= this.out.vars.size() || xmlIsEmpty(typeNode)) {
        return
    }
    this.out.vars[slot].typeIndex = this.typeIndex(ilTypeText(typeNode), typeNode)
    this.frameAdd(this.out.vars[slot].name, typeNode)
}

// The type table: text for the dump, the node for a backend. The first node for a text wins.
fun IlExtractor.typeIndex(text: *Str, node: *AstXmlNode): Int {
    val found: *Int = this.typeAt.getPtr(text)
    if (found != null) {
        val index: Int = *found
        if (!xmlIsEmpty(node) && index < this.out.typeNodes.size()) {
            val existing: *AstXmlNode = *this.out.typeNodes[index]
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

fun IlExtractor.typeIndexText(text: *Str): Int {
    return this.typeIndex(text, xmlEmptyNode())
}

fun IlExtractor.poolIndex(text: *Str): Int {
    val found: *Int = this.poolAt.getPtr(text)
    if (found != null) {
        return *found
    }
    this.out.pool.append(text)
    this.poolAt.insert(text, this.out.pool.size() - 1)
    return this.out.pool.size() - 1
}

fun IlExtractor.labelIndex(name: *Str): Int {
    val found: *Int = this.labelAt.getPtr(name)
    if (found != null) {
        return *found
    }
    this.out.labels.append(name)
    this.labelAt.insert(name, this.out.labels.size() - 1)
    return this.out.labels.size() - 1
}

// A method's identity is its name, kind, static base and argument types, so the same name
// over two receivers is two entries.
fun IlExtractor.methodIndex(
    name: *Str, kind: IlMethodKind, staticBase: Int, returnType: Int,
    argTypes: *List<Int>, recvIsValue: Bool
): Int {
    var key: Str = name + "|" + ilMethodKindText(kind) + "|" + ilIntText(staticBase)
    var i: Int = 0
    while (i < argTypes.size()) {
        key = key + "|" + ilIntText(argTypes[i])
        i = i + 1
    }
    val found: *Int = this.methodAt.getPtr(key)
    if (found != null) {
        return *found
    }
    this.out.methods.append(
        IlMethod(name, kind, argTypes.size(), staticBase, returnType, argTypes, recvIsValue)
    )
    this.methodAt.insert(key, this.out.methods.size() - 1)
    return this.out.methods.size() - 1
}

fun IlExtractor.hasVar(name: *Str): Bool {
    return this.varAt.has(name)
}

fun IlExtractor.varIndex(name: *Str): Int {
    val found: *Int = this.varAt.getPtr(name)
    if (found != null) {
        return *found
    }
    return -1
}

fun IlExtractor.freshSlot(typeText: *Str, typeNode: *AstXmlNode): Int {
    val slot: Int = this.addVar(
        "_sm_base" + ilIntText(this.nextBase), typeText,
        IlVarKind.Temp, typeNode
    )
    this.nextBase = this.nextBase + 1
    if (!xmlIsEmpty(typeNode)) {
        this.hoisted.append(slot)
        this.hoistedLines.append(this.line)
    } else {
        // A slot the type rules could not name is declared here, in front of its first write.
        this.emit(IlOpKind.Declare, ilOps1(slot))
    }
    return slot
}

fun IlExtractor.freshSlotText(typeText: Str): Int {
    return this.freshSlot(typeText, xmlEmptyNode())
}

// The context `semTypeOfExpr` reads, and the frame as it wants to see it. Both are
// constant for a body except when a slot is added, so they are built once and reused.
fun IlExtractor.frameChanged(): Unit {
    this.frameNamesStale = true
}

// One new (or newly typed) binding. An unbuilt frame stays unbuilt; a built one takes the
// single entry (`addVar`/`setSlotType` only ever add).
fun IlExtractor.frameAdd(name: *Str, typeNode: *AstXmlNode): Unit {
    if (this.frameNamesStale || xmlIsEmpty(typeNode)) {
        return
    }
    this.frameNames.insert(name, typeNode)
}

// The borrowed context every question about this body is asked with; written once, on the
// first question.
fun IlExtractor.bodyContext(): *SemBody {
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
        // A machine method or a lambda body: `this` is the class instance the lowering built.
        this.typeContext.selfType = ilNamedTypeNode(this.fn.closureSymbol)
    }
    this.typeContextReady = true
    return this.typeContext
}

// The frame as a scope: every slot's name and type, the shape `semTypeOfExpr` takes. A slot
// without one cannot be declared, so its reader inlines the expression it stands for.
fun IlExtractor.frameTypes(): *Dictionary<Str, AstXmlNode> {
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

// The type of an expression, from the rules the *type pass* applies to a whole body, asked
// about one node with this frame in scope. Empty when the rules cannot name it.
fun IlExtractor.exprType(e: *AstXmlNode): AstXmlNode {
    if (this.fn.facts == null) {
        return xmlEmptyNode()
    }
    return semTypeOfExpr(e, this.fn.facts, this.bodyContext(), this.frameTypes())
}

// The type of the value an instruction writes for this expression: the rules' answer,
// unchanged.
fun IlExtractor.valueType(e: *AstXmlNode): AstXmlNode {
    return this.exprType(e)
}

// A synthesized slot's type, spelled as the frame does, or `?` when there is none.
fun IlExtractor.slotTypeText(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return "?"
    }
    return ilTypeText(typeNode)
}

fun IlExtractor.emit(kind: IlOpKind, operands: *List<Int>): Unit {
    this.out.ops.append(IlOp(kind, operands))
    this.out.lines.append(this.line)
}

fun IlExtractor.unsupported(what: Str): Unit {
    this.emit(IlOpKind.Unsupported, ilOps2(this.freshSlotText("?"), this.poolIndex(what)))
}

fun IlExtractor.literalOperand(text: *Str): Int {
    return -1 - this.poolIndex(text)
}

fun IlExtractor.begin(file: *Str): Unit {
    this.out.file = file
    if (xmlIsEmpty(this.fn.decl)) {
        this.out.line = 0
    } else {
        this.out.line = xmlLine(this.fn.decl)
    }
    this.out.symbol = this.fn.symbol
    // The types the enclosing pass proved, so the frame carries slots a declaration could
    // not name (a `..T` machine). Both are keyed by name.
    val declaredTypes: List<Str> = this.fn.inferredTypes.keys()
    var ti: Int = 0
    while (ti < declaredTypes.size()) {
        this.out.inferredTypes.insert(
            declaredTypes[ti], *this.fn.inferredTypes.getPtr(declaredTypes[ti])
        )
        ti = ti + 1
    }
    this.buildFrame()
    this.out.signature = this.signatureText()
}

fun IlExtractor.run(body: *List<AstXmlNode>): IlBody {
    this.stmts(body)
    // The extractor's own slots are the body's registers: a typed one is declared once at
    // the top of the instruction list, so the frame is flat and no jump crosses a
    // declaration. An untyped one keeps its declaration in front of the instruction that
    // first writes it.
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

fun IlExtractor.buildFrame(): Unit {
    if (!this.fn.closureSymbol.isEmpty()) {
        // A lambda (or a machine's method): the receiver is the class instance whose
        // fields the captures are.
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
        val typeNode: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
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

fun IlExtractor.signatureText(): Str {
    var params: List<Str> = List<Str>()
    for (*slot in this.out.vars) {
        if (slot.kind == IlVarKind.Argument) {
            params.append(fmtStr("| |", this.out.types[slot.typeIndex], slot.name))
        }
    }
    // A lambda's result is what its body returns; a function's is its declared type.
    var retText: Str = "?"
    if (!xmlIsEmpty(this.fn.decl)) {
        val declared: *AstXmlNode = xmlChildPtr(this.fn.decl, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(declared)) {
            retText = ilTypeText(declared)
        }
    }
    return fmtStr("(|) -> |", joinStrs(params, ", "), retText)
}

