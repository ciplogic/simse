// SemaCall.kt
//
// `Analyzer`'s call checks: views, handles, arity and the extension-call form. Extension
// methods on `Analyzer` (Sema.kt).

package sema
import compiler

import parser
import common

// Whether a parameter type is a *view*: `Span<T>`, `StrView`, or a program
// `typealias` reaching one (followed one hop at a time, as `Emitter.resolveAlias`).
fun Analyzer.isViewType(typeNode: *AstXmlNode): Bool {
    if (semaIsSpanType(typeNode)) {
        return true
    }
    var name: Str = ""
    if (xmlKind(typeNode) == AstNodeCategory.TypeNamed) {
        name = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    }
    var guard: Int = 0
    while (name != "" && guard < 64) {
        guard = guard + 1
        if (name == "StrView") {
            return true
        }
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null) {
            return false
        }
        if (xmlKind(decl) != AstNodeCategory.TypeAlias) {
            return false
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            return false
        }
        if (semaIsSpanType(target)) {
            return true
        }
        if (xmlKind(target) != AstNodeCategory.TypeNamed) {
            return false
        }
        name = xmlAttr(target, AstNodeAttributeKind.Name)
    }
    return false
}

// `exprType` looked through the wrappers whose value is their operand's (`copy(x)`,
// `*x`, `&x`): peeling to the operand is what the view check wants.
fun Analyzer.viewArgBase(arg: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(arg)
    if (kind == AstNodeCategory.ExprDeref || kind == AstNodeCategory.ExprRef
        || kind == AstNodeCategory.ExprCopy || kind == AstNodeCategory.ExprUnary
    ) {
        val operand: *AstXmlNode = xmlChildPtr(arg, AstNodeKind.Operand)
        if (xmlIsEmpty(operand)) {
            return xmlEmptyNode()
        }
        return this.viewArgBase(operand)
    }
    return this.exprType(arg)
}

// Whether some overload of `callee` with `argCount` parameters would take `arg` for
// parameter `index`: a non-view parameter takes the argument, and a view parameter
// takes it when the argument is (or may be) a view. `checkCallArity` sees only the
// first arity match, but two overloads may differ in parameter shape.
fun Analyzer.viewArgHasOverload(callee: *Str, argCount: Int, index: Int, arg: *AstXmlNode): Bool {
    val overloads: *List<AstXmlNode> = this.functions.getPtr(callee)
    if (overloads == null) {
        return false
    }
    for (*overload in overloads) {
        if (xmlCount(overload, AstNodeKind.Param) != argCount) {
            continue
        }
        val params: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
        if (index >= params.size()) {
            continue
        }
        val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
        if (xmlIsEmpty(param) || !this.isViewType(param)) {
            return true
        }
        if (xmlKind(arg) == AstNodeCategory.ExprStrLit) {
            return true
        }
        val actual: AstXmlNode = this.viewArgBase(arg)
        if (xmlIsEmpty(actual) || !semaIsOwnedViewSource(actual)) {
            return true
        }
    }
    return false
}

// A view parameter takes a view: `Span<T>` owns nothing, so the C++ has no conversion
// from a `Str` or `List<T>` and the emitter prints the call as written. A string
// literal is already a view (`__sm_stringTable[k]`).
fun Analyzer.checkViewArgument(callee: *Str, function: *AstXmlNode, index: Int, arg: *AstXmlNode): Unit {
    val params: List<AstXmlNode> = xmlChildren(function, AstNodeKind.Param)
    if (index >= params.size()) {
        return
    }
    val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
    if (xmlIsEmpty(param) || !this.isViewType(param)) {
        return
    }
    if (xmlKind(arg) == AstNodeCategory.ExprStrLit) {
        return
    }
    val actual: AstXmlNode = this.viewArgBase(arg)
    if (xmlIsEmpty(actual) || !semaIsOwnedViewSource(actual)) {
        return
    }
    if (this.viewArgHasOverload(callee, params.size(), index, arg)) {
        return
    }
    val paramText: Str = semaTypeText(param)
    val actualText: Str = semaTypeText(actual)
    this.diag(
        xmlLine(arg), xmlColumn(arg),
        `types are not compatible: '@callee' takes '@paramText' and the argument is '@actualText'`
    )
}

