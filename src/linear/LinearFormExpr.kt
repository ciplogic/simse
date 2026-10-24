// LinearFormExpr.kt
//
// `IlExtractor`'s expression, place and call lowering, and the lambda/pack helpers it uses.
// Extension methods on `IlExtractor` (LinearForm.kt).

package linear

import compiler

import common
import sema


// `x.initByValue(args)` for `val/var x = T(args)` when `T` declares an `initByValue`
// extension; empty otherwise. `initByValue` is an extension on the instance, so `x`
// (already default-built) is its receiver.
fun IlExtractor.ilInitByValueStmt(x: *Str, e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprCall) {
        return xmlEmptyNode()
    }
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    val calleeKind: AstNodeCategory = xmlKind(callee)
    if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprGenericName) {
        return xmlEmptyNode()
    }
    val typeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (!this.hasInitByValueExt(typeName)) {
        return xmlEmptyNode()
    }
    var recvAttrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Name, x)
    )
    var recv: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, recvAttrs, Array<AstXmlNode>())
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, List<AstNodeAttribute>(), Array<AstXmlNode>())
    member.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, "initByValue"))
    xmlAddChild(member, linRole(recv, AstNodeKind.Receiver))
    var call: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>())
    xmlAddChild(call, linRole(member, AstNodeKind.Callee))
    for (*arg in xmlChildren(e, AstNodeKind.Arg)) {
        xmlAddChild(call, linRole(arg, AstNodeKind.Arg))
    }
    return call
}

// Whether the type declares an `initByValue` extension (the construction convention).
fun IlExtractor.hasInitByValueExt(typeName: *Str): Bool {
    if (this.fn.facts == null) {
        return false
    }
    val named: *List<Int> = this.fn.facts.functionsByName.getPtr("initByValue")
    if (named == null) {
        return false
    }
    for (index in named) {
        val fact: *SemFnFact = *this.fn.facts.functions[index]
        if (xmlAttr(this.receiverPattern(fact), AstNodeAttributeKind.Name) == typeName) {
            return true
        }
    }
    return false
}

fun IlExtractor.isTypeBase(e: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(e)
    if (kind == AstNodeCategory.ExprGenericName) {
        return true
    }
    if (kind != AstNodeCategory.ExprName) {
        return false
    }
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    if (name == "this" || this.hasVar(name)) {
        return false
    }
    if (this.fn.captures.has(name)) {
        return false
    }
    return !this.fn.statics.has(name)
}

// A name that is file-level static storage rather than a slot of the frame.
fun IlExtractor.isStaticName(name: *Str): Bool {
    if (this.hasVar(name)) {
        return false
    }
    return this.fn.statics.has(name)
}

fun IlExtractor.baseText(e: *AstXmlNode): Str {
    if (xmlKind(e) == AstNodeCategory.ExprName) {
        return xmlAttr(e, AstNodeAttributeKind.Name)
    }
    return ilTypeText(this.calleeToType(e))
}

// The type a `GenericName` names: a fresh generic node from its name and type arguments.
fun IlExtractor.calleeToType(e: *AstXmlNode): AstXmlNode {
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    if (xmlKind(e) == AstNodeCategory.ExprGenericName) {
        var node: AstXmlNode = AstXmlNode(
            AstNodeKind.Type, AstNodeCategory.TypeGeneric,
            List<AstNodeAttribute>(), Array<AstXmlNode>()
        )
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        val args: List<AstXmlNode> = xmlChildren(e, AstNodeKind.TypeArg)
        for (*typeArg in args) {
            var arg: AstXmlNode = typeArg
            arg.name = AstNodeKind.TypeArg
            xmlAddChild(node, arg)
        }
        return node
    }
    return ilNamedTypeNode(name)
}

// A literal expression's type: a literal operand is a value with a type like any other, so
// the method table's `argTypes` read the same for a slot or a constant.
fun IlExtractor.literalTypeText(e: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprIntLit -> {
            return "Int"
        }

        AstNodeCategory.ExprFloatLit -> {
            return "Float64"
        }

        AstNodeCategory.ExprStrLit -> {
            return "Str"
        }

        AstNodeCategory.ExprCharLit -> {
            return "Char"
        }

        AstNodeCategory.ExprBoolLit -> {
            return "Bool"
        }
    }
    return "?"
}

