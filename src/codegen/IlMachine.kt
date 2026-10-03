// IlMachine.kt
//
// The closure and machine classes and the task methods (`impl_specs/yield.md`,
// `impl_specs/async.md`). `Emitter` extension functions.

package codegen

import compiler

import sema
import common
import io
import linear
import optimizations
import profiling

// The closures a body constructs, into their own section: first every data class (so a
// lambda's body may construct a nested one), then every free invoke method. The class text
// is emitted once per symbol; a second half marks the methods with a `_invoke` key.
fun Emitter.emitClosureClasses(unit: *IlUnit): IlText {
    if (unit.closures.size() == 0) {
        return IlText(true, "", "")
    }
    val savedClosures: Dictionary<Str, Str> = this.closureTypes
    this.closureTypes = this.ilClosureTypeTable(unit)
    var classesText = Str()
    var i: Int = 0
    while (i < unit.closures.size()) {
        val closure: *IlClosure = *unit.closures[i]
        i = i + 1
        if (this.emittedClosures.has(closure.symbol)) {
            continue
        }
        val classText: IlText = this.emitClosureStruct(closure)
        if (!classText.ok) {
            this.closureFail(closure, classText.reason)
            this.closureTypes = savedClosures
            return classText
        }
        classesText.appendStr(classText.text)
        this.emittedClosures.insert(closure.symbol, true)
    }
    var methodsText = Str()
    i = 0
    while (i < unit.closures.size()) {
        val closure: *IlClosure = *unit.closures[i]
        i = i + 1
        val methodKey: Str = closure.symbol + "_invoke"
        if (this.emittedClosures.has(methodKey)) {
            continue
        }
        val methodText: IlText = this.emitClosureInvoke(unit, closure)
        if (!methodText.ok) {
            this.closureFail(closure, methodText.reason)
            this.closureTypes = savedClosures
            return methodText
        }
        methodsText.appendStr(methodText.text)
        this.emittedClosures.insert(methodKey, true)
    }
    this.closureTypes = savedClosures
    var text = Str()
    text.appendStr(classesText)
    text.appendStr(methodsText)
    this.sections.appendTo("closures", text)
    return IlText(true, text, "")
}

// A closure failure is the *lambda's*, not the enclosing function's: position it at the
// lambda expression the author wrote.
fun Emitter.closureFail(closure: *IlClosure, message: *Str): Unit {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Line, ilIntText(closure.line)))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Column, ilIntText(closure.column)))
    this.fail(node, message)
}

// A yielding function, emitted as the state machine it was lowered to
// (impl_specs/yield.md). It builds a machine on the stack and returns it by value, so a
// local iterator is a local struct; `&evens(n)` boxes a copy.
fun Emitter.emitYieldable(
    fn: *
    CgFn,
    decl: *
    AstXmlNode,
    className: *Str,
    classType: *Str,
    prototypeOnly: Bool,
    selfK: NameKind,
    selfTypePtr: *
    AstXmlNode,
    facts: *
    SemFacts
): Unit {
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    val elementType: *AstXmlNode = xmlChildPtr(returnNode, AstNodeKind.Inner)
    if (xmlIsEmpty(elementType)) {
        this.fail(decl, "unsupported: '..' without an element type")
        return
    }

    // A machine is emitted once: the prototype pass writes the class and the factory's
    // declaration, the definition pass only fills the factory in.
    if (!this.emittedYieldables.has(className)) {
        this.emittedYieldables.insert(className, true)
        // The linear body first: `yield` is a *lowering* and trades on the control flow
        // being labels and gotos (impl_specs/yield.md).
        var lowered: List<AstXmlNode> =
            linLowerForEmission(xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt))
        val semantics: SemBody = SemBody(
            decl, fn.templateParams, selfTypePtr, xmlEmptyNode(),
            List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
        )
        // This map is the machine's methods' frame too: the lowering rewrites the
        // statements in place, so the names the pass proved are the names they carry.
        var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        lowered = semInferTypes(lowered, facts, semantics, inferred)
        // The machine's method storage is the machine's fields (`this->`). These names
        // are reserved: a local of any of them would alias the machine's own.
        var machineReserved: List<Str> = listOf<Str>("current", "branch", "_sm_self")
        val finalBody: List<AstXmlNode> = linFinishForEmission(lowered, machineReserved)
        val machine: Yielded = linLowerYield(decl, elementType, finalBody, "advance")
        if (!machine.error.isEmpty()) {
            this.fail(decl, machine.error)
            return
        }
        this.emitMachine(fn, decl, className, elementType, machine, facts, inferred)
        if (this.failed) {
            return
        }
    }

    val qualifyText: Str = this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
    val factory: Str = `@classType @qualifyText`
    val tmpl: Str = this.templateClause(this.fnTemplateParams(fn))
    val factoryParams: List<Str> = this.parameterList(fn, decl)
    if (prototypeOnly) {
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        val cgJoinText: Str = cgJoin(factoryParams, ", ")
        this.line(0, `@factory(@cgJoinText);`)
        this.emitDeducedCallableOverload(fn, decl, *classType, qualifyText, true)
        return
    }
    this.sourceComment(decl)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    val cgJoinText2: Str = cgJoin(factoryParams, ", ")
    this.line(0, `@factory(@cgJoinText2) {`)
    this.line(1, classType + " machine{};")
    val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in declared) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        // The field carries a mangled name when the parameter's own would collide with a
        // machine member (`linear::yldFieldName`), the same rule the body rewrite uses.
        val yldFieldNameText: Str = yldFieldName(name)
        this.line(1, `machine.@yldFieldNameText = @name;`)
    }
    if (!xmlIsEmpty(xmlChildPtr(decl, AstNodeKind.Receiver))) {
        // An extension function's receiver crosses a yield like any other value, so it is
        // a field the factory fills from its own `self` (`yldReceiverField`).
        val yldReceiverFieldText: Str = yldReceiverField()
        this.line(1, `machine.@yldReceiverFieldText = self;`)
    }
    this.line(1, "machine.branch = 0;")
    this.line(1, "return machine;")
    this.line(0, "}")
    this.emitDeducedCallableOverload(fn, decl, *classType, qualifyText, false)
}