fun Analyzer.checkCallArity(call: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlIsEmpty(callee)) {
        return
    }
    val kind: AstNodeCategory = xmlKind(callee)
    val generic: Bool = kind == AstNodeCategory.ExprGenericName
    if (kind != AstNodeCategory.ExprName && !generic) {
        return
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (this.lookupValue(name).hasValue()) {
        return // shadowed by a local/param
    }
    val argCount: Int = xmlCount(call, AstNodeKind.Arg)

    // A construction is not a call: the argument count must match the field count.
    val decl: *AstXmlNode = this.types.getPtr(name)
    if (decl != null) {
        if (xmlKind(decl) == AstNodeCategory.DataClass) {
            val fieldCount: Int = xmlCount(decl, AstNodeKind.Field)
            if (fieldCount != argCount) {
                this.diag(
                    xmlLine(call), xmlColumn(call), `data class '@name' expects @fieldCount field(s) but got @argCount`
                )
            }
            return
        }
    }

    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return
    }
    var typeArgCount: Int = 0
    if (generic) {
        typeArgCount = xmlCount(callee, AstNodeKind.TypeArg)
    }
    for (*overload in overloads) {
        if (generic && xmlCount(overload, AstNodeKind.TypeParam) != typeArgCount) {
            continue
        }
        val paramCount: Int = xmlCount(overload, AstNodeKind.Param)
        if (paramCount == argCount) {
            val args: List<AstXmlNode> = xmlChildren(call, AstNodeKind.Arg)
            var a: Int = 0
            while (a < argCount) {
                this.checkHandleArgument(name, overload, a, args[a])
                this.checkViewArgument(name, overload, a, args[a])
                a = a + 1
            }
            return
        }
        // Trailing arguments may pack into a final list parameter, so counts both above
        // and below the parameter count are legal (`specs/functions.md`).
        if (paramCount > 0) {
            val params: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
            val lastType: *AstXmlNode = xmlChildPtr(params[paramCount-1], AstNodeKind.Type)
            if (semIsPackTarget(lastType) && argCount >= paramCount - 1) {
                return
            }
        }
    }
    this.diag(
        xmlLine(call), xmlColumn(call),
        `no overload of '@name' takes @argCount argument(s)`
    )
}

// The receiver's type when the checker tracks it (a local/parameter, a literal, a field
// read, a member call, or the machine a wrap builds); empty when unknown.
fun Analyzer.exprType(expr: *AstXmlNode): AstXmlNode {
    if (xmlKind(expr) == AstNodeCategory.ExprName) {
        val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(expr, AstNodeAttributeKind.Name))
        if (binding.hasValue()) {
            return binding.value().type
        }
        return xmlEmptyNode()
    }
    // A literal's type is its spelling; an unannotated `val s = "x"` is a `Str`, and the
    // `for` rewrite needs it to see the string through `spanOfStr`.
    if (xmlKind(expr) == AstNodeCategory.ExprStrLit) {
        return semNamedType("Str")
    }
    if (xmlKind(expr) == AstNodeCategory.ExprCharLit) {
        return semNamedType("Char")
    }
    if (xmlKind(expr) == AstNodeCategory.ExprMember) {
        // A field read (`this.functions`, `box.items`) or a machine's element
        // (`_sm_for1.current`): the base carries the type both are read through.
        val base: AstXmlNode = this.exprType(xmlChildPtr(expr, AstNodeKind.Receiver))
        if (xmlIsEmpty(base)) {
            return xmlEmptyNode()
        }
        val member: Str = xmlAttr(expr, AstNodeAttributeKind.Name)
        if (xmlKind(base) == AstNodeCategory.TypeYield && member == "current") {
            return this.semaYieldElement(base)
        }
        return this.semaFieldType(base, member)
    }
    if (xmlKind(expr) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
        if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
            var built: AstXmlNode = AstXmlNode(
                AstNodeKind.Type,
                AstNodeCategory.TypeGeneric,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            built.attributes.append(
                AstNodeAttribute(
                    AstNodeAttributeKind.Name,
                    xmlAttr(callee, AstNodeAttributeKind.Name)
                )
            )
            val args: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
            xmlAddChildren(built, args)
            return built
        }
        if (xmlKind(callee) == AstNodeCategory.ExprMember) {
            // A member call (`xs.toArray()`, `c.iter()`): the declared extension's return
            // type, or the machine a wrap builds. Naming the wrap's machine is what lets a
            // loop variable (`_sm_forN.current`) and every field read through it carry the
            // element type - the chain the `for` rewrite follows.
            return this.semaMemberCall(expr)
        }
        if (xmlKind(callee) == AstNodeCategory.ExprName) {
            // A construction (`Rack(...)`) builds a value of the name it calls.
            val builtName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.types.has(builtName)) {
                return semNamedType(builtName)
            }
        }
        return xmlEmptyNode()
    }
    return xmlEmptyNode()
}