fun IlExtractor.operandType(slot: Int, expr: *AstXmlNode): Int {
    if (slot >= 0) {
        return this.out.vars[slot].typeIndex
    }
    if (xmlIsEmpty(expr)) {
        return this.typeIndexText("?")
    }
    return this.typeIndexText(this.literalTypeText(expr))
}

// The symbol a `native("...")` declaration names, without its quotes.
fun IlExtractor.ilNativeSymbolOf(decl: *AstXmlNode): Str {
    val text: Str = xmlAttr(decl, AstNodeAttributeKind.NativeSymbol)
    if (text.size() >= 2 && text[0] == '\"' && text[text.size() - 1] == '\"') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// Whether a call's target is the prelude's list literal (`rtl.kt`'s `listOf<T>`).
fun IlExtractor.ilIsListOf(target: *AstXmlNode): Bool {
    return xmlAttr(target, AstNodeAttributeKind.IsNative) == "true"
            && xmlAttr(target, AstNodeAttributeKind.HasNativeSymbol) == "true"
            && this.ilNativeSymbolOf(target) == "simse_listOf"
}

// The `List<T>` a `listOf<T>(...)` names: its written type argument, or - when it was
// written without one, which the language infers - the type of its first element.
fun IlExtractor.ilListOfType(callee: AstXmlNode, args: List<AstXmlNode>, from: Int): AstXmlNode {
    if (xmlKind(callee) == AstNodeCategory.ExprGenericName
        && xmlCount(callee, AstNodeKind.TypeArg) == 1
    ) {
        return semGenericType("List", xmlChildren(callee, AstNodeKind.TypeArg))
    }
    if (from < args.size()) {
        val element: AstXmlNode = this.exprType(args[from])
        if (!xmlIsEmpty(element)) {
            var typeArgs: List<AstXmlNode> = List<AstXmlNode>()
            typeArgs.append(semReRole(element, AstNodeKind.TypeArg))
            return semGenericType("List", typeArgs)
        }
    }
    return xmlEmptyNode()
}

// The declaration a call resolves to, by the checker's rule: an exact parameter count
// first, then a last parameter a call may pack into. A member call also resolves by its
// receiver's type. An ambiguous name resolves to nothing (no packing, no conversion), and
// so does a call whose facts are unavailable.
// The resolved declaration of a call, as its index into the facts (-1 when nothing
// matched): the caller reads the declaration and the package the C++ name spells from.
fun IlExtractor.callTarget(callee: *AstXmlNode, receiver: *AstXmlNode, argNodes: List<AstXmlNode>, member: Bool): Int {
    val argCount: Int = argNodes.size()
    if (this.fn.facts == null) {
        return -1
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    // The candidates are *indices* into the facts, not the facts themselves: a `SemFnFact`
    // carries its declaration node and attribute list, so a copy per candidate would be work.
    // The name index narrows the walk to this name's declarations; receiver form and arity
    // stay per candidate below.
    val named: *List<Int> = this.fn.facts.functionsByName.getPtr(name)
    if (named == null) {
        return -1
    }
    var candidates: List<Int> = List<Int>()
    for (index in named) {
        val fact: *SemFnFact = *this.fn.facts.functions[index]
        if (xmlIsEmpty(fact.decl)) {
            continue
        }
        val factMember: Bool = !xmlIsEmpty(fact.receiver)
                || (member && fact.isExtension)
        if (factMember != member) {
            continue
        }
        candidates.append(index)
    }
    if (candidates.size() > 1 && member && !xmlIsEmpty(receiver)) {
        val receiverType: AstXmlNode = this.exprType(receiver)
        val recv: AstXmlNode = semPointeeOf(receiverType)
        var matching: List<Int> = List<Int>()
        for (index in candidates) {
            val fact: *SemFnFact = *this.fn.facts.functions[index]
            val pattern: AstXmlNode = this.receiverPattern(fact)
            if (!xmlIsEmpty(recv) && !xmlIsEmpty(pattern)) {
                var matches: Bool = semUnifyType(pattern, recv, fact.templateParams)
                if (!matches) {
                    // A method declared on a base resolves on a derived receiver
                    // (specs/declarations.md, "Inheritance").
                    val patternName: Str = semClassTypeName(*this.fn.facts.types, pattern)
                    val recvName: Str = semClassTypeName(*this.fn.facts.types, recv)
                    matches = patternName != "" && recvName != ""
                            && semDerivesFromName(*this.fn.facts.types, recvName, patternName)
                }
                if (!matches) {
                    continue // another type's method of the same name
                }
            }
            matching.append(index)
        }
        candidates = matching
    }
    var exact: Int = -1
    var pack: Int = -1
    var exactCount: Int = 0
    var packCount: Int = 0
    var k: Int = 0
    while (k < candidates.size()) {
        val at: Int = candidates[k]
        k++
        val fact: *SemFnFact = *this.fn.facts.functions[at]
        // The count and the pack test are the fact's own, so a candidate that does not match by
        // arity needs no parameter list.
        val paramCount: Int = fact.paramCount
        if (paramCount == argCount) {
            exact = at
            exactCount++
            continue
        }
        if (fact.packTarget && argCount >= paramCount - 1) {
            pack = at
            packCount++
        }
    }
    if (exactCount > 0) {
        if (exactCount == 1) {
            return exact
        }
        // Several same-arity declarations of the name: the arguments' own types pick the
        // overload - the `xmlAtEnd` pair (minui's `*XmlCursor`, xml's `*XmlParser`) is the
        // shape. An argument whose type the rules cannot name leaves the field unresolved
        // (the ambiguity is not this call's to settle), and so does a field where no
        // candidate's parameters all unify - the wrong-overload spelling C++ cannot
        // compile is worse than the failure here.
        var matched: Int = -1
        var matchedCount: Int = 0
        var k2: Int = 0
        while (k2 < candidates.size()) {
            val fact: *SemFnFact = *this.fn.facts.functions[candidates[k2]]
            k2++
            if (fact.paramCount != argCount) {
                continue
            }
            var unify: Bool = true
            var t: Int = 0
            while (t < argCount && unify) {
                val argType: AstXmlNode = this.exprType(argNodes[t])
                if (xmlIsEmpty(argType)) {
                    unify = false
                    break
                }
                val params: List<AstXmlNode> = xmlChildren(fact.decl, AstNodeKind.Param)
                val paramType: *AstXmlNode = xmlChildPtr(params[t], AstNodeKind.Type)
                if (xmlIsEmpty(paramType)) {
                    unify = false
                    break
                }
                // A pointer parameter takes a value argument of its pointee (the borrow
                // conversion the call applies later): `xmlSkipMisc(*p)` with a `*XmlParser`
                // parameter reads the argument as the value, so the comparison strips the
                // parameter's handle before unifying.
                unify = semUnifyType(*paramType, argType, fact.templateParams)
                if (!unify) {
                    val paramPointee: AstXmlNode = semPointeeOf(paramType)
                    if (!xmlIsEmpty(paramPointee)) {
                        unify = semUnifyType(paramPointee, argType, fact.templateParams)
                    }
                }
                t++
            }
            if (unify) {
                matched = candidates[k2 - 1]
                matchedCount++
            }
            if (name == "xmlSkipMisc") {
                var arg0: AstXmlNode = this.exprType(argNodes[0])
                if (xmlIsEmpty(arg0)) {
                    println("skipMisc arg UNKNOWN")
                } else {
                    println("skipMisc arg known")
                }
            }
        }
        if (name == "xmlSkipMisc") {
            println("narrow done")
        }
        if (matchedCount == 1) {
            return matched
        }
        return -1
    }
    if (packCount == 1) {
        return pack
    }
    return -1
}

// Whether `e` builds a value of a declared data class - `Box<Int>(3)` by its generic
// name, `Res(7)` by a bare one - which is what makes `&e` a box to build in place. A
// container's construction (`List<Int>(n)`) is not one: it is boxed like any value, and
// neither is a `union class` construction, whose only spelling is the generated
// `initByValue` arms - so `&U(v)` boxes a *value* like the container, the shape the
// promotion can turn into a stack pointer (`PromoteRefs.kt`).
fun IlExtractor.isBoxedConstruction(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprCall) {
        return false
    }
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    val kind: AstNodeCategory = xmlKind(callee)
    if (kind != AstNodeCategory.ExprGenericName && kind != AstNodeCategory.ExprName) {
        return false
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (name == "") {
        return false
    }
    val decl: AstXmlNode = this.dataClassDecl(name)
    if (xmlIsEmpty(decl)) {
        return false
    }
    return xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) != "true"
}

// `Ctor(args)` as the construction it is: a generic name already is one, and a bare name
// is re-spelled as one, because `call` takes its construction branch on the callee's kind.
fun IlExtractor.asConstruction(e: *AstXmlNode): AstXmlNode {
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
        return * e
    }
    var generic: AstXmlNode =
        AstXmlNode(AstNodeKind.Callee, AstNodeCategory.ExprGenericName, List<AstNodeAttribute>(), Array<AstXmlNode>())
    generic.attributes.append(
        AstNodeAttribute(AstNodeAttributeKind.Name, xmlAttr(callee, AstNodeAttributeKind.Name))
    )
    var callees: List<AstXmlNode> = List<AstXmlNode>()
    callees.append(generic)
    return exprReplaceRole(e, AstNodeKind.Callee, callees)
}

