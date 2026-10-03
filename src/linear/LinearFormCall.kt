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

    // A call whose callee is a *value*: a lambda called where it stands, or one of the
    // lambda's own captured function fields. The value is materialized into a slot first, so
    // the backend makes it a normal call through a local - a free invoke reads the capture
    // as `self.f`, which C++ member lookup used to spell for a member class.
    var callName: Str = calleeName
    if (!member && !staticCall) {
        if (xmlKind(callee) == AstNodeCategory.ExprLambda) {
            val instance: Int = this.lambdaOf(callee, -1, xmlEmptyNode(), xmlEmptyNode())
            callName = this.out.vars[instance].name
        } else if (!calleeName.isEmpty() && this.fn.captures.has(calleeName)) {
            var captureType: AstXmlNode = xmlEmptyNode()
            val found: *AstXmlNode = this.fn.captureTypes.getPtr(calleeName)
            if (found != null) {
                captureType = * found
            }
            var typeText: Str = "?"
            if (!xmlIsEmpty(captureType)) {
                typeText = ilTypeText(captureType)
            }
            val field: Int = this.freshSlot(typeText, captureType)
            this.emit(
                IlOpKind.GetField,
                ilOps3(field, this.varIndex("self"), this.poolIndex(calleeName))
            )
            callName = this.out.vars[field].name
        }
    }

    // A call through a value answers what the value's own type says, so a chained call
    // (`f(41).toString()`) resolves against the result instead of guessing an overload.
    if (hasDst && !member && xmlKind(callee) != AstNodeCategory.ExprMember
        && xmlIsEmpty(ilVarTypeNode(this.out, dst))
    ) {
        val calleeSlot: Int = this.varIndex(callName)
        if (calleeSlot >= 0) {
            val calleeType: AstXmlNode = ilVarType(this.out, calleeSlot)
            val result: AstXmlNode = this.callResultTypeOf(calleeType)
            if (!xmlIsEmpty(result) && ilTypeText(result) != "Unit") {
                this.setSlotType(dst, result)
                returnType = this.out.vars[dst].typeIndex
            }
        }
    }

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
    if (!staticCall && callName == calleeName) {
        var receiverNode: AstXmlNode = xmlEmptyNode()
        if (receiverCall) {
            receiverNode = lhs
        }
        target = this.callTarget(callee, receiverNode, argNodes.size(), receiverCall)
    }
    val packFrom: Int = this.packStart(target, argNodes)

    // A lambda argument to a name several declarations share: the compiler picked none of them
    // (`callTarget` answers nothing when the arity alone does not choose), and the C++ overload
    // resolution would silently take the *plain* parameter - an exact match, no conversion -
    // while the call plainly asks for a callable. Report it, with the instantiation that
    // disambiguates (`pick<Int>(lambda)` picks the callable one).
    if (xmlIsEmpty(target) && packFrom < 0 && callName == calleeName && !staticCall
        && xmlKind(callee) != AstNodeCategory.ExprGenericName
    ) {
        val ambiguity: Str = this.ilConfusingLambdaOverload(callee, receiverCall, *argNodes)
        if (ambiguity != "") {
            this.unsupported(ambiguity)
            return
        }
    }

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
    // A generic callee's bindings, as far as this call fixes them: used below to substitute a
    // callable parameter's type, so a lambda's *omitted* parameter types can come from the
    // argument that fixes the type parameter (`twice((x) -> x + 1, 5)`), and to spell an
    // explicit instantiation where C++ cannot deduce (`peek((x: Int) -> x + 1)`).
    var genericParams: List<Str> = List<Str>()
    var genericBindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    // The last type parameter C++ cannot deduce at this call (a callable parameter names it,
    // and MSVC deduces nothing through a `std::function` shape). The compiler's own binding for
    // it is spelled as an explicit instantiation through this index; -1 means nothing to spell.
    var explicitNeeded: Int = -1
    if (!xmlIsEmpty(target)) {
        genericParams = xmlTypeParamNames(target)
        if (genericParams.size() > 0) {
            val typeArgs: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
            var t: Int = 0
            while (t < typeArgs.size() && t < genericParams.size()) {
                semBindOne(*genericBindings, genericParams[t], typeArgs[t])
                t = t + 1
            }
            var recvType: AstXmlNode = xmlEmptyNode()
            if (receiverCall && recvSlot >= 0) {
                recvType = ilVarType(this.out, recvSlot)
            }
            var argTypes: List<AstXmlNode> = List<AstXmlNode>()
            var g: Int = 0
            while (g < argNodes.size()) {
                argTypes.append(this.exprType(argNodes[g]))
                g = g + 1
            }
            semBindCallArgs(target, recvType, *argTypes, *genericParams, *genericBindings)
            // A lambda's own annotations bind what no argument could (`peek((x: Int) -> x + 1)`),
            // and a callable *value* binds the callable pattern it is passed into (`peek(fn)`).
            var g2: Int = 0
            while (g2 < argNodes.size() && paramOffset + g2 < paramNodes.size()) {
                val pattern: AstXmlNode = xmlChildPtr(paramNodes[paramOffset + g2], AstNodeKind.Type)
                if (xmlKind(argNodes[g2]) == AstNodeCategory.ExprLambda) {
                    this.ilBindLambdaParams(pattern, argNodes[g2], *genericParams, *genericBindings)
                    this.ilBindLambdaReturn(pattern, argNodes[g2], *genericParams, *genericBindings)
                } else if (g2 < argTypes.size() && !xmlIsEmpty(argTypes[g2])) {
                    this.ilBindCallableArg(pattern, argTypes[g2], *genericParams, *genericBindings)
                }
                g2 = g2 + 1
            }
            // What *C++* can deduce is less: MSVC deduces no template parameter through a
            // `std::function` shape, so a parameter only a callable names has nothing to
            // instantiate the template with. The compiler's binding for it is spelled as an
            // explicit instantiation (`peek<Int>(...)`) when the call can carry one - a plain
            // call with a destination and no type arguments of its own - and reported
            // otherwise, rather than left to fail under MSVC's own wording.
            //
            // The target is found by name (and receiver, when the names collide), so a member
            // call's target must first *be* this call's callee: `list.clear()` on a `List`
            // resolves to the one `clear` fact there is (`Dictionary.clear`), and those type
            // parameters are not this call's.
            var targetIsCallee: Bool = true
            if (receiverCall && recvSlot >= 0) {
                var recvPattern: AstXmlNode = xmlChild(target, AstNodeKind.Receiver)
                if (xmlIsEmpty(recvPattern)) {
                    recvPattern = semExtensionReceiver(target)
                }
                if (!xmlIsEmpty(recvPattern)) {
                    targetIsCallee = semUnifyType(
                        recvPattern, ilVarType(this.out, recvSlot), *genericParams
                    )
                }
            }
            if (targetIsCallee && packFrom < 0 && !semMachineReceiverDecl(target)) {
                var deducible: Dictionary<Str, AstXmlNode> = this.ilCppDeducible(
                    target, recvType, *argTypes, xmlChildren(callee, AstNodeKind.TypeArg), *genericParams
                )
                var needed: Int = -1
                var m: Int = 0
                while (m < genericParams.size()) {
                    if (!deducible.has(genericParams[m])) {
                        needed = m
                    }
                    m = m + 1
                }
                if (needed >= 0) {
                    var haveAll: Bool = typeArgs.size() == 0
                    var k: Int = 0
                    while (k <= needed) {
                        if (!genericBindings.has(genericParams[k])) {
                            haveAll = false
                        }
                        k = k + 1
                    }
                    if (haveAll && hasDst && plain == argNodes.size() && !receiverCall) {
                        explicitNeeded = needed
                    } else {
                        var missing: Str = ""
                        var missingCount: Int = 0
                        m = 0
                        while (m < genericParams.size()) {
                            if (!deducible.has(genericParams[m])) {
                                missingCount = missingCount + 1
                                if (missing != "") {
                                    missing = missing + ", "
                                }
                                missing = missing + "'" + genericParams[m] + "'"
                            }
                            m = m + 1
                        }
                        var what: Str = "type parameter "
                        var them: Str = "it"
                        if (missingCount > 1) {
                            what = "type parameters "
                            them = "them"
                        }
                        this.unsupported(
                            `cannot infer @what@missing of '@calleeName': no argument names @them (a callable argument does not); pass @them explicitly ('@calleeName<...>(...)')`
                        )
                        return
                    }
                }
            }
        }
    }
    var i: Int = 0
    while (i < plain) {
        var param: AstXmlNode = xmlEmptyNode()
        if (paramOffset + i < paramNodes.size()) {
            param = xmlChildPtr(paramNodes[paramOffset + i], AstNodeKind.Type)
        }
        if (xmlKind(argNodes[i]) == AstNodeCategory.ExprLambda && genericParams.size() > 0
            && !xmlIsEmpty(param)
        ) {
            val mapped: AstXmlNode = semSubstitute(param, *genericBindings, *genericParams)
            if (!xmlIsEmpty(mapped)) {
                param = mapped
            }
        }
        // The parameter with the call's bindings substituted: a generic parameter's slot must
        // be typed with `*List<Int>`, not the declaration's `*List<T>` (there is no `T` at the
        // call).
        var paramType: AstXmlNode = param
        if (!xmlIsEmpty(param) && genericParams.size() > 0 && genericBindings.size() > 0) {
            val mapped: AstXmlNode = semSubstitute(param, *genericBindings, *genericParams)
            if (!xmlIsEmpty(mapped)) {
                paramType = mapped
            }
        }
        val slot: Int = this.convertArgument(paramOwner, paramType, argNodes[i])
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
    if (explicitNeeded >= 0) {
        // C++ cannot deduce these (a callable parameter names them) and the compiler knows the
        // binding: spell the instantiation, a prefix through the last parameter that needs one.
        // A `CallCtor` is how an explicit instantiation already rides the IL (`f<Int>(...)`).
        var explicitType: AstXmlNode = AstXmlNode(
            AstNodeKind.Type, AstNodeCategory.TypeGeneric,
            List<AstNodeAttribute>(), Array<AstXmlNode>()
        )
        explicitType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, calleeName))
        var k: Int = 0
        while (k <= explicitNeeded) {
            val bound: *AstXmlNode = genericBindings.getPtr(genericParams[k])
            if (bound != null) {
                xmlAddChild(explicitType, semReRole(*bound, AstNodeKind.TypeArg))
            }
            k = k + 1
        }
        operands.append(this.typeIndex(calleeName, explicitType))
        i = 0
        while (i < args.size()) {
            operands.append(args[i])
            i = i + 1
        }
        this.emit(IlOpKind.CallCtor, operands)
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
    // A plain function (or a native - the backend resolves the symbol). A callee that is a
    // value was materialized above, so its slot name is the method's own.
    operands.append(this.methodIndex(callName, IlMethodKind.Function, -1, returnType, argTypes, true))
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