// The declared type of the field `member` on a base of type `base`, when both are known.
fun Analyzer.semaFieldType(base: AstXmlNode, member: Str): AstXmlNode {
    val outer: AstXmlNode = this.semaReceiverOuter(base)
    val kind: AstNodeCategory = xmlKind(outer)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return xmlEmptyNode()
    }
    val decl: *AstXmlNode = this.types.getPtr(xmlAttr(outer, AstNodeAttributeKind.Name))
    if (decl == null || decl.name != AstNodeKind.DataClass) {
        return xmlEmptyNode()
    }
    for (*field in xmlChildren(decl, AstNodeKind.Field)) {
        if (xmlAttr(field, AstNodeAttributeKind.Name) == member) {
            return xmlChild(field, AstNodeKind.Type)
        }
    }
    return xmlEmptyNode()
}

// The element a machine hands out: the `Inner` of its `..T`, as a value type.
fun Analyzer.semaYieldElement(machine: AstXmlNode): AstXmlNode {
    val inner: AstXmlNode = xmlChild(machine, AstNodeKind.Inner)
    if (xmlIsEmpty(inner)) {
        return xmlEmptyNode()
    }
    return semReRole(inner, AstNodeKind.Type)
}

// The element a container's span iterates, or empty when the container has no span view:
// `List<T>`/`Array<T>`/`Span<T>` hold `T`, `Str` holds `Char` (`spanConversion`). A `StrView`
// is `Span<Char>` through its alias, so it lands on the `Span` arm.
fun Analyzer.semaSpanElement(containerType: AstXmlNode): AstXmlNode {
    val outer: AstXmlNode = this.semaReceiverOuter(containerType)
    val kind: AstNodeCategory = xmlKind(outer)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return xmlEmptyNode()
    }
    val name: Str = xmlAttr(outer, AstNodeAttributeKind.Name)
    if (name == "Str") {
        return semNamedType("Char")
    }
    if (name != "Span" && this.spanConversion(containerType) == "") {
        return xmlEmptyNode()
    }
    val args: List<AstXmlNode> = xmlChildren(outer, AstNodeKind.TypeArg)
    if (args.size() == 1) {
        return semReRole(args[0], AstNodeKind.Type)
    }
    return xmlEmptyNode()
}

// The receiver a function declares, whichever spelling: the `fun T.f()` receiver child, or
// the first parameter named `this` (`fun f(this: T)`). Empty when the function has none.
fun semaReceiverPattern(fn: *AstXmlNode): AstXmlNode {
    val receiver: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Receiver)
    if (!xmlIsEmpty(receiver)) {
        return *receiver
    }
    return semExtensionReceiver(fn)
}