// The declaration of a data class the facts know, by name (empty otherwise): a
// construction's arguments convert against its *fields*, as a call's do against parameters.
fun IlExtractor.dataClassDecl(name: *Str): AstXmlNode {
    if (this.fn.facts == null || name == "") {
        return xmlEmptyNode()
    }
    val decl: *AstXmlNode = this.fn.facts.types.getPtr(name)
    if (decl == null) {
        return xmlEmptyNode()
    }
    if (xmlKind(decl) != AstNodeCategory.DataClass) {
        return xmlEmptyNode()
    }
    return * decl
}

// A fact's receiver *pattern*: its recorded receiver, or - for a `native fun` extension,
// whose receiver is an explicit `this` first parameter - that parameter's type.
fun IlExtractor.receiverPattern(fact: *SemFnFact): AstXmlNode {
    if (!xmlIsEmpty(fact.receiver)) {
        return fact.receiver
    }
    return semExtensionReceiver(fact.decl)
}

// The type of an *argument*, which the pack and the handle conversions both ask for: a name
// already carries one, so the common argument costs a lookup; anything else asks the rules.
fun IlExtractor.argumentType(e: AstXmlNode): AstXmlNode {
    if (xmlKind(e) == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        val found: *Int = this.varAt.getPtr(name)
        if (found != null) {
            return ilVarType(this.out, *found)
        }
    }
    return this.exprType(e)
}

