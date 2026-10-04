// LinearFormStmt.kt
//
// `IlExtractor`'s statement walk: statements, assignments and the operand/place readers.
// Extension methods on `IlExtractor` (LinearForm.kt); LinearFormFrame.kt has the frame.

package linear

import compiler

import common
import sema

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
            } else if (this.isUnitValue(value)) {
                // `return println(x)` (or any call that answers nothing): the call is the
                // statement and the return answers nothing - a `void` slot is not a value
                // the emitter can declare.
                this.call(-1, value)
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
            // The block's statements, walked as places (`for (*child in ...)` is the span walk
            // `spanOfArray(...).iter()` spells): the copy this used to build was one of the
            // extractor's largest list sources, and every `AstXmlNode` copied deep-copies its
            // attributes.
            val container: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Body)
            for (*child in container.Children) {
                this.statement(child)
            }
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

// Whether an expression answers nothing (`Unit`): a declared `Unit` result, or one of the
// two builtins the emitter lowers with no value at all (`print`/`println`). `flat` keeps a
// void call in place (`exprIsVoidCall`), so the extractor sees it here.
fun IlExtractor.isUnitValue(expr: *AstXmlNode): Bool {
    if (exprIsVoidCall(expr)) {
        return true
    }
    val proven: AstXmlNode = this.valueType(expr)
    if (xmlIsEmpty(proven)) {
        return false
    }
    return ilTypeText(proven) == "Unit"
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
                val baseText: Str = this.baseText(lhs)
                this.emit(
                    IlOpKind.SetStatic, ilOps2(
                        this.poolIndex(`@baseText.@fieldName`),
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
                val baseText: Str = this.baseText(lhs)
                val field: Int = this.poolIndex(`@baseText.@fieldName`)
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
            return this.lambdaOf(expr, -1, xmlEmptyNode(), xmlEmptyNode())
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
                val baseText: Str = this.baseText(lhs)
                this.emit(
                    IlOpKind.GetStatic,
                    ilOps2(slot, this.poolIndex(`@baseText.@fieldName`))
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
                && !this.isOperatorIndex(operand)
            ) {
                // A chain that is a place: the address *is* the place slot. (A call's result is not a
                // place.) A deref's slot is the pointer it reads through (`receiverOf`), so `*(*p)`
                // is `p` - never the address of a copy of the pointee, which is what an argument
                // converted to a `*T` parameter used to build. An operator index is not a place:
                // the value slot below is addressed instead.
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
            // A lambda whose value is dropped still constructs its class; a destination with a
            // type (`val f: F = lambda`) states the callable type it converts into.
            this.lambdaOf(e, slot, ilVarTypeNode(this.out, slot), xmlEmptyNode())
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

// Whether the index's receiver is a raw pointer (`p[i]`): the element is the pointee and
// the index is a place at that address, whatever the rules can name it.
fun IlExtractor.isPointerIndex(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprIndex) {
        return false
    }
    val recvType: AstXmlNode = this.exprType(xmlChildPtr(e, AstNodeKind.Receiver))
    return !xmlIsEmpty(recvType) && xmlKind(recvType) == AstNodeCategory.TypePointer
}

// The type through a `typealias` (`StrView` is `Span<Char>`): a receiver pattern matches
// the alias's target, the way the emitter's `resolveAlias` does before its own match.
fun ilAliasTarget(facts: *SemFacts, typeNode: AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (xmlKind(current) == AstNodeCategory.TypeNamed) {
        guard = guard + 1
        if (guard >= 100) {
            break
        }
        val decl: *AstXmlNode = facts.types.getPtr(xmlAttr(current, AstNodeAttributeKind.Name))
        if (decl == null || decl.name != AstNodeKind.TypeAlias) {
            break
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            break
        }
        current = *target
    }
    return current
}

// Whether `e[i]` reads through an `operator get` (specs/functions.md): the result is the
// getter's *value* - a temporary - not a place into the receiver, so the address-of paths
// must not take its address. `set` needs no such check here: an index write is lowered
// base-and-index, never by taking the whole index's address.
fun IlExtractor.isOperatorIndex(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprIndex) {
        return false
    }
    val recvType: AstXmlNode = this.exprType(xmlChildPtr(e, AstNodeKind.Receiver))
    if (xmlIsEmpty(recvType)) {
        return false
    }
    val resolved: AstXmlNode = ilAliasTarget(this.fn.facts, recvType)
    for (*fn in this.fn.facts.functions) {
        if (fn.name != "get" || fn.paramCount != 1 || fn.isNative) {
            continue
        }
        if (xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true") {
            continue
        }
        // Bound first: the matcher takes pointers, and `*` of a field of a pointed-to fact
        // is the copy trap (`agents.md`).
        val pattern: AstXmlNode = fn.receiver
        val params: List<Str> = fn.templateParams
        if (semaUnifyReceiver(*pattern, *resolved, *params)) {
            return true
        }
    }
    return false
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
            // An index through `operator get` is a value, not a place into the receiver
            // (specs/functions.md): its address does not exist, so it is read as a value.
            // A raw-pointer index is always a place, even when the element's type has no
            // name the rules can spell (`atPtr` over `Span<T>`'s `*T ptr`).
            if (this.isOperatorIndex(e) || (!this.needsPlace(e) && !this.isPointerIndex(e))) {
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
            typeText = * staticType
        }
    } else {
        typeText = ilTypeText(typeNode)
    }
    val fresh: Int = this.freshSlot(typeText, typeNode)
    this.emit(IlOpKind.GetStatic, ilOps2(fresh, this.poolIndex(name)))
    return fresh
}
