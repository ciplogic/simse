// TypeInferCall.kt
//
// `SemInfer`'s call and return types: explicit instantiation, the callee's type parameters,
// member/extension returns and alias resolution. Extension methods on `SemInfer`
// (TypeInfer.kt).

package sema

import compiler

import common

// The return type of a call: the callee's type parameters bound from the receiver, an
// explicit instantiation (`identity<Int>(7)`) or the types of the arguments - the last is
// what makes `twice(f, 5)` an `Int`. A result that still mentions an unbound parameter has
// no type to spell; the checker reports that at the call (`checkCallDeduction`).
fun SemInfer.functionReturn(
    name: *Str, typeArgs: *List<AstXmlNode>, receiver: *AstXmlNode, argNodes: *List<AstXmlNode>
): AstXmlNode {
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.name != name) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        if (xmlIsEmpty(ret)) {
            continue
        }
        val hasReceiver: Bool = !xmlIsEmpty(fn.receiver)
        if (hasReceiver != !xmlIsEmpty(receiver)) {
            continue
        }
        // A `native fun` extension has no recorded receiver but is still a *member*: a
        // plain call must not reach it (`functionReturn` for `fun find(...)`).
        if (!hasReceiver && fn.isExtension) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (hasReceiver) {
            if (!semBindTypes(fn.receiver, receiver, fn.templateParams, bindings)) {
                continue
            }
        }
        if (typeArgs.size() > 0) {
            if (typeArgs.size() != fn.templateParams.size()) {
                continue
            }
            var bound: Bool = true
            var a: Int = 0
            while (a < typeArgs.size()) {
                if (bound) {
                    bound = semBindOne(bindings, fn.templateParams[a], typeArgs[a])
                }
                a = a + 1
            }
            if (!bound) {
                continue
            }
        }
        if (fn.templateParams.size() > 0) {
            var argTypes: List<AstXmlNode> = List<AstXmlNode>()
            var a: Int = 0
            while (a < argNodes.size()) {
                argTypes.append(this.infer(argNodes[a]))
                a = a + 1
            }
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings, receiver)
        }
    }
    return xmlEmptyNode()
}

// Binds a declaration's type parameters from the types of a call's arguments: one more
// source the emitter's C++ deduction cannot see (a lambda names its contract only against
// the parameter type, and a `Str` literal is a `StrView` in C++). `argTypes` follows the
// receiver when the call has one, like `params` does; `decl` is the *callee's*, so the
// parameter pair is `params[paramOffset + i]`.
// A parameter/argument pair as the *conversion* sees it: a pointer parameter receives the
// address of a value argument (`convertArgument`), so binding goes through the pointee. A
// pointer (or handle) argument binds as written.
fun semBindArgShape(pattern: AstXmlNode, actual: AstXmlNode): AstXmlNode {
    if (xmlKind(pattern) != AstNodeCategory.TypePointer || xmlIsEmpty(actual)) {
        return pattern
    }
    val actualKind: AstNodeCategory = xmlKind(actual)
    if (actualKind == AstNodeCategory.TypePointer || actualKind == AstNodeCategory.TypeReference) {
        return pattern
    }
    val inner: AstXmlNode = xmlChild(pattern, AstNodeKind.Inner)
    if (xmlIsEmpty(inner)) {
        return pattern
    }
    return inner
}

fun semBindCallArgs(
    decl: *AstXmlNode, receiver: AstXmlNode, argTypes: *List<AstXmlNode>,
    templateParams: *List<Str>, bindings: *Dictionary<Str, AstXmlNode>
): Unit {
    if (templateParams.size() == 0) {
        return
    }
    if (!xmlIsEmpty(receiver)) {
        // The written receiver (`fun List<T>.mapAll(...)`) or the explicit-`this` one
        // (`fun toString(this: Int)`), whichever the declaration uses.
        var pattern: AstXmlNode = xmlChild(decl, AstNodeKind.Receiver)
        if (xmlIsEmpty(pattern)) {
            pattern = semExtensionReceiver(decl)
        }
        if (!xmlIsEmpty(pattern)) {
            semBindBestEffort(pattern, receiver, templateParams, bindings)
        }
    }
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    val offset: Int = semReceiverParams(decl)
    var i: Int = 0
    while (i < argTypes.size()) {
        val at: Int = offset + i
        if (at >= params.size()) {
            break
        }
        val paramType: *AstXmlNode = xmlChildPtr(params[at], AstNodeKind.Type)
        val argType: AstXmlNode = argTypes[i]
        i = i + 1
        if (xmlIsEmpty(paramType) || xmlIsEmpty(argType)) {
            continue
        }
        semBindBestEffort(semBindArgShape(*paramType, argType), argType, templateParams, bindings)
    }
}

