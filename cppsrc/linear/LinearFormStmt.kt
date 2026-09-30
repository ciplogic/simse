// LinearFormStmt.kt
//
// `IlExtractor`'s frame setup, statement walk and the operand/place readers. Extension
// methods on `IlExtractor` (LinearForm.kt has the class and its fields).

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
    return fmtStr("(|) -> |", ilJoinList(params, ", "), retText)
}

fun IlExtractor.stmts(list: *List<AstXmlNode>): Unit {
    for (*stmt in list) {
        this.statement(stmt)
    }
}

fun IlExtractor.statement(stmt: *AstXmlNode): Unit {
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
            val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
            this.emit(
                ilCategoryName(kind), ilOps2(
                    this.operandOf(cond),
                    this.labelIndex(xmlAttr(stmt, AstNodeAttributeKind.Name))
                )
            )
            return
        }

        AstNodeCategory.StmtVarDecl -> {
            val typeNode: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
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
            val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
            if (xmlIsEmpty(init)) {
                this.emit(IlOpKind.Declare, ilOps1(slot))
            } else {
                this.emit(IlOpKind.DeclareInit, ilOps1(slot))
                if (xmlAttr(stmt, AstNodeAttributeKind.InitByValue) == "true") {
                    // `var x = T(a)`, a construction: the default-built `x` is set by
                    // `T.initByValue` (the type pass marks the declaration).
                    val setter: AstXmlNode = this.ilInitByValueStmt(name, init)
                    if (xmlIsEmpty(setter)) {
                        this.into(slot, init)
                    } else {
                        this.call(-1, setter)
                    }
                } else {
                    this.into(slot, init)
                }
            }
            return
        }

        AstNodeCategory.StmtAssign -> {
            this.assign(stmt, xmlChildPtr(stmt, AstNodeKind.Target), xmlChildPtr(stmt, AstNodeKind.Value))
            return
        }

        AstNodeCategory.StmtReturn -> {
            val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
            if (xmlIsEmpty(value)) {
                this.emit(IlOpKind.ReturnVoid, List<Int>())
            } else {
                this.emit(IlOpKind.Return, ilOps1(this.operandOf(value)))
            }
            return
        }

        AstNodeCategory.StmtExprStmt -> {
            val expr: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Expr)
            if (xmlKind(expr) == AstNodeCategory.ExprCall) {
                this.call(-1, expr)
                return
            }
            // A read with no destination: keep it visible rather than dropping it.
            this.unsupported("expression statement")
            return
        }

        AstNodeCategory.StmtBlock -> {
            val container: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Body)
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

fun IlExtractor.assign(stmt: *AstXmlNode, target: *AstXmlNode, value: *AstXmlNode): Unit {
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
            val lhs: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Receiver)
            val fieldName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            if (this.isTypeBase(lhs)) {
                this.emit(
                    IlOpKind.SetStatic, ilOps2(
                        this.poolIndex(fmtStr("|.|", this.baseText(lhs), fieldName)),
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
                    this.receiverOf(xmlChildPtr(target, AstNodeKind.Receiver)),
                    this.operandOf(xmlChildPtr(target, AstNodeKind.Index)),
                    this.operandOf(value)
                )
            )
            return
        }

        AstNodeCategory.ExprDeref -> {
            // `*p = v`.
            this.emit(
                IlOpKind.Store, ilOps2(
                    this.operandOf(xmlChildPtr(target, AstNodeKind.Operand)),
                    this.operandOf(value)
                )
            )
            return
        }
    }
    this.unsupported("assignment target")
}

