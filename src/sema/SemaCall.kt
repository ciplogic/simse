// SemaCall.kt
//
// `Analyzer`'s call checks: views, handles, arity and the extension-call form. Extension
// methods on `Analyzer` (Sema.kt).

package sema
import compiler

import parser
import common
import errors

// `<base>Task` is the factory the task lowering declares for a suspending function `base`
// (`Emitter.asyncFactory`): a name no declaration carries, because the emitter builds it.
fun Analyzer.isTaskFactoryName(name: *Str): Bool {
    if (!name.endsWith("Task")) {
        return false
    }
    val base: Str = name.substr(0, name.size() - 4)
    return this.declaresFunctionAnywhere(base)
}

// The overload parameter counts, deduplicated and joined: what the arity diagnostic's hint
// offers as "declared with 1, 3 parameter(s)". A generic call skips the overloads its type
// argument count rules out, matching the arity check above it.
fun semaParamCountsText(overloads: *List<AstXmlNode>, generic: Bool, typeArgCount: Int): Str {
    var counts: List<Str> = List<Str>()
    for (*overload in overloads) {
        if (generic && xmlCount(overload, AstNodeKind.TypeParam) != typeArgCount) {
            continue
        }
        val countText: Str = xmlCount(overload, AstNodeKind.Param).toString()
        if (!counts.contains(countText)) {
            counts.append(countText)
        }
    }
    return joinStrs(*counts, ", ")
}

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
        guard++
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
            if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true") {
                this.checkUnionConstruction(call, name, decl, argCount)
                return
            }
            val fieldCount: Int = semAllFields(*this.types, decl).size()
            if (fieldCount != argCount) {
                this.diag(
                    xmlLine(call), xmlColumn(call), `data class '@name' expects @fieldCount field(s) but got @argCount`
                )
            }
            return
        }
    }

    // A builtin type's construction (`List<T>(0)`, `Array<T>(count)`) is not a function
    // call: no declaration collects it, and the emitter lowers it itself. `print`/`println`
    // are the emitter's own spellings (`semaIsBuiltinCall`), `<base>Task` the task
    // lowering's factory (`isTaskFactoryName`).
    if (semaIsBuiltinType(name) || semaIsBuiltinCall(name) || this.isTaskFactoryName(name)) {
        return
    }
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        // No local (checked above), no construction, no function of the name: without this
        // the emitter spells the call into the C++ and the "not declared" surfaces there.
        this.diagUnknownFunction(name, xmlLine(call), xmlColumn(call))
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
                a++
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
    val counts: Str = semaParamCountsText(overloads, generic, typeArgCount)
    if (counts != "") {
        this.diag(
            xmlLine(call), xmlColumn(call),
            diagHint(`no overload of '@name' takes @argCount argument(s)`, `'@name' is declared with @counts parameter(s)`)
        )
        return
    }
    this.diag(
        xmlLine(call), xmlColumn(call),
        `no overload of '@name' takes @argCount argument(s)`
    )
}

