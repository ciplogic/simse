// IlEmit.kt
//
// Emitting the IL: the per-op text (`ilEmitOps`), the closure and machine classes and the
// task methods. `Emitter` extension functions; IlCodeGen.kt holds the IL model and walks,
// IlConcat.kt the concatenation expansion.

package codegen

import compiler

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
                val ilDeclTypeTextText: Str = this.ilDeclTypeText(il, slot)
                val nameText: Str = il.vars[slot].name
                this.ilLine(
                    text, lvl, `@ilDeclTypeTextText @nameText;`
                )
                i = i + 1
                continue
            }
            // An untyped slot is declared with `auto` and its own definition as the
            // initializer - only where the two are adjacent; otherwise it prints nothing.
            val def: Int = this.ilIntAt(frame.defOp, slot, -1)
            if (def != i + 1) {
                if (def < 0) {
                    val nameText2: Str = il.vars[slot].name
                    return IlText(
                        false, "", `the slot '@nameText2' has neither a type nor an initializer`
                    )
                }
                i = i + 1
                continue
            }
            val valueText: Opt<Str> = this.ilValueText(il, frame, def, xmlEmptyNode())
            if (!valueText.hasValue()) {
                if (def >= 0 && def < il.ops.size()
                    && il.ops[def].kind == IlOpKind.Unsupported
                ) {
                    // The initializer is the extractor's own report: carry it out instead of
                    // the generic shape message (the report names the construct).
                    return IlText(false, "", this.ilUnsupportedReason(il, def))
                }
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "an initializer with no expression form")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            val nameText3: Str = il.vars[slot].name
            val valueText2: Str = valueText.value()
            this.ilLine(text, lvl, `auto @nameText3 = @valueText2;`)
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
                this.ilLine(text, lvl, `goto @target;`)
                i = i + 1
                continue
            }
            val cond: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(cond)) {
                return IlText(false, "", "a jump with no condition")
            }
            val test: Str = this.expr(cond, 0, xmlEmptyNode())
            var jumpLine: Str = `if (!(@test)) goto @target;`
            if (kind == IlOpKind.IfTrue) {
                jumpLine = `if (@test) goto @target;`
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
                    val ilOpKindTextText: Str = ilOpKindText(kind)
                    return IlText(false, "", `'@ilOpKindTextText' cannot be expressed yet`)
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            if (declares) {
                val nameText4: Str = il.vars[dst].name
                val valueText3: Str = valueText.value()
                this.ilLine(text, lvl, `auto @nameText4 = @valueText3;`)
                i = i + 1
                continue
            }
            if (xmlIsEmpty(slotType)) {
                val nameText5: Str = il.vars[dst].name
                return IlText(
                    false, "", `the slot '@nameText5' has no type to assign`
                )
            }
            val nameText6: Str = il.vars[dst].name
            val valueText4: Str = valueText.value()
            this.ilLine(text, lvl, `@nameText6 = @valueText4;`)
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
            val exprText: Str = this.expr(target, 0, xmlEmptyNode())
            val exprText2: Str = this.expr(value, 0, targetType)
            this.ilLine(
                text, lvl, `@exprText = @exprText2;`
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
                // The index syntax on a type that declares `operator set`
                // (specs/functions.md) is that call: `x[i] = v` is `set(x, i, v)`.
                val setAt: Int = this.operatorFn("set", base, 2)
                if (setAt >= 0) {
                    val setValue: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
                    if (xmlIsEmpty(base) || xmlIsEmpty(index) || xmlIsEmpty(setValue)) {
                        return IlText(false, "", "an index write with no operator arguments")
                    }
                    val callText: Str = this.operatorIndexSetText(setAt, base, index, setValue)
                    this.ilLine(text, lvl, `@callText;`)
                    i = i + 1
                    continue
                }
                if (this.operatorFn("get", base, 1) >= 0) {
                    // The type reads through `get` but declares no write: an index write
                    // needs its own operator, the way Kotlin's indexers work.
                    return IlText(
                        false, "",
                        "this index has no 'operator set(index, value)': an index write needs one"
                    )
                }
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
            val exprText3: Str = this.expr(target, 0, xmlEmptyNode())
            val exprText4: Str = this.expr(value, 0, targetType)
            this.ilLine(
                text, lvl, `@exprText3 = @exprText4;`
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
            val exprText5: Str = this.expr(target, 0, xmlEmptyNode())
            val exprText6: Str = this.expr(value, 0, targetType)
            this.ilLine(
                text, lvl, `@exprText5 = @exprText6;`
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
            val exprText7: Str = this.expr(value, 0, this.curReturnType)
            this.ilLine(text, lvl, `return @exprText7;`)
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Lambda) {
            return IlText(false, "", "a lambda body")
        }
        if (kind == IlOpKind.Unsupported) {
            return IlText(false, "", this.ilUnsupportedReason(il, i))
        }
        val ilOpKindTextText2: Str = ilOpKindText(kind)
        return IlText(false, "", `the instruction '@ilOpKindTextText2'`)
    }
    while (scopes.size() > 0) {
        scopes.removeAt(scopes.size() - 1)
        lvl = lvl - 1
        this.ilLine(text, lvl, "}")
    }
    return IlText(true, text, "")
}

// The report an `Unsupported` instruction carries: the extractor's own reason, as the
// instruction's text operand.
fun Emitter.ilUnsupportedReason(il: *IlBody, opIndex: Int): Str {
    val op: *IlOp = *il.ops[opIndex]
    val textIndex: Int = this.ilOpOperand(op.operands, 1)
    if (textIndex >= 0 && textIndex < il.pool.size()) {
        return "unsupported: " + il.pool[textIndex]
    }
    return "an unsupported shape"
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
        // The emitter's own `fail` already names the file, line and column (and falls back
        // to the declaration being emitted when the node is IL-reconstructed): the message
        // must survive as it is, not be re-wrapped as an extractor report.
        final = IlText(false, "", this.error)
    }
    this.failed = savedFailed
    this.error = savedError
    return final
}