// `x op= v` (and `x++`/`x--`, the parser's `x += 1`/`x -= 1`): the target's place is located
// once, its value read out of it, folded and written back through it - so an index with a
// side effect runs once and a write cannot land in a copy.
fun IlExtractor.assignCompound(target: *AstXmlNode, op: Str, value: *AstXmlNode): Unit {
    val targetKind: AstNodeCategory = xmlKind(target)
    when (targetKind) {
        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            // A captured variable lives in the closure's object: read and write its field.
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
            // A local slot *is* the place: `i = i + 1` is one instruction.
            val slot: Int = this.varIndex(name)
            if (slot >= 0) {
                this.emit(
                    IlOpKind.BinaryOp, ilOps4(
                        slot, this.poolIndex(op), slot, this.operandOf(value)
                    )
                )
                return
            }
            // A file-level `var` lives in static storage: read, fold, write back through the name.
            val staticName: Int = this.poolIndex(name)
            val current: Int = this.readStatic(target, staticName)
            this.emit(
                IlOpKind.SetStatic,
                ilOps2(staticName, this.fold(current, op, value, target))
            )
            return
        }

        AstNodeCategory.ExprMember -> {
            val lhs: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Receiver)
            val fieldName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            if (this.isTypeBase(lhs)) {
                val field: Int = this.poolIndex(fmtStr("|.|", this.baseText(lhs), fieldName))
                val current: Int = this.readStatic(target, field)
                this.emit(IlOpKind.SetStatic, ilOps2(field, this.fold(current, op, value, target)))
                return
            }
            // The base is located once; the read and the write both name the field on it.
            val base: Int = this.receiverOf(lhs)
            val field: Int = this.poolIndex(fieldName)
            val current: Int = this.readField(target, base, field)
            this.emit(IlOpKind.SetField, ilOps3(base, field, this.fold(current, op, value, target)))
            return
        }

        AstNodeCategory.ExprIndex -> {
            // The base and the index are evaluated once, into operands the read and the write share.
            val base: Int = this.receiverOf(xmlChildPtr(target, AstNodeKind.Receiver))
            val index: Int = this.operandOf(xmlChildPtr(target, AstNodeKind.Index))
            val current: Int = this.freshValueSlot(target)
            this.emit(IlOpKind.GetIndex, ilOps3(current, base, index))
            this.emit(IlOpKind.SetIndex, ilOps3(base, index, this.fold(current, op, value, target)))
            return
        }

        AstNodeCategory.ExprDeref -> {
            // `*p += 1`: the pointer *is* the place; the load and the store both go through it.
            val pointer: Int = this.operandOf(xmlChildPtr(target, AstNodeKind.Operand))
            val current: Int = this.freshValueSlot(target)
            this.emit(IlOpKind.Deref, ilOps2(current, pointer))
            this.emit(IlOpKind.Store, ilOps2(pointer, this.fold(current, op, value, target)))
            return
        }
    }
    this.unsupported("compound assignment target")
}

// The current value of a field, as one instruction; the base is the one the write uses.
fun IlExtractor.readField(whole: *AstXmlNode, base: Int, field: Int): Int {
    val slot: Int = this.freshValueSlot(whole)
    this.emit(IlOpKind.GetField, ilOps3(slot, base, field))
    return slot
}

fun IlExtractor.readStatic(whole: *AstXmlNode, name: Int): Int {
    val slot: Int = this.freshValueSlot(whole)
    this.emit(IlOpKind.GetStatic, ilOps2(slot, name))
    return slot
}

// `current <op> value`, in a slot of the target's own type.
fun IlExtractor.fold(current: Int, op: Str, value: *AstXmlNode, whole: *AstXmlNode): Int {
    val slot: Int = this.freshValueSlot(whole)
    this.emit(IlOpKind.BinaryOp, ilOps4(slot, this.poolIndex(op), current, this.operandOf(value)))
    return slot
}

// A slot for the value of `e`, typed the way `valueOf` types its slots.
fun IlExtractor.freshValueSlot(e: *AstXmlNode): Int {
    val typeNode: AstXmlNode = this.valueType(e)
    return this.freshSlot(this.slotTypeText(typeNode), typeNode)
}

// The type of the value in `slot`, which came from `e`: a place already carries one, so the
// common operand costs a lookup; anything untyped is the rules' expensive question.
fun IlExtractor.operandValueType(slot: Int, e: *AstXmlNode): AstXmlNode {
    val carried: AstXmlNode = ilVarType(this.out, slot)
    if (!xmlIsEmpty(carried)) {
        return carried
    }
    return this.exprType(e)
}

// One operand of a binary operation: read through the handle it is, because the operation
// is on *values* (`out + separator` with `separator: *Str` is `out + *separator`; the
// `*T -> T` row of impl_specs/linear-il.md). A handle against a different type, or against
// `null`, is left alone.
fun IlExtractor.binaryOperand(me: *AstXmlNode, other: *AstXmlNode, slot: Int): Int {
    val mine: AstXmlNode = this.operandValueType(slot, me)
    if (xmlIsEmpty(mine) || !ilIsHandleType(mine)) {
        return slot
    }
    val theirs: AstXmlNode = this.exprType(other)
    if (xmlIsEmpty(theirs)) {
        return slot
    }
    val pointee: AstXmlNode = semPointeeOf(mine)
    val otherValue: AstXmlNode = semPointeeOf(theirs)
    if (xmlIsEmpty(pointee) || xmlIsEmpty(otherValue)
        || ilTypeText(pointee) != ilTypeText(otherValue)
    ) {
        return slot
    }
    return this.readThrough(pointee, slot)
}

