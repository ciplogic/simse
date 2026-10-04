// CgEmitFn.kt
//
// Emitting bodies: `emitFunctions`/`emitFunction`, the frame's scope and receiver, the
// emitted-type set, and `unInit` (the destructor). Extension methods on `Emitter`
// (Codegen.kt).

package codegen

import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources


fun Emitter.emitFunctions(prototypeOnly: Bool, facts: *SemFacts): Unit {
    for (*fn in this.functions) {
        // A prelude body is emitted only when the program reaches it
        // (`reachesPreludeBody`), so it costs a program only what it uses.
        if (fn.prelude && !this.reachesPreludeBody(fn)) {
            continue
        }
        this.curFile = fn.file
        this.curPrelude = fn.prelude
        this.emitFunction(fn, prototypeOnly, facts)
        if (this.failed) {
            return
        }
    }
}

fun Emitter.beginScope(fn: *CgFn, selfK: NameKind, selfTypePtr: *AstXmlNode): Unit {
    this.nameKinds.clear()
    this.localTypes.clear()
    this.selfKind = selfK
    this.selfType = selfTypePtr
    if (!xmlIsEmpty(fn.receiver)) {
        this.nameKinds.insert("self", selfK)
    }
    val params: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
    for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (!xmlIsEmpty(paramType)) {
            this.nameKinds.insert(xmlAttr(param, AstNodeAttributeKind.Name), this.kindOf(paramType))
            this.localTypes.insert(xmlAttr(param, AstNodeAttributeKind.Name), paramType)
        }
    }
}

fun Emitter.receiverParam(receiverType: *AstXmlNode): Str {
    val mapped: Str = this.type(receiverType)
    val kind: AstNodeCategory = xmlKind(receiverType)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return mapped + " self"
    }
    return mapped + "* self"
}

// The class name of a machine: the function's own, prefixed with the receiver's outer
// type name for an extension (`List<T>`'s `iter` is `List_iter`), so containers do not
// collide.
fun Emitter.machineName(decl: *AstXmlNode): Str {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val receiver: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Receiver)
    val outer: Str = this.outerTypeName(receiver)
    if (outer == "") {
        return name
    }
    return `@(outer)_@name`
}

// The outer name of a type, ignoring handles and arguments: `*List<Int>` and `List<Str>`
// are both `List`.
fun Emitter.outerTypeName(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return ""
    }
    var node: AstXmlNode = typeNode
    while (true) {
        val kind: AstNodeCategory = xmlKind(node)
        if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
            break
        }
        val inner: AstXmlNode = xmlChild(node, AstNodeKind.Inner)
        if (xmlIsEmpty(inner)) {
            break
        }
        node = inner
    }
    val outerKind: AstNodeCategory = xmlKind(node)
    if (outerKind != AstNodeCategory.TypeNamed && outerKind != AstNodeCategory.TypeGeneric) {
        return ""
    }
    return xmlAttr(node, AstNodeAttributeKind.Name)
}

// Every type name in a type node, nesting included: `List<Array<Int>>` names both. The
// roles are the positions a type occupies (`Type` for a declaration's, `Inner` for a
// handle's pointee, ...).
fun Emitter.collectTypeNames(node: *AstXmlNode): Unit {
    val role: AstNodeKind = node.name
    if (role != AstNodeKind.Type && role != AstNodeKind.Inner && role != AstNodeKind.TypeArg
        && role != AstNodeKind.ReturnType && role != AstNodeKind.TargetType
        && role != AstNodeKind.ParamType && role != AstNodeKind.Receiver
    ) {
        return
    }
    val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
    if (name != "") {
        this.referencedTypes.insert(name, true)
    }
    var i: Int = 0
    while (i < node.Children.count()) {
        this.collectTypeNames(node.Children[i])
        i = i + 1
    }
}

