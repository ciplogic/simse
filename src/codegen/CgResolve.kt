// CgResolve.kt
//
// Symbol and type resolution: extension/overload/receiver lookups, member access, alias
// and callable resolution. Extension methods on `Emitter` (Codegen.kt).

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources

// Re-roots `child` under `role` (a shallow copy whose element name changes).
fun Emitter.renameRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    var renamed: AstXmlNode = child
    renamed.name = role
    return renamed
}

// The extension function a member call names, for a receiver whose type is already known
// (the IL's slot types - `ilCallNode` attaches the explicit template arguments a
// machine-receiver call needs).
fun Emitter.findExtensionFnByType(name: *Str, recv: AstXmlNode, argCount: Int): Int {
    if (xmlIsEmpty(recv)) {
        return -1
    }
    var i: Int = 0
    while (i < this.functions.size()) {
        val fn: *CgFn = *this.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != *name || fn.paramCount != argCount) {
            continue
        }
        if (this.unifyType(this.resolveAlias(fn.receiver), recv, fn.templateParams)) {
            return i - 1
        }
    }
    return -1
}

// The type-parameter name a machine pattern yields (`..*T`'s `T`, through the pointer a
// `..*T` writes), or "" when the node is not a machine pattern over a parameter.
fun machinePatternElement(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode) || xmlKind(typeNode) != AstNodeCategory.TypeYield) {
        return ""
    }
    var inner: *AstXmlNode = xmlChildPtr(typeNode, AstNodeKind.Inner)
    while (!xmlIsEmpty(inner)
        && (xmlKind(inner) == AstNodeCategory.TypePointer || xmlKind(inner) == AstNodeCategory.TypeReference)
    ) {
        inner = xmlChildPtr(inner, AstNodeKind.Inner)
    }
    if (xmlIsEmpty(inner) || xmlKind(inner) != AstNodeCategory.TypeNamed) {
        return ""
    }
    return xmlAttr(inner, AstNodeAttributeKind.Name)
}

// The function whose yielding body created a machine class (`where_yieldable` -> `where`),
// the inverse of `machineName(...) + "_yieldable"`. Null when nothing matches.
fun Emitter.findMachineCreator(recvType: AstXmlNode): *CgFn {
    val name: Str = xmlAttr(recvType, AstNodeAttributeKind.Name)
    if (name == "") {
        return null
    }
    var i: Int = 0
    while (i < this.functions.size()) {
        val fn: *CgFn = *this.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        val bare: Str = this.machineName(fn.decl) + "_yieldable" + fn.machineSuffix
        val qualified: Str = this.qualify(fn.packageName, this.machineName(fn.decl)) + "_yieldable" + fn.machineSuffix
        if (name == bare || name == qualified) {
            return fn
        }
    }
    return null
}

// The callee's type arguments for a call whose *result* does not name them: a `Unit`-terminal
// like `forEach` (`fun ..*T.forEach<T>(action: (*T) -> Unit)`) has no machine type to copy
// them from, so the element parameter is read from the receiver machine's own creating
// function (`where_yieldable<Int, ...>` yields `Int`) and bound by name. Answers `out` in
// `fn.templateParams` order; false - do not attach - when a parameter is not the element.
fun Emitter.machineCallArgsFromReceiver(fn: *CgFn, recvType: AstXmlNode, out: *List<AstXmlNode>): Bool {
    val calleeElement: Str = machinePatternElement(fn.receiver)
    if (calleeElement == "") {
        return false
    }
    val creator: *CgFn = this.findMachineCreator(recvType)
    if (creator == null) {
        return false
    }
    val creatorReturn: *AstXmlNode = xmlChildPtr(creator.decl, AstNodeKind.ReturnType)
    val creatorElement: Str = machinePatternElement(creatorReturn)
    if (creatorElement == "") {
        return false
    }
    var elementAt: Int = -1
    var e: Int = 0
    while (e < creator.templateParams.size()) {
        if (creator.templateParams[e] == creatorElement) {
            elementAt = e
        }
        e = e + 1
    }
    val recvArgs: List<AstXmlNode> = xmlChildren(recvType, AstNodeKind.TypeArg)
    if (elementAt < 0 || elementAt >= recvArgs.size()) {
        return false
    }
    val element: AstXmlNode = recvArgs[elementAt]
    var i: Int = 0
    while (i < fn.templateParams.size()) {
        if (fn.templateParams[i] != calleeElement) {
            return false
        }
        out.append(element)
        i = i + 1
    }
    return true
}