// The declared return type of a plain call `f(args)`: the arity-matching overload's,
// with the type parameters bound - explicitly from the callee's type arguments when it
// names some (`optOf<Int>(...)`), otherwise shallowly from the arguments' own types
// (`listOf(xs)` binds `T` to the element). A type parameter nothing bound - or a
// trailing-pack match, whose element type the call site alone knows - answers empty,
// exactly what the checker saw before calls resolved.
fun Analyzer.semaPlainCallReturn(call: *AstXmlNode): AstXmlNode {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    val generic: Bool = xmlKind(callee) == AstNodeCategory.ExprGenericName
    if (xmlKind(callee) != AstNodeCategory.ExprName && !generic) {
        return xmlEmptyNode()
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (this.lookupValue(name).hasValue()) {
        return xmlEmptyNode() // shadowed by a local/param: not a function call
    }
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return xmlEmptyNode()
    }
    val argCount: Int = xmlCount(call, AstNodeKind.Arg)
    var typeArgCount: Int = 0
    if (generic) {
        typeArgCount = xmlCount(callee, AstNodeKind.TypeArg)
    }
    val args: List<AstXmlNode> = xmlChildren(call, AstNodeKind.Arg)
    for (*overload in overloads) {
        if (generic && xmlCount(overload, AstNodeKind.TypeParam) != typeArgCount) {
            continue
        }
        val paramCount: Int = xmlCount(overload, AstNodeKind.Param)
        if (paramCount != argCount) {
            // A trailing-pack match answers empty: the packed element type is the call
            // site's knowledge, not the declaration's.
            if (paramCount > 0) {
                val packParams: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
                val lastType: *AstXmlNode = xmlChildPtr(packParams[paramCount-1], AstNodeKind.Type)
                if (semIsPackTarget(lastType) && argCount >= paramCount - 1) {
                    return xmlEmptyNode()
                }
            }
            continue
        }
        val typeParams: List<Str> = xmlTypeParamNames(overload)
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (generic) {
            val typeArgs: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
            var t: Int = 0
            while (t < typeParams.size() && t < typeArgs.size()) {
                bindings.insert(typeParams[t], typeArgs[t])
                t++
            }
        } else if (typeParams.size() > 0) {
            // Infer from the arguments: a parameter whose type names a type parameter
            // binds it to the argument's type, when the argument's type is known.
            val declParams: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
            var a: Int = 0
            while (a < argCount && a < declParams.size()) {
                val paramType: *AstXmlNode = xmlChildPtr(declParams[a], AstNodeKind.Type)
                val argType: AstXmlNode = this.unionArgType(args[a])
                if (!xmlIsEmpty(argType)) {
                    semBindTypes(paramType, argType, typeParams, bindings)
                }
                a++
            }
        }
        val ret: AstXmlNode = semSubstitute(xmlChildPtr(overload, AstNodeKind.ReturnType), bindings, typeParams)
        if (xmlIsEmpty(ret)) {
            return xmlEmptyNode() // Unit: absence, like every unresolved type here
        }
        if (!semTypeParamsAllBound(ret, typeParams, bindings)) {
            return xmlEmptyNode()
        }
        return ret
    }
    return xmlEmptyNode()
}

// Whether every type parameter `ret` mentions is bound: a `List<T>` with a `T` nothing
// bound is not a type the caller can name.
fun semTypeParamsAllBound(ret: *AstXmlNode, typeParams: *List<Str>, bindings: *Dictionary<Str, AstXmlNode>): Bool {
    if (xmlIsEmpty(ret)) {
        return true
    }
    val kind: AstNodeCategory = xmlKind(ret)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        val name: Str = xmlAttr(ret, AstNodeAttributeKind.Name)
        if (xmlIsTypeParam(name, typeParams) && !bindings.has(name)) {
            return false
        }
    }
    for (*child in ret.Children) {
        if (!semTypeParamsAllBound(child, typeParams, bindings)) {
            return false
        }
    }
    return true
}

// A `union class` construction (`specs/declarations.md`): at most one value, chosen by its
// type, because only one arm can be live. Zero arguments construct `None`; one argument
// must match a field's type - the call is then routed through that field's generated
// `initByValue` arm (`var x = U(v)`). When several fields share the argument's type the
// earlier one is the arm (`Res2<Str>`'s convention), so no diagnostic: the later field is
// set by name (`set<Field>`).
fun Analyzer.checkUnionConstruction(
    call: *AstXmlNode, name: *Str, decl: *AstXmlNode, argCount: Int
): Unit {
    if (argCount == 0) {
        return
    }
    if (argCount > 1) {
        this.diag(
            xmlLine(call), xmlColumn(call),
            `union class '@name' takes at most one value`
        )
        return
    }
    val argType: AstXmlNode = this.unionArgType(xmlChildPtr(call, AstNodeKind.Arg))
    if (xmlIsEmpty(argType)) {
        this.diag(
            xmlLine(call), xmlColumn(call),
            `union class '@name': the argument's type is unknown`
        )
        return
    }
    // A generic class's fields mention its type parameters; the construction's explicit
    // arguments bind them, so `Res2<Int>(5)` matches `Value: T` as `Int`.
    val params: List<Str> = xmlTypeParamNames(decl)
    var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    val typeArgs: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
    if (params.size() == typeArgs.size()) {
        var t: Int = 0
        while (t < params.size()) {
            bindings.insert(params[t], typeArgs[t])
            t++
        }
    }
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    var count: Int = 0
    for (*field in fields) {
        var fieldType: AstXmlNode =
            this.unionResolveAlias(xmlChildPtr(field, AstNodeKind.Type))
        if (bindings.size() > 0) {
            fieldType = semSubstitute(fieldType, *bindings, *params)
        }
        if (semaSameType(fieldType, argType)) {
            count++
        }
    }
    if (count > 0) {
        // One match, or several of one type: either way the earlier matching field is the
        // arm the construction builds (the convention a generic `Res2<Str>` already had).
        return
    }
    val argText: Str = semaTypeText(argType)
    this.diag(
        xmlLine(call), xmlColumn(call),
        `union class '@name' has no field of type '@argText'`
    )
}

