// IlEmit.kt
//
// Emitting the IL: the per-op text (`ilEmitOps`), the closure and machine classes and the
// task methods. `Emitter` extension functions; IlCodeGen.kt holds the IL model and walks,
// IlConcat.kt the concatenation expansion.

package codegen

import sema
import common
import io
import linear
import optimizations
import profiling

fun Emitter.ilLine(out: *Str, level: Int, text: Str): Unit {
    out.appendStr(cgIndent(level))
    out.appendStr(text)
    out.append('\n')
}

// One body, instruction by instruction: each instruction is one statement of the
// emitted C++ (a `Declare` pairs with the instruction that writes it).
fun Emitter.ilEmitOps(il: *IlBody, frame: *IlFrame, level: Int): IlText {
    // A declaration a jump can cross needs a block of its own.
    var blockEnd: Dictionary<Int, IlCrossing> = Dictionary<Int, IlCrossing>()
    // Both halves of the crossing: where the block ends (here) and the last jump that
    // crosses (below, when the block opens).
    val labelPos: List<Int> = this.ilLabelPositions(il)
    var scan: Int = 0
    while (scan < il.ops.size()) {
        val op: *IlOp = *il.ops[scan]
        if (op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            // A folded declaration prints nothing, so it needs no block.
            if (this.ilFolded(il, frame, slot)) {
                scan = scan + 1
                continue
            }
            // A typed slot is declared where the `Declare` stands; an untyped one at
            // the instruction that first writes it (or here, when the two are adjacent).
            var at: Int = scan
            if (!this.ilDeclaredAtTop(il, slot)) {
                val def: Int = this.ilIntAt(frame.defOp, slot, -1)
                if (def == scan + 1) {
                    at = scan
                } else {
                    at = def
                }
                if (at < 0) {
                    scan = scan + 1
                    continue
                }
            }
            val crossing: IlCrossing = this.ilJumpCrossing(il, labelPos, at)
            if (crossing.end >= 0) {
                blockEnd.insert(at, crossing)
            }
        }
        scan = scan + 1
    }
    // Grouped storage (`ilDeclGroups`): a declaration that needs a block ends a run.
    val declLines: Dictionary<Int, List<Str>> = this.ilDeclGroups(il, frame, *blockEnd)
    var scopes: List<IlScope> = List<IlScope>()
    var consumedByDeclare: Int = -1
    var lvl: Int = level
    var text = Str()
    this.ilConcatPreamble(il, text, lvl)

    var i: Int = 0
    while (i < il.ops.size()) {
        // A block ends where its jump lands: the target stays outside it.
        while (scopes.size() > 0 && scopes[scopes.size() - 1].end == i) {
            scopes.removeAt(scopes.size() - 1)
            lvl = lvl - 1
            this.ilLine(text, lvl, "}")
        }
        if (i == consumedByDeclare) {
            i = i + 1
            continue
        }
        val op: *IlOp = *il.ops[i]
        val kind: IlOpKind = op.kind
        val dst: Int = this.ilDst(op)

        val crossing: *IlCrossing = blockEnd.getPtr(i)
        if (crossing != null) {
            val end: Int = crossing.end
            val lastJump: Int = crossing.lastJump
            var covered: Bool = false
            var s: Int = 0
            while (s < scopes.size()) {
                if (scopes[s].start > lastJump && scopes[s].end >= end) {
                    covered = true
                }
                s = s + 1
            }
            if (!covered) {
                this.ilLine(text, lvl, "{")
                lvl = lvl + 1
                scopes.append(IlScope(i, end))
            }
        }

        if (kind == IlOpKind.Declare || kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            if (slot < 0 || slot >= il.vars.size()) {
                return IlText(false, "", "a declare with no slot")
            }
            if (this.ilFolded(il, frame, slot)) {
                i = i + 1
                continue // inlined at its use
            }
            val slotType: AstXmlNode = ilVarType(il, slot)
            if (!xmlIsEmpty(slotType)) {
                val shared: *List<Str> = declLines.getPtr(i)
                if (shared != null) {
                    // A declaration a group already lists: the first of its type prints
                    // the group's lines (`ilDeclLines`), the others print nothing.
                    var s: Int = 0
                    while (s < shared.size()) {
                        // A continuation line is one level deeper: the same declaration.
                        var lineLvl: Int = lvl
                        if (s > 0) {
                            lineLvl = lvl + 1
                        }
                        this.ilLine(text, lineLvl, shared[s])
                        s = s + 1
                    }
                    i = i + 1
                    continue
                }
                // A typed slot on its own line: the instruction that computes its value
                // assigns it where that instruction stands.
                this.ilLine(
                    text, lvl, fmtStr("| |;", this.ilDeclTypeText(il, slot), il.vars[slot].name)
                )
                i = i + 1
                continue
            }
            // An untyped slot is declared with `auto` and its own definition as the
            // initializer - only where the two are adjacent; otherwise it prints nothing.
            val def: Int = this.ilIntAt(frame.defOp, slot, -1)
            if (def != i + 1) {
                if (def < 0) {
                    return IlText(
                        false, "", fmtStr("the slot '|' has neither a type nor an initializer", il.vars[slot].name)
                    )
                }
                i = i + 1
                continue
            }
            val valueText: Opt<Str> = this.ilValueText(il, frame, def, xmlEmptyNode())
            if (!valueText.hasValue()) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "an initializer with no expression form")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            this.ilLine(text, lvl, fmtStr("auto | = |;", il.vars[slot].name, valueText.value()))
            consumedByDeclare = def
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Label) {
            val label: Int = this.ilOpOperand(op.operands, 0)
            if (label < 0 || label >= il.labels.size()) {
                return IlText(false, "", "a label with no name")
            }
            this.ilLine(text, lvl, il.labels[label] + ":;")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Goto || kind == IlOpKind.IfTrue || kind == IlOpKind.IfFalse) {
            var labelAt: Int = 1
            if (kind == IlOpKind.Goto) {
                labelAt = 0
            }
            val label: Int = this.ilOpOperand(op.operands, labelAt)
            if (label < 0 || label >= il.labels.size()) {
                return IlText(false, "", "a jump with no label")
            }
            val target: Str = il.labels[label]
            if (kind == IlOpKind.Goto) {
                this.ilLine(text, lvl, fmtStr("goto |;", target))
                i = i + 1
                continue
            }
            val cond: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(cond)) {
                return IlText(false, "", "a jump with no condition")
            }
            val test: Str = this.expr(cond, 0, xmlEmptyNode())
            var jumpLine: Str = fmtStr("if (!(|)) goto |;", test, target)
            if (kind == IlOpKind.IfTrue) {
                jumpLine = fmtStr("if (|) goto |;", test, target)
            }
            this.ilLine(text, lvl, jumpLine)
            i = i + 1
            continue
        }
        if (dst >= 0 && kind == IlOpKind.Concat) {
            // A concatenation is a *sequence*, not one expression: one length sum, one
            // `resize`, one slot write per part (`ilConcatStatements`).
            if (!this.ilConcatStatements(il, frame, op, lvl, text)) {
                var why: Str = this.ilWhy
                if (why == "") {
                    why = "a concatenation"
                }
                return IlText(false, "", "cannot express " + why)
            }
            i = i + 1
            continue
        }
        if (dst >= 0 && this.ilFolded(il, frame, dst)) {
            i = i + 1
            continue // inlined at its use
        }
        if (dst >= 0) {
            val slotType: AstXmlNode = ilVarType(il, dst)
            // An instruction that defines an untyped slot *is* that slot's declaration
            // (`auto x = <this>;`); a typed one was declared with the frame at the top.
            val declares: Bool = xmlIsEmpty(slotType) && this.ilIntAt(frame.defOp, dst, -1) == i
            val valueText: Opt<Str> = this.ilValueText(il, frame, i, slotType)
            if (!valueText.hasValue()) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", fmtStr("'|' cannot be expressed yet", ilOpKindText(kind)))
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            if (declares) {
                this.ilLine(text, lvl, fmtStr("auto | = |;", il.vars[dst].name, valueText.value()))
                i = i + 1
                continue
            }
            if (xmlIsEmpty(slotType)) {
                return IlText(
                    false, "", fmtStr("the slot '|' has no type to assign", il.vars[dst].name)
                )
            }
            this.ilLine(text, lvl, fmtStr("| = |;", il.vars[dst].name, valueText.value()))
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Store) {
            val ptr: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
            if (xmlIsEmpty(ptr) || xmlIsEmpty(value)) {
                return IlText(false, "", "a store with no pointer")
            }
            var target: AstXmlNode = AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprDeref,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            xmlAddChild(target, this.renameRole(ptr, AstNodeKind.Operand))
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.SetField || kind == IlOpKind.SetIndex) {
            var target: AstXmlNode = xmlEmptyNode()
            if (kind == IlOpKind.SetField) {
                val textIndex: Int = this.ilOpOperand(op.operands, 1)
                if (textIndex < 0 || textIndex >= il.pool.size()) {
                    return IlText(false, "", "a field write with no name")
                }
                val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
                target = this.ilMemberNode(base, il.pool[textIndex])
            } else {
                val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
                val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
                if (!xmlIsEmpty(base) && !xmlIsEmpty(index)) {
                    target = AstXmlNode(
                        AstNodeKind.Expr,
                        AstNodeCategory.ExprIndex,
                        List<AstNodeAttribute>(),
                        Array<AstXmlNode>()
                    )
                    xmlAddChild(target, this.renameRole(base, AstNodeKind.Receiver))
                    xmlAddChild(target, this.renameRole(index, AstNodeKind.Index))
                }
            }
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
            if (xmlIsEmpty(target) || xmlIsEmpty(value)) {
                return IlText(false, "", "a write with no target")
            }
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.SetStatic) {
            val textIndex: Int = this.ilOpOperand(op.operands, 0)
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
            if (textIndex < 0 || textIndex >= il.pool.size() || xmlIsEmpty(value)) {
                return IlText(false, "", "a static write with no target")
            }
            val staticText: Str = il.pool[textIndex]
            val dot: Int = this.ilLastDot(staticText)
            var target: AstXmlNode = this.ilNameNode(staticText)
            if (dot > 0) {
                val base: AstXmlNode = this.ilNameNode(staticText.substr(0, dot))
                target = this.ilMemberNode(base, staticText.substr(dot + 1, staticText.size() - dot - 1))
            }
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.CallVoid || kind == IlOpKind.CallIndirectVoid) {
            val call: AstXmlNode = this.ilCallNode(il, frame, op)
            if (xmlIsEmpty(call)) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "a void call")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            this.ilLine(text, lvl, this.expr(call, 0, xmlEmptyNode()) + ";")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Return || kind == IlOpKind.ReturnVoid) {
            if (kind == IlOpKind.ReturnVoid) {
                this.ilLine(text, lvl, "return;")
                i = i + 1
                continue
            }
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(value)) {
                return IlText(false, "", "a return with no value")
            }
            this.ilLine(text, lvl, fmtStr("return |;", this.expr(value, 0, this.curReturnType)))
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Lambda) {
            return IlText(false, "", "a lambda body")
        }
        if (kind == IlOpKind.Unsupported) {
            return IlText(false, "", "an unsupported shape")
        }
        return IlText(false, "", fmtStr("the instruction '|'", ilOpKindText(kind)))
    }
    while (scopes.size() > 0) {
        scopes.removeAt(scopes.size() - 1)
        lvl = lvl - 1
        this.ilLine(text, lvl, "}")
    }
    return IlText(true, text, "")
}