// The receiver argument for a lowered Simse call: a value receiver is a raw pointer, so
// the argument is the receiver's address (`simse_addressOf`, src/rtl/types.hpp); a
// counted reference is unwrapped with `.get()`, and a bare `this` is already that pointer.
fun Emitter.receiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    if (this.selfKind == NameKind.Value && xmlKind(recv) == AstNodeCategory.ExprName
        && xmlAttr(recv, AstNodeAttributeKind.Name) == "this"
    ) {
        return this.selfPointer()
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (!xmlIsEmpty(recvType)) {
        val kind: AstNodeCategory = xmlKind(recvType)
        if (kind == AstNodeCategory.TypeReference
            || (kind == AstNodeCategory.TypeGeneric && xmlAttr(recvType, AstNodeAttributeKind.Name) == "PList")
        ) {
            val exprText: Str = this.expr(recv, 12, xmlEmptyNode())
            return `(@exprText).get()`
        }
        if (kind == AstNodeCategory.TypePointer) {
            return this.expr(recv, 12, xmlEmptyNode())
        }
    }
    val exprText2: Str = this.expr(recv, 12, xmlEmptyNode())
    return `simse_addressOf(@exprText2)`
}

// The emitted receiver as the raw pointer it already is: `self`, or C++'s `this` inside a
// closure class. That pointer is the receiver's address, so a borrow of `this` spells no
// dereference.
fun Emitter.selfPointer(): Str {
    if (this.inClosureMethod) {
        return "this"
    }
    return "self"
}

// The receiver argument for a lowered native call: the host signature decides the form, so
// the expression passes as it is, dereferenced through a handle (the RTL's value receivers
// are `T&`).
fun Emitter.nativeReceiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (this.isHandleType(recvType)) {
        val exprText3: Str = this.expr(recv, 12, xmlEmptyNode())
        return `(*@exprText3)`
    }
    return this.expr(recv, 12, xmlEmptyNode())
}

// Index into `functions` of the first Simse-declared receiver function with this name, or -1.
fun Emitter.findReceiverFnByName(name: *Str): Int {
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name) {
            return i
        }
    }
    return -1
}

// Index into `functions` of a Simse extension matching the receiver and arity, or -1.
fun Emitter.findExtensionFn(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name && fn.paramCount == argCount && this.unifyType(
                this.resolveAlias(fn.receiver),
                recv,
                fn.templateParams
            )
        ) {
            return i
        }
    }
    return -1
}

// The operator a receiver's type declares for a syntax (specs/functions.md): `get`/`set`
// for the indexers, `compareTo`/`equals`/`plus` for a binary one (`a < b` is `compareTo`,
// `a == b` `equals`, `a + b` `plus`). -1 when the type declares none - the built-in
// meaning applies then.
fun Emitter.operatorFn(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val at: Int = this.findExtensionFn(name, recvExpr, argCount)
    if (at < 0) {
        return -1
    }
    val fn: *CgFn = *this.functions[at]
    if (xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true") {
        return -1
    }
    return at
}

// The binary-operator lookup for `a < b`/`a == b`/`a + b`: the ordinary receiver match,
// then the view fallback. A `Str` or literal operand of an operator declared on a
// `Span<Char>` (`StrView`, src/rtl/StrView.kt) reads as a view of itself (`spanOfStr`),
// so `str == view`, `view + "lit"` and `f() < "lit"` all reach the view declaration and
// no C++ overload has to exist for a mixed pair.
fun Emitter.operatorBinaryFn(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val direct: Int = this.operatorFn(name, recvExpr, argCount)
    if (direct >= 0) {
        return direct
    }
    if (!this.isViewableStringOperand(recvExpr)) {
        return -1
    }
    var i: Int = 0
    while (i < this.functions.size()) {
        val fn: *CgFn = *this.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != *name || fn.paramCount != argCount) {
            continue
        }
        if (xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true") {
            continue
        }
        if (this.isCharSpanType(fn.receiver)) {
            return i - 1
        }
    }
    return -1
}