// The type of a construction argument, literals and enum members included: `exprType`
// resolves a binding or a field, but neither a literal nor `Enum.Member` has a declaration
// to read.
fun Analyzer.unionArgType(arg: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(arg)
    if (kind == AstNodeCategory.ExprIntLit) {
        if (semIsInt64Literal(xmlAttr(arg, AstNodeAttributeKind.Text))) {
            return semNamedType("Int64")
        }
        return semNamedType("Int")
    }
    if (kind == AstNodeCategory.ExprFloatLit) {
        return semNamedType("Float64")
    }
    if (kind == AstNodeCategory.ExprBoolLit) {
        return semNamedType("Bool")
    }
    if (kind == AstNodeCategory.ExprStrLit) {
        return semNamedType("Str")
    }
    if (kind == AstNodeCategory.ExprCall) {
        // A scalar conversion names its result: `Int64(x)` is an Int64 whatever `x`
        // was - the spelling a bound value arrives at a union construction by.
        val callee: *AstXmlNode = xmlChildPtr(arg, AstNodeKind.Callee)
        if (xmlKind(callee) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (name == "Int" || name == "Int32" || name == "Int8" || name == "Int16"
                || name == "Int64" || name == "Float32" || name == "Float64"
                || name == "Char" || name == "Bool") {
                if (name == "Int32") {
                    return semNamedType("Int")
                }
                return semNamedType(name)
            }
        }
    }
    if (kind == AstNodeCategory.ExprMember) {
        val recv: *AstXmlNode = xmlChildPtr(arg, AstNodeKind.Receiver)
        if (xmlKind(recv) == AstNodeCategory.ExprName) {
            val recvName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
            val decl: *AstXmlNode = this.types.getPtr(recvName)
            if (decl != null && xmlKind(decl) == AstNodeCategory.Enum) {
                return semNamedType(recvName)
            }
        }
    }
    return this.exprType(arg)
}