// One pattern/actual pair into `bindings`, when their *shapes* match: a pair that does not
// is either an argument the call converts (an `Int` into a `Float64`) or one another
// overload takes, and binds nothing. Bindings are collected aside first, so a pair that
// fails halfway does not leave a half-bound parameter behind.
fun semBindBestEffort(
    pattern: AstXmlNode, actual: AstXmlNode, templateParams: *List<Str>,
    bindings: *Dictionary<Str, AstXmlNode>
): Unit {
    var found: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    if (!semBindTypes(pattern, actual, templateParams, found)) {
        return
    }
    val names: List<Str> = found.keys()
    var i: Int = 0
    while (i < names.size()) {
        val name: Str = names[i]
        i = i + 1
        val bound: *AstXmlNode = found.getPtr(name)
        if (bound != null) {
            semBindOne(bindings, name, *bound)
        }
    }
}

// The result of a member call (`recv.name(...)`): a declared extension/method, then a
// native extension, then the built-in accessors, the way the emitter lowers it.
// A receiver type through a `typealias` (`Emitter.resolveAlias`): `StrView` is
// `Span<Char>`, so an extension on `Span<T>` is reachable through a view.
fun SemInfer.resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && guard < 16) {
        guard = guard + 1
        if (xmlKind(current) != AstNodeCategory.TypeNamed) {
            return current
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.facts.types.getPtr(name)
        if (decl == null) {
            return current
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            return current
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            return current
        }
        current = target
    }
    return current
}

// `x[i]` for a receiver whose type declares `operator fun get` (specs/functions.md): the
// index syntax is the call, so the read's type is that `get`'s return type. Empty when no
// operator matches - a built-in index shape decides then.
fun SemInfer.operatorGetReturn(recvExpr: *AstXmlNode, indexNode: *AstXmlNode): AstXmlNode {
    val recv: AstXmlNode = this.resolveAlias(semPointee(this.infer(recvExpr)))
    if (xmlIsEmpty(recv)) {
        return xmlEmptyNode()
    }
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != "get" || fn.paramCount != 1
            || xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true"
        ) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        if (xmlIsEmpty(ret)) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (!semBindTypes(this.resolveAlias(fn.receiver), recv, fn.templateParams, bindings)) {
            continue
        }
        if (fn.templateParams.size() > 0) {
            var argTypes: List<AstXmlNode> = List<AstXmlNode>()
            argTypes.append(this.infer(indexNode))
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings, recv)
        }
    }
    return xmlEmptyNode()
}

// `a < b`/`a == b`/`a + b` for a left operand whose type declares the operator
// (specs/functions.md): the expression's type is the operator's return type - Bool for the
// comparisons and `equals` by contract, whatever `plus` answers for `+`. Empty when no
// operator matches: the built-in rule decides then.
fun SemInfer.operatorBinaryReturn(name: *Str, lhsExpr: *AstXmlNode, rhsExpr: *AstXmlNode): AstXmlNode {
    val recv: AstXmlNode = this.resolveAlias(semPointee(this.infer(lhsExpr)))
    if (xmlIsEmpty(recv)) {
        return xmlEmptyNode()
    }
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != name || fn.paramCount != 1
            || xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true"
        ) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        if (xmlIsEmpty(ret)) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (!semBindTypes(this.resolveAlias(fn.receiver), recv, fn.templateParams, bindings)) {
            continue
        }
        if (fn.templateParams.size() > 0) {
            var argTypes: List<AstXmlNode> = List<AstXmlNode>()
            argTypes.append(this.infer(rhsExpr))
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings, recv)
        }
    }
    return xmlEmptyNode()
}