// The prelude types the program reaches, closed over the field types of the *prelude*
// structs it names: naming AstXmlNode reaches AstNodeAttribute, which reaches
// AstNodeAttributeKind, and so on. A program that never names the compiler AST carries
// none of it; the compiler itself names it everywhere and carries it all. The seed is
// `referencedTypes` - which collectProgramNames closed over the reached prelude bodies,
// and which collectTypeNames filled from the program itself, so the fields of a program
// struct are already here and only the prelude structs need walking.
//
// The pointer form on every walk: a value binds a copy per element.
fun Emitter.computeEmittedTypes(): Dictionary<Str, Bool> {
    var out: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    val seed: List<Str> = this.referencedTypes.keys()
    for (*name in seed) {
        out.insert(*name, true)
    }
    // `Span` and its `StrView` alias are named by the runtime's own sections (`strtable`,
    // `strops`, the print overloads), not only by a program's declarations, so they are
    // emitted for every program - the role the headers had.
    out.insert("StrView", true)
    out.insert("Span", true)
    var changed: Bool = true
    while (changed) {
        changed = false
        val names: List<Str> = out.keys()
        for (*name in names) {
            // The merged prelude's declarations carry no package (all of them are bare), so
            // both spellings of "prelude" are walked here.
            val pkg: Str = this.typePackage(name)
            if (pkg != "rtl" && pkg != "") {
                continue
            }
            val decl: *AstXmlNode = this.types.getPtr(*name)
            if (decl == null || decl.name != AstNodeKind.DataClass || this.typeIsRaw(decl)) {
                continue
            }
            // A union class is emitted with its implicit tag enum, which is a declaration of
            // its own: reaching `Opt` has to reach `SmOptTypes` too.
            if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true") {
                val tag: Str = unionTagName(*name)
                if (!out.has(tag)) {
                    out.insert(tag, true)
                    changed = true
                }
            }
            for (*field in xmlChildren(decl, AstNodeKind.Field)) {
            val fieldType: *AstXmlNode = xmlChildPtr(field, AstNodeKind.Type)
            if (!xmlIsEmpty(fieldType) && this.gatherTypeNames(fieldType, out)) {
                changed = true
            }
        }
        }
    }
    return out
}

// The type names a type node names, nesting included (List<AstXmlNode> names both), added
// to `out`; true when a name was added that was not there (the closure walks until no
// struct adds one).
fun Emitter.gatherTypeNames(node: *AstXmlNode, out: *Dictionary<Str, Bool>): Bool {
    val role: AstNodeKind = node.name
    if (role != AstNodeKind.Type && role != AstNodeKind.Inner && role != AstNodeKind.TypeArg
        && role != AstNodeKind.ReturnType && role != AstNodeKind.TargetType
        && role != AstNodeKind.ParamType && role != AstNodeKind.Receiver
    ) {
        return false
    }
    var added: Bool = false
    val name: *Str = xmlAttr(node, AstNodeAttributeKind.Name)
    if ( * name != "") {
        val at: Int = out.size()
        out.insert(*name, true)
        if (out.size() != at) {
            added = true
        }
    }
    for (*child in node.Children) {
        if (this.gatherTypeNames(child, out)) {
            added = true
        }
    }
    return added
}

// The names a body's own C++ scope already has, which a hoisted declaration may not
// collide with (`linFinishForEmission`'s `reserved`).
fun Emitter.cgReservedNames(decl: *AstXmlNode, hasSelf: Bool, argv: Bool): List<Str> {
    var names: List<Str> = List<Str>()
    if (hasSelf) {
        names.append("self")
    }
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in params) {
        names.append(xmlAttr(param, AstNodeAttributeKind.Name))
    }
    if (argv) {
        names.append("simse_argIndex")
    }
    return names
}