// A type node through its `typealias`es, one hop at a time (the shape `isViewType` follows):
// `typealias MyInt = Int` and `Int` are one field type, while two enums never are.
fun Analyzer.unionResolveAlias(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && guard < 16) {
        guard++
        if (xmlKind(current) != AstNodeCategory.TypeNamed) {
            return current
        }
        val decl: *AstXmlNode = this.types.getPtr(xmlAttr(current, AstNodeAttributeKind.Name))
        if (decl == null || xmlKind(decl) != AstNodeCategory.TypeAlias) {
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

// Whether two type nodes denote the same written type: kind, name (and integer literal, for
// `SmallVector<4, T>`) per node, children recursively. Roles and positions differ between a
// declaration's own type and an inferred one, so neither is compared.
fun semaSameType(a: *AstXmlNode, b: *AstXmlNode): Bool {
    if (xmlKind(a) != xmlKind(b)
        || xmlAttr(a, AstNodeAttributeKind.Name) != xmlAttr(b, AstNodeAttributeKind.Name)
        || xmlAttr(a, AstNodeAttributeKind.Text) != xmlAttr(b, AstNodeAttributeKind.Text)
    ) {
        return false
    }
    if (a.Children.count() != b.Children.count()) {
        return false
    }
    var i: Int = 0
    while (i < a.Children.count()) {
        if (!semaSameType(a.Children[i], b.Children[i])) {
            return false
        }
        i++
    }
    return true
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
    // `*x` is the address of a value and the read-through of a pointer (`specs/memory-model.md`),
    // so the operand's type decides which: through a handle it is the pointee, otherwise a
    // pointer to the value. The checker needs it to type a `*p` argument (`checkProtocolCall`).
    if (xmlKind(expr) == AstNodeCategory.ExprDeref) {
        val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
        val inner: AstXmlNode = this.exprType(operand)
        if (xmlIsEmpty(inner)) {
            return xmlEmptyNode()
        }
        if (semaIsHandleType(inner)) {
            return semPointeeOf(inner)
        }
        return semPointerOf(inner)
    }
    if (xmlKind(expr) == AstNodeCategory.ExprRef) {
        val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
        val inner: AstXmlNode = this.exprType(operand)
        if (xmlIsEmpty(inner)) {
            return xmlEmptyNode()
        }
        return semReferenceOf(inner)
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
            // A plain call answers the arity-matching overload's declared return type,
            // type parameters bound - what a union construction's arm match and a `for`
            // over a call's result both need (`Xml.descendants(name)` used to be a type
            // the checker could not see through).
            return this.semaPlainCallReturn(expr)
        }
        return xmlEmptyNode()
    }
    return xmlEmptyNode()
}

// The declared type of the field `member` on a base of type `base`, when both are known.
// The field may be inherited: the walk follows the base chain, so a derived value reads a
// base's field like its own.
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
    return xmlChild(semFindField(*this.types, *decl, member), AstNodeKind.Type)
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
// parameters substituted. A wrap (`iterValues`/`iter`) answers with the machine it builds:
// the container's element through its span, a machine itself (identity), or the declared wrap.
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
    if (name == "iterValues" || name == "iter") {
        if (xmlKind(receiverType) == AstNodeCategory.TypeYield) {
            return receiverType
        }
        val element: AstXmlNode = this.semaSpanElement(receiverType)
        if (!xmlIsEmpty(element)) {
            // `iter` hands out the element's *place*, so the machine yields `..*T`.
            if (name == "iter") {
                return semMachineOfElement(semPointerOf(element))
            }
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

// A `&pointee` node for the checker's own bindings (`&x` boxes a copy).
fun semReferenceOf(pointee: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypeReference, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    xmlAddChild(node, semReRole(pointee, AstNodeKind.Inner))
    return node
}

// A `*pointee` node for the checker's own bindings (`iter` hands out places).
fun semPointerOf(pointee: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypePointer, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    xmlAddChild(node, semReRole(pointee, AstNodeKind.Inner))
    return node
}

// `for` is lowered into the declaration of the machine it iterates (`_sm_for<n>`,
// impl_specs/for.md), whose initializer is an invisible `iterValues()`/`iter()` wrap:
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
        // A machine is the identity for `iterValues`: it hands out values, not places, so
        // it has no pointer form.
        if (wrap == "iterValues") {
            return
        }
        this.diag(
            xmlLine(stmt), xmlColumn(stmt),
            "a `for (*x in m)` needs an `iter`, and a machine yields values "
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

// Whether a wrap (`iterValues`, `iter`) takes this receiver, by receiver *name* only:
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
        guard++
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
// `spanOf(c)` (or `spanOfArray(c)`/`spanOfStr(c)`), so the span's `iterValues`/`iter` is the one
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
    if (wrap != "iterValues" && wrap != "iter") {
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
        // A generic callee's declared return names its own type parameters (`spanOf(xs)`
        // is `Span<T>` until the call's arguments bind them): bind from the argument
        // types and substitute, the way call inference answers a call's type. Without it
        // the machine a `for` builds over the span carries the raw `T`, and the element -
        // with it the promotion's deep-element test - reads as the parameter.
        val params: List<Str> = xmlTypeParamNames(*overload)
        if (params.size() == 0 || xmlIsEmpty(declared)) {
            continue
        }
        var argTypes: List<AstXmlNode> = List<AstXmlNode>()
        for (*arg in xmlChildren(expr, AstNodeKind.Arg)) {
            argTypes.append(this.exprType(arg))
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        semBindCallArgs(*overload, xmlEmptyNode(), *argTypes, *params, *bindings)
        if (bindings.size() > 0) {
            val substituted: AstXmlNode = semSubstitute(declared, *bindings, *params)
            if (!xmlIsEmpty(substituted)) {
                return substituted
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
    var valueCounts: List<Str> = List<Str>()
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
                val valueText: Str = valueParamCount.toString()
                if (!valueCounts.contains(valueText)) {
                    valueCounts.append(valueText)
                }
                if (valueParamCount == argCount) {
                    return
                }
            }
        }
    }
    if (compatible) {
        val counts: Str = joinStrs(*valueCounts, ", ")
        if (counts != "") {
            this.diag(
                xmlLine(call), xmlColumn(call),
                diagHint(
                    `no overload of '@name' takes @argCount argument(s)`,
                    `'@name' is declared with @counts value parameter(s) on this receiver`
                )
            )
            return
        }
        this.diag(
            xmlLine(call), xmlColumn(call),
            `no overload of '@name' takes @argCount argument(s)`
        )
    }
}



// The `for` promotion (impl_specs/for.md, "iter"): a `for (x in c)` over a *deep* element (a
// `Str`, or a data class holding one) becomes the pointer wrap `iter` when the body only reads
// `x`. The parser chose the wrap before types were known - a machine has no pointer form - so the
// choice is made here: the receiver type is resolved, `iter` must exist for it, the element
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
            this.promotePlaceAt(stmts, i)
            this.hoistSpanAt(stmts, i)
        }
        i++
    }
}

// One statement: the machine declaration a `for` desugars to (`var _sm_forN = c.iterValues()`). Its
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
        || xmlAttr(callee, AstNodeAttributeKind.Name) != "iterValues"
    ) {
        return
    }
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val receiverType: AstXmlNode = this.iteratedType(receiver)
    if (xmlIsEmpty(receiverType) || xmlKind(receiverType) == AstNodeCategory.TypeYield) {
        return
    }
    var ptrWrap: Str = "iter"
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
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "iter"))
        } else {
            var kept: AstNodeAttribute = attr
            attrs.append(kept)
        }
    }
    callee.attributes = attrs
}