fun SemInfer.memberReturn(callee: *AstXmlNode, argNodes: *List<AstXmlNode>): AstXmlNode {
    val receiverType: AstXmlNode = this.infer(xmlChildPtr(callee, AstNodeKind.Receiver))
    val recv: AstXmlNode = this.resolveAlias(semPointee(receiverType))
    if (xmlIsEmpty(recv)) {
        return xmlEmptyNode()
    }
    val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != calleeText) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        if (xmlIsEmpty(ret)) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (!semBindTypes(this.resolveAlias(fn.receiver), recv, fn.templateParams, bindings)) {
            continue
        }
        if (fn.templateParams.size() > 0) {
            var argTypes: List<AstXmlNode> = List<AstXmlNode>()
            var a: Int = 0
            while (a < argNodes.size()) {
                argTypes.append(this.infer(argNodes[a]))
                a = a + 1
            }
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings, recv)
        }
    }
    val extensions: *List<SemExtFact> = this.facts.nativeExtensions.getPtr(calleeText)
    if (extensions != null) {
        var e: Int = 0
        while (e < extensions.size()) {
            val ext: *SemExtFact = *extensions[e]
            e = e + 1
            if (xmlIsEmpty(ext.receiver) || xmlIsEmpty(ext.returnType)) {
                continue
            }
            var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            if (!semBindTypes(this.resolveAlias(ext.receiver), recv, ext.typeParams, bindings)) {
                continue
            }
            val result: AstXmlNode = semSubstitute(ext.returnType, bindings, ext.typeParams)
            if (!xmlIsEmpty(result)) {
                return result
            }
        }
    }
    if (xmlKind(recv) == AstNodeCategory.TypeYield) {
        // A machine's surface is the lowering's ABI: `advance()` steps it, and `current`
        // holds what it yielded. Typing them makes a `for`'s loop variable a typed binding.
        if (calleeText == "advance") {
            return semNamedType("Bool")
        }
        // A machine is already iterable: `x.iterValues()` is `x` (`impl_specs/for.md`); `..T`
        // is not spellable, so no function could take one.
        if (calleeText == "iterValues") {
            return receiverType
        }
    }
    if (xmlKind(recv) == AstNodeCategory.TypeGeneric) {
        val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
        val recvName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
        // The static constructor forms (`Opt<int>.none()`, `Res<int>.ok(42)`,
        // specs/core-types.md) have no declaration, so their result type is stated here:
        // the type they are qualified by. Per *name* - `EnumType.fromInt(n)` is the enum
        // rule's (`specs/declarations.md`), deliberately not in this list.
        if ((recvName == "Opt" && (calleeText == "none" || calleeText == "some"))
            || (recvName == "Res" && (calleeText == "ok" || calleeText == "err"))
        ) {
            return semReRole(recv, AstNodeKind.Type)
        }
        if (calleeText == "value" && (recvName == "Opt" || recvName == "Res")
            && typeArgs.size() > 0
        ) {
            return semReRole(typeArgs[0], AstNodeKind.Type)
        }
        if (calleeText == "error" && recvName == "Res") {
            return semNamedType("Str")
        }
        if ((calleeText == "size" || calleeText == "count")
            && (recvName == "List" || recvName == "Array" || recvName == "Dictionary"
                    || recvName == "SmallVector" || recvName == "Span")
        ) {
            return semNamedType("Int")
        }
    }
    if (xmlKind(recv) == AstNodeCategory.TypeNamed && calleeText == "size"
        && xmlAttr(recv, AstNodeAttributeKind.Name) == "Str"
    ) {
        return semNamedType("Int")
    }
    if (calleeText == "isOk" || calleeText == "hasValue") {
        return semNamedType("Bool")
    }
    // An enum's conversions (`specs/declarations.md`): `E.toInt()` is the member's
    // integer value, `E.fromInt(n)` the unchecked cast back.
    if (xmlKind(recv) == AstNodeCategory.TypeNamed
        && this.facts.enumNames.has(xmlAttr(recv, AstNodeAttributeKind.Name))
    ) {
        if (calleeText == "toInt") {
            return semNamedType("Int")
        }
        if (calleeText == "fromInt") {
            return semReRole(recv, AstNodeKind.Type)
        }
    }
    return this.classMemberReturn(*recv, calleeText)
}