fun Emitter.emitFunction(fn: *CgFn, prototypeOnly: Bool, facts: *SemFacts): Unit {
    // Read-only here: borrow instead of copying the whole function AST out of the CgFn.
    val decl: *AstXmlNode = *fn.decl
    if (fn.isNative) {
        return
    }
    // A `union class`'s generated member: resolved and called like any method, but its C++
    // is written inline with the struct (`emitUnionClass`), so no prototype or body here.
    if (xmlAttr(decl, AstNodeAttributeKind.IsUnionGenerated) == "true") {
        return
    }
    // `unInit` is the type's destructor, not a callable function: the struct declares
    // `~T()` and the definition is emitted where a body belongs (`emitUninit`).
    if (!xmlIsEmpty(fn.receiver) && fn.name == "unInit") {
        this.emitUninit(fn, decl, facts, prototypeOnly)
        return
    }
    // A suspending function is emitted as the task it lowers to (impl_specs/async.md), not as
    // a function: the coloring pass's set is what says so, and a `@SmGen` leaf's C++ is its
    // own (the file and socket steps).
    if (this.asyncFns.has(fn.name)) {
        this.emitTask(fn, decl, prototypeOnly, facts)
        return
    }
    val isMain: Bool = xmlIsEmpty(fn.receiver) && fn.name == "main"
    if (isMain && prototypeOnly) {
        return
    }
    val mainArgs: Bool = isMain && cgIsMainArgs(decl)
    val params0: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (isMain && params0.size() > 0 && !mainArgs) {
        this.fail(decl, "unsupported: main with parameters")
        return
    }

    this.setActiveTypeParams(fn.templateParams)
    // A machine receiver (`fun ..*T.select<T, U>(...)`): the receiver's machine class is
    // the caller's, so the function is a C++ template over it (`_SmIter`) and a machine
    // pattern in the signature spells that parameter (`type`).
    this.machineIter = semMachineReceiver(fn.receiver)
    val tmplParams: List<Str> = this.fnTemplateParams(fn)
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    // A yielding body is lowered to a state machine and the function to a factory for it
    // (impl_specs/yield.md): the emitted return type is the machine's class, not `..T`;
    // for a generic function the class is a template, so its name carries the parameters.
    val yielding: Bool = !xmlIsEmpty(returnNode) && xmlKind(returnNode) == AstNodeCategory.TypeYield
    val yieldClass: Str = this.qualify(fn.packageName, this.machineName(decl)) + "_yieldable"
    var yieldType: Str = yieldClass
    if (yielding && tmplParams.size() > 0) {
        val cgJoinText: Str = cgJoin(tmplParams, ", ")
        yieldType = `@yieldClass<@cgJoinText>`
    }
    var ret: Str = "void"
    if (isMain) {
        ret = "int"
    } else if (yielding) {
        ret = yieldType
    } else if (!xmlIsEmpty(returnNode)) {
        ret = this.type(returnNode)
    }
    if (this.failed) {
        return
    }

    var params: List<Str> = List<Str>()
    var hasSelf: Bool = false
    var selfK: NameKind = NameKind.Value
    var selfTypePtr: AstXmlNode = xmlEmptyNode()
    if (!xmlIsEmpty(fn.receiver)) {
        params.append(this.receiverParam(fn.receiver))
        hasSelf = true
        selfK = this.kindOf(fn.receiver)
        selfTypePtr = fn.receiver
        if (this.failed) {
            return
        }
    }
    // The argv form's parameter is built from argc/argv, not passed.
    if (!mainArgs) {
        for (*param in params0) {
            val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
            if (xmlIsEmpty(paramType)) {
                val xmlAttrText: Str = xmlAttr(param, AstNodeAttributeKind.Name)
                this.fail(
                    param,
                    `unsupported: parameter '@xmlAttrText' without a type`
                )
                return
            }
            if (xmlAttr(param, AstNodeAttributeKind.Name) == "this" && !hasSelf) {
                params.append(this.receiverParam(paramType))
                hasSelf = true
                selfK = this.kindOf(paramType)
                selfTypePtr = paramType
            } else {
                val typeText: Str = this.type(paramType)
                val xmlAttrText2: Str = xmlAttr(param, AstNodeAttributeKind.Name)
                params.append(`@typeText @xmlAttrText2`)
            }
            if (this.failed) {
                return
            }
        }
    }
    if (!hasSelf) {
        selfK = NameKind.Value
    }

    // The entry point keeps its unprefixed name; every other function is prefixed.
    var fnName: Str = "main"
    if (!isMain) {
        fnName = this.qualify(fn.packageName, fn.name)
    }
    val cgJoinText2: Str = cgJoin(params, ", ")
    var signature: Str = `@ret @fnName(@cgJoinText2)`
    if (mainArgs) {
        signature = "int main(int argc, char** argv)"
    }
    val tmpl: Str = this.templateClause(tmplParams)
    if (yielding) {
        // The machine plus the factory, nothing else: the source body *is* the machine.
        this.emitYieldable(fn, decl, yieldClass, yieldType, prototypeOnly, selfK, selfTypePtr, facts)
        return
    }
    if (prototypeOnly) {
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, signature + ";")
        if (!isMain && !mainArgs) {
            this.emitDeducedCallableOverload(fn, decl, ret, fnName, true)
        }
        return
    }
    if (!fn.hasBody) {
        return
    }

    this.sourceComment(decl)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, signature + " {")
    this.beginScope(fn, selfK, selfTypePtr)
    if (isMain && this.needsStaticInit()) {
        // Static storage is initialized before the body runs (specs/statics.md), the
        // resources among them (specs/resources.md).
        this.line(1, "simse_initStatics();")
    }
    if (mainArgs) {
        val argName: Str = xmlAttr(params0[0], AstNodeAttributeKind.Name)
        // The argument list is built here, not at a program call site, so the reach is
        // recorded as well as spelled: `append`'s C++ is a generated section.
        val appendSymbol: Str = "simse_list_append"
        this.referencedNames.insert(appendSymbol, true)
        this.line(1, `List<Str> @argName = List<Str>();`)
        this.line(1, "int simse_argIndex = 1;")
        this.line(1, "while (simse_argIndex < argc) {")
        this.line(2, `@appendSymbol(@argName, Str(argv[simse_argIndex]));`)
        this.line(2, "simse_argIndex = simse_argIndex + 1;")
        this.line(1, "}")
    }
    this.curReturnType = returnNode
    // Structured control flow is lowered to labels/gotos and expressions extracted into
    // temporaries, in a loop because each stage leaves work for the others
    // (impl_specs/linear-lowering.md); the emitter below knows the linear forms only.
    var lowered: List<AstXmlNode> =
        linLowerForEmission(xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt))
    val semantics: SemBody = SemBody(
        decl, fn.templateParams, selfTypePtr, xmlEmptyNode(),
        List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
    )
    // The proof of the pass: `inferred` tells the backend a slot holds a machine, which a
    // declaration can never say (`..T` is not spellable).
    var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    lowered = semInferTypes(lowered, facts, semantics, inferred)
    val finalBody: List<AstXmlNode> = linFinishForEmission(
        lowered,
        this.cgReservedNames(decl, !xmlIsEmpty(fn.receiver), mainArgs)
    )
    this.dumpIl(fn, decl, finalBody, facts, inferred)
    this.emitBodyAt(this.ilFunctionFor(fn, decl, facts, inferred), finalBody, fn.file, 1, true)
    if (this.failed) {
        return
    }
    this.line(0, "}")
    if (!isMain && !mainArgs) {
        this.emitDeducedCallableOverload(fn, decl, ret, fnName, false)
    }
}