// Where the *pack* starts in an argument list, or -1 when the arguments are passed as they
// are. A single argument that *is* a list, whatever its handle form, is still the list
// itself; an argument the rules cannot name leaves the call exactly as it was.
fun IlExtractor.packStart(target: *AstXmlNode, args: *List<AstXmlNode>): Int {
    if (xmlIsEmpty(target)) {
        return -1
    }
    val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
    if (params.size() == 0) {
        return -1
    }
    val wanted: *AstXmlNode = xmlChildPtr(params[params.size()-1], AstNodeKind.Type)
    if (!semIsPackTarget(wanted)) {
        return -1
    }
    if (args.size() == params.size() - semReceiverParams(target) && args.size() > 0) {
        val last: AstXmlNode = this.argumentType(args[args.size() - 1])
        if (xmlIsEmpty(last) || !xmlIsEmpty(semListTypeOf(last))) {
            return -1
        }
    }
    return params.size() - 1 - semReceiverParams(target)
}

// The trailing arguments as one list, built by one `Pack` instruction: the list's type is
// the parameter's, the elements are the arguments as values. A `*List<T>` parameter gets the
// address of the fresh list, which outlives the call because it is a slot of this body.
fun IlExtractor.packArguments(target: AstXmlNode, args: List<AstXmlNode>, from: Int): Int {
    val params: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
    var wanted: AstXmlNode = xmlEmptyNode()
    if (params.size() > 0) {
        wanted = xmlChildPtr(params[params.size() - 1], AstNodeKind.Type)
    }
    val list: AstXmlNode = semListTypeOf(wanted)
    var element: AstXmlNode = xmlEmptyNode()
    if (xmlCount(list, AstNodeKind.TypeArg) > 0) {
        element = xmlChildPtr(list, AstNodeKind.TypeArg)
    }
    val slot: Int = this.freshSlot(this.slotTypeText(list), list)
    var operands: List<Int> = List<Int>()
    operands.append(slot)
    var i: Int = from
    while (i < args.size()) {
        operands.append(this.convertArgument(target, element, args[i]))
        i++
    }
    this.emit(IlOpKind.Pack, operands)
    if (xmlIsEmpty(wanted) || xmlKind(wanted) == AstNodeCategory.TypeGeneric) {
        return slot // by value
    }
    val handle: AstXmlNode = wanted
    val bound: Int = this.freshSlot(ilTypeText(handle), handle)
    this.emit(IlOpKind.Deref, ilOps2(bound, slot)) // `*List<T>`: the borrow of a fresh slot
    return bound
}