// The result type of a member call (`recv.name(...)`), when the receiver's type and the
// declaration are known: the declared function's return type with the receiver's type
// parameters substituted. A wrap (`iter`/`iterPtr`) answers with the machine it builds: the
// container's element through its span, a machine itself (identity), or the declared wrap.
// Empty when the checker cannot name it; the rewrite stays silent then.
fun Analyzer.semaMemberCall(call: *AstXmlNode): AstXmlNode {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return xmlEmptyNode()
    }
    val receiverType: AstXmlNode = this.iteratedType(xmlChildPtr(callee, AstNodeKind.Receiver))
    if (xmlIsEmpty(receiverType)) {
        return xmlEmptyNode()
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (name == "iter" || name == "iterPtr") {
        if (xmlKind(receiverType) == AstNodeCategory.TypeYield) {
            return receiverType
        }
        val element: AstXmlNode = this.semaSpanElement(receiverType)
        if (!xmlIsEmpty(element)) {
            return semMachineOfElement(element)
        }
    }
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return xmlEmptyNode()
    }
    for (*candidate in overloads) {
        val pattern: AstXmlNode = semaReceiverPattern(candidate)
        if (xmlIsEmpty(pattern)) {
            continue
        }
        val params: List<Str> = xmlTypeParamNames(candidate)
        if (!this.semaReceiverNameMatches(pattern, receiverType, params)) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (!semBindTypes(pattern, receiverType, params, bindings)) {
            continue
        }
        val ret: AstXmlNode = semSubstitute(xmlChildPtr(candidate, AstNodeKind.ReturnType), bindings, params)
        if (!xmlIsEmpty(ret)) {
            return ret
        }
    }
    return xmlEmptyNode()
}

// A `..element` node for the checker's own bindings. The C++ class is named by
// `semMachineType` at the call site; the checker only needs the element type.
fun semMachineOfElement(element: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypeYield, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    xmlAddChild(node, semReRole(element, AstNodeKind.Inner))
    return node
}

// `for` is lowered into the declaration of the machine it iterates (`_sm_for<n>`,
// impl_specs/for.md), whose initializer is an invisible `iter()`/`iterPtr()` wrap:
// the template names are the marker, and something is iterable when that wrap
// resolves. Reporting here names the user's line rather than the generated statement.
fun Analyzer.checkForIterable(stmt: *AstXmlNode): Unit {
    val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
    if (xmlIsEmpty(init) || !semaIsForTemplateName(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
        return
    }
    if (xmlKind(init) != AstNodeCategory.ExprCall) {
        return
    }
    val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val wrap: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val receiverType: AstXmlNode = this.iteratedType(receiver)
    if (xmlIsEmpty(receiverType)) {
        return
    }
    if (xmlKind(receiverType) == AstNodeCategory.TypeYield) {
        // A machine is the identity for `iter`: it hands out values, not places, so
        // it has no pointer form.
        if (wrap == "iter") {
            return
        }
        this.diag(
            xmlLine(stmt), xmlColumn(stmt),
            "a `for (*x in m)` needs an `iterPtr`, and a machine yields values "
                    + "rather than places: iterate it with `for (x in m)`"
        )
        return
    }
    if (this.hasWrap(wrap, receiverType)) {
        return
    }
    // A container's `for` is rewritten to iterate its span (`spanForAt`), so it is iterable
    // even though the prelude declares no `iter` of its own for it.
    if (this.spanConversion(receiverType) != "") {
        return
    }
    this.diag(
        xmlLine(stmt), xmlColumn(stmt),
        fmtStr(
            "a `for` iterates a machine (`..T`) or a type with a `|`, and | has neither; iterate a container with `while` and an index",
            wrap, semaTypeText(receiverType)
        )
    )
}

// Whether a wrap (`iter`, `iterPtr`) takes this receiver, by receiver *name* only:
// `List<T>` takes any `List<...>`, a type parameter takes anything. An undecidable
// name stays silent; the emitted call is resolved with full unification in `codegen`.
fun Analyzer.hasWrap(wrap: *Str, receiverType: *AstXmlNode): Bool {
    val overloads: *List<AstXmlNode> = this.functions.getPtr(wrap)
    if (overloads == null) {
        return false
    }
    for (*candidate in overloads) {
        val receiverPattern: *AstXmlNode = xmlChildPtr(candidate, AstNodeKind.Receiver)
        if (xmlIsEmpty(receiverPattern)) {
            continue
        }
        if (this.semaReceiverNameMatches(receiverPattern, receiverType, xmlTypeParamNames(candidate))) {
            return true
        }
    }
    return false
}

// The receiver's outer type, ignoring handles and type arguments - and following a
// `typealias`, so a view (`StrView` is `Span<Char>`) matches the span's own extensions.
fun Analyzer.semaReceiverOuter(actual: AstXmlNode): AstXmlNode {
    var current: AstXmlNode = actual
    var guard: Int = 0
    while (guard < 32) {
        guard = guard + 1
        val kind: AstNodeCategory = xmlKind(current)
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            val inner: AstXmlNode = xmlChild(current, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return current
            }
            current = inner
            continue
        }
        if (kind == AstNodeCategory.TypeNamed) {
            val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
            val decl: *AstXmlNode = this.types.getPtr(name)
            if (decl != null && decl.name == AstNodeKind.TypeAlias) {
                val target: AstXmlNode = xmlChild(decl, AstNodeKind.TargetType)
                if (!xmlIsEmpty(target)) {
                    current = target
                    continue
                }
            }
        }
        return current
    }
    return current
}