// Whether a type node is a `Span<Char>` after aliases (`StrView`): the target a string
// operand's view conversion produces. A `Span<Int>` is not a string view, so a `Str`
// operand does not convert to it.
fun Emitter.isCharSpanType(typeNode: *AstXmlNode): Bool {
    val resolved: AstXmlNode = this.resolveAlias(typeNode)
    if (xmlIsEmpty(resolved) || xmlKind(resolved) != AstNodeCategory.TypeGeneric) {
        return false
    }
    if (xmlAttr(resolved, AstNodeAttributeKind.Name) != "Span") {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(resolved, AstNodeKind.TypeArg)
    if (args.size() != 1) {
        return false
    }
    val element: AstXmlNode = this.resolveAlias(args[0])
    return xmlKind(element) == AstNodeCategory.TypeNamed
        && xmlAttr(element, AstNodeAttributeKind.Name) == "Char"
}

// Whether an operand can be read as a `StrView` (`spanOfStr`): a string literal is
// already a pool entry at emission, and a `Str` is borrowed, through a pointer or a
// reference the same way. Anything else is not a string.
fun Emitter.isViewableStringOperand(expr: *AstXmlNode): Bool {
    if (xmlKind(expr) == AstNodeCategory.ExprStrLit) {
        return true
    }
    val type: AstXmlNode = this.resolveAlias(this.pointee(this.inferType(expr)))
    if (xmlIsEmpty(type) || xmlKind(type) != AstNodeCategory.TypeNamed) {
        return false
    }
    return xmlAttr(type, AstNodeAttributeKind.Name) == "Str"
}

// One string operand read as a view: a literal as it stands (the string table's entry
// *is* a `StrView`), a `Str` borrowed by its address, a `*Str` as it is, a `&Str`
// unwrapped. The `spanOfStr` reach is recorded here: the conversion is synthesized, so
// no call node exists for `collectNames` to see (the rule the indexers follow).
fun Emitter.stringOperandView(expr: *AstXmlNode): Str {
    val text: Str = this.expr(expr, 12, xmlEmptyNode())
    if (xmlKind(expr) == AstNodeCategory.ExprStrLit) {
        return text
    }
    this.referencedNames.insert("simse_spanOfStr", true)
    val type: AstXmlNode = this.inferType(expr)
    if (xmlKind(type) == AstNodeCategory.TypePointer) {
        return `simse_spanOfStr(@text)`
    }
    if (xmlKind(type) == AstNodeCategory.TypeReference) {
        return `simse_spanOfStr(simse_addressOf((@text).get()))`
    }
    return `simse_spanOfStr(simse_addressOf(@text))`
}

// The synthesized call for one index operator, as the receiver argument the method
// convention takes (`T* self`) plus the index and - for `set` - the value. The reach is
// recorded here because the AST has no call node for `collectNames` to see: a prelude
// `get`/`set` is emitted only when reached, and this is its only caller. The arguments go
// through `cgMethodStrArgs`: a string literal into a bare type parameter must materialize
// the way a method call's does, or C++ cannot deduce the operator's `T`.
fun Emitter.operatorIndexGetText(at: Int, recvExpr: *AstXmlNode, indexNode: *AstXmlNode): Str {
    val fn: *CgFn = *this.functions[at]
    this.referencedNames.insert(fn.name, true)
    val recvText: Str = this.receiverArg(fn.receiver, recvExpr)
    var rendered: List<Str> = List<Str>()
    rendered.append(this.expr(indexNode, 0, xmlEmptyNode()))
    var argNodes: List<AstXmlNode> = List<AstXmlNode>()
    argNodes.append(*indexNode)
    val fixed: List<Str> = this.cgMethodStrArgs(fn, *argNodes, *rendered)
    val qualifyText: Str = this.qualify(fn.packageName, fn.name)
    val cgJoinText: Str = cgJoin(fixed, ", ")
    return `@qualifyText(@recvText, @cgJoinText)`
}

fun Emitter.operatorIndexSetText(
    at: Int, recvExpr: *AstXmlNode, indexNode: *AstXmlNode, valueNode: *AstXmlNode
): Str {
    val fn: *CgFn = *this.functions[at]
    this.referencedNames.insert(fn.name, true)
    val recvText: Str = this.receiverArg(fn.receiver, recvExpr)
    var rendered: List<Str> = List<Str>()
    rendered.append(this.expr(indexNode, 0, xmlEmptyNode()))
    rendered.append(this.expr(valueNode, 0, xmlEmptyNode()))
    var argNodes: List<AstXmlNode> = List<AstXmlNode>()
    argNodes.append(*indexNode)
    argNodes.append(*valueNode)
    val fixed: List<Str> = this.cgMethodStrArgs(fn, *argNodes, *rendered)
    val qualifyText: Str = this.qualify(fn.packageName, fn.name)
    val cgJoinText: Str = cgJoin(fixed, ", ")
    return `@qualifyText(@recvText, @cgJoinText)`
}

// One synthesized binary-operator call (specs/functions.md): the receiver argument the
// method convention takes (`T* self`), the right operand, and the syntax's derivation from
// the operator's answer - `<` is `compareTo(...) < 0` (`<=`, `>`, `>=` with their own
// test), `==` is `equals(...)`, `!=` its negation, `+` is `plus(...)`. The reach is
// recorded here for the same reason the indexers' is: no call node exists for
// `collectNames` to see. The right operand converts against the *emitted* parameter the
// way `convertArgument` converts a written one: the auto-borrow pass may have turned it
// into a `*T`, and the argument is then its address. An operand of a view operator that
// is a `Str` (or a literal) reads as a view of itself (`spanOfStr`).
fun Emitter.operatorBinaryText(at: Int, op: *Str, lhsExpr: *AstXmlNode, rhsExpr: *AstXmlNode): Str {
    val fn: *CgFn = *this.functions[at]
    this.referencedNames.insert(fn.name, true)
    var recvText: Str = ""
    if (this.isCharSpanType(fn.receiver) && this.isViewableStringOperand(lhsExpr)) {
        val recvView: Str = this.stringOperandView(lhsExpr)
        recvText = `simse_addressOf(@recvView)`
    } else {
        recvText = this.receiverArg(fn.receiver, lhsExpr)
    }
    var rhsText: Str = ""
    if (this.operatorParamIsView(fn, rhsExpr)) {
        rhsText = this.stringOperandView(rhsExpr)
    } else if (this.operatorParamIsPointer(fn, rhsExpr)) {
        var borrowed: AstXmlNode = this.ilBorrowNode(rhsExpr, 0)
        rhsText = this.expr(borrowed, 0, xmlEmptyNode())
    } else {
        rhsText = this.expr(rhsExpr, 0, xmlEmptyNode())
    }
    var rendered: List<Str> = List<Str>()
    rendered.append(rhsText)
    var argNodes: List<AstXmlNode> = List<AstXmlNode>()
    argNodes.append(*rhsExpr)
    val fixed: List<Str> = this.cgMethodStrArgs(fn, *argNodes, *rendered)
    val qualifyText: Str = this.qualify(fn.packageName, fn.name)
    val cgJoinText: Str = cgJoin(fixed, ", ")
    val call: Str = `@qualifyText(@recvText, @cgJoinText)`
    if (op == "==" || op == "+") {
        return call
    }
    if (op == "!=") {
        return `(!@call)`
    }
    if (op == "<") {
        return `(@call < 0)`
    }
    if (op == "<=") {
        return `(@call <= 0)`
    }
    if (op == ">") {
        return `(@call > 0)`
    }
    return `(@call >= 0)`
}

// Whether one operator's only parameter is a view while the argument is a `Str` (or a
// literal): the argument is then read through `spanOfStr` rather than passed as it is.
fun Emitter.operatorParamIsView(fn: *CgFn, argExpr: *AstXmlNode): Bool {
    val params: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
    if (params.size() == 0) {
        return false
    }
    val paramType: AstXmlNode = xmlChildPtr(params[0], AstNodeKind.Type)
    if (!this.isCharSpanType(paramType)) {
        return false
    }
    return this.isViewableStringOperand(argExpr)
}

// Whether one operator's only parameter is a pointer while the argument is a value: the
// auto-borrow signature `convertArgument` would address at a written call site.
fun Emitter.operatorParamIsPointer(fn: *CgFn, argExpr: *AstXmlNode): Bool {
    val params: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
    if (params.size() == 0) {
        return false
    }
    val paramType: AstXmlNode = xmlChildPtr(params[0], AstNodeKind.Type)
    if (xmlKind(paramType) != AstNodeCategory.TypePointer) {
        return false
    }
    val argType: AstXmlNode = this.inferType(argExpr)
    if (xmlIsEmpty(argType) || xmlKind(argType) == AstNodeCategory.TypePointer
        || this.isHandleType(argType)
    ) {
        return false
    }
    return semUnifyType(semPointeeOf(paramType), semPointeeOf(argType), xmlTypeParamNames(fn.decl))
}

// Index into `nativeExtensions[name]` of a matching receiver, or -1.
fun Emitter.findNativeExt(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(name)
    if (extensions == null) {
        return -1
    }
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*ext, i) in extensions) {
        if (!xmlIsEmpty(ext.receiver) && ext.argCount == argCount
            && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)
        ) {
            return i
        }
    }
    return -1
}

