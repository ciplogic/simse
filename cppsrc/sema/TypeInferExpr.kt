// TypeInferExpr.kt
//
// `SemInfer`'s inference: scopes and lookups, the statement walk, and the expression and
// call return types. Extension methods on `SemInfer` (TypeInfer.kt has the class).

package sema

import common

fun SemInfer.pushScope(): Unit {
    this.scopes.append(Dictionary<Str, AstXmlNode>())
}

fun SemInfer.popScope(): Unit {
    this.scopes.removeAt(this.scopes.size() - 1)
}

// One expression, typed against the caller's (flat) frame `names`; the scope is pushed
// and popped so the call leaves nothing behind, and `names` is borrowed, not copied.
fun SemInfer.typeOf(e: *AstXmlNode, names: *Dictionary<Str, AstXmlNode>): AstXmlNode {
    this.pushScope()
    this.baseScope = names
    val result: AstXmlNode = this.infer(e)
    this.popScope()
    return result
}

// Records a binding in the scope being built *and* in the flat record.
fun SemInfer.mark(name: *Str, typeNode: *AstXmlNode): Unit {
    if (this.scopes.size() == 0) {
        return
    }
    this.scopes[this.scopes.size() - 1].insert(name, typeNode)
    this.types.insert(name, typeNode)
}

fun SemInfer.lookup(name: *Str): AstXmlNode {
    var i: Int = this.scopes.size() - 1
    while (i >= 0) {
        val declared: *AstXmlNode = this.scopes[i].getPtr(name)
        if (declared != null) {
            return * declared
        }
        i = i - 1
    }
    val base: *AstXmlNode = this.baseScope.getPtr(name)
    if (base != null) {
        return * base
    }
    return xmlEmptyNode()
}

// A declaration is annotated only when the *value* of its initializer has a C++ type:
// a lambda needs its expected callable type and `null` has none. Everything else,
// including `&x`/`*x`/`copy(x)`, is typed below.
fun SemInfer.declarable(init: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(init)
    if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.ExprNullLit) {
        return false
    }
    return true
}

fun SemInfer.spellableName(name: *Str): Bool {
    if (name == "Unit") {
        return false // `void` has no values
    }
    if (xmlIsTypeParam(name, this.body.typeParams)) {
        return true
    }
    if (this.facts.types.has(name)) {
        return true
    }
    return semIsRtlTypeName(name)
}

// Whether the emitter can spell this type in this body: an out-of-scope type parameter
// or an undeclared name would not compile, so both mean "leave it to `auto`".
fun SemInfer.spellable(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return true
        }

        AstNodeCategory.TypeNamed -> {
            return this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))
        }

        AstNodeCategory.TypeGeneric -> {
            if (!this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))) {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*arg in args) {
                if (!this.spellable(arg)) {
                    return false
                }
            }
            return true
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            return this.spellable(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            if (!this.spellable(xmlChildPtr(typeNode, AstNodeKind.ReturnType))) {
                return false
            }
            val params: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            for (*param in params) {
                if (!this.spellable(param)) {
                    return false
                }
            }
            return true
        }

        AstNodeCategory.TypeYield -> {
            // A machine (`..T`) is spellable only once `semMachineType` named its class;
            // an anonymous one has no C++ spelling, so its declaration stays an `auto`.
            if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "") {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*arg in args) {
                if (!this.spellable(arg)) {
                    return false
                }
            }
            return true
        }
    }
    return false
}

fun SemInfer.isTypeName(name: *Str): Bool {
    return this.facts.types.has(name) || semIsRtlTypeName(name)
}

// Whether the type `typeName` declares an `initByValue` extension (the construction
// convention): the receiver is the fact's own, or the explicit `this` parameter's type.
fun SemInfer.isInitByValueType(typeName: *Str): Bool {
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.name != "initByValue") {
            continue
        }
        var pattern: AstXmlNode = fn.receiver
        if (xmlIsEmpty(pattern)) {
            pattern = semExtensionReceiver(fn.decl)
        }
        if (xmlAttr(pattern, AstNodeAttributeKind.Name) == typeName) {
            return true
        }
    }
    return false
}

// Whether `init` is `T(args)` for a type `T` that declares an `initByValue` extension:
// the construction convention (`var x = T(a)`), as against a plain constructor call
// (`var x: T = T(a)`, the explicit-type form, which is left alone).
fun SemInfer.isInitByValueCtor(init: *AstXmlNode): Bool {
    if (xmlKind(init) != AstNodeCategory.ExprCall) {
        return false
    }
    val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
    val calleeKind: AstNodeCategory = xmlKind(callee)
    if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprGenericName) {
        return false
    }
    return this.isInitByValueType(xmlAttr(callee, AstNodeAttributeKind.Name))
}