// Only a `List`/`Array`/`Span` of a deep element is promoted: a user type's `iter`, a receiver
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
        guard++
        j++
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

// The place promotion (impl_specs/for.md, "Auto-promotion", the binding form): a
// `val x = <place>` whose initializer is a member/index chain rooted at a plain name - the
// `val it = r.items[i]` a `while` loop writes, the shape the `for` promotion already
// borrows - becomes the element's *place* when the element is deep and the rest of the
// block only reads it. The value form deep-copies (and, when a `Str` is anywhere in the
// element, allocates) per binding; the place form is one address the block's reads go
// through. `--no-borrow` turns it off with the rest of the rewrite.
fun Analyzer.promotePlaceAt(stmts: *List<AstXmlNode>, index: Int): Unit {
    val decl: *AstXmlNode = *stmts[index]
    if (xmlKind(decl) != AstNodeCategory.StmtVarDecl) {
        return
    }
    if (xmlAttr(decl, AstNodeAttributeKind.IsVar) == "true") {
        return
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (name == "" || name.startsWith("_sm_")) {
        return
    }
    val init: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Init)
    if (xmlIsEmpty(init)) {
        return
    }
    val initKind: AstNodeCategory = xmlKind(init)
    if (initKind != AstNodeCategory.ExprIndex && initKind != AstNodeCategory.ExprMember) {
        return
    }
    val root: Str = this.placeRootName(init)
    if (root == "") {
        return
    }
    val elementType: AstXmlNode = this.iteratedType(init)
    if (xmlIsEmpty(elementType) || !bpDeepElement(elementType)) {
        return
    }
    if (index + 1 >= stmts.size()) {
        return
    }
    var rest: List<AstXmlNode> = List<AstXmlNode>()
    var j: Int = index + 1
    while (j < stmts.size()) {
        rest.append(*stmts[j])
        j++
    }
    if (!bpPlaceReadOnly(*rest, *name, *root)) {
        return
    }
    // The declaration's initializer becomes `*(place)` - `*x` is the address of a value,
    // so the binding is the element's place - and the declared type wraps the element in
    // a pointer. The Init child is replaced whole (the node itself is shared with the
    // facts the proof read).
    var place: AstXmlNode = *init
    place.name = AstNodeKind.Operand
    var wrap: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprDeref, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    xmlAddChild(wrap, place)
    wrap.name = AstNodeKind.Init
    var replaced: Bool = false
    var at: Int = 0
    while (at < decl.Children.count()) {
        if (decl.Children[at].name == AstNodeKind.Init) {
            decl.Children[at] = wrap
            replaced = true
            break
        }
        at++
    }
    if (!replaced) {
        xmlAddChild(decl, wrap)
    }

    val typeChild: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Type)
    var pointee: AstXmlNode = elementType
    if (!xmlIsEmpty(typeChild)) {
        pointee = *typeChild
    }
    var pointer: AstXmlNode = semPointerOf(pointee)
    pointer.name = AstNodeKind.Type
    if (xmlIsEmpty(typeChild)) {
        xmlAddChild(decl, pointer)
    } else {
        var swapped: Bool = false
        var atType: Int = 0
        while (atType < decl.Children.count()) {
            if (decl.Children[atType].name == AstNodeKind.Type) {
                decl.Children[atType] = pointer
                swapped = true
                break
            }
            atType++
        }
        if (!swapped) {
            xmlAddChild(decl, pointer)
        }
    }
}