fun Emitter.memberAccess(base: *AstXmlNode, name: *Str): Str {
    var arrow: Bool = false
    val baseType: AstXmlNode = this.inferType(base)
    if (xmlKind(base) == AstNodeCategory.ExprName
        && xmlAttr(base, AstNodeAttributeKind.Name) == "this" && this.selfKind == NameKind.Value
    ) {
        // A value receiver is a raw pointer (`T* self`), so its members are reached with `->`.
        arrow = true
    } else if (!xmlIsEmpty(baseType)) {
        arrow = this.isHandleType(baseType)
    } else if (xmlKind(base) == AstNodeCategory.ExprName) {
        val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
        if (baseName == "this") {
            arrow = this.selfKind != NameKind.Value
        } else {
            val kind: *NameKind = this.nameKinds.getPtr(baseName)
            if (kind != null && *kind == NameKind.Shared) {
                arrow = true
            }
        }
    }
    var field: Str = name
    val recv: AstXmlNode = this.pointee(baseType)
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric && xmlAttr(
            recv,
            AstNodeAttributeKind.Name
        ) == "Res"
    ) {
        if (name == "value") {
            field = "Value"
        } else if (name == "error") {
            field = "Error"
        }
    }
    var op: Str = "."
    if (arrow) {
        op = "->"
    }
    // A value receiver's own member access reads through its pointer (`self->field`); the
    // bare name `this` elsewhere reads as the object (`(*self)`).
    if (xmlKind(base) == AstNodeCategory.ExprName && xmlAttr(base, AstNodeAttributeKind.Name) == "this"
        && this.selfKind == NameKind.Value
    ) {
        if (this.inClosureMethod) {
            return "this->" + field
        }
        return "self->" + field
    }
    return this.expr(base, 12, xmlEmptyNode()) + op + field
}