// One parameter of a generic function whose declared type is a callable that mentions the
// function's own type parameters.
data class CgCallableArg(
    var name: Str,
    var typeText: Str
)

// A generic function whose callable parameter type mentions its own type parameters gets a
// forwarding overload: the callable is taken as its own template parameter and converted
// in the forwarding call. MSVC will not deduce a type parameter through a dependent
// `std::function` argument when the argument is a closure or a named function - it works
// when the argument already *is* a `Func<...>`, which is why the declared signature stays:
// the overload is less specialized, so partial ordering keeps using it there.
fun Emitter.emitDeducedCallableOverload(
    fn: *CgFn, decl: *AstXmlNode, ret: Str, fnName: Str, prototypeOnly: Bool
): Unit {
    if (fn.templateParams.size() == 0 || !fn.hasBody) {
        return
    }
    val callables: List<CgCallableArg> = this.cgDeducedCallables(fn, decl)
    if (callables.size() == 0) {
        return
    }
    var taken: List<Str> = List<Str>()
    for (*typeParam in fn.templateParams) {
        taken.append(typeParam)
    }
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        taken.append(xmlAttr(param, AstNodeAttributeKind.Name))
    }
    var cbNames: List<Str> = List<Str>()
    var i: Int = 0
    while (i < callables.size()) {
        var cbName: Str = "_sm_cb" + (i + 1).toString()
        while (taken.contains(cbName)) {
            cbName = cbName + "_"
        }
        taken.append(cbName)
        cbNames.append(cbName)
        i = i + 1
    }
    var params: List<Str> = List<Str>()
    var args: List<Str> = List<Str>()
    if (!xmlIsEmpty(fn.receiver)) {
        params.append(this.receiverParam(fn.receiver))
        args.append("self")
        if (this.failed) {
            return
        }
    }
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            continue
        }
        var callableAt: Int = -1
        var c: Int = 0
        while (c < callables.size()) {
            if (callables[c].name == name) {
                callableAt = c
            }
            c = c + 1
        }
        if (callableAt >= 0) {
            val cbName2: Str = cbNames[callableAt]
            params.append(`@cbName2 @name`)
            val typeText: Str = callables[callableAt].typeText
            args.append(`@typeText(@name)`)
            continue
        }
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            return // the declared signature reports the missing type
        }
        val typeText2: Str = this.type(paramType)
        if (this.failed) {
            return
        }
        params.append(`@typeText2 @name`)
        args.append(name)
    }
    var all: List<Str> = this.fnTemplateParams(fn)
    for (*cbName3 in cbNames) {
        all.append(cbName3)
    }
    val tmpl: Str = this.templateClause(all)
    val cgJoinText: Str = cgJoin(params, ", ")
    val cgJoinText2: Str = cgJoin(args, ", ")
    if (prototypeOnly) {
        this.line(0, tmpl)
        this.line(0, `@ret @fnName(@cgJoinText);`)
        return
    }
    // The forwarding call spells the function's template arguments when a machine
    // receiver is involved: neither the machine's class nor a parameter that lives only
    // in the machine is deducible from a callable argument.
    var callee: Str = fnName
    if (this.machineIter) {
        val explicitText: Str = cgJoin(this.fnTemplateParams(fn), ", ")
        callee = `@fnName<@explicitText>`
    }
    this.line(0, tmpl)
    this.line(0, `@ret @fnName(@cgJoinText) {`)
    if (ret == "void") {
        this.line(1, `@callee(@cgJoinText2);`)
    } else {
        this.line(1, `return @callee(@cgJoinText2);`)
    }
    this.line(0, "}")
}