// Whether `stmt` is the `initByValue` call of a `_sm_ctor` temp (`t.initByValue(...)`).
fun SemInfer.isCtorCall(stmt: AstXmlNode, name: *Str): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtExprStmt) {
        return false
    }
    val call: AstXmlNode = xmlChild(stmt, AstNodeKind.Expr)
    if (xmlKind(call) != AstNodeCategory.ExprCall) {
        return false
    }
    val callee: AstXmlNode = xmlChild(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return false
    }
    if (xmlAttr(callee, AstNodeAttributeKind.Name) != "initByValue") {
        return false
    }
    val recv: AstXmlNode = xmlChild(callee, AstNodeKind.Receiver)
    return xmlKind(recv) == AstNodeCategory.ExprName
        && xmlAttr(recv, AstNodeAttributeKind.Name) == name
}

// Whether `stmt` is `return <name>`.
fun SemInfer.isCtorReturn(stmt: AstXmlNode, name: *Str): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtReturn) {
        return false
    }
    val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
    return xmlKind(value) == AstNodeCategory.ExprName
        && xmlAttr(value, AstNodeAttributeKind.Name) == name
}

// A parenthesized `return (...)` desugars to a `_sm_ctor<n>` temp, its `initByValue` call
// and the return (cppsrc/parser/Parser.kt) - the lowering may have put argument temporaries
// between them. When the temp's type declares no `initByValue` the parenthesized form is an
// ordinary value, so the construction collapses to a plain `return e` (only a single
// expression has such a spelling). Marks the temp and the call for dropping and the return
// for rewriting; leaves them all alone when the construction stands.
fun SemInfer.markCtorFallback(list: *List<AstXmlNode>, i: Int, drop: *List<Bool>, rewrite: *List<AstXmlNode>): Unit {
    val decl: AstXmlNode = list[i]
    if (xmlKind(decl) != AstNodeCategory.StmtVarDecl) {
        return
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (!name.startsWith("_sm_ctor")) {
        return
    }
    if (this.isInitByValueType(xmlAttr(xmlChild(decl, AstNodeKind.Type), AstNodeAttributeKind.Name))) {
        return
    }
    var callIndex: Int = -1
    var retIndex: Int = -1
    var j: Int = i + 1
    while (j < list.size()) {
        if (callIndex < 0 && this.isCtorCall(list[j], name)) {
            callIndex = j
        }
        if (callIndex >= 0 && this.isCtorReturn(list[j], name)) {
            retIndex = j
            break
        }
        j = j + 1
    }
    if (callIndex < 0 || retIndex < 0) {
        return
    }
    val args: List<AstXmlNode> = xmlChildren(xmlChild(list[callIndex], AstNodeKind.Expr), AstNodeKind.Arg)
    if (args.size() != 1) {
        return
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, list[retIndex].attributes, Array<AstXmlNode>())
    var value: AstXmlNode = args[0]
    value.name = AstNodeKind.Value
    xmlAddChild(node, value)
    drop[i] = true
    drop[callIndex] = true
    rewrite[retIndex] = node
}

fun SemInfer.stmts(list: *List<AstXmlNode>): List<AstXmlNode> {
    var drop: List<Bool> = List<Bool>()
    var rewrite: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < list.size()) {
        drop.append(false)
        rewrite.append(xmlEmptyNode())
        i = i + 1
    }
    i = 0
    while (i < list.size()) {
        this.markCtorFallback(list, i, drop, rewrite)
        i = i + 1
    }
    var out: List<AstXmlNode> = List<AstXmlNode>()
    i = 0
    while (i < list.size()) {
        if (!drop[i]) {
            if (xmlIsEmpty(rewrite[i])) {
                out.append(this.stmt(list[i]))
            } else {
                out.append(rewrite[i])
            }
        }
        i = i + 1
    }
    return out
}