// The type parameters of `target` the *C++ call* can deduce: the explicit instantiation (if
// the source wrote one), the receiver, and the arguments whose parameter is not callable -
// MSVC deduces nothing through a `std::function` shape, whether the argument is a closure, a
// named function or a `Func<...>` value (`emitDeducedCallableOverload` is the other half:
// it lets an argument deduce a parameter, never a callable parameter deduce its own type).
fun IlExtractor.ilCppDeducible(
    target: *AstXmlNode, recvType: AstXmlNode, argTypes: *List<AstXmlNode>,
    explicitArgs: *List<AstXmlNode>, params: *List<Str>
): Dictionary<Str, AstXmlNode> {
    var found: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var t: Int = 0
    while (t < explicitArgs.size() && t < params.size()) {
        semBindOne(*found, params[t], explicitArgs[t])
        t = t + 1
    }
    if (!xmlIsEmpty(recvType)) {
        var pattern: AstXmlNode = xmlChild(target, AstNodeKind.Receiver)
        if (xmlIsEmpty(pattern)) {
            pattern = semExtensionReceiver(target)
        }
        if (!xmlIsEmpty(pattern)) {
            semBindBestEffort(pattern, recvType, params, *found)
        }
    }
    val declParams: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
    val offset: Int = semReceiverParams(target)
    var i: Int = 0
    while (i < argTypes.size()) {
        val at: Int = offset + i
        if (at >= declParams.size()) {
            break
        }
        val pattern: *AstXmlNode = xmlChildPtr(declParams[at], AstNodeKind.Type)
        val actual: AstXmlNode = argTypes[i]
        i = i + 1
        if (xmlIsEmpty(pattern) || xmlIsEmpty(actual)) {
            continue
        }
        if (xmlKind(pattern) == AstNodeCategory.TypeFunction) {
            continue
        }
        if (!xmlIsEmpty(this.ilCallableOf(actual))) {
            continue
        }
        semBindBestEffort(semBindArgShape(*pattern, actual), actual, params, *found)
    }
    return found
}