// The callable parameters that need the forwarding overload: the declared type resolves to
// a function type and mentions one of the function's own type parameters.
fun Emitter.cgDeducedCallables(fn: *CgFn, decl: *AstXmlNode): List<CgCallableArg> {
    var out: List<CgCallableArg> = List<CgCallableArg>()
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            continue
        }
        val typeNode: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(typeNode)) {
            continue
        }
        if (xmlKind(this.resolveAlias(*typeNode)) != AstNodeCategory.TypeFunction) {
            continue
        }
        if (!cgTypeMentions(typeNode, fn.templateParams)) {
            continue
        }
        out.append(CgCallableArg(name, this.type(typeNode)))
    }
    return out
}

// Whether a type node names one of the given type parameters, at any depth.
fun cgTypeMentions(node: *AstXmlNode, names: *List<Str>): Bool {
    if (names.size() == 0) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        if (names.contains(xmlAttr(node, AstNodeAttributeKind.Name))) {
            return true
        }
    }
    for (*child in node.Children) {
        if (cgTypeMentions(child, names)) {
            return true
        }
    }
    return false
}

// The instruction-list backend lives in `src/codegen/IlCodeGen.kt`: the IL's types and
// the walks that spell a body's instructions are extension functions on `Emitter` there.

// `unInit` is a type's destructor. The struct declares `~T()` (`emitDataClass`) and this
// emits the definition, `T::~T() { ... }`, where a body belongs - after the prototypes, so the
// body may call anything. The receiver is C++'s `this` rather than a `self` parameter, which
// is what the closure spelling already means (`inClosureMethod`); the frame's `self` slot is
// what `this` names, so the body reaches its fields through `this->`.
fun Emitter.emitUninit(fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts, prototypeOnly: Bool): Unit {
    if (prototypeOnly) {
        // Declared inside the struct, which `emitDataClass` writes.
        return
    }
    if (!fn.hasBody) {
        return
    }
    val className: Str = this.outerTypeName(fn.receiver)
    if (className == "") {
        this.fail(decl, "unInit: the receiver has no type name")
        return
    }
    val classPtr: *AstXmlNode = this.types.getPtr(className)
    if (classPtr == null) {
        this.fail(decl, `unInit: no declaration of '@className'`)
        return
    }
    val classDecl: AstXmlNode = *classPtr
    val emittedName: Str = this.qualify(this.typePackage(className), className)
    var qualified: Str = emittedName
    if (fn.templateParams.size() > 0) {
        val cgJoinText3: Str = cgJoin(fn.templateParams, ", ")
        qualified = `@emittedName<@cgJoinText3>`
    }
    this.setActiveTypeParams(fn.templateParams)
    val tmpl: Str = this.templateClause(fn.templateParams)
    this.sourceComment(decl)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, `@qualified::~@emittedName() {`)

    val savedClosure: Bool = this.inClosureMethod
    val savedSelfKind: NameKind = this.selfKind
    val savedSelfType: AstXmlNode = this.selfType
    val savedReturn: AstXmlNode = this.curReturnType
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    this.nameKinds = Dictionary<Str, NameKind>()
    this.localTypes = Dictionary<Str, AstXmlNode>()
    this.inClosureMethod = true
    this.selfKind = NameKind.Value
    var classType: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
    this.selfType = classType
    this.curReturnType = xmlEmptyNode()

    var lowered: List<AstXmlNode> =
        linLowerForEmission(xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt))
    val semantics: SemBody = SemBody(
        decl, fn.templateParams, fn.receiver, xmlEmptyNode(),
        List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
    )
    var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    lowered = semInferTypes(lowered, facts, semantics, inferred)
    // `self` is reserved: the frame has a slot of that name for `this`.
    val finalBody: List<AstXmlNode> = linFinishForEmission(lowered, this.cgReservedNames(decl, true, false))
    this.emitBodyAt(
        this.ilDestructorFor(fn, decl, classDecl, `@qualified::~@emittedName()`, emittedName, facts, inferred),
        finalBody, fn.file, 1, false
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
}