// The span hoist (impl_specs/typed-il.md, the buffer form): a loop whose body indexes a
// container - c[i] reads and writes - and keeps it storage-stable gets the container
// buffer bound once before the loop: var _sm_span<n>: Span<T> = spanOf(c), and every
// c[i] in the condition and body indexes the span instead - one pointer load where every
// access used to re-derive the buffer through the container inline-or-heap branch
// (raw(), src/rtl/containers.hpp). Element writes are stable, which is what a scan
// kernel dst[i] = v needs; a mutates-marked callee on another named receiver is trusted.
// A yielding body is the machine pass own territory, and `this` is the receiver, not a
// local container - neither hoists. --no-borrow turns it off with the rest.
var semaSpanHoistId: Int = 0

fun Analyzer.hoistSpanAt(stmts: *List<AstXmlNode>, index: Int): Unit {
    val loop: *AstXmlNode = *stmts[index]
    if (xmlKind(loop) != AstNodeCategory.StmtWhile) {
        return
    }
    val cond: *AstXmlNode = xmlChildPtr(loop, AstNodeKind.Cond)
    val bodyNode: *AstXmlNode = xmlChildPtr(loop, AstNodeKind.Body)
    if (xmlIsEmpty(bodyNode)) {
        return
    }
    val body: List<AstXmlNode> = xmlChildren(bodyNode, AstNodeKind.Stmt)
    if (body.size() == 0) {
        return
    }
    if (semaBodyYields(bodyNode)) {
        return
    }
    var roots: List<Str> = List<Str>()
    semaSpanCollectIndexRoots(*cond, *roots)
    for (*stmt in body) {
        semaSpanCollectIndexRoots(stmt, *roots)
    }
    for (*root in roots) {
        if (root.startsWith("_sm_") || root == "this" || bpStaticNames.has(root)) {
            continue
        }
        var rootExpr: AstXmlNode = AstXmlNode(
            AstNodeKind.Expr, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>()
        )
        rootExpr.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, root))
        val rootType: AstXmlNode = this.exprType(rootExpr)
        if (xmlIsEmpty(rootType)) {
            continue
        }
        val conversion: Str = this.spanConversion(rootType)
        if (conversion == "") {
            continue
        }
        var stable: Bool = bpSpanStableNode(*cond, root)
        var at: Int = 0
        while (stable && at < body.size()) {
            stable = bpSpanStableNode(body[at], root)
            at++
        }
        if (!stable) {
            continue
        }
        // The rewrite is declaration-free: every c[i] becomes spanOf(c).ptr[i] in place,
        // correct by construction (no injected declaration to place or reorder), typed by
        // the existing rules (the call is Span<T>, the member *T, the index T), and the
        // C++ optimizer hoists the loop-invariant spanOf out of the loop itself - the
        // buffer load it exists to remove is gone either way.
        this.semaSpanRewrite(*cond, root, conversion)
        for (*stmt in body) {
            this.semaSpanRewrite(stmt, root, conversion)
        }
    }
}

fun semaBodyYields(node: *AstXmlNode): Bool {
    if (xmlKind(node) == AstNodeCategory.StmtYield) {
        return true
    }
    for (*child in node.Children) {
        if (semaBodyYields(child)) {
            return true
        }
    }
    return false
}