// The C++ type each of a unit's closure classes prints as: a generic owner's class is a
// template, so the text carries its arguments.
fun Emitter.ilClosureTypeTable(unit: *IlUnit): Dictionary<Str, Str> {
    var table: Dictionary<Str, Str> = Dictionary<Str, Str>()
    for (*closure in unit.closures) {
        table.insert(closure.symbol, this.ilClosureTypeText(closure))
    }
    return table
}

fun Emitter.ilClosureTypeText(closure: *IlClosure): Str {
    var text: Str = closure.symbol
    if (closure.templateParams.size() > 0) {
        val cgJoinText: Str = cgJoin(closure.templateParams, ", ")
        text = `@text<@cgJoinText>`
    }
    return text
}

// A closure class's name spelled by a *type node* (a capture's or a result's, when a nested
// lambda's class is the type); `closureTypes` is the table of the unit being emitted.
fun Emitter.ilClosureTypeOfNode(typeNode: AstXmlNode): Str {
    val found: Opt<Str> = this.ilClosureTypeOfNodeOpt(typeNode)
    if (found.hasValue()) {
        return found.value()
    }
    return this.type(typeNode)
}

// The C++ of one function body, from its IL. The frame's types are installed for the
// spelling helpers, and nothing from a previous body is left behind.
fun Emitter.emitIlBodyText(unit: *IlUnit, level: Int): IlText {
    val il: *IlBody = *unit.body
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedClosures: Dictionary<Str, Str> = this.closureTypes
    this.ilSeedFrameTypes(il)
    this.closureTypes = this.ilClosureTypeTable(unit)
    val result: IlText = this.ilEmitOpsChecked(il, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.closureTypes = savedClosures
    return result
}

// A lambda's body as its class's free method: `self` is the closure *value* (the invoke
// takes a copy), so a capture reads `self.field`, and the frame is the lambda's own.
fun Emitter.emitClosureBodyText(unit: *IlUnit, closure: *IlClosure, level: Int): IlText {
    if (closure.bodyIndex < 0 || closure.bodyIndex >= unit.lambdas.size()) {
        return IlText(false, "", "a closure with no body")
    }
    val body: *IlBody = *unit.lambdas[closure.bodyIndex]
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedSelfKind: NameKind = this.selfKind
    val savedSelfType: AstXmlNode = this.selfType
    val savedReturn: AstXmlNode = this.curReturnType
    val savedClosure: Bool = this.inClosureMethod
    // The closure's own template parameters name the enclosing function's type parameters
    // when the lambda is inside a generic one, so the protocol constraints of the enclosing
    // context still apply inside the body (a captured `*T` can call its protocol methods).
    val savedActiveParams: Dictionary<Str, Bool> = this.activeTypeParams
    var closureParams: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*name in savedActiveParams.keys()) {
        closureParams.insert(*name, true)
    }
    for (*name in closure.templateParams) {
        closureParams.insert(*name, true)
    }
    this.activeTypeParams = closureParams
    this.nameKinds.clear()
    this.localTypes.clear()
    this.ilSeedFrameTypes(body)
    this.selfKind = NameKind.Value
    this.selfType = ilNamedTypeNode(closure.symbol)
    // A free function, not a member: `this` is not the instance (`SemaAnalyze` reports a
    // `this` inside a lambda).
    this.inClosureMethod = false
    this.curReturnType = closure.returnType
    val result: IlText = this.ilEmitOpsChecked(body, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.selfKind = savedSelfKind
    this.selfType = savedSelfType
    this.curReturnType = savedReturn
    this.inClosureMethod = savedClosure
    this.activeTypeParams = savedActiveParams
    return result
}

// The free method's parameter list: the closure itself (by value) then the lambda's own
// parameters. Empty when one has no type, with `ilWhy` stating which.
fun Emitter.ilClosureParamList(closure: *IlClosure): List<Str> {
    var params: List<Str> = List<Str>()
    val selfTypeText: Str = this.ilClosureTypeText(closure)
    params.append(`@selfTypeText self`)
    var i: Int = 0
    while (i < closure.params.size()) {
        var paramType: AstXmlNode = xmlEmptyNode()
        if (i < closure.paramTypes.size()) {
            paramType = closure.paramTypes[i]
        }
        if (xmlIsEmpty(paramType)) {
            val paramNameText: Str = closure.params[i].name
            this.ilWhy =
                `the lambda parameter '@paramNameText' has no type: annotate it (e.g. '@paramNameText: T') or use the lambda where a callable type expects one`
            return List<Str>()
        }
        val paramNameText2: Str = closure.params[i].name
        val paramTypeText: Str = this.ilClosureTypeOfNode(paramType)
        params.append(`@paramTypeText @paramNameText2`)
        if (this.failed) {
            return List<Str>()
        }
        i = i + 1
    }
    return params
}

// The callable type a closure converts into, as C++ (`Func<Ret(Params)>`); empty when a
// parameter's type is unknown, with the reason stated.
fun Emitter.ilClosureFuncType(closure: *IlClosure): Opt<Str> {
    var retText: Str = "void"
    if (!xmlIsEmpty(closure.returnType)) {
        retText = this.ilClosureTypeOfNode(closure.returnType)
        if (this.failed) {
            return ()
        }
    }
    var params: List<Str> = List<Str>()
    var i: Int = 0
    while (i < closure.params.size()) {
        var paramType: AstXmlNode = xmlEmptyNode()
        if (i < closure.paramTypes.size()) {
            paramType = closure.paramTypes[i]
        }
        if (xmlIsEmpty(paramType)) {
            val paramNameText: Str = closure.params[i].name
            this.ilWhy =
                `the lambda parameter '@paramNameText' has no type: annotate it (e.g. '@paramNameText: T') or use the lambda where a callable type expects one`
            return ()
        }
        params.append(this.ilClosureTypeOfNode(paramType))
        if (this.failed) {
            return ()
        }
        i = i + 1
    }
    val cgJoinText: Str = cgJoin(params, ", ")
    return (`Func<@retText(@cgJoinText)>`)
}

// The lambda's result type as C++, `void` when it answers nothing.
fun Emitter.ilClosureReturnTypeText(closure: *IlClosure): Str {
    if (xmlIsEmpty(closure.returnType)) {
        return "void"
    }
    return this.ilClosureTypeOfNode(closure.returnType)
}

// The data class a lambda is: one field per capture. The call itself is the free
// `<symbol>_invoke` below; the one member bridges into the callable representation, so a
// lambda still converts where a function-typed value is wanted.
fun Emitter.emitClosureStruct(closure: *IlClosure): IlText {
    val symbolText: Str = closure.symbol
    val funcType: Opt<Str> = this.ilClosureFuncType(closure)
    if (!funcType.hasValue()) {
        return IlText(false, "", this.ilWhy)
    }
    val funcTypeText: Str = funcType.value()
    val retText: Str = this.ilClosureReturnTypeText(closure)
    if (this.failed) {
        return IlText(false, "", this.error)
    }
    val params: List<Str> = this.ilClosureParamList(closure)
    if (params.size() == 0) {
        return IlText(false, "", this.ilWhy)
    }
    var invokeRef: Str = `&@(symbolText)_invoke`
    if (closure.templateParams.size() > 0) {
        val cgJoinText: Str = cgJoin(closure.templateParams, ", ")
        invokeRef = `@invokeRef<@cgJoinText>`
    }
    val tmpl: Str = this.templateClause(closure.templateParams)
    val cgJoinText2: Str = cgJoin(params, ", ")
    var text = Str()
    if (tmpl != "") {
        text.appendStr(tmpl + "\n")
    }
    text.appendStr(`struct @symbolText;` + "\n")
    if (tmpl != "") {
        text.appendStr(tmpl + "\n")
    }
    text.appendStr(`@retText @(symbolText)_invoke(@cgJoinText2);` + "\n")
    if (tmpl != "") {
        text.appendStr(tmpl + "\n")
    }
    text.appendStr(`struct @symbolText {` + "\n")
    var i: Int = 0
    while (i < closure.captures.size()) {
        var fieldType: AstXmlNode = xmlEmptyNode()
        if (i < closure.captureTypes.size()) {
            fieldType = closure.captureTypes[i]
        }
        if (xmlIsEmpty(fieldType)) {
            val capturesText: Str = closure.captures[i]
            return IlText(false, "", `the capture '@capturesText' has no type`)
        }
        val fieldTypeText: Str = this.ilClosureTypeOfNode(fieldType)
        val captureText: Str = closure.captures[i]
        text.appendStr(`    @fieldTypeText @captureText;` + "\n")
        i = i + 1
    }
    val indent2: Str = cgIndent(2)
    text.appendStr(`    operator @funcTypeText() const {` + "\n")
    text.appendStr(`@(indent2)return simse_closureFunc<@funcTypeText>(@invokeRef, *this);` + "\n")
    text.appendStr("    }\n")
    text.appendStr("};\n\n")
    return IlText(true, text, "")
}

// The free method a lambda is: `<symbol>_invoke(<symbol> self, params) { body }` - a plain
// function whose first parameter is the closure, passed by copy.
fun Emitter.emitClosureInvoke(unit: *IlUnit, closure: *IlClosure): IlText {
    val symbolText: Str = closure.symbol
    val params: List<Str> = this.ilClosureParamList(closure)
    if (params.size() == 0) {
        return IlText(false, "", this.ilWhy)
    }
    val retText: Str = this.ilClosureReturnTypeText(closure)
    if (this.failed) {
        return IlText(false, "", this.error)
    }
    val tmpl: Str = this.templateClause(closure.templateParams)
    var text = Str()
    if (tmpl != "") {
        text.appendStr(tmpl + "\n")
    }
    val cgJoinText: Str = cgJoin(params, ", ")
    text.appendStr(`@retText @(symbolText)_invoke(@cgJoinText) {` + "\n")
    val preamble: Str = profPreamble(this.profIndexOf(symbolText + "_invoke"))
    if (preamble != "") {
        text.appendStr(cgIndent(1) + preamble + "\n")
    }
    val bodyText: IlText = this.emitClosureBodyText(unit, closure, 1)
    if (!bodyText.ok) {
        return bodyText
    }
    text.appendStr(bodyText.text)
    text.appendStr("}\n\n")
    return IlText(true, text, "")
}