// The span a container's `for` iterates through, or "" when the receiver is not a container
// the prelude can view: `List` through `spanOf`, `Array` through `spanOfArray` (a name of its
// own - the extractor refuses two same-arity overloads of a plain call), `Str` through
// `spanOfStr`. A `Span`/`StrView` is already a span, and a machine or a user type is somebody
// else's `iter`.
fun Analyzer.spanConversion(receiverType: *AstXmlNode): Str {
    val outer: AstXmlNode = this.semaReceiverOuter(*receiverType)
    val kind: AstNodeCategory = xmlKind(outer)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return ""
    }
    val name: Str = xmlAttr(outer, AstNodeAttributeKind.Name)
    if (name == "List") {
        return "spanOf"
    }
    if (name == "Array") {
        return "spanOfArray"
    }
    // A `Str` iterates its bytes: `spanOfStr` borrows the string into a view, so a `for`
    // over a `Str` is the same span machine a `StrView` uses.
    if (name == "Str") {
        return "spanOfStr"
    }
    return ""
}

// A `for` over a `List`/`Array`/`Str` iterates its *span*: the wrap's receiver becomes
// `spanOf(c)` (or `spanOfArray(c)`/`spanOfStr(c)`), so the span's `iter`/`iterPtr` is the one
// iterator machine a program carries, and the machine's C++ class is named after the span
// (`semMachineType`), not the container. The lowering hoists the view into a function-scope
// slot, so it outlives the loop (`stress/collections`'s for-temporary part). A `Span`/`StrView`
// receiver is already a span; a machine or a user type is left as written.
fun Analyzer.spanForAt(stmts: *List<AstXmlNode>, index: Int): Unit {
    val machineDecl: *AstXmlNode = *stmts[index]
    if (xmlKind(machineDecl) != AstNodeCategory.StmtVarDecl) {
        return
    }
    val machineName: Str = xmlAttr(machineDecl, AstNodeAttributeKind.Name)
    if (!semaIsForTemplateName(*machineName)) {
        return
    }
    val init: *AstXmlNode = xmlChildPtr(machineDecl, AstNodeKind.Init)
    if (xmlKind(init) != AstNodeCategory.ExprCall) {
        return
    }
    val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val wrap: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (wrap != "iter" && wrap != "iterPtr") {
        return
    }
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val receiverType: AstXmlNode = this.iteratedType(receiver)
    if (xmlIsEmpty(receiverType) || xmlKind(receiverType) == AstNodeCategory.TypeYield) {
        return
    }
    val conversion: Str = this.spanConversion(receiverType)
    if (conversion == "") {
        return
    }
    // The receiver *becomes* `conversion(receiver)`, in place: the machine declaration, the
    // wrap and the loop are untouched, so only the iterated expression changes.
    val original: AstXmlNode = *receiver
    var conversionName: AstXmlNode = AstXmlNode(
        AstNodeKind.Callee, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    conversionName.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, conversion))
    var call: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    xmlAddChild(call, conversionName)
    var argument: AstXmlNode = original
    argument.name = AstNodeKind.Arg
    xmlAddChild(call, argument)
    receiver.kind = AstNodeCategory.ExprCall
    receiver.attributes = call.attributes
    receiver.Children = call.Children
}