// The value `expr` produces, as an operand: a frame slot, a literal (a negative operand),
// or - when the position holds more than a name - a slot the extractor synthesizes.
fun IlExtractor.operandOf(expr: *AstXmlNode): Int {
    val kind: AstNodeCategory = xmlKind(expr)
    when (kind) {
        AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit,
        AstNodeCategory.ExprStrLit, AstNodeCategory.ExprCharLit -> {
            // A literal in a value position rides the instruction as an operand: the pool holds
            // the token's own text, so a backend prints `i > 0` and not a slot.
            return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Text))
        }

        AstNodeCategory.ExprBoolLit -> {
            return this.literalOperand(xmlAttr(expr, AstNodeAttributeKind.Value))
        }

        AstNodeCategory.ExprNullLit -> {
            // `null`'s spelling depends on the expected type, which only the destination slot's
            // type states for certain - so it is materialised, untyped, and the backend inlines it.
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
            // A value the body does not hold in a slot: the extractor makes one and the *type rules*
            // type it, so the instruction names a declared slot instead of inlining the expression.
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

// Writes `e` into `slot`, one instruction per operation: a value the lowering produces is
// one operation deep.
fun IlExtractor.into(slot: Int, e: *AstXmlNode): Unit {
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
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
            val fieldName: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (this.isTypeBase(lhs)) {
                this.emit(
                    IlOpKind.GetStatic,
                    ilOps2(slot, this.poolIndex(fmtStr("|.|", this.baseText(lhs), fieldName)))
                )
                return
            }
            this.emit(IlOpKind.GetField, ilOps3(slot, this.receiverOf(lhs), this.poolIndex(fieldName)))
            return
        }

        AstNodeCategory.ExprIndex -> {
            this.emit(
                IlOpKind.GetIndex, ilOps3(
                    slot, this.receiverOf(xmlChildPtr(e, AstNodeKind.Receiver)),
                    this.operandOf(xmlChildPtr(e, AstNodeKind.Index))
                )
            )
            return
        }

        AstNodeCategory.ExprBinary -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Lhs)
            val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
            this.emit(
                IlOpKind.BinaryOp, ilOps4(
                    slot, this.poolIndex(xmlAttr(e, AstNodeAttributeKind.Op)),
                    this.binaryOperand(lhs, rhs, this.operandOf(lhs)),
                    this.binaryOperand(rhs, lhs, this.operandOf(rhs))
                )
            )
            return
        }

        AstNodeCategory.ExprUnary -> {
            this.emit(
                IlOpKind.UnaryOp, ilOps3(
                    slot, this.poolIndex(xmlAttr(e, AstNodeAttributeKind.Op)),
                    this.operandOf(xmlChildPtr(e, AstNodeKind.Operand))
                )
            )
            return
        }

        AstNodeCategory.ExprRef -> {
            val operand: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
            if (this.isBoxedConstruction(operand)) {
                // `&Ctor(args)` is one box built in place: the construction's own
                // destination is the `&C` slot, which the backend spells
                // `makeRef<C>(args...)`. No temporary, so no destructor runs on one.
                this.call(slot, this.asConstruction(operand))
                return
            }
            this.emit(IlOpKind.Box, ilOps2(slot, this.operandOf(operand)))
            return
        }

        AstNodeCategory.ExprDeref -> {
            // `*x` is the address of what `x` denotes: of a value's own storage (or `&name`), of a
            // counted reference's pointee (`.get()`), or the load through a raw pointer. A place that
            // is a chain already *is* its address, so it is copied; other forms are read first.
            val operand: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
            val operandName: Str = xmlAttr(operand, AstNodeAttributeKind.Name)
            if (xmlKind(operand) == AstNodeCategory.ExprName && this.isStaticName(operandName)) {
                // A file-level static is not a frame slot, so its address is its own instruction -
                // writing it into a slot first would take the address of a copy.
                this.emit(IlOpKind.GetStaticAddr, ilOps2(slot, this.poolIndex(operandName)))
                return
            }
            if ((xmlKind(operand) == AstNodeCategory.ExprMember
                        || xmlKind(operand) == AstNodeCategory.ExprIndex
                        || xmlKind(operand) == AstNodeCategory.ExprDeref)
                && !this.isHandleExpr(operand)
            ) {
                // A chain that is a place: the address *is* the place slot. (A call's result is not a
                // place.) A deref's slot is the pointer it reads through (`receiverOf`), so `*(*p)`
                // is `p` - never the address of a copy of the pointee, which is what an argument
                // converted to a `*T` parameter used to build.
                this.emit(IlOpKind.SetVar, ilOps2(slot, this.receiverOf(operand)))
                return
            }
            this.emit(IlOpKind.Deref, ilOps2(slot, this.valueOf(operand)))
            return
        }

        AstNodeCategory.ExprCopy -> {
            this.emit(IlOpKind.CopyValue, ilOps2(slot, this.operandOf(xmlChildPtr(e, AstNodeKind.Operand))))
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

// A read of a place: the value behind it, as a slot, read through `receiverOf` - so
// reading `a[i].f` borrows the element rather than copying it into a temporary.
fun IlExtractor.valueOf(e: *AstXmlNode): Int {
    if (xmlKind(e) == AstNodeCategory.ExprName) {
        return this.nameOf(e)
    }
    val typeNode: AstXmlNode = this.valueType(e)
    val slot: Int = this.freshSlot(this.slotTypeText(typeNode), typeNode)
    this.into(slot, e)
    return slot
}

// Whether an expression's *value* is already a handle (`&T`/`*T`/`PList`): such a value
// denotes storage, so it *is* the place - its address would be the address of the pointer.
fun IlExtractor.isHandleExpr(e: AstXmlNode): Bool {
    val typeNode: AstXmlNode = this.exprType(e)
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    return ilIsHandleType(typeNode)
}

// Whether a base needs an explicit address instruction: it is an inline value of a type the
// rules can name. A handle (or an unnameable type) keeps the value form.
fun IlExtractor.needsPlace(e: *AstXmlNode): Bool {
    val typeNode: AstXmlNode = this.exprType(e)
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    return !ilIsHandleType(typeNode)
}

// The address of an inline value's storage, as one instruction: the slot holds the pointer,
// typed from the rules. A place is never a copy.
fun IlExtractor.place(
    kind: IlOpKind, baseExpr: *AstXmlNode, whole: *AstXmlNode, field: *Str,
    indexExpr: *AstXmlNode
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

// The address of a file-level static (`&ns1_names`), never a copy of it.
fun IlExtractor.staticAddr(e: AstXmlNode): Int {
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

// A base through which something is reached. A plain name *is* that base; a handle value is
// one too; an inline value must have its address taken - what `FieldAddr`/`IndexAddr` are -
// which is what keeps a read from copying an aggregate and a write from being lost.
fun IlExtractor.receiverOf(e: *AstXmlNode): Int {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprName -> {
            // A file-level static is not a frame slot: as a base it is its own *address*, so a call
            // that mutates it reaches the storage (`names.append(x)` appends to `names`).
            if (this.isStaticName(xmlAttr(e, AstNodeAttributeKind.Name))) {
                return this.staticAddr(e)
            }
            return this.nameOf(e)
        }

        AstNodeCategory.ExprMember -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
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
                IlOpKind.IndexAddr, xmlChildPtr(e, AstNodeKind.Receiver), e, Str(),
                xmlChildPtr(e, AstNodeKind.Index)
            )
        }

        AstNodeCategory.ExprDeref -> {
            return this.operandOf(xmlChildPtr(e, AstNodeKind.Operand))
        }
    }
    return this.valueOf(e)
}