// The names indexed directly in `node` (c[i], any depth): the hoist candidates.
fun semaSpanCollectIndexRoots(node: AstXmlNode, roots: *List<Str>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprIndex) {
        val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
        if (xmlKind(receiver) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(receiver, AstNodeAttributeKind.Name)
            if (name != "" && !roots.contains(name)) {
                roots.append(name)
            }
        }
    }
    for (*child in node.Children) {
        semaSpanCollectIndexRoots(child, roots)
    }
}



// One container accesses rewritten in place: every c[i] under `node` keeps its own index
// and only the receiver becomes the span name; the receiver of a `set` call follows too
// (c[i] = v on a Str is c.set(i, v), the parser operator rewrite).
fun Analyzer.semaSpanRewrite(node: *AstXmlNode, root: Str, conversion: Str): Unit {
    var buffer: AstXmlNode = semaSpanBuffer(root, conversion, xmlLine(node), xmlColumn(node))
    if (xmlKind(node) == AstNodeCategory.ExprIndex) {
        var at: Int = 0
        while (at < node.Children.count()) {
            val receiver: AstXmlNode = node.Children[at]
            if (receiver.name == AstNodeKind.Receiver
                && xmlKind(receiver) == AstNodeCategory.ExprName
                && xmlAttr(receiver, AstNodeAttributeKind.Name) == root
            ) {
                // The buffer takes the seat *and the role*: a node without `Receiver` is
                // invisible to `xmlChildPtr`, and the extractor would lower an index with
                // no receiver at all (the typing edge the v3 stash stalled on).
                buffer.name = AstNodeKind.Receiver
                node.Children[at] = buffer
                break
            }
            at++
        }
    }
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        if (xmlKind(callee) == AstNodeCategory.ExprMember
            && xmlAttr(callee, AstNodeAttributeKind.Name) == "set"
        ) {
            val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (xmlKind(receiver) == AstNodeCategory.ExprName
                && xmlAttr(receiver, AstNodeAttributeKind.Name) == root
            ) {
                var second: AstXmlNode = semaSpanBuffer(root, conversion, xmlLine(node), xmlColumn(node))
                second.name = AstNodeKind.Receiver
                var swapped: Bool = false
                var atC: Int = 0
                while (atC < callee.Children.count()) {
                    if (callee.Children[atC].name == AstNodeKind.Receiver) {
                        callee.Children[atC] = second
                        swapped = true
                        break
                    }
                    atC++
                }
                if (!swapped) {
                    xmlAddChild(callee, second)
                }
            }
        }
    }
    for (*child in node.Children) {
        this.semaSpanRewrite(child, root, conversion)
    }
}

// `spanOf(root)` - the buffer expression every index in the loop reads through;
// the emitter spells the span index through its pointer itself (CgCall, IlEmit).
// A fresh node per site (the tree shares nothing between uses).
fun semaSpanBuffer(root: Str, conversion: Str, line: Int, column: Int): AstXmlNode {
    var callAttrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    callAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, conversion))
    callAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()))
    callAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Column, column.toString()))
    var callee: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprName, callAttrs, Array<AstXmlNode>()
    )
    callee.name = AstNodeKind.Callee
    var rootAttrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    rootAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, root))
    rootAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()))
    rootAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Column, column.toString()))
    var rootExpr: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprName, rootAttrs, Array<AstXmlNode>()
    )
    rootExpr.name = AstNodeKind.Arg
    var call: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    call.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()))
    call.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Column, column.toString()))
    xmlAddChild(call, callee)
    xmlAddChild(call, rootExpr)
    return call
}

// The plain name a member/index place chain is rooted at, or "" when the expression is
// not a place: every link must be a field read or an index, and the root a name.
fun Analyzer.placeRootName(place: *AstXmlNode): Str {
    var walk: *AstXmlNode = place
    var guard: Int = 0
    while (guard < 32) {
        guard++
        val kind: AstNodeCategory = xmlKind(walk)
        if (kind == AstNodeCategory.ExprName) {
            return xmlAttr(walk, AstNodeAttributeKind.Name)
        }
        if (kind == AstNodeCategory.ExprMember || kind == AstNodeCategory.ExprIndex) {
            walk = xmlChildPtr(walk, AstNodeKind.Receiver)
            if (xmlIsEmpty(walk)) {
                return ""
            }
            continue
        }
        return ""
    }
    return ""
}