fun Analyzer.semaReceiverNameMatches(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    val current: AstXmlNode = this.semaReceiverOuter(*actual)
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind == AstNodeCategory.TypeNamed) {
        return xmlKind(current) == AstNodeCategory.TypeNamed
                && xmlAttr(current, AstNodeAttributeKind.Name) == xmlAttr(pattern, AstNodeAttributeKind.Name)
    }
    if (patternKind == AstNodeCategory.TypeGeneric) {
        val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
        if (xmlIsTypeParam(patternName, typeParams)) {
            return true
        }
        return xmlKind(current) == AstNodeCategory.TypeGeneric
                && xmlAttr(current, AstNodeAttributeKind.Name) == patternName
    }
    return false
}

// The type a `for` iterates, for the shapes the checker can name: a tracked binding (with
// `this`), a field read (`this.functions`, `box.items`), an indexed element (`lists[0]`),
// a member call (`xs.toArray()`), a type construction, or a declared function's return
// type. Unknown stays silent.
fun Analyzer.iteratedType(expr: *AstXmlNode): AstXmlNode {
    if (xmlKind(expr) == AstNodeCategory.ExprName) {
        return this.exprType(expr)
    }
    if (xmlKind(expr) == AstNodeCategory.ExprMember) {
        // A field read: the field's declared type, reached through the base's.
        val base: AstXmlNode = this.iteratedType(xmlChildPtr(expr, AstNodeKind.Receiver))
        return this.semaFieldType(base, xmlAttr(expr, AstNodeAttributeKind.Name))
    }
    if (xmlKind(expr) == AstNodeCategory.ExprIndex) {
        // The element of the indexed container (`lists[0]` is what `lists` holds).
        val base: AstXmlNode = this.iteratedType(xmlChildPtr(expr, AstNodeKind.Receiver))
        val outer: AstXmlNode = this.semaReceiverOuter(base)
        if (xmlKind(outer) == AstNodeCategory.TypeGeneric) {
            val args: List<AstXmlNode> = xmlChildren(outer, AstNodeKind.TypeArg)
            if (args.size() == 1) {
                return args[0]
            }
        }
        return xmlEmptyNode()
    }
    if (xmlKind(expr) != AstNodeCategory.ExprCall) {
        return xmlEmptyNode()
    }
    val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
    if (xmlKind(callee) == AstNodeCategory.ExprMember) {
        // A member call (`xs.toArray()`, `words.keys()`): the declared return type,
        // resolved through the receiver (`exprType`).
        return this.exprType(expr)
    }
    var name: Str = ""
    if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
        name = xmlAttr(callee, AstNodeAttributeKind.Name)
        // `List<Int>()` builds a value of the name it calls; `f<Int>(x)` calls f.
        if (this.types.has(name)) {
            return this.exprType(expr)
        }
    } else if (xmlKind(callee) == AstNodeCategory.ExprName) {
        name = xmlAttr(callee, AstNodeAttributeKind.Name)
    } else {
        return xmlEmptyNode()
    }
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return xmlEmptyNode()
    }
    var known: AstXmlNode = xmlEmptyNode()
    for (*overload in overloads) {
        val declared: *AstXmlNode = xmlChildPtr(overload, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(declared)) {
            if (xmlKind(declared) == AstNodeCategory.TypeYield) {
                return declared
            }
            if (xmlIsEmpty(known)) {
                known = declared
            }
        }
    }
    return known
}