// A member of a data class no `SemFnFact` stands for: a *prelude* class's methods
// (`Emitter.collect` skips prelude declarations, so `Span<Char>.at`'s `T` is not in
// the facts). The receiver's own type arguments bind the class's type parameters.
fun SemInfer.classMemberReturn(recv: *AstXmlNode, calleeName: *Str): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(recv)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return xmlEmptyNode()
    }
    val typeName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
    val classDecl: *AstXmlNode = this.facts.types.getPtr(typeName)
    if (classDecl == null) {
        return xmlEmptyNode()
    }
    if (classDecl.name != AstNodeKind.DataClass) {
        return xmlEmptyNode()
    }
    val classParams: List<Str> = xmlTypeParamNames(classDecl)
    val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
    if (classParams.size() != typeArgs.size()) {
        return xmlEmptyNode()
    }
    var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var i: Int = 0
    while (i < classParams.size()) {
        bindings.insert(classParams[i], typeArgs[i])
        i = i + 1
    }
    val methods: List<AstXmlNode> = xmlChildren(classDecl, AstNodeKind.Function)
    for (*method in methods) {
        if (xmlAttr(method, AstNodeAttributeKind.Name) != calleeName) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(method, AstNodeKind.ReturnType)
        if (xmlIsEmpty(ret)) {
            continue
        }
        val result: AstXmlNode = semSubstitute(ret, bindings, classParams)
        if (!xmlIsEmpty(result)) {
            return result
        }
    }
    return xmlEmptyNode()
}

// A callable's own return type: calling a *value* of function type (`predicate(x)`) is
// an indirect call, resolved through a `typealias` as the emitter resolves it.
fun SemInfer.callableReturn(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = semPointee(typeNode)
    var guard: Int = 0
    while (!xmlIsEmpty(current) && guard < 16) {
        guard = guard + 1
        if (xmlKind(current) == AstNodeCategory.TypeFunction) {
            return xmlChild(current, AstNodeKind.ReturnType)
        }
        if (xmlKind(current) != AstNodeCategory.TypeNamed) {
            return xmlEmptyNode()
        }
        val aliasName: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.facts.types.getPtr(aliasName)
        if (decl == null) {
            return xmlEmptyNode()
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            return xmlEmptyNode()
        }
        current = xmlChild(decl, AstNodeKind.TargetType)
    }
    return xmlEmptyNode()
}

fun SemInfer.callReturn(callee: *AstXmlNode, argNodes: *List<AstXmlNode>): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(callee)
    when (kind) {
        AstNodeCategory.ExprGenericName -> {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semGenericType(name, xmlChildren(callee, AstNodeKind.TypeArg))
            }
            return this.functionReturn(name, xmlChildren(callee, AstNodeKind.TypeArg), xmlEmptyNode(), argNodes)
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semNamedType(name)
            }
            val direct: AstXmlNode = this.functionReturn(name, List<AstXmlNode>(), xmlEmptyNode(), argNodes)
            if (!xmlIsEmpty(direct)) {
                return direct
            }
            return this.callableReturn(this.lookup(name))
        }

        AstNodeCategory.ExprMember -> {
            return this.memberReturn(callee, argNodes)
        }
    }
    return xmlEmptyNode()
}