// A lambda argument's *annotated* parameter types bind the callable parameter's own type
// parameters (`peek((x: Int) -> x + 1)` fixes `T = Int`): C++ cannot deduce them through the
// callable, so the binding is what lets the emitter spell `peek<Int>`. A lambda whose
// parameter types are only partly written, or a pattern that is not a callable type, binds
// nothing here - the ordinary diagnostic covers it.
fun IlExtractor.ilBindLambdaParams(
    pattern: AstXmlNode, lambda: *AstXmlNode, params: *List<Str>,
    bindings: *Dictionary<Str, AstXmlNode>
): Unit {
    if (params.size() == 0 || xmlKind(lambda) != AstNodeCategory.ExprLambda) {
        return
    }
    val callable: AstXmlNode = this.ilCallableOf(pattern)
    if (xmlIsEmpty(callable)) {
        return
    }
    val annotated: List<AstXmlNode> = xmlChildren(lambda, AstNodeKind.ParamType)
    if (annotated.size() != ilSplitParams(xmlAttr(lambda, AstNodeAttributeKind.Params)).size()) {
        return
    }
    val expectedParams: List<AstXmlNode> = xmlChildren(callable, AstNodeKind.ParamType)
    var i: Int = 0
    while (i < expectedParams.size() && i < annotated.size()) {
        semBindBestEffort(expectedParams[i], annotated[i], params, bindings)
        i = i + 1
    }
}