fun IlExtractor.nameOf(e: *AstXmlNode): Int {
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    // A captured variable is a field of the closure, not a frame slot: read through `self`.
    if (this.fn.captures.has(name)) {
        var text: Str = "?"
        var captureNode: AstXmlNode = xmlEmptyNode()
        val capturedType: *AstXmlNode = this.fn.captureTypes.getPtr(name)
        if (capturedType != null) {
            // The node is read through the pointer: the table is not copied from.
            captureNode = *capturedType
            text = ilTypeText(capturedType)
        }
        val slot: Int = this.freshSlot(text, captureNode)
        this.emit(IlOpKind.GetField, ilOps3(slot, this.varIndex("self"), this.poolIndex(name)))
        return slot
    }
    if (name == "this") {
        // Not `self`: a receiver's slot is `self`, so a local of that name would shadow it.
        val selfSlot: Int = this.varIndex("self")
        if (selfSlot >= 0) {
            return selfSlot
        }
    }
    val slot: Int = this.varIndex(name)
    if (slot >= 0) {
        return slot
    }
    // A file-level static, or a name only the backend can resolve (`GetStatic` either way). The
    // slot carries a type, so the backend declares it with the frame.
    val typeNode: AstXmlNode = this.exprType(e)
    var typeText: Str = "?"
    if (xmlIsEmpty(typeNode)) {
        val staticType: *Str = this.fn.statics.getPtr(name)
        if (staticType != null) {
            typeText = *staticType
        }
    } else {
        typeText = ilTypeText(typeNode)
    }
    val fresh: Int = this.freshSlot(typeText, typeNode)
    this.emit(IlOpKind.GetStatic, ilOps2(fresh, this.poolIndex(name)))
    return fresh
}