// Reports an arity mismatch only when a receiver-compatible extension exists but no
// overload takes the argument count; unknown receiver types stay silent.
fun Analyzer.checkExtensionCallArity(call: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return
    }
    val actual: AstXmlNode = this.exprType(xmlChildPtr(callee, AstNodeKind.Receiver))
    if (xmlIsEmpty(actual)) {
        return
    }
    val argCount: Int = xmlCount(call, AstNodeKind.Arg)
    var compatible: Bool = false
    for (*fn in overloads) {
        var receiver: AstXmlNode = xmlEmptyNode()
        var valueParamCount: Int = 0
        var hasRecv: Bool = false
        val params: List<AstXmlNode> = xmlChildren(fn, AstNodeKind.Param)
        if (xmlAttr(fn, AstNodeAttributeKind.HasReceiver) == "true" && !xmlIsEmpty(
                xmlChildPtr(
                    fn,
                    AstNodeKind.Receiver
                )
            )
        ) {
            receiver = xmlChild(fn, AstNodeKind.Receiver)
            valueParamCount = params.size()
            hasRecv = true
        } else if (params.size() > 0) {
            val first: AstXmlNode = params[0]
            if (xmlAttr(first, AstNodeAttributeKind.Name) == "this" && !xmlIsEmpty(
                    xmlChildPtr(
                        first,
                        AstNodeKind.Type
                    )
                )
            ) {
                receiver = xmlChild(first, AstNodeKind.Type)
                valueParamCount = params.size() - 1
                hasRecv = true
            }
        }
        if (hasRecv) {
            val typeParams: List<Str> = xmlTypeParamNames(fn)
            if (semaUnifyReceiver(receiver, actual, typeParams)) {
                compatible = true
                if (valueParamCount == argCount) {
                    return
                }
            }
        }
    }
    if (compatible) {
        this.diag(
            xmlLine(call), xmlColumn(call),
            `no overload of '@name' takes @argCount argument(s)`
        )
    }
}



// The `for` promotion (impl_specs/for.md, "iterPtr"): a `for (x in c)` over a *deep* element (a
// `Str`, or a data class holding one) becomes the pointer wrap `iterPtr` when the body only reads
// `x`. The parser chose the wrap before types were known - a machine has no pointer form - so the
// choice is made here: the receiver type is resolved, `iterPtr` must exist for it, the element
// must be worth it (`bpDeepElement`, from the borrow pass's analysis), and the body must prove
// read-only (`bpLoopReadOnly`, the parameter rule). The value form copies - and, for a `Str`,
// allocates - on every iteration; the pointer form hands out the element's place instead.
// `--no-borrow` turns it off with the rest of the rewrite.
fun Analyzer.promoteForLoops(stmts: *List<AstXmlNode>): Unit {
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        // Every place a statement list can hide: a block's body, and an `if`'s arms. Missing
        // `Then`/`Else` is why a `for` inside an `if` used to keep its container wrap.
        for (*child in stmt.Children) {
            if (child.name != AstNodeKind.Body && child.name != AstNodeKind.Then
                && child.name != AstNodeKind.Else
            ) {
                continue
            }
            val nested: List<AstXmlNode> = xmlChildren(*child, AstNodeKind.Stmt)
            if (nested.size() > 0) {
                this.promoteForLoops(*nested)
            }
        }
        // The span rewrite is the lowering, not the borrow optimization below: a `for` over
        // a container iterates the span's one iterator machine.
        this.spanForAt(stmts, i)
        if (!bpNoBorrow()) {
            this.promoteForAt(stmts, i)
        }
        i = i + 1
    }
}