// One body's text, with its frame analysed and the type pass's failure state kept out
// of the caller's: a body the IL cannot spell is a *report*, not an error.
fun Emitter.ilEmitOpsChecked(il: *IlBody, level: Int): IlText {
    var frame: IlFrame = IlFrame(Dictionary<Int, Int>(), Dictionary<Int, Int>(), Dictionary<Int, Int>())
    this.ilAnalyze(il, frame)
    val savedFailed: Bool = this.failed
    val savedError: Str = this.error
    val result: IlText = this.ilEmitOps(il, frame, level)
    var final: IlText = result
    if (result.ok && this.failed) {
        final = IlText(false, "", "the emitter reported: " + this.error)
    }
    this.failed = savedFailed
    this.error = savedError
    return final
}

// The C++ of one function body, from its IL. The frame's types are installed for the
// spelling helpers, and nothing from a previous body is left behind.
fun Emitter.emitIlBodyText(unit: *IlUnit, level: Int): IlText {
    val il: *IlBody = *unit.body
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedClosures: Dictionary<Str, Bool> = this.closureSymbols
    this.ilSeedFrameTypes(il)
    var closures: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*closure in unit.closures) {
        closures.insert(closure.symbol, true)
    }
    this.closureSymbols = closures
    val result: IlText = this.ilEmitOpsChecked(il, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.closureSymbols = savedClosures
    return result
}