// The factory's parameters: the receiver first when there is one (an extension function's
// receiver is an ordinary `T* self`), then the declaration's own.
fun Emitter.parameterList(fn: *CgFn, decl: *AstXmlNode): List<Str> {
    var params: List<Str> = List<Str>()
    if (!xmlIsEmpty(fn.receiver)) {
        params.append(this.receiverParam(fn.receiver))
        if (this.failed) {
            return params
        }
    }
    val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in declared) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            val xmlAttrText: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            this.fail(
                param,
                `unsupported: parameter '@xmlAttrText' without a type`
            )
            return params
        }
        val typeText: Str = this.type(paramType)
        val xmlAttrText2: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        params.append(`@typeText @xmlAttrText2`)
        if (this.failed) {
            return params
        }
    }
    return params
}

// The machine is a class the type pass never saw, so its fields are registered as a data
// class here for the spelling helpers. A receiver field is a *pointer* (`T* self`), so
// `this._sm_self.size()` reaches through it; the frame, not this table, decides names.
fun Emitter.registerMachineType(className: *Str, machine: *Yielded): Unit {
    var declNode: AstXmlNode =
        AstXmlNode(AstNodeKind.DataClass, AstNodeCategory.DataClass, List<AstNodeAttribute>(), Array<AstXmlNode>())
    declNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
    for (*field in machine.fields) {
        var fieldNode: AstXmlNode =
            AstXmlNode(AstNodeKind.Field, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
        fieldNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, field.name))
        xmlAddChild(fieldNode, this.renameRole(field.typeNode, AstNodeKind.Type))
        xmlAddChild(declNode, this.renameRole(fieldNode, AstNodeKind.Field))
    }
    this.types.insert(className, declNode)
    this.machineDecl = declNode
}

