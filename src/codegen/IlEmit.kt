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
// The out-parameter rewrite's key for one Call op: the `sretFns` key the definition side
// used (`qualify(package, name)`), "" when the call is not an out-parameter one. For a
// free function the package is `functionPackage`; for a method, the receiver's class
// names it - the member callee carries the receiver, whose type is inferred here (the
// same inference the by-value rendering runs). Closures and indirect calls keep their
// own ABI and answer "".
fun Emitter.ilSretKey(il: *IlBody, frame: *IlFrame, op: *IlOp): Str {
    val hasDst: Bool = this.ilDst(op) >= 0
    var methodAt: Int = 0
    if (hasDst) {
        methodAt = 1
    }
    if (methodAt >= op.operands.size()) {
        return ""
    }
    val methodIndex: Int = this.ilOpOperand(op.operands, methodAt)
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        return ""
    }
    val method: IlMethod = *il.methods[methodIndex]
    if (this.ilClosureNameOf(method.name) != "") {
        return ""
    }
    val call: AstXmlNode = this.ilCallNode(il, frame, op)
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    // The key the definition side used: `qualify(package, name)` of the declaration.
    // A member call is decided by the same resolution the by-value rendering runs
    // (`CgCall.kt`'s member arm), so the ABI the call spells and the head the renderer
    // emits can never name two declarations: an extension or a class method resolves
    // through the receiver (`findExtensionFn`, the renderer's own lookup) and answers
    // that declaration's key or "" - and a call it does not resolve is a native
    // extension (a symbol whose ABI is as declared) or a protocol/member-access shape,
    // neither of which the out-parameter rewrite covers. The package-table lanes below
    // are the *free-call* resolution and stop here: a bare name several packages declare
    // (`sqlite`'s `insert` beside the RTL's `Dictionary.insert`) is a different C++
    // symbol than the one a receiver-based rendering spells, so guessing by name alone
    // rewrote calls into the wrong ABI.
    if (method.kind == IlMethodKind.Method) {
        if (xmlKind(callee) == AstNodeCategory.ExprMember) {
            val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (!xmlIsEmpty(recv)) {
                val argCount: Int = xmlChildren(call, AstNodeKind.Arg).size()
                val fnIndex: Int = this.findExtensionFn(method.name, recv, argCount)
                if (fnIndex >= 0) {
                    val fn: *CgFn = *this.functions[fnIndex]
                    val typeKey: Str = this.qualify(fn.packageName, fn.name)
                    if (this.sretFns.has(typeKey)) {
                        return typeKey
                    }
                    return ""
                }
                // The renderer's own fallback when the receiver's type is unknown
                // (`CgCall.kt`'s `receiverFnNames` lane): a receiver function spelled
                // by name, its head the same `functionPackage` qualification the
                // emitted call carries.
                if (xmlIsEmpty(this.pointee(this.inferType(recv)))
                    && this.receiverFnNames.has(method.name)
                ) {
                    val byNameKey: Str = this.qualify(this.functionPackage(method.name), method.name)
                    if (this.sretFns.has(byNameKey)) {
                        return byNameKey
                    }
                }
            }
        }
        return ""
    }
    // The method's own package, when the resolution carried one: a name several packages
    // declare is two C++ functions, and only the resolved one's sret-ness applies
    // (`xmlSkipMisc` over a cursor returns a Res - sret; over a parser it is Unit - not).
    if (method.packageName != "") {
        val ownKey: Str = this.qualify(method.packageName, method.name)
        if (this.sretFns.has(ownKey)) {
            return ownKey
        }
        return ""
    }
    val fnKey: Str = this.qualify(this.functionPackage(method.name), method.name)
    if (this.sretFns.has(fnKey)) {
        return fnKey
    }
    val calleeText: Str = this.expr(callee, 0, xmlEmptyNode())
    if (calleeText != "" && this.sretFns.has(calleeText)) {
        return calleeText
    }
    return ""
}

// The call's by-value text, split at its own parenthesis - the callee head
// (`ns1_printName`, `Str::fromBytes`) and the argument text - so the sret spelling is
// `head(&dst, args)`. The split mirrors the by-value rendering (`expr` on the call
// node), which is where every callee shape is already resolved: a free name, an
// extension's free function with its receiver conversion, a static base.
fun Emitter.ilSretCallText(il: *IlBody, frame: *IlFrame, op: *IlOp): Str {
    val call: AstXmlNode = this.ilCallNode(il, frame, op)
    return this.expr(call, 0, xmlEmptyNode())
}