// A lambda argument's *result* binds the callable pattern's return type where only the body
// can name it (`select<T, U>((x: *Int) -> *x * 2)` fixes `U = Int`): the type pass infers a
// lambda's result (`SemInfer.infer`'s ExprLambda case), and this is the one place the call
// can take it from.
fun IlExtractor.ilBindLambdaReturn(
    pattern: AstXmlNode, lambda: *AstXmlNode, params: *List<Str>,
    bindings: *Dictionary<Str, AstXmlNode>
): Unit {
    if (params.size() == 0 || xmlKind(lambda) != AstNodeCategory.ExprLambda) {
        return
    }
    val callable: AstXmlNode = this.ilCallableOf(pattern)
    if (xmlIsEmpty(callable)) {
        return
    }
    val patternReturn: *AstXmlNode = xmlChildPtr(callable, AstNodeKind.ReturnType)
    if (xmlIsEmpty(patternReturn)) {
        return
    }
    val inferred: AstXmlNode = this.exprType(lambda)
    if (xmlIsEmpty(inferred) || xmlKind(inferred) != AstNodeCategory.TypeFunction) {
        return
    }
    val actualReturn: *AstXmlNode = xmlChildPtr(inferred, AstNodeKind.ReturnType)
    if (xmlIsEmpty(actualReturn)) {
        return
    }
    semBindBestEffort(*patternReturn, *actualReturn, params, bindings)
}

// A callable *value* passed into a callable pattern binds the pattern's own parameter and
// return types (`peek(fn)` with `fn: (Int) -> Int` fixes `T = Int`); both sides follow their
// `typealias` first. A callable pattern never binds through `semBindTypes` (it has no
// `TypeFunction` arm), which is why this is here and not in the shared binder.
fun IlExtractor.ilBindCallableArg(
    pattern: AstXmlNode, actualType: AstXmlNode, params: *List<Str>,
    bindings: *Dictionary<Str, AstXmlNode>
): Unit {
    val patternCallable: AstXmlNode = this.ilCallableOf(pattern)
    if (xmlIsEmpty(patternCallable)) {
        return
    }
    val actualCallable: AstXmlNode = this.ilCallableOf(actualType)
    if (xmlIsEmpty(actualCallable)) {
        return
    }
    val patternParams: List<AstXmlNode> = xmlChildren(patternCallable, AstNodeKind.ParamType)
    val actualParams: List<AstXmlNode> = xmlChildren(actualCallable, AstNodeKind.ParamType)
    var i: Int = 0
    while (i < patternParams.size() && i < actualParams.size()) {
        semBindBestEffort(patternParams[i], actualParams[i], params, bindings)
        i = i + 1
    }
    val patternReturn: *AstXmlNode = xmlChildPtr(patternCallable, AstNodeKind.ReturnType)
    val actualReturn: *AstXmlNode = xmlChildPtr(actualCallable, AstNodeKind.ReturnType)
    if (!xmlIsEmpty(patternReturn) && !xmlIsEmpty(actualReturn)) {
        semBindBestEffort(*patternReturn, *actualReturn, params, bindings)
    }
}