// The machine: its fields, then one method per way of advancing it.
fun Emitter.emitMachine(
    fn: *CgFn, decl: *AstXmlNode, className: *Str, elementType: *AstXmlNode,
    machine: *Yielded, facts: *SemFacts, inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    this.sourceComment(decl)
    this.registerMachineType(className, machine)
    // A machine's methods are the lowering's output (linear/Yield.kt), so they have not been
    // optimized: a machine method is a body like any other (`Optimize.kt`), and its own
    // control flow is what the passes are careful never to disturb.
    for (*method in machine.methods) {
        linOptimizeBody(*method.body)
    }
    // A generic function's machine is a class template: its fields are typed with the
    // function's type parameters, so they are declared where used (impl_specs/yield.md).
    // A machine receiver adds its own class parameter to both (`fnTemplateParams`).
    val tmplParams: List<Str> = this.fnTemplateParams(fn)
    val tmpl: Str = this.templateClause(tmplParams)
    // The class carries the values that cross a yield and nothing else: the method that
    // advances it is a free *extension* function below (`advance(M* self)`), the shape a
    // lambda's call has (`<sym>_invoke`), so the class is an ordinary data class.
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, `struct @className {`)
    for (*field in machine.fields) {
        if (xmlIsEmpty(field.typeNode)) {
            val fieldNameText: Str = field.name
            this.fail(decl, `yield: the field '@fieldNameText' has no type`)
            return
        }
        val typeText2: Str = this.type(field.typeNode)
        val fieldNameText2: Str = field.name
        this.line(1, `@typeText2 @fieldNameText2{};`)
        if (this.failed) {
            return
        }
    }
    this.line(0, "};")
    this.line(0, "")
    // A generic machine's class is a template, so its use as a *type* carries the
    // parameters: `List_iter_yieldable<T>* self`.
    var classTypeText: Str = className
    if (tmplParams.size() > 0) {
        val cgJoinText: Str = cgJoin(tmplParams, ", ")
        classTypeText = `@className<@cgJoinText>`
    }
    for (*method in machine.methods) {
        // The receiver rides first, the way every receiver function's `self` does.
        var params: List<Str> = List<Str>()
        params.append(`@classTypeText* self`)
        for (*param in method.params) {
        val typeText3: Str = this.type(param.typeNode)
        val paramNameText: Str = param.name
        params.append(`@typeText3 @paramNameText`)
        if (this.failed) {
            return
        }
    }
        // `advance()` answers whether there was a value; `value()` hands out the element.
        var result: Str = this.type(elementType)
        if (method.name == "advance") {
            result = "Bool"
        }
        val methodNameText: Str = method.name
        val cgJoinText3: Str = cgJoin(params, ", ")
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, `@result @methodNameText(@cgJoinText3) {`)
        // An extension function: the receiver is a `self` parameter (`M*`), so the machine's
        // fields are reached through `self->`, exactly as any receiver function's are.
        val savedClosure: Bool = this.inClosureMethod
        val savedSelfKind: NameKind = this.selfKind
        val savedSelfType: AstXmlNode = this.selfType
        val savedReturn: AstXmlNode = this.curReturnType
        val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
        val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
        this.inClosureMethod = false
        this.selfKind = NameKind.Value
        var classType: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
        classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
        this.selfType = classType
        this.curReturnType = elementType
        // The method's own parameters tell a pointer receiver from a value one.
        for (*param in method.params) {
        if (!xmlIsEmpty(param.typeNode)) {
            this.nameKinds.insert(param.name, this.kindOf(param.typeNode))
            this.localTypes.insert(param.name, param.typeNode)
        }
    }
        // The body goes through the same two paths as any other, with the machine's frame:
        // its fields are read and written through `self`, as a lambda body reads captures.
        this.emitBodyAt(
            this.ilMachineMethod(className, method, this.machineDecl, facts, inferred), method.body,
            fn.file, 1, false
        )
        this.inClosureMethod = savedClosure
        this.selfKind = savedSelfKind
        this.selfType = savedSelfType
        this.curReturnType = savedReturn
        this.nameKinds = savedKinds
        this.localTypes = savedTypes
        if (this.failed) {
            return
        }
        this.line(0, "}")
        this.line(0, "")
    }
}

// A machine method as the extractor's body context: no declaration, the method's
// parameters, and - like a lambda - a class reached through `this`. Its fields are not
// captures (the lowering already spelled every field access as `this.x`), so a parameter
// sharing a field's name resolves to the parameter; the class travels as `selfDecl`.
fun Emitter.ilMachineMethod(
    className: *Str, method: *YldMethod, selfDecl: *AstXmlNode, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    val methodNameText2: Str = method.name
    var info: IlFunction = IlFunction(
        xmlEmptyNode(), xmlEmptyNode(),
        `@className::@methodNameText2`, Dictionary<Str, Str>(),
        selfDecl, List<Str>(), List<AstXmlNode>(), className,
        Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, List<Str>(), inferred
    )
    var i: Int = 0
    for (*param in method.params) {
        info.paramNames.append(param.name)
        info.paramTypes.append(param.typeNode)
    }
    i = 0
    for (*entry in this.statics) {
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(xmlAttr(entry.decl, AstNodeAttributeKind.Name), ilTypeText(typeNode))
        }
    }
    return info
}