// The out-parameter return type's C++ text, keyed like `sretFns`; "" when absent.
fun Emitter.ilSretRet(key: Str): Str {
    val text: *Str = this.sretRets.getPtr(key)
    if (text == null) {
        return ""
    }
    return *text
}

// Whether `slot` is one of the twin body's aliased returns (only asked while
// `curTwinOut` is set): a `&T` slot whose single definition is the boxed construction -
// the twin has no storage of its own for it, the caller's `_sm_out` is where the value
// lives, so its declaration prints nothing and its return is the plain exit.
fun Emitter.ilTwinAliased(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    if (this.curTwinOut == "" || slot < 0 || slot >= il.vars.size()) {
        return false
    }
    val typeNode: AstXmlNode = ilVarType(il, slot)
    if (xmlIsEmpty(typeNode) || xmlKind(typeNode) != AstNodeCategory.TypeReference) {
        return false
    }
    val def: Int = this.ilIntAt(frame.defOp, slot, -1)
    if (def < 0 || def >= il.ops.size() || il.ops[def].kind != IlOpKind.CallCtor) {
        return false
    }
    return true
}

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
                scan++
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
                    scan++
                    continue
                }
            }
            val crossing: IlCrossing = this.ilJumpCrossing(il, labelPos, at)
            if (crossing.end >= 0) {
                blockEnd.insert(at, crossing)
            }
        }
        scan++
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
            lvl--
            this.ilLine(text, lvl, "}")
        }
        if (i == consumedByDeclare) {
            i++
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
            for (scope in scopes) {
                if (scope.start > lastJump && scope.end >= end) {
                    covered = true
                }
            }
            if (!covered) {
                this.ilLine(text, lvl, "{")
                lvl++
                scopes.append(IlScope(i, end))
            }
        }

        if (kind == IlOpKind.Declare || kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            if (slot < 0 || slot >= il.vars.size()) {
                return IlText(false, "", "a declare with no slot")
            }
            if (this.ilFolded(il, frame, slot)) {
                i++
                continue // inlined at its use
            }
            val slotType: AstXmlNode = ilVarType(il, slot)
            if (!xmlIsEmpty(slotType)) {
                if (this.ilTwinAliased(il, frame, slot)) {
                    i++
                    continue // the twin's aliased return: the caller's storage, no local
                }
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
                        s++
                    }
                    i++
                    continue
                }
                // A typed slot on its own line: the instruction that computes its value
                // assigns it where that instruction stands.
                val ilDeclTypeTextText: Str = this.ilDeclTypeText(il, slot)
                val nameText: Str = il.vars[slot].name
                this.ilLine(
                    text, lvl, `@ilDeclTypeTextText @nameText;`
                )
                i++
                continue
            }
            // An untyped slot is declared with `auto` and its own definition as the
            // initializer - only where the two are adjacent; otherwise it prints nothing.
            val def: Int = this.ilIntAt(frame.defOp, slot, -1)
            if (def != i + 1) {
                if (def < 0) {
                    // The extractor's own report outranks the shape message: a slot the
                    // failed lowering left behind (a call that could not resolve declares
                    // its destination before reporting) is the report's consequence, not a
                    // second error of its own.
                    var u: Int = 0
                    while (u < il.ops.size()) {
                        if (il.ops[u].kind == IlOpKind.Unsupported) {
                            return IlText(false, "", this.ilUnsupportedReason(il, u))
                        }
                        u++
                    }
                    val nameText2: Str = il.vars[slot].name
                    val at: Str = ilLineOf(il, i)
                    return IlText(
                        false, "", `the slot '@nameText2' has neither a type nor an initializer - line @at`
                    )
                }
                i++
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
            i++
            continue
        }
        if (kind == IlOpKind.Label) {
            val label: Int = this.ilOpOperand(op.operands, 0)
            if (label < 0 || label >= il.labels.size()) {
                return IlText(false, "", "a label with no name")
            }
            this.ilLine(text, lvl, il.labels[label] + ":;")
            i++
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
                i++
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
            i++
            continue
        }
        if (dst >= 0 && kind == IlOpKind.BinaryOp) {
            // `x op= v` resolved as an operator before the fold: `+=` against a matching
            // `plusAssign` declaration is the in-place append (`out += part` on a Str,
            // `parts += item` on a List - src/rtl/rtl.kt names the C++), and every compound
            // op falls back to the fold of its binary, spelled here because the pool's
            // compound ops are not binaries the expression path knows. The compound form
            // only ever arrives here from a *local slot* compound assignment
            // (`assignCompound`), so the receiver is a variable the call can take by
            // reference.
            val opIndexPA: Int = this.ilOpOperand(op.operands, 1)
            if (opIndexPA >= 0 && opIndexPA < il.pool.size()
                && isCompoundAssignOp(il.pool[opIndexPA])
            ) {
                val compoundPA: Str = il.pool[opIndexPA]
                var spelledPA: Bool = false
                if (compoundPA == "+=") {
                    val recvPA: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
                    if (!xmlIsEmpty(recvPA)) {
                        val extIndexPA: Int = this.findNativeExt("plusAssign", recvPA, 1)
                        if (extIndexPA >= 0) {
                            val extsPA: *List<CgNativeExt> = this.nativeExtensions.getPtr("plusAssign")
                            val extPA: CgNativeExt = *extsPA[extIndexPA]
                            this.referencedNames.insert(extPA.symbol, true)
                            val recvTextPA: Str = this.expr(recvPA, 0, xmlEmptyNode())
                            val valuePA: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 3), 0)
                            val valueTextPA: Str = this.expr(valuePA, 0, xmlEmptyNode())
                            this.ilLine(text, lvl, extPA.symbol + "(" + recvTextPA + ", " + valueTextPA + ");")
                            spelledPA = true
                        }
                    }
                }
                if (spelledPA) {
                    i++
                    continue
                }
                // No `plusAssign` (or not a `+=`): the fold of the compound's binary,
                // spelled here because the pool's compound ops are not binaries the
                // expression path knows.
                val lhsPA: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
                val rhsPA: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 3), 0)
                val foldPA: AstXmlNode = this.ilBinaryNode(lhsPA, compoundBinaryOp(compoundPA), rhsPA)
                val foldTextPA: Str = this.expr(foldPA, 0, xmlEmptyNode())
                val dstNamePA: Str = il.vars[dst].name
                this.ilLine(text, lvl, dstNamePA + " = " + foldTextPA + ";")
                i++
                continue
            }
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
            i++
            continue
        }
        if (dst >= 0 && this.ilFolded(il, frame, dst)) {
            i++
            continue // inlined at its use
        }
        if (dst >= 0 && kind == IlOpKind.Call) {
            // The out-parameter ABI at the call: the value is written straight into the
            // slot it was destined for - the return copy the by-value signature would
            // make is not there to elide, it never happens.
            val keyS: Str = this.ilSretKey(il, frame, op)
            if (keyS != "") {
                val callTextS: Str = this.ilSretCallText(il, frame, op)
                val openS: Int = callTextS.find("(")
                val closeS: Int = callTextS.lastIndexOf(")")
                if (openS >= 0 && closeS >= openS) {
                    val headS: Str = callTextS.substr(0, openS)
                    val argsTextS: Str = callTextS.substr(openS + 1, closeS - openS - 1)
                    val dstNameS: Str = il.vars[dst].name
                    var callPartsS: List<Str> = List<Str>()
                    callPartsS.append("&" + dstNameS)
                    if (argsTextS.size() > 0) {
                        callPartsS.append(argsTextS)
                    }
                    val argsJoinedS: Str = joinStrs(callPartsS, ", ")
                    this.ilLine(text, lvl, `@headS(@argsJoinedS);`)
                    i++
                    continue
                }
            }
        }
        if (dst >= 0) {
            val slotType: AstXmlNode = ilVarType(il, dst)
            if (this.curTwinOut != "" && kind == IlOpKind.CallCtor && this.ilTwinAliased(il, frame, dst)) {
                // The twin's one store: the construction goes straight into the caller's
                // storage - the `makeRef` the authored ABI builds here never happens.
                val aliasedType: AstXmlNode = slotType
                val payload: *AstXmlNode = xmlChildPtr(aliasedType, AstNodeKind.Inner)
                if (xmlIsEmpty(payload)) {
                    return IlText(false, "", "a twin return with no payload")
                }
                var twinArgs: List<Str> = List<Str>()
                var t: Int = 2
                while (t < op.operands.size()) {
                    val argNode: AstXmlNode = this.ilOperandNode(il, frame, op.operands[t], 0)
                    if (xmlIsEmpty(argNode)) {
                        return IlText(false, "", "a twin return with no argument")
                    }
                    twinArgs.append(this.expr(argNode, 0, xmlEmptyNode()))
                    t++
                }
                val payloadText: Str = this.type(*payload)
                val argsText: Str = cgJoin(twinArgs, ", ")
                val outName: Str = this.curTwinOut
                this.ilLine(text, lvl, `*@outName = @payloadText{@argsText};`)
                i++
                continue
            }
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
                i++
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
            i++
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
            i++
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
                var spanDirect: Bool = false
                val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
                val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
                // A span write goes through its pointer directly: the operator set is a
                // generic template whose declaration can trail the use in the amalgamation,
                // and the direct spelling is the faster one anyway (the span hoist writes).
                val baseType: AstXmlNode = this.inferType(base)
                if (xmlKind(baseType) == AstNodeCategory.TypeGeneric
                    && xmlAttr(baseType, AstNodeAttributeKind.Name) == "Span"
                    && !xmlIsEmpty(base) && !xmlIsEmpty(index)
                ) {
                    var ptrMember: AstXmlNode = this.ilMemberNode(base, "ptr")
                    if (!xmlIsEmpty(ptrMember)) {
                        spanDirect = true
                        target = AstXmlNode(
                            AstNodeKind.Expr, AstNodeCategory.ExprIndex,
                            List<AstNodeAttribute>(), Array<AstXmlNode>()
                        )
                        xmlAddChild(target, this.renameRole(ptrMember, AstNodeKind.Receiver))
                        xmlAddChild(target, this.renameRole(index, AstNodeKind.Index))
                    }
                }
                // The index syntax on a type that declares `operator set`
                // (specs/functions.md) is that call: `x[i] = v` is `set(x, i, v)` - unless
                // the direct span spelling above already built the target.
                var setAt: Int = -1
                if (!spanDirect) {
                    setAt = this.operatorFn("set", base, 2)
                }
                if (setAt >= 0) {
                    val setValue: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
                    if (xmlIsEmpty(base) || xmlIsEmpty(index) || xmlIsEmpty(setValue)) {
                        return IlText(false, "", "an index write with no operator arguments")
                    }
                    val callText: Str = this.operatorIndexSetText(setAt, base, index, setValue)
                    this.ilLine(text, lvl, `@callText;`)
                    i++
                    continue
                }
                if (!spanDirect && this.operatorFn("get", base, 1) >= 0) {
                    // The type reads through `get` but declares no write: an index write
                    // needs its own operator, the way Kotlin's indexers work.
                    return IlText(
                        false, "",
                        "this index has no 'operator set(index, value)': an index write needs one"
                    )
                }
                if (!spanDirect && !xmlIsEmpty(base) && !xmlIsEmpty(index)) {
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
            i++
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
            i++
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
            // A call to an out-parameter function whose value the statement drops: the
            // ABI still needs its destination, so a scoped scratch takes it.
            if (kind == IlOpKind.CallVoid) {
                val keyV: Str = this.ilSretKey(il, frame, op)
                if (keyV != "") {
                    val callTextV: Str = this.ilSretCallText(il, frame, op)
                    val openV: Int = callTextV.find("(")
                    val closeV: Int = callTextV.lastIndexOf(")")
                    if (openV >= 0 && closeV >= openV) {
                        val headV: Str = callTextV.substr(0, openV)
                        val argsTextV: Str = callTextV.substr(openV + 1, closeV - openV - 1)
                        val retTextV: Str = this.ilSretRet(keyV)
                        val scratchV: Str = "_sm_sret" + i.toString()
                        var callPartsV: List<Str> = List<Str>()
                        callPartsV.append("&" + scratchV)
                        if (argsTextV.size() > 0) {
                            callPartsV.append(argsTextV)
                        }
                        val argsJoinedV: Str = joinStrs(callPartsV, ", ")
                        this.ilLine(text, lvl, "{")
                        this.ilLine(text, lvl + 1, `@retTextV @scratchV;`)
                            this.ilLine(text, lvl + 1, `@headV(@argsJoinedV);`)
                        this.ilLine(text, lvl, "}")
                        i++
                        continue
                    }
                }
            }
            this.ilLine(text, lvl, this.expr(call, 0, xmlEmptyNode()) + ";")
            i++
            continue
        }
        if (kind == IlOpKind.Return || kind == IlOpKind.ReturnVoid) {
            if (kind == IlOpKind.ReturnVoid) {
                this.ilLine(text, lvl, "return;")
                i++
                continue
            }
            if (this.ilTwinAliased(il, frame, this.ilOpOperand(op.operands, 0))) {
                // The aliased construction already stored into `_sm_out`; the return is
                // only the exit.
                this.ilLine(text, lvl, "return;")
                i++
                continue
            }
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(value)) {
                return IlText(false, "", "a return with no value")
            }
            val exprText7: Str = this.expr(value, 0, this.curReturnType)
            if (this.curSretOut != "") {
                // The out-parameter ABI: the return value is stored where the caller
                // asked for it, and the body exits - nothing crosses the boundary.
                val outName: Str = this.curSretOut
                this.ilLine(text, lvl, `*@outName = @exprText7;`)
                this.ilLine(text, lvl, "return;")
            } else {
                this.ilLine(text, lvl, `return @exprText7;`)
            }
            i++
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
        lvl--
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

// The permissive verifier (impl_specs/typed-il.md, step 1): the failures that are certain
// whichever emitter path reaches them, reported before emission with the op's line named.
// Today that is the jump targets - a jump to a label the body never defines used to reach
// cl.exe as a `goto` with nothing to jump to. The typed-IL steps grow this body: each shape
// that gains a typed op moves from "fails late with a generic message" to "verified here".
// Answers "" when the body verifies.
fun Emitter.ilVerifyBody(il: *IlBody): Str {
    var defined: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: IlOp = *il.ops[i]
        if (op.kind == IlOpKind.Label) {
            val label: Int = this.ilOpOperand(op.operands, 0)
            if (label < 0 || label >= il.labels.size()) {
                val at: Str = ilLineOf(il, i)
                return `a label with no name - line @at`
            }
            defined.insert(label, true)
        }
        i++
    }
    i = 0
    while (i < il.ops.size()) {
        val op: IlOp = *il.ops[i]
        if (op.kind == IlOpKind.Goto || op.kind == IlOpKind.IfTrue || op.kind == IlOpKind.IfFalse) {
            var labelAt: Int = 1
            if (op.kind == IlOpKind.Goto) {
                labelAt = 0
            }
            val label: Int = this.ilOpOperand(op.operands, labelAt)
            if (label < 0 || label >= il.labels.size()) {
                val at: Str = ilLineOf(il, i)
                return `a jump with no label - line @at`
            }
            if (!defined.has(label)) {
                val target: Str = il.labels[label]
                val at: Str = ilLineOf(il, i)
                return `a jump to '@target', which the body never defines - line @at`
            }
        }
        i++
    }
    return ""
}

// The source line an op carries, for the verifier's messages.
fun ilLineOf(il: *IlBody, opIndex: Int): Str {
    if (opIndex < 0 || opIndex >= il.lines.size()) {
        return "?"
    }
    return il.lines[opIndex].toString()
}

// The C++ of one function body, from its IL. The frame's types are installed for the
// spelling helpers, and nothing from a previous body is left behind.
fun Emitter.emitIlBodyText(unit: *IlUnit, level: Int): IlText {
    val il: *IlBody = *unit.body
    val verified: Str = this.ilVerifyBody(il)
    if (verified != "") {
        return IlText(false, "", verified)
    }
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
    // A closure's `invoke` returns by value (`std::function` converts against that
    // signature): never the out-parameter ABI, whatever body was emitted before it.
    this.curSretOut = ""
    val body: *IlBody = *unit.lambdas[closure.bodyIndex]
    val verified: Str = this.ilVerifyBody(body)
    if (verified != "") {
        return IlText(false, "", verified)
    }
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
        i++
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
        i++
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
        i++
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