fun Emitter.nullTo(expected: *AstXmlNode): Str {
    if (!xmlIsEmpty(expected) && xmlKind(expected) == AstNodeCategory.TypeGeneric && xmlAttr(
            expected,
            AstNodeAttributeKind.Name
        ) == "Opt"
    ) {
        val typeArgsStringText: Str = this.typeArgsString("Opt", xmlChildren(expected, AstNodeKind.TypeArg))
        return `Opt<@typeArgsStringText>()`
    }
    return "nullptr"
}

// A receiver's type is resolved through a `typealias` before matching (`StrView` is
// `Span<Char>`, src/rtl/StrView.kt), and so is the declared receiver, so the match works
// from either side. A non-alias type is returned as it came in.
fun Emitter.resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && xmlKind(current) == AstNodeCategory.TypeNamed) {
        guard = guard + 1
        if (guard >= 100) {
            break
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null) {
            break
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            break
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            break
        }
        current = target
    }
    return current
}

fun Emitter.expectedCallable(expected: *AstXmlNode): AstXmlNode {
    val resolved: AstXmlNode = this.resolveAlias(expected)
    if (!xmlIsEmpty(resolved) && xmlKind(resolved) == AstNodeCategory.TypeFunction) {
        return resolved
    }
    return xmlEmptyNode()
}

// A non-native function with the given name and parameter count; a method is not one.
fun Emitter.findFunction(name: *Str, argCount: Int): AstXmlNode {
    for (*fn in this.functions) {
        if (fn.isNative || fn.isMethod
            || fn.name != name
        ) {
            continue
        }
        if (xmlCount(fn.decl, AstNodeKind.Param) == argCount) {
            return fn.decl
        }
    }
    return xmlEmptyNode()
}

fun Emitter.isUnitType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return true
    }
    return xmlKind(typeNode) == AstNodeCategory.TypeNamed && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Unit"
}