// A task method as the extractor's body context: the `run()` of a lowered `suspend` body. Like a
// machine's method there is no declaration and the class travels as `selfDecl`, so `this.<field>`
// resolves to the task's own storage; the protocol is free functions (`tasks*`), so no header field
// or member is named by the body.
//
// `task` is a *pointer* for the same reason `ilMachineMethod`'s `method` is: `IlFunction.inferredTypes`
// is a pointer into `task.inferred`, and a by-value parameter would make that address dangle the
// moment this returns (a stack-use-after-scope the extractor then reads - `stress/suspend` pinned
// it).
fun Emitter.ilTaskMethod(
    className: *Str, task: *TskTask, selfDecl: *AstXmlNode, facts: *SemFacts
): IlFunction {
    var info: IlFunction = IlFunction(
        xmlEmptyNode(), xmlEmptyNode(),
        `@className::run`, Dictionary<Str, Str>(),
        selfDecl, List<Str>(), List<AstXmlNode>(), className,
        Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, List<Str>(), task.inferred
    )
    for (*entry in this.statics) {
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(xmlAttr(entry.decl, AstNodeAttributeKind.Name), ilTypeText(typeNode))
        }
    }
    return info
}

// A failure with a position when the frame has a declaration, position-less otherwise (a
// lambda's body, a machine's method).
fun Emitter.failFromInfo(info: *IlFunction, message: *Str): Unit {
    var node: AstXmlNode = xmlEmptyNode()
    if (!xmlIsEmpty(info.decl)) {
        node = copy(info.decl)
    }
    this.fail(node, message)
}

// The IL's own post-pass (src/linear/MergeConcat.kt): a `+` chain over `Str` and an
// `fmtStr`/`fmtStrWith` whose format is a literal become one `Concat` instruction, which the emitter
// expands into one length sum, one `resize` and one slot write per part
// (`ilConcatStatements`) - one buffer, allocated once, each part written once. The
// primitives' C++ is a *generated* section, so its reach is recorded as well as spelled: the
// same rule the `main` argument list's `append` follows (src/rtl/rtl.kt).
fun Emitter.ilFuseConcatUnit(unit: *IlUnit): Unit {
    var fused: Bool = ilFuseConcat(*unit.body)
    for (*lambda in unit.lambdas) {
        if (ilFuseConcat(lambda)) {
            fused = true
        }
    }
    if (fused) {
        this.referencedNames.insert(ilConcatSymbol(), true)
    }
}

// A body is emitted from its instruction list - the IL is the *only* codegen
// (impl_specs/linear-il.md). One it cannot spell is an extractor bug, so it fails.
fun Emitter.emitBodyAt(info: *IlFunction, body: *List<AstXmlNode>, file: *Str, level: Int, measure: Bool): Unit {
    val unit: IlUnit = ilExtractUnit(info, body, file)
    this.ilFuseConcatUnit(unit)
    ilPromoteRefsUnit(unit, info.facts)
    ilReuseUnit(unit, *this.pureCallees)
    val emitted: IlText = this.emitIlBodyText(unit, level)
    if (!emitted.ok) {
        val infoSymbolText: Str = info.symbol
        val emittedReasonText: Str = emitted.reason
        // A shape the extractor itself reported is the author's, not an internal one.
        var message: Str = `internal: the body of '@infoSymbolText' is not expressible in the IL (@emittedReasonText)`
        if (emittedReasonText.startsWith("unsupported: ")) {
            message = emittedReasonText
        }
        this.failFromInfo(info, message)
        return
    }
    // A lambda is a data class plus a free invoke method, which the text above *constructs*
    // but does not define: both go into the `closures` section, all of them before the
    // bodies, so no body needs a local class with an `auto operator()`.
    val classes: IlText = this.emitClosureClasses(unit)
    if (!classes.ok) {
        if (!this.failed) {
            val classesReasonText: Str = classes.reason
            this.failFromInfo(
                info, `internal: a closure class could not be written (@classesReasonText)`
            )
        }
        return
    }
    // The profiler's timer comes before the body's storage, so no jump can cross into its
    // scope (impl_specs/profiling.md). `measure` is false for a machine's methods.
    if (measure) {
        val preamble: Str = profPreamble(this.profIndexOf(info.symbol))
        if (preamble != "") {
            this.sections.appendLine(cgIndent(level), preamble)
        }
    }
    this.sections.appendText(emitted.text)
}