fun SemInfer.infer(e: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprIntLit -> {
            return semNamedType("Int")
        }

        AstNodeCategory.ExprFloatLit -> {
            return semNamedType("Float64")
        }

        AstNodeCategory.ExprStrLit -> {
            return semNamedType("Str")
        }

        AstNodeCategory.ExprCharLit -> {
            return semNamedType("Char")
        }

        AstNodeCategory.ExprBoolLit -> {
            return semNamedType("Bool")
        }

        AstNodeCategory.ExprNullLit -> {
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return semReRole(copy(this.body.selfType), AstNodeKind.Type)
            }
            val local: AstXmlNode = this.lookup(name)
            if (!xmlIsEmpty(local)) {
                return local
            }
            // File-level static storage (specs/statics.md).
            val staticType: *AstXmlNode = this.facts.statics.getPtr(name)
            if (staticType != null) {
                return semReRole(*staticType, AstNodeKind.Type)
            }
            // A bare enum type name used as the receiver of a static conversion.
            if (this.facts.enumNames.has(name)) {
                return semNamedType(name)
            }
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprGenericName -> {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (!this.isTypeName(name)) {
                return xmlEmptyNode()
            }
            return semGenericType(name, xmlChildren(e, AstNodeKind.TypeArg))
        }

        AstNodeCategory.ExprMember -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
            // An enum member expression has the enum's type.
            if (xmlKind(lhs) == AstNodeCategory.ExprName
                && this.facts.enumNames.has(xmlAttr(lhs, AstNodeAttributeKind.Name))
            ) {
                return semNamedType(xmlAttr(lhs, AstNodeAttributeKind.Name))
            }
            val baseType: AstXmlNode = this.infer(lhs)
            val base: AstXmlNode = semPointee(baseType)
            if (xmlIsEmpty(base)) {
                return xmlEmptyNode()
            }
            val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (xmlKind(base) == AstNodeCategory.TypeYield) {
                // A machine's `current` field (`impl_specs/for.md`): what it last yielded.
                // For `iter` the element type *is* `*T`, a place rather than a copy.
                if (memberText == "current") {
                    return semReRole(xmlChild(base, AstNodeKind.Inner), AstNodeKind.Type)
                }
            }
            if (xmlKind(base) == AstNodeCategory.TypeGeneric && xmlAttr(
                    base,
                    AstNodeAttributeKind.Name
                ) == "Res"
            ) {
                val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                // The spec spells these `value`/`error`, the RTL's fields `Value`/`Error`;
                // an unremapped name is emitted as written, so both reach C++ (core-types.md).
                if ((memberText == "value" || memberText == "Value") && typeArgs.size() > 0) {
                    return semReRole(typeArgs[0], AstNodeKind.Type)
                }
                if (memberText == "error" || memberText == "Error") {
                    return semNamedType("Str")
                }
            }
            val baseKind: AstNodeCategory = xmlKind(base)
            if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                var decl: *AstXmlNode = this.facts.types.getPtr(baseName)
                if (decl == null) {
                    if (!xmlIsEmpty(this.body.selfDecl)
                        && xmlAttr(this.body.selfDecl, AstNodeAttributeKind.Name) == baseName
                    ) {
                        // A class the lowering built (a state machine): its fields are reached
                        // through the body's own context, not a written declaration.
                        decl = *this.body.selfDecl
                    }
                }
                if (decl != null && decl.name == AstNodeKind.DataClass) {
                    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
                    for (*field in fields) {
                        if (xmlAttr(field, AstNodeAttributeKind.Name) == memberText) {
                            return this.instantiate(decl, base, xmlChild(field, AstNodeKind.Type))
                        }
                    }
                }
            }
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprLambda -> {
            // A lambda's type is the callable it is used as: the parameter types it
            // annotates, plus its *result*, inferred from the body with the lambda's own
            // parameters in scope. A callable's return has nothing else to name it, and a
            // generic call that only its result can bind (`select`'s `U`) needs it.
            var fnType: AstXmlNode = AstXmlNode(
                AstNodeKind.Type, AstNodeCategory.TypeFunction, List<AstNodeAttribute>(), Array<AstXmlNode>()
            )
            val paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
            xmlAddChildren(fnType, paramTypes)
            val paramNames: List<Str> = xmlLambdaParams(e)
            this.pushScope()
            var i: Int = 0
            while (i < paramNames.size() && i < paramTypes.size()) {
                this.scopes[this.scopes.size() - 1].insert(paramNames[i], paramTypes[i])
                i = i + 1
            }
            var result: AstXmlNode = xmlEmptyNode()
            for (*stmt in xmlChildren(xmlChildPtr(e, AstNodeKind.Body), AstNodeKind.Stmt)) {
                if (xmlKind(stmt) == AstNodeCategory.StmtExprStmt) {
                    val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Expr)
                    if (!xmlIsEmpty(value)) {
                        result = this.infer(value)
                    }
                } else if (xmlKind(stmt) == AstNodeCategory.StmtReturn) {
                    val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                    if (!xmlIsEmpty(value)) {
                        result = this.infer(value)
                    }
                }
                if (!xmlIsEmpty(result)) {
                    break
                }
            }
            this.popScope()
            if (!xmlIsEmpty(result)) {
                xmlAddChild(fnType, semReRole(result, AstNodeKind.ReturnType))
            }
            return fnType
        }

        AstNodeCategory.ExprCall -> {
            return this.callReturn(xmlChildPtr(e, AstNodeKind.Callee), xmlChildren(e, AstNodeKind.Arg))
        }

        AstNodeCategory.ExprIndex -> {
            // An `operator fun get` on the receiver's type is the index syntax's meaning
            // (specs/functions.md): the result is that `get`'s return type. The built-in
            // container shapes below decide when nothing declares one.
            val operatorType: AstXmlNode = this.operatorGetReturn(
                xmlChildPtr(e, AstNodeKind.Receiver), xmlChildPtr(e, AstNodeKind.Index)
            )
            if (!xmlIsEmpty(operatorType)) {
                return operatorType
            }
            // The receiver through a `typealias`: `StrView` is `Span<Char>`, so a view's
            // index is the span's element (`Char`) where the bare alias would leave it `?`.
            val baseType: AstXmlNode =
                this.resolveAlias(this.infer(xmlChildPtr(e, AstNodeKind.Receiver)))
            val base: AstXmlNode = this.resolveAlias(semPointee(baseType))
            if (xmlIsEmpty(base)) {
                return xmlEmptyNode()
            }
            if (xmlKind(base) == AstNodeCategory.TypeNamed && xmlAttr(base, AstNodeAttributeKind.Name) == "Str") {
                return semNamedType("Char")
            }
            if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
                return xmlEmptyNode()
            }
            val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
            if (typeArgs.size() == 0) {
                return xmlEmptyNode()
            }
            val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
            if (baseName == "SmallVector" && typeArgs.size() == 2) {
                return semReRole(typeArgs[1], AstNodeKind.Type) // <N, T>
            }
            if (baseName == "Dictionary" && typeArgs.size() == 2) {
                return semReRole(typeArgs[1], AstNodeKind.Type)
            }
            return semReRole(typeArgs[0], AstNodeKind.Type)
        }

        AstNodeCategory.ExprRef -> {
            // `&x` boxes a copy for the call (`std::make_shared<T>(x)`), so the C++
            // type is a counted reference to whatever `x` is.
            return this.handle(AstNodeCategory.TypeReference, this.infer(xmlChildPtr(e, AstNodeKind.Operand)))
        }

        AstNodeCategory.ExprDeref -> {
            // `*x` is the *address* of what `x` denotes: a value's own storage (`&x`), a
            // counted reference's pointee (`x.get()`), or a pointer (read through).
            val operand: AstXmlNode = this.infer(xmlChildPtr(e, AstNodeKind.Operand))
            if (xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            val operandKind: AstNodeCategory = xmlKind(operand)
            if (operandKind == AstNodeCategory.TypePointer) {
                return semReRole(xmlChild(operand, AstNodeKind.Inner), AstNodeKind.Type)
            }
            if (operandKind == AstNodeCategory.TypeReference) {
                return this.handle(AstNodeCategory.TypePointer, xmlChildPtr(operand, AstNodeKind.Inner))
            }
            return this.handle(AstNodeCategory.TypePointer, operand)
        }

        AstNodeCategory.ExprCopy -> {
            // `copy(x)` is the *value*: a plain read, or the pointee of a handle (`*(x)`).
            val operand: AstXmlNode = this.infer(xmlChildPtr(e, AstNodeKind.Operand))
            val value: AstXmlNode = semPointee(operand)
            if (xmlIsEmpty(value)) {
                return xmlEmptyNode()
            }
            return semReRole(value, AstNodeKind.Type)
        }

        AstNodeCategory.ExprUnary -> {
            return this.infer(xmlChildPtr(e, AstNodeKind.Operand))
        }

        AstNodeCategory.ExprBinary -> {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                || op == "&&" || op == "||"
            ) {
                return semNamedType("Bool")
            }
            // A declared operator answers the expression's type (`a + b` is `a.plus(b)`,
            // specs/functions.md); the built-in rule below answers the left operand.
            val operatorName: Str = semBinaryOperatorName(op)
            if (operatorName != "") {
                val operatorType: AstXmlNode = this.operatorBinaryReturn(
                    operatorName, xmlChildPtr(e, AstNodeKind.Lhs), xmlChildPtr(e, AstNodeKind.Rhs)
                )
                if (!xmlIsEmpty(operatorType)) {
                    return operatorType
                }
            }
            // The operation is on *values*: a handle operand is read through to its
            // pointee, so the result is the left operand as a value. Returning the handle
            // itself would make the emitter declare every slot for `a + b` as one.
            return semPointee(this.infer(xmlChildPtr(e, AstNodeKind.Lhs)))
        }
    }
    // A lambda's type comes from the callable type it is used against (the emitter's
    // business, which may infer its parameters from there).
    return xmlEmptyNode()
}