// A value read out of a handle, as a slot of the pointee's type - what a by-value parameter
// needs when the argument is a borrow or a counted reference (the language's `copy`).
fun IlExtractor.readThrough(pointeeType: *AstXmlNode, from: Int): Int {
    val typeNode: AstXmlNode = pointeeType
    val slot: Int = this.freshSlot(ilTypeText(typeNode), typeNode)
    this.emit(IlOpKind.CopyValue, ilOps2(slot, from))
    return slot
}

// The argument a call passes for one parameter, converted as the two types require
// (`specs/functions.md`):
//
// | parameter | argument  |                                |
// | --------- | --------- | ------------------------------ |
// | `*T`      | `T`       | the place's address (`&x`)     |
// | `*T`      | `&T`      | the handle's pointee (`x.get()`) |
// | `T`       | `*T`/`&T` | a copy of the pointee          |
// | `&T`      | `T`       | a boxed copy - `&x` means that |
// | `&T`      | `*T`      | nothing; the checker reports it |
//
// Nothing is converted when the two are not the same type; a `*T` binding is a different
// question, so the writer writes it.
fun IlExtractor.convertArgument(callee: *AstXmlNode, param: *AstXmlNode, arg: *AstXmlNode): Int {
    if (xmlIsEmpty(param)) {
        return this.operandOf(arg)
    }
    // A lambda takes its contract from the parameter's callable type: an omitted parameter
    // type and the result type come from there, and the closure is built directly.
    if (xmlKind(arg) == AstNodeCategory.ExprLambda) {
        if (!xmlIsEmpty(this.ilCallableOf(param))) {
            return this.lambdaOf(arg, -1, param, *callee)
        }
    }
    val wantPointer: Bool = xmlKind(param) == AstNodeCategory.TypePointer
    val wantShared: Bool = !wantPointer && ilIsHandleType(param)
    // The argument's type, cheaply where it can be: the rest asks the type *rules*, never a
    // materialised value.
    var actual: AstXmlNode = xmlEmptyNode()
    if (xmlKind(arg) == AstNodeCategory.ExprName) {
        val named: Int = this.varIndex(xmlAttr(arg, AstNodeAttributeKind.Name))
        if (named >= 0) {
            actual = ilVarType(this.out, named)
        }
    }
    if (xmlIsEmpty(actual)) {
        actual = this.exprType(arg)
    }
    if (xmlIsEmpty(actual)) {
        return this.operandOf(arg)
    }
    val given: AstXmlNode = semPointeeOf(actual)
    if (xmlIsEmpty(given)) {
        return this.operandOf(arg)
    }
    val havePointer: Bool = xmlKind(actual) == AstNodeCategory.TypePointer
    val haveShared: Bool = !havePointer && ilIsHandleType(actual)
    val haveValue: Bool = !havePointer && !haveShared
    val wanted: AstXmlNode = semPointeeOf(param)
    if (xmlIsEmpty(wanted)) {
        return this.operandOf(arg)
    }
    // Whether the argument is a *derived* type standing where its base is expected
    // (specs/declarations.md, "Inheritance"): the value form copies the prefix, the pointer
    // form is the same address, and an exact match is not a conversion.
    var derived: Bool = false
    if (!semUnifyType(wanted, given, xmlTypeParamNames(callee))) {
        val givenName: Str = semClassTypeName(*this.fn.facts.types, given)
        val wantedName: Str = semClassTypeName(*this.fn.facts.types, wanted)
        derived = givenName != "" && wantedName != ""
                && semDerivesFromName(*this.fn.facts.types, givenName, wantedName)
    }
    if (!derived && ((wantPointer && havePointer) || (wantShared && haveShared))) {
        return this.operandOf(arg) // the same shape already
    }
    // A parameter that is one of the callee's own bare type parameters
    // (`List<T>.append(value: T)`): the *receiver* binds it, so the two types cannot be compared
    // here - a bare parameter is a value, so a handle argument is read through to its own type.
    if (!derived && semIsBareTypeParam(callee, param)) {
        if (haveValue) {
            return this.operandOf(arg)
        }
        return this.readThrough(given, this.operandOf(arg))
    }
    // The pointee comparison follows the callee's own type parameters: `spanOf(xs)` with
    // `items: *List<T>` must see `List<T>` and `List<Int>` as the same shape, which a textual
    // comparison cannot (the non-generic `spanOfStr` matched by accident). Without this the
    // address is never taken and the argument is passed by value.
    if (!derived && !semUnifyType(wanted, given, xmlTypeParamNames(callee))) {
        return this.operandOf(arg) // not the same type
    }
    if (wantPointer) {
        if (derived && havePointer) {
            // `*Derived` where a `*Base` is wanted: the same address, cast. The `Deref`
            // spelling reads a pointer's pointee, so the slot takes the pointer as it is
            // (`cgSubtypeText` spells the `reinterpret_cast`).
            val ptrHandle: AstXmlNode = param
            val ptrSlot: Int = this.freshSlot(ilTypeText(ptrHandle), ptrHandle)
            this.emit(IlOpKind.SetVar, ilOps2(ptrSlot, this.operandOf(arg)))
            return ptrSlot
        }
        // The address, spelled as an explicit `*` spells it (`into`'s `Deref` arm is the
        // definition): a member/index chain *is* its own address (`&a.f`, never the address of a
        // copy), a static name is its `GetStaticAddr`, a name is `&x`.
        val handle: AstXmlNode = param
        val bound: Int = this.freshSlot(ilTypeText(handle), handle)
        this.into(bound, ilDerefNode(arg))
        return bound
    }
    if (wantShared) {
        if (!haveValue && !derived) {
            return this.operandOf(arg) // `&T` from a pointer: the checker reports it
        }
        var source: Int = this.operandOf(arg)
        if (!haveValue) {
            // A `*Derived`/`&Derived` source is read into a value first: the box holds a
            // copy of the prefix, never the source's storage.
            source = this.readThrough(given, source)
        }
        val box: AstXmlNode = param
        val held: Int = this.freshSlot(ilTypeText(box), box)
        // `&x` boxes a copy; a derived `x` boxes the *prefix* - the box's type is its own,
        // so a `&Base` handle is a snapshot slice (`cgSubtypeText`).
        this.emit(IlOpKind.Box, ilOps2(held, source))
        return held
    }
    if (haveValue && !derived) {
        return this.operandOf(arg)
    }
    // The value form: a derived value is copied into the parameter's own type - the base's
    // fields, off the derived value (`cgSubtypeText`).
    return this.readThrough(wanted, this.operandOf(arg))
}