// A lambda's body as its class's method: `self` is C++'s `this`, and the frame is the
// lambda's own.
fun Emitter.emitClosureMethodText(unit: *IlUnit, closure: *IlClosure, level: Int): IlText {
    if (closure.bodyIndex < 0 || closure.bodyIndex >= unit.lambdas.size()) {
        return IlText(false, "", "a closure with no body")
    }
    val body: *IlBody = *unit.lambdas[closure.bodyIndex]
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedSelfKind: NameKind = this.selfKind
    val savedSelfType: AstXmlNode = this.selfType
    val savedClosure: Bool = this.inClosureMethod
    this.nameKinds.clear()
    this.localTypes.clear()
    this.ilSeedFrameTypes(body)
    var classType: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, closure.symbol))
    this.selfKind = NameKind.Value
    this.selfType = classType
    this.inClosureMethod = true
    val result: IlText = this.ilEmitOpsChecked(body, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.selfKind = savedSelfKind
    this.selfType = savedSelfType
    this.inClosureMethod = savedClosure
    return result
}

// The class a lambda is: one field per capture and one `operator()` method - the
// language's `invoke`. An explicit struct is what lets a lambda live in the instruction
// list.
fun Emitter.emitClosureClass(unit: *IlUnit, closure: *IlClosure): IlText {
    var text: Str = fmtStr("struct | {\n", closure.symbol)
    var i: Int = 0
    while (i < closure.captures.size()) {
        var fieldType: AstXmlNode = xmlEmptyNode()
        if (i < closure.captureTypes.size()) {
            fieldType = closure.captureTypes[i]
        }
        if (xmlIsEmpty(fieldType)) {
            return IlText(false, "", fmtStr("the capture '|' has no type", closure.captures[i]))
        }
        text.appendStr(fmtStr("    | |;\n", this.type(fieldType), closure.captures[i]))
        i = i + 1
    }
    var params: List<Str> = List<Str>()
    for (*param in closure.params) {
        val paramType: AstXmlNode = ilTypeNode(unit.lambdas[closure.bodyIndex], param.typeIndex)
        if (xmlIsEmpty(paramType)) {
            return IlText(false, "", fmtStr("the lambda parameter '|' has no type", param.name))
        }
        params.append(fmtStr("| |", this.type(paramType), param.name))
    }
    text.appendStr(fmtStr("    auto operator()(|) {\n", cgJoin(params, ", ")))
    val preamble: Str = profPreamble(this.profIndexOf(closure.symbol + "::operator()"))
    if (preamble != "") {
        text.appendStr(fmtStr("||\n", cgIndent(2), preamble))
    }
    val bodyText: IlText = this.emitClosureMethodText(unit, closure, 2)
    if (!bodyText.ok) {
        return bodyText
    }
    text.appendStr(bodyText.text)
    text.appendStr("    }\n")
    text.appendStr("};\n\n")
    return IlText(true, text, "")
}