// One statement: the machine declaration a `for` desugars to (`var _sm_forN = c.iter()`). Its
// loop follows in the same list, and the promotion is the wrap's name.
fun Analyzer.promoteForAt(stmts: *List<AstXmlNode>, index: Int): Unit {
    val machineDecl: *AstXmlNode = *stmts[index]
    if (xmlKind(machineDecl) != AstNodeCategory.StmtVarDecl) {
        return
    }
    val machineName: Str = xmlAttr(machineDecl, AstNodeAttributeKind.Name)
    if (!semaIsForTemplateName(*machineName)) {
        return
    }
    val init: *AstXmlNode = xmlChildPtr(machineDecl, AstNodeKind.Init)
    if (xmlKind(init) != AstNodeCategory.ExprCall) {
        return
    }
    val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember
        || xmlAttr(callee, AstNodeAttributeKind.Name) != "iter"
    ) {
        return
    }
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val receiverType: AstXmlNode = this.iteratedType(receiver)
    if (xmlIsEmpty(receiverType) || xmlKind(receiverType) == AstNodeCategory.TypeYield) {
        return
    }
    var ptrWrap: Str = "iterPtr"
    if (!this.hasWrap(ptrWrap, receiverType)) {
        return
    }
    if (!this.promoteElementDeep(receiverType)) {
        return
    }
    val loopIndex: Int = this.forLoopIndex(stmts, index)
    if (loopIndex < 0) {
        return
    }
    val loop: *AstXmlNode = *stmts[loopIndex]
    val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(loop, AstNodeKind.Body), AstNodeKind.Stmt)
    val elementName: Str = this.loopElementName(*body, machineName)
    if (elementName == "") {
        return
    }
    if (!bpLoopReadOnly(*body, *elementName)) {
        return
    }
    var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    for (*attr in callee.attributes) {
        if (attr.name == AstNodeAttributeKind.Name) {
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "iterPtr"))
        } else {
            var kept: AstNodeAttribute = attr
            attrs.append(kept)
        }
    }
    callee.attributes = attrs
}

// Only a `List`/`Array`/`Span` of a deep element is promoted: a user type's `iterPtr`, a receiver
// whose element cannot be named, and a non-deep element are all left as written.
fun Analyzer.promoteElementDeep(receiverType: *AstXmlNode): Bool {
    if (xmlKind(receiverType) != AstNodeCategory.TypeGeneric) {
        return false
    }
    val base: Str = xmlAttr(receiverType, AstNodeAttributeKind.Name)
    if (base != "List" && base != "Array" && base != "Span") {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(receiverType, AstNodeKind.TypeArg)
    if (args.size() != 1) {
        return false
    }
    return bpDeepElement(*args[0])
}

// The `while` the parser wrote for this machine: it follows the machine's declaration (an index
// declaration may sit between) and its condition is the machine's `advance()`.
fun Analyzer.forLoopIndex(stmts: *List<AstXmlNode>, index: Int): Int {
    var j: Int = index + 1
    var guard: Int = 0
    while (j < stmts.size() && guard < 4) {
        val stmt: *AstXmlNode = *stmts[j]
        if (xmlKind(stmt) == AstNodeCategory.StmtWhile) {
            val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
            if (xmlKind(cond) == AstNodeCategory.ExprCall && bpMachineStep(xmlChildPtr(cond, AstNodeKind.Callee))) {
                return j
            }
        }
        guard = guard + 1
        j = j + 1
    }
    return -1
}

// The value binding the template wrote first in the loop body: `val x = machine.current`.
fun Analyzer.loopElementName(body: *List<AstXmlNode>, machineName: Str): Str {
    for (*stmt in body) {
        if (xmlKind(stmt) != AstNodeCategory.StmtVarDecl) {
            continue
        }
        val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
        if (xmlKind(init) != AstNodeCategory.ExprMember
            || xmlAttr(init, AstNodeAttributeKind.Name) != "current"
        ) {
            continue
        }
        val recv: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Receiver)
        if (xmlKind(recv) == AstNodeCategory.ExprName
            && xmlAttr(recv, AstNodeAttributeKind.Name) == machineName
        ) {
            return xmlAttr(stmt, AstNodeAttributeKind.Name)
        }
    }
    return ""
}
