// LinearFormCall.kt
//
// `IlExtractor`'s call lowering (arguments, packs, conversions) and the lambda construct.
// Extension methods on `IlExtractor` (LinearForm.kt).

package linear
import compiler

import common
import sema

// `dst < 0` means the result is dropped (`CallVoid`).
fun IlExtractor.call(dst: Int, e: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    val hasDst: Bool = dst >= 0
    var returnType: Int = -1
    if (hasDst) {
        returnType = this.out.vars[dst].typeIndex
    }
    val member: Bool = xmlKind(callee) == AstNodeCategory.ExprMember
    val lhs: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    var staticCall: Bool = false
    if (member) {
        staticCall = this.isTypeBase(lhs)
    }
    val receiverCall: Bool = member && !staticCall

    // The receiver is evaluated first, and a `Method` call carries it as its first argument, so
    // the `Var` operands after the callee match `IlMethod::argCount`.
    var recvSlot: Int = -1
    if (receiverCall) {
        recvSlot = this.receiverOf(lhs)
    }

    val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    val argNodes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)

    // `h.getAs<T>()`: the language's one cast (specs/memory-model.md, "Memory operators on
    // types"). `RawPtr` is a `void*` and C++ will not narrow one implicitly, so this is the
    // single place the language spells the reinterpretation. The *destination's* type is what
    // the instruction carries - the `Cast` the IL declares - and the type argument is what
    // fixes that destination here, so the emitter never needs to see it (`ilValueText`).
    if (member && !staticCall && calleeName == "getAs") {
        val typeArgs: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
        if (typeArgs.size() == 1 && argNodes.size() == 0) {
            if (!hasDst) {
                this.unsupported("a cast with no destination")
                return
            }
            var pointed: AstXmlNode = typeArgs[0]
            if (xmlKind(pointed) != AstNodeCategory.TypePointer
                && xmlKind(pointed) != AstNodeCategory.TypeReference
            ) {
                pointed = ilPointerNode(pointed)
            }
            this.setSlotType(dst, pointed)
            this.emit(IlOpKind.Cast, ilOps2(dst, recvSlot))
            return
        }
    }

    // The trailing arguments may pack into a list-valued last parameter. A static call
    // (`Res<Str>.ok(x)`) is not looked up: its callee is a type.
    var target: AstXmlNode = xmlEmptyNode()
    if (!staticCall) {
        var receiverNode: AstXmlNode = xmlEmptyNode()
        if (receiverCall) {
            receiverNode = lhs
        }
        target = this.callTarget(callee, receiverNode, argNodes.size(), receiverCall)
    }
    val packFrom: Int = this.packStart(target, argNodes)

    // `listOf<T>(a, b, c)` is the language's list literal: the same `Pack` a packed call builds,
    // so its elements convert the same way. It is not a call - the native it names exists only
    // so the checker has a signature to read.
    if (hasDst && packFrom >= 0 && !xmlIsEmpty(target) && this.ilIsListOf(target)) {
        val listType: AstXmlNode = this.ilListOfType(callee, argNodes, packFrom)
        if (!xmlIsEmpty(listType)) {
            this.setSlotType(dst, listType)
            var element: AstXmlNode = xmlEmptyNode()
            if (xmlCount(listType, AstNodeKind.TypeArg) > 0) {
                element = xmlChildPtr(listType, AstNodeKind.TypeArg)
            }
            var packing: List<Int> = List<Int>()
            packing.append(dst)
            var p: Int = packFrom
            while (p < argNodes.size()) {
                packing.append(this.convertArgument(target, element, argNodes[p]))
                p = p + 1
            }
            this.emit(IlOpKind.Pack, packing)
            return
        }
    }

    var args: List<Int> = List<Int>()
    var argTypes: List<Int> = List<Int>()
    var plain: Int = argNodes.size()
    if (packFrom >= 0) {
        plain = packFrom
    }
    // The parameters the arguments convert against, and the declaration that owns them: the
    // callee's, or a prelude data class's *fields* (a construction seen as a plain call).
    var paramOwner: AstXmlNode = xmlEmptyNode()
    var paramNodes: List<AstXmlNode> = List<AstXmlNode>()
    var paramOffset: Int = 0
    if (!xmlIsEmpty(target)) {
        paramOwner = target
        paramNodes = xmlChildren(target, AstNodeKind.Param)
        paramOffset = semReceiverParams(target)
    } else {
        val decl: AstXmlNode = this.dataClassDecl(calleeName)
        if (!xmlIsEmpty(decl)) {
            paramOwner = decl
            paramNodes = xmlChildren(decl, AstNodeKind.Field)
        }
    }
    // The receiver's declared kind (see `IlMethod.recvIsValue`); an unresolved callee is
    // treated as the unsafe shape.
    var recvIsValue: Bool = false
    if (!receiverCall) {
        recvIsValue = true
    } else if (!xmlIsEmpty(target)) {
        if (semReceiverParams(target) == 0) {
            recvIsValue = true
        } else {
            val recvType: AstXmlNode = semExtensionReceiver(target)
            recvIsValue = !ilIsReceiverRef(recvType)
        }
    }
    var i: Int = 0
    while (i < plain) {
        var param: AstXmlNode = xmlEmptyNode()
        if (paramOffset + i < paramNodes.size()) {
            param = xmlChildPtr(paramNodes[paramOffset + i], AstNodeKind.Type)
        }
        val slot: Int = this.convertArgument(paramOwner, param, argNodes[i])
        args.append(slot)
        argTypes.append(this.operandType(slot, argNodes[i]))
        i = i + 1
    }
    if (packFrom >= 0 && !xmlIsEmpty(target)) {
        val slot: Int = this.packArguments(target, argNodes, packFrom)
        args.append(slot)
        argTypes.append(this.out.vars[slot].typeIndex)
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

    if (receiverCall) {
        operands.append(
            this.methodIndex(calleeName, IlMethodKind.Method, -1, returnType, fullTypes, recvIsValue)
        )
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
        // A static call (`Res<Str>.ok(x)`): the type is part of the method's identity.
        operands.append(
            this.methodIndex(
                calleeName, IlMethodKind.Function,
                this.typeIndex(this.baseText(lhs), this.calleeToType(lhs)),
                returnType, argTypes, true
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
    if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
        // A construction (`Point(1, 2)`): the type operand is the *node* the callee was, so a
        // backend can spell its type arguments as the source wrote them.
        if (!hasDst) {
            this.unsupported("constructor call with no destination")
            return
        }
        // `List<T>()` is the empty list; `List<T>(n)`/`List<T>(n, v)` are the RTL's *count*
        // constructions. A literal is `listOf<T>(a, b, c)`, the `Pack` above.
        operands.append(this.typeIndex(this.baseText(callee), this.calleeToType(callee)))
        i = 0
        while (i < args.size()) {
            operands.append(args[i])
            i = i + 1
        }
        this.emit(IlOpKind.CallCtor, operands)
        return
    }
    // A plain function (or a native - the backend resolves the symbol).
    operands.append(this.methodIndex(calleeName, IlMethodKind.Function, -1, returnType, argTypes, true))
    i = 0
    while (i < args.size()) {
        operands.append(args[i])
        i = i + 1
    }
    this.emitCall(hasDst, operands)
}

fun IlExtractor.emitCall(hasDst: Bool, operands: *List<Int>): Unit {
    if (hasDst) {
        this.emit(IlOpKind.Call, operands)
    } else {
        this.emit(IlOpKind.CallVoid, operands)
    }
}

// The symbol prefix a synthesized class takes: the body's own emitted name.
fun IlExtractor.ownerSymbol(): Str {
    if (this.fn.symbol.isEmpty()) {
        return "_closure_owner"
    }
    return this.fn.symbol
}

// A lambda: a class with one field per captured variable and one method. `dst < 0` means the
// value goes nowhere (the class is still constructed).
fun IlExtractor.lambdaOf(e: *AstXmlNode, dst: Int): Int {
    val counter: Int = this.closureCounter[0]
    this.closureCounter[0] = counter + 1
    val ownerSymbolText: Str = this.ownerSymbol()
    val ilIntTextText: Str = ilIntText(counter)
    val symbol: Str = `@(ownerSymbolText)_closure@ilIntTextText`

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
    val bodyContainer: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Body)
    var bodyList: List<AstXmlNode> = List<AstXmlNode>()
    var bi: Int = 0
    while (bi < bodyContainer.Children.count()) {
        bodyList.append(bodyContainer.Children[bi])
        bi = bi + 1
    }
    ilCollectStmtNames(bodyList, declared, read, readSeen)

    // The closure: what the body reads that the *enclosing* frame holds. A static, a type or a
    // function is not captured.
    var captured: List<Str> = List<Str>()
    for (name in read) {
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

    // Filled by the lambda's own type pass below; `begin` copies it into the body.
    var lambdaTypes: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var info: IlFunction = IlFunction(
        xmlEmptyNode(), xmlEmptyNode(), symbol, this.fn.statics, xmlEmptyNode(),
        paramNames, paramTypes, symbol,
        Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        this.fn.facts, this.fn.typeParams, *lambdaTypes
    )
    var ci: Int = 0
    while (ci < captured.size()) {
        val name: Str = captured[ci]
        info.captures.insert(name, true)
        // The field's type is the enclosing slot's, so a read in the body is typed without inference.
        val slotType: AstXmlNode = ilVarTypeNode(this.out, this.varIndex(name))
        if (!xmlIsEmpty(slotType)) {
            info.captureTypes.insert(name, slotType)
        }
        ci = ci + 1
    }

    // The lambda's body has a frame of its own, so it is lowered and *typed* here, with that
    // frame: parameters and captures. Without this pass its declarations stay untyped, and a
    // slot the frame cannot name sends every spelling decision the wrong way.
    var lowered: List<AstXmlNode> = ilLambdaLower(bodyList)
    val lambdaSemantics: SemBody = SemBody(
        xmlEmptyNode(), this.fn.typeParams, ilNamedTypeNode(symbol), xmlEmptyNode(),
        paramNames, paramTypes, info.captureTypes
    )
    lowered = semInferTypes(lowered, this.fn.facts, lambdaSemantics, lambdaTypes)
    lowered = linFinishForEmission(lowered, paramNames)

    // Type context for the inner extractor, borrowed as `unit` and `closureCounter` are.
    var innerContext: SemBody = ilEmptySemantics()
    var inner: IlExtractor = IlExtractor(
        info, this.unit, this.closureCounter,
        ilEmptyBody(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(),
        Dictionary<Str, Int>(), Dictionary<Str, Int>(), 1, 0,
        List<Int>(), List<Int>(),
        *innerContext, false, Dictionary<Str, AstXmlNode>(), true
    )
    inner.begin(this.out.file)
    val innerBody: IlBody = inner.run(lowered)

    var closure: IlClosure = IlClosure(
        symbol, "", captured, List<AstXmlNode>(),
        List<IlVar>(), this.unit.lambdas.size()
    )
    ci = 0
    while (ci < captured.size()) {
        val capturedType: *AstXmlNode = info.captureTypes.getPtr(captured[ci])
        if (capturedType != null) {
            closure.captureTypes.append(*capturedType)
        } else {
            closure.captureTypes.append(xmlEmptyNode())
        }
        ci = ci + 1
    }
    for (*slot in innerBody.vars) {
        if (slot.kind == IlVarKind.Argument && slot.name != "self") {
            closure.params.append(slot)
        }
    }
    val joinStrsText: Str = joinStrs(captured, ", ")
    val signatureText: Str = innerBody.signature
    closure.signature = `(@joinStrsText) @signatureText`
    this.unit.lambdas.append(innerBody)
    this.unit.closures.append(closure)

    // The value: a construction of the class with the captured values.
    val classType: AstXmlNode = ilNamedTypeNode(symbol)
    var target: Int = dst
    if (dst < 0) {
        target = this.freshSlot(symbol, classType)
    }
    var operands: List<Int> = listOf<Int>(target, this.typeIndex(symbol, classType))
    ci = 0
    while (ci < captured.size()) {
        // The capture is a slot of *this* frame, so the construction passes it by index.
        operands.append(this.varIndex(captured[ci]))
        ci = ci + 1
    }
    this.emit(IlOpKind.CallCtor, operands)
    return target
}

