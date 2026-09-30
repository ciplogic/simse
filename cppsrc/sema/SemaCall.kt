// SemaCall.kt
//
// `Analyzer`'s call checks: views, handles, arity and the extension-call form. Extension
// methods on `Analyzer` (Sema.kt).

package sema

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
    this.diag(
        xmlLine(arg), xmlColumn(arg), fmtStr(
            "types are not compatible: '|' takes '|' and the argument is '|'",
            callee, semaTypeText(param), semaTypeText(actual)
        )
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
                    xmlLine(call), xmlColumn(call), fmtStr(
                        "data class '|' expects | field(s) but got |",
                        name, fieldCount.toString(), argCount.toString()
                    )
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
        fmtStr("no overload of '|' takes | argument(s)", name, argCount.toString())
    )
}

// The receiver's type when the checker tracks it (a local/parameter or a generic
// construction); empty when unknown.
fun Analyzer.exprType(expr: *AstXmlNode): AstXmlNode {
    if (xmlKind(expr) == AstNodeCategory.ExprName) {
        val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(expr, AstNodeAttributeKind.Name))
        if (binding.hasValue()) {
            return binding.value().type
        }
        return xmlEmptyNode()
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
    }
    return xmlEmptyNode()
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

// The receiver's outer type, ignoring handles and type arguments.
fun Analyzer.semaReceiverNameMatches(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: *AstXmlNode = actual
    while (true) {
        val kind: AstNodeCategory = xmlKind(actualPtr)
        if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
            break
        }
        val inner: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.Inner)
        if (xmlIsEmpty(inner)) {
            break
        }
        actualPtr = inner
    }
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind == AstNodeCategory.TypeNamed) {
        return xmlKind(actualPtr) == AstNodeCategory.TypeNamed
                && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(pattern, AstNodeAttributeKind.Name)
    }
    if (patternKind == AstNodeCategory.TypeGeneric) {
        val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
        if (xmlIsTypeParam(patternName, typeParams)) {
            return true
        }
        return xmlKind(actualPtr) == AstNodeCategory.TypeGeneric
                && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == patternName
    }
    return false
}

// The type a `for` iterates, for the shapes the checker can name: a tracked binding,
// a type construction, or a declared function's return type. Unknown stays silent.
fun Analyzer.iteratedType(expr: *AstXmlNode): AstXmlNode {
    if (xmlKind(expr) == AstNodeCategory.ExprName) {
        return this.exprType(expr)
    }
    if (xmlKind(expr) != AstNodeCategory.ExprCall) {
        return xmlEmptyNode()
    }
    val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
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
            fmtStr("no overload of '|' takes | argument(s)", name, argCount.toString())
        )
    }
}