// The report for a lambda argument that several of a name's declarations both accept: the
// compiler picked none of them (`callTarget` answers nothing when the arity alone does not
// choose), and the C++ overload resolution would silently take the *plain* parameter - the
// exact match, no conversion - while the call plainly asks for a callable. The candidate
// sets must stay apart: one declaration that takes both shapes does not make a call
// ambiguous. "" when the call is not this shape.
fun IlExtractor.ilConfusingLambdaOverload(
    callee: *AstXmlNode, member: Bool, argNodes: *List<AstXmlNode>
): Str {
    if (this.fn.facts == null) {
        return ""
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    var callableCandidates: Int = 0
    var valueCandidates: Int = 0
    var fi: Int = 0
    while (fi < this.fn.facts.functions.size()) {
        val fact: *SemFnFact = *this.fn.facts.functions[fi]
        fi = fi + 1
        if (xmlIsEmpty(fact.decl) || fact.name != name) {
            continue
        }
        val factMember: Bool = !xmlIsEmpty(fact.receiver) || (member && fact.isExtension)
        if (factMember != member) {
            continue
        }
        if (fact.paramCount != argNodes.size()) {
            continue
        }
        val params: List<AstXmlNode> = xmlChildren(fact.decl, AstNodeKind.Param)
        val offset: Int = semReceiverParams(fact.decl)
        var takesCallable: Bool = false
        var takesValue: Bool = false
        var a: Int = 0
        while (a < argNodes.size()) {
            if (xmlKind(argNodes[a]) == AstNodeCategory.ExprLambda) {
                val at: Int = offset + a
                if (at < params.size()) {
                    val pattern: *AstXmlNode = xmlChildPtr(params[at], AstNodeKind.Type)
                    if (!xmlIsEmpty(pattern)) {
                        if (!xmlIsEmpty(this.ilCallableOf(*pattern))) {
                            takesCallable = true
                        } else if (semIsBareTypeParam(fact.decl, pattern)) {
                            takesValue = true
                        }
                    }
                }
            }
            a = a + 1
        }
        if (takesCallable && !takesValue) {
            callableCandidates = callableCandidates + 1
        }
        if (takesValue) {
            valueCandidates = valueCandidates + 1
        }
    }
    if (callableCandidates > 0 && valueCandidates > 0) {
        return `ambiguous call of '@name': a lambda argument fits a declaration taking a callable and one taking a plain value (C++ would silently take the plain one); pass the type arguments explicitly ('@name<...>(...)')`
    }
    return ""
}

// The symbol prefix a synthesized class takes: the body's own emitted name.
fun IlExtractor.ownerSymbol(): Str {
    if (this.fn.symbol.isEmpty()) {
        return "_closure_owner"
    }
    return this.fn.symbol
}

// A lambda: a data class with one field per captured variable and a free method. `dst < 0`
// means the value goes nowhere (the class is still constructed); `expected` is the callable
// type the lambda is used against, when the position names one - it supplies omitted
// parameter types and the result type. `owner` is the declaration the expected type came
// from (a generic callee), whose type parameters are not spellable at the lambda's site.
fun IlExtractor.lambdaOf(e: *AstXmlNode, dst: Int, expected: AstXmlNode, owner: AstXmlNode): Int {
    val counter: Int = this.closureCounter[0]
    this.closureCounter[0] = counter + 1
    val ownerSymbolText: Str = this.ownerSymbol()
    val ilIntTextText: Str = ilIntText(counter)
    val symbol: Str = `@(ownerSymbolText)_closure@ilIntTextText`

    var read: List<Str> = List<Str>()
    var readSeen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var declared: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    val paramNames: List<Str> = ilSplitParams(xmlAttr(e, AstNodeAttributeKind.Params))
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

    // The callable type the lambda is used against: it supplies the parameter types the
    // lambda omitted (a `typealias` is followed) and the result type. A piece that names one
    // of the *callee's* type parameters (`apply<T>(...)` with `T` bound at the call) is not
    // spellable where the lambda stands, so it is treated as absent.
    val callable: AstXmlNode = this.ilCallableOf(expected)
    var unspellable: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*typeParam in xmlTypeParamNames(owner)) {
        unspellable.insert(typeParam, true)
    }

    // The parameter types: the lambda's own annotations when it has one per name, else the
    // callable type's. A lambda with neither is reported by the emitter, at the lambda.
    var paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
    if (paramTypes.size() != paramNames.size()) {
        paramTypes = List<AstXmlNode>()
        if (!xmlIsEmpty(callable)) {
            val expectedParams: List<AstXmlNode> = xmlChildren(callable, AstNodeKind.ParamType)
            if (expectedParams.size() == paramNames.size()) {
                for (*expectedParam in expectedParams) {
                    if (ilTypeMentions(expectedParam, *unspellable)) {
                        paramTypes.append(xmlEmptyNode())
                    } else {
                        paramTypes.append(expectedParam)
                    }
                }
            }
        }
    }

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

    // The lambda's result: the callable type's when it names one that stands where the
    // lambda does.
    var returnType: AstXmlNode = xmlEmptyNode()
    if (!xmlIsEmpty(callable)) {
        val declaredReturn: AstXmlNode = xmlChild(callable, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(declaredReturn) && !ilTypeMentions(declaredReturn, *unspellable)) {
            returnType = semReRole(declaredReturn, AstNodeKind.Type)
        }
    }

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

    // The type pass could not name every return a plain expression spells is one thing; what
    // the extractor proved (`null`, a nested lambda) is the last word.
    if (xmlIsEmpty(returnType)) {
        returnType = this.lambdaReturnOf(lowered, *lambdaSemantics, *lambdaTypes, *innerBody)
    }

    var closure: IlClosure = IlClosure(
        symbol, "", captured, List<AstXmlNode>(),
        List<IlVar>(), List<AstXmlNode>(), returnType, this.fn.typeParams,
        xmlLine(e), xmlColumn(e), this.unit.lambdas.size()
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
    ci = 0
    while (ci < paramNames.size()) {
        if (ci < paramTypes.size()) {
            closure.paramTypes.append(paramTypes[ci])
        } else {
            closure.paramTypes.append(xmlEmptyNode())
        }
        ci = ci + 1
    }
    var paramTexts: List<Str> = List<Str>()
    for (*slot in innerBody.vars) {
        if (slot.kind != IlVarKind.Argument || slot.name == "self") {
            continue
        }
        var typeText: Str = "?"
        if (slot.typeIndex >= 0 && slot.typeIndex < innerBody.types.size()) {
            typeText = innerBody.types[slot.typeIndex]
        }
        val slotNameText: Str = slot.name
        paramTexts.append(`@typeText @slotNameText`)
    }
    var retText: Str = "void"
    if (!xmlIsEmpty(returnType)) {
        retText = ilTypeText(returnType)
    }
    val joinStrsText: Str = joinStrs(captured, ", ")
    val paramsText: Str = joinStrs(paramTexts, ", ")
    closure.signature = `(@joinStrsText) (@paramsText) -> @retText`
    this.unit.lambdas.append(innerBody)
    this.unit.closures.append(closure)

    // The value: a construction of the class with the captured values.
    val classType: AstXmlNode = ilNamedTypeNode(symbol)
    var target: Int = dst
    if (dst < 0) {
        target = this.freshSlot(symbol, classType)
    } else if (xmlIsEmpty(ilVarTypeNode(this.out, dst))) {
        // `val f = <lambda>`: the closure class *is* the value's type, so the slot is
        // declared with it - no `auto` in the emitted C++, and a call reaches `_invoke`.
        this.setSlotType(dst, classType)
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

// Whether a type node names one of the given type parameters, at any depth.
fun ilTypeMentions(node: *AstXmlNode, names: *Dictionary<Str, Bool>): Bool {
    if (names.size() == 0) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        if (names.has(xmlAttr(node, AstNodeAttributeKind.Name))) {
            return true
        }
    }
    for (*child in node.Children) {
        if (ilTypeMentions(child, names)) {
            return true
        }
    }
    return false
}

// What a value of `typeNode` answers when it is called: a callable type's result, or a
// closure class's own lambda result. Empty for anything else.
fun IlExtractor.callResultTypeOf(typeNode: AstXmlNode): AstXmlNode {
    val callable: AstXmlNode = this.ilCallableOf(typeNode)
    if (!xmlIsEmpty(callable)) {
        val declaredReturn: AstXmlNode = xmlChild(callable, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(declaredReturn)) {
            return semReRole(declaredReturn, AstNodeKind.Type)
        }
    }
    if (xmlKind(typeNode) == AstNodeCategory.TypeNamed) {
        val symbol: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        for (*closure in this.unit.closures) {
            if (closure.symbol == symbol && !xmlIsEmpty(closure.returnType)) {
                return closure.returnType
            }
        }
    }
    return xmlEmptyNode()
}

// The callable type a lambda is used against, following a `typealias` chain the way the
// type rules do (`SemInfer.resolveAlias`); empty when the type is not one.
fun IlExtractor.ilCallableOf(expected: AstXmlNode): AstXmlNode {
    var current: AstXmlNode = expected
    var guard: Int = 0
    while (!xmlIsEmpty(current) && guard < 16) {
        guard = guard + 1
        if (xmlKind(current) == AstNodeCategory.TypeFunction) {
            return current
        }
        if (xmlKind(current) != AstNodeCategory.TypeNamed) {
            return xmlEmptyNode()
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.fn.facts.types.getPtr(name)
        if (decl == null) {
            return xmlEmptyNode()
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            return xmlEmptyNode()
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            return xmlEmptyNode()
        }
        current = * target
    }
    return xmlEmptyNode()
}

// A lambda's result where the callable type did not name one: the type of the first `return`
// the type pass proved, else what the extractor's own slots carry (a `null`, a nested
// lambda's class). Empty means the lambda answers nothing.
fun IlExtractor.lambdaReturnOf(
    lowered: *List<AstXmlNode>, ctx: *SemBody, names: *Dictionary<Str, AstXmlNode>,
    body: *IlBody
): AstXmlNode {
    for (*stmt in lowered) {
        val fromStmt: AstXmlNode = ilFirstReturnType(stmt, this.fn.facts, ctx, names)
        if (!xmlIsEmpty(fromStmt)) {
            return fromStmt
        }
    }
    var i: Int = 0
    while (i < body.ops.size()) {
        val op: *IlOp = *body.ops[i]
        i = i + 1
        if (op.kind != IlOpKind.Return || op.operands.size() == 0) {
            continue
        }
        val typeNode: AstXmlNode = ilVarTypeNode(*body, op.operands[0])
        if (!xmlIsEmpty(typeNode)) {
            return typeNode
        }
    }
    return xmlEmptyNode()
}

// The type of the first `return` in one statement, at any depth; a nested lambda is its own
// body and does not answer this one.
fun ilFirstReturnType(
    stmt: *AstXmlNode, facts: *SemFacts, ctx: *SemBody, names: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    if (xmlKind(stmt) == AstNodeCategory.ExprLambda) {
        return xmlEmptyNode()
    }
    if (xmlKind(stmt) == AstNodeCategory.StmtReturn) {
        val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
        if (xmlIsEmpty(value)) {
            return xmlEmptyNode()
        }
        val proven: AstXmlNode = semTypeOfExpr(value, facts, ctx, names)
        if (!xmlIsEmpty(proven)) {
            return semReRole(proven, AstNodeKind.Type)
        }
        return xmlEmptyNode()
    }
    for (*child in stmt.Children) {
        val found: AstXmlNode = ilFirstReturnType(child, facts, ctx, names)
        if (!xmlIsEmpty(found)) {
            return found
        }
    }
    return xmlEmptyNode()
}