fun SemInfer.stmt(stmtNode: AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(stmtNode)
    when (kind) {
        AstNodeCategory.StmtVarDecl -> {
            val declared: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Type)
            val init: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Init)
            var typeNode: AstXmlNode = declared
            if (xmlIsEmpty(typeNode) && !xmlIsEmpty(init)) {
                typeNode = this.infer(init)
            }
            val name: Str = xmlAttr(stmtNode, AstNodeAttributeKind.Name)
            if (!xmlIsEmpty(typeNode)) {
                this.mark(name, typeNode)
            }
            if (!xmlIsEmpty(declared) || xmlIsEmpty(typeNode) || xmlIsEmpty(init)
                || !this.declarable(init) || !this.spellable(typeNode)
            ) {
                return stmtNode
            }
            val typed: AstXmlNode = semWithType(stmtNode, semReRole(typeNode, AstNodeKind.Type))
            // `var x = T(a)` with no declared type constructs through `initByValue`: mark it
            // so the lowering keeps the declaration for the backend to route.
            if (this.isInitByValueCtor(init)) {
                typed.attributes.append(AstNodeAttribute(AstNodeAttributeKind.InitByValue, "true"))
            }
            return typed
        }

        AstNodeCategory.StmtBlock -> {
            this.pushScope()
            val inner: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmtNode, AstNodeKind.Body), AstNodeKind.Stmt)
            val rebuilt: List<AstXmlNode> = this.stmts(inner)
            this.popScope()
            var body: AstXmlNode =
                AstXmlNode(AstNodeKind.Body, AstNodeCategory.None, List<AstNodeAttribute>(), rebuilt.toArray())
            return semReplaceRole(stmtNode, AstNodeKind.Body, semOne(body))
        }
    }
    return stmtNode
}

fun SemInfer.handle(kind: AstNodeCategory, inner: *AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(inner)) {
        return xmlEmptyNode()
    }
    return AstXmlNode(
        AstNodeKind.Type, kind, List<AstNodeAttribute>(),
        semOne(semReRole(inner, AstNodeKind.Inner)).toArray()
    )
}

// A data class member type with the class's type parameters bound to the
// receiver's arguments (`Box<Int>.value` with `value: T` is `Int`).
fun SemInfer.instantiate(decl: *AstXmlNode, base: *AstXmlNode, memberType: AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(memberType)) {
        return xmlEmptyNode()
    }
    val classParams: List<Str> = xmlTypeParamNames(decl)
    if (classParams.size() == 0) {
        return memberType
    }
    if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
        return memberType
    }
    val args: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
    if (args.size() != classParams.size()) {
        return memberType
    }
    var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var i: Int = 0
    while (i < classParams.size()) {
        bindings.insert(classParams[i], args[i])
        i = i + 1
    }
    return semSubstitute(memberType, bindings, classParams)
}

// The return type of a call, the callee's type parameters bound from an explicit
// instantiation (`identity<Int>(7)`) or the receiver (`Box<Int>.get()`). A result that
// still mentions a type parameter stays symbolic (the C++ template specializes it
// later); a parameter nothing binds leaves no type to spell.
fun SemInfer.functionReturn(name: *Str, typeArgs: *List<AstXmlNode>, receiver: *AstXmlNode): AstXmlNode {
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
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings)
        }
    }
    return xmlEmptyNode()
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

fun SemInfer.memberReturn(callee: *AstXmlNode): AstXmlNode {
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
        val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
        if (!xmlIsEmpty(result)) {
            return semMachineType(result, fn, bindings)
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
        // A machine is already iterable: `x.iter()` is `x` (`impl_specs/for.md`); `..T`
        // is not spellable, so no function could take one.
        if (calleeText == "iter") {
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
        if (calleeText == "value" && recvName == "Opt" && typeArgs.size() > 0) {
            return semReRole(typeArgs[0], AstNodeKind.Type)
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

fun SemInfer.callReturn(callee: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(callee)
    when (kind) {
        AstNodeCategory.ExprGenericName -> {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semGenericType(name, xmlChildren(callee, AstNodeKind.TypeArg))
            }
            return this.functionReturn(name, xmlChildren(callee, AstNodeKind.TypeArg), xmlEmptyNode())
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semNamedType(name)
            }
            val direct: AstXmlNode = this.functionReturn(name, List<AstXmlNode>(), xmlEmptyNode())
            if (!xmlIsEmpty(direct)) {
                return direct
            }
            return this.callableReturn(this.lookup(name))
        }

        AstNodeCategory.ExprMember -> {
            return this.memberReturn(callee)
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
                // For `iterPtr` the element type *is* `*T`, a place rather than a copy.
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

        AstNodeCategory.ExprCall -> {
            return this.callReturn(xmlChildPtr(e, AstNodeKind.Callee))
        }

        AstNodeCategory.ExprIndex -> {
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
