// CgTask.kt
//
// The async/task lowering (`impl_specs/async.md`) and the driver's `run`. Extension
// methods on `Emitter` (Codegen.kt).

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources


// The async side of the program (impl_specs/async.md): which names can suspend and what a call
// to each answers, plus the per-callee factory/result pair the lowering calls. The coloring is
// the driver's `--showAsync` pass, run here because the lowering is the emitter's.
fun Emitter.collectAsync(): Unit {
    var decls: List<AstXmlNode> = List<AstXmlNode>()
    for (*input in this.inputs) {
        asyncCollect(input.module, decls)
    }
    var reasons: List<Str> = List<Str>()
    val names: List<Str> = asyncColor(decls, reasons)
    for (*name in names) {
        // Only a name some *emitted* function carries (`asyncEmitted`): a prelude declaration the
        // program never reaches is not part of it, so it must not color anything.
        if (!this.asyncEmitted(name)) {
            continue
        }
        var ret: AstXmlNode = xmlEmptyNode()
        for (*decl in decls) {
            if (xmlAttr(decl, AstNodeAttributeKind.Name) == name) {
                ret = xmlChild(decl, AstNodeKind.ReturnType)
                break
            }
        }
        // `Unit` is the absence of a value: the lowering gives such a task no `result` field
        // (`lowerTask`), so the table has to agree here or the accessor reads a field that was
        // never declared (`suspend fun f(): Unit` - the socket server's leaves).
        if (
            xmlKind(ret) == AstNodeCategory.TypeNamed
            && xmlAttr(ret, AstNodeAttributeKind.Name) == "Unit"
        ) {
            ret = xmlEmptyNode()
        }
        this.asyncFns.insert(name, ret)
    }
    if (this.asyncFns.size() == 0) {
        return
    }
    // The task runtime is reached by generated calls, not by the program's own, so the reach
    // is recorded here (the rule the `main` argument list's `append` follows).
    this.referencedNames.insert("simse_tasksFinish", true)
    val count: Int = this.functions.size()
    var i: Int = 0
    while (i < count) {
        val fn: *CgFn = *this.functions[i]
        i = i + 1
        if (this.asyncFns.has(fn.name)) {
            this.addTaskHelpers(fn)
        }
    }
}

// The type a call to `name` answers, or an empty node for `Unit`; an empty node for a name that
// cannot suspend at all (the two are told apart by `asyncFns.has`).
fun Emitter.asyncReturn(name: *Str): AstXmlNode {
    if (!this.asyncFns.has(name)) {
        return xmlEmptyNode()
    }
    return *this.asyncFns.getPtr(name)
}

fun Emitter.asyncBase(fn: *CgFn): Str {
    return this.qualify(fn.packageName, fn.name)
}

fun Emitter.asyncTaskClass(fn: *CgFn): Str {
    return this.asyncBase(fn) + "_task"
}

fun Emitter.asyncFactory(fn: *CgFn): Str {
    return this.asyncBase(fn) + "Task"
}

fun Emitter.asyncResult(fn: *CgFn): Str {
    return this.asyncBase(fn) + "_smResult"
}

// The two generated symbols a suspension reaches: `<f>_smNew` (the factory) and `<f>_smResult`
// (the value accessor, only when the callee answers one). Registered as ordinary functions so
// `call` spells them and the prototype pass writes them; `emitTask` writes their definitions.
fun Emitter.addTaskHelpers(fn: *CgFn): Unit {
    // Read what is needed from `fn` *before* anything is appended: `addFunction` grows
    // `this.functions`, which moves it - a pointer taken into that list does not survive the
    // append (this is what made a self-recursive (or just unlucky) suspend program crash the
    // compiler non-deterministically).
    val taskName: Str = fn.name
    val taskFile: Str = fn.file
    val taskPackage: Str = fn.packageName
    val decl: *AstXmlNode = *fn.decl
    var factory: AstXmlNode = AstXmlNode(
        AstNodeKind.Function, AstNodeCategory.Function, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    factory.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, taskName + "Task"))
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        var copied: AstXmlNode = copy(param)
        copied.name = AstNodeKind.Param
        xmlAddChild(factory, copied)
    }
    // The factory answers the opaque handle a caller holds: a `RawPtr` (`*Unit`), never the
    // task type - the caller passes it back to the protocol and never dereferences it.
    var factoryReturn: AstXmlNode = ilPointerNode(this.namedTypeExpr("Unit"))
    factoryReturn.name = AstNodeKind.ReturnType
    xmlAddChild(factory, factoryReturn)
    val ret: AstXmlNode = this.asyncReturn(taskName)
    var accessor: AstXmlNode = AstXmlNode(
        AstNodeKind.Function, AstNodeCategory.Function, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var hasAccessor: Bool = false
    if (!xmlIsEmpty(ret)) {
        hasAccessor = true
        accessor.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, taskName + "_smResult"))
        var handle: AstXmlNode = AstXmlNode(
            AstNodeKind.Param, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>()
        )
        handle.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, "_sm_h"))
        xmlAddChild(handle, ilPointerNode(this.namedTypeExpr("Unit")))
        xmlAddChild(accessor, handle)
        var accessorReturn: AstXmlNode = ret
        accessorReturn.name = AstNodeKind.ReturnType
        xmlAddChild(accessor, accessorReturn)
    }
    this.addFunction(factory, xmlEmptyNode(), taskFile, List<Str>(), false, taskPackage, false)
    if (this.failed || !hasAccessor) {
        return
    }
    this.addFunction(accessor, xmlEmptyNode(), taskFile, List<Str>(), false, taskPackage, false)
}

// A suspending function, emitted as the task it was lowered to (impl_specs/async.md). The
// struct is declared on the prototype pass and defined on the body pass, so a `run()` that
// calls another task's factory is always preceded by every factory's prototype.
fun Emitter.emitTask(fn: *CgFn, decl: *AstXmlNode, prototypeOnly: Bool, facts: *SemFacts): Unit {
    val isMain: Bool = xmlIsEmpty(fn.receiver) && fn.name == "main"
    if (!xmlIsEmpty(fn.receiver)) {
        this.fail(decl, "suspend: an extension function is not supported yet")
        return
    }
    if (fn.templateParams.size() > 0) {
        this.fail(decl, "suspend: a generic suspending function is not supported yet")
        return
    }
    if (isMain && cgIsMainArgs(decl)) {
        this.fail(decl, "suspend: a `main(args)` that suspends is not supported yet")
        return
    }
    val className: Str = this.asyncTaskClass(fn)
    val task: TskTask = this.lowerTask(fn, decl, facts)
    if (this.failed) {
        return
    }
    if (prototypeOnly) {
        this.sourceComment(decl)
        this.line(0, fmtStr("struct | : simse_tasks::Task {", className))
        for (*field in task.fields) {
            this.line(1, fmtStr("| |{};", this.type(field.typeNode), field.member))
            if (this.failed) {
                return
            }
        }
        this.line(1, "void run() override;")
        this.line(0, "};")
        this.line(0, "")
        return
    }
    this.registerTaskType(className, *task)
    this.sourceComment(decl)
    this.line(0, fmtStr("void |::run() {", className))
    // A C++ member function: the task's own storage is `this->`, the way a machine's method
    // reads it (there is no `self` parameter).
    val savedClosure: Bool = this.inClosureMethod
    val savedSelfKind: NameKind = this.selfKind
    val savedSelfType: AstXmlNode = this.selfType
    val savedReturn: AstXmlNode = this.curReturnType
    this.inClosureMethod = true
    this.selfKind = NameKind.Value
    this.selfType = this.namedTypeExpr(className)
    this.curReturnType = this.asyncReturn(fn.name)
    val info: IlFunction = this.ilTaskMethod(className, *task, this.machineDecl, facts)
    this.emitBodyAt(
        info, task.body, fn.file, 1, false
    )
    this.inClosureMethod = savedClosure
    this.selfKind = savedSelfKind
    this.selfType = savedSelfType
    this.curReturnType = savedReturn
    if (this.failed) {
        return
    }
    this.line(0, "}")
    this.emitTaskFactory(fn, decl, className, *task)
    this.emitTaskAccessor(fn, className, *task)
    if (isMain) {
        this.emitAsyncMain(fn, className, *task)
    }
}

// The body lowered to a task: the fields, the `run()` body, and the frame the expression pass
// proved. Computed per pass (the struct is written on the prototype pass, `run()` on the body
// pass), which is the same body twice and deterministic each time.
fun Emitter.lowerTask(fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts): TskTask {
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    var hasValue: Bool = !xmlIsEmpty(returnNode)
    if (hasValue && xmlKind(returnNode) == AstNodeCategory.TypeNamed
        && xmlAttr(returnNode, AstNodeAttributeKind.Name) == "Unit"
    ) {
        hasValue = false
    }
    val body: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        this.fail(decl, "suspend: a body-less `suspend` declaration has no lowering yet (the leaves arrive with the file and socket steps)")
        return TskTask(List<TskField>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>(), "")
    }
    if (linHasYield(xmlChildren(body, AstNodeKind.Stmt))) {
        this.fail(decl, "suspend: a body that also yields is not supported (a `for` machine is a local and cannot cross a suspension)")
        return TskTask(List<TskField>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>(), "")
    }
    var lowered: List<AstXmlNode> = linLowerForEmission(xmlChildren(body, AstNodeKind.Stmt))
    val semantics: SemBody = SemBody(
        decl, fn.templateParams, xmlEmptyNode(), xmlEmptyNode(),
        List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
    )
    var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    lowered = semInferTypes(lowered, facts, semantics, inferred)
    val reserved: List<Str> = listOf<Str>(
        "branch", "status", "parent", "refcount", "loop", "result"
    )
    val finalBody: List<AstXmlNode> = linFinishForEmission(lowered, reserved)
    var resultType: AstXmlNode = xmlEmptyNode()
    if (hasValue) {
        resultType = *returnNode
    }
    val task: TskTask = linLowerAsync(decl, resultType, hasValue, *this.asyncFns, finalBody)
    if (!task.error.isEmpty()) {
        this.fail(decl, task.error)
        return task
    }
    task.inferred = inferred
    return task
}

// The task class as the type pass sees it: a data class with the task's own fields, so a
// body's `this.<name>` resolves. The header (`branch`, ...) is the runtime's and the protocol
// is free functions, so nothing here spells it.
fun Emitter.registerTaskType(className: *Str, task: TskTask): Unit {
    var declNode: AstXmlNode = AstXmlNode(
        AstNodeKind.DataClass, AstNodeCategory.DataClass, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    declNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
    // `branch` is the runtime's `Task` field, not the class's own: registering it as a field of
    // the view is what lets the dispatcher read `this.branch` (the machine's own shape).
    var branchNode: AstXmlNode = AstXmlNode(
        AstNodeKind.Field, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    branchNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, "branch"))
    xmlAddChild(branchNode, this.renameRole(this.namedTypeExpr("Int"), AstNodeKind.Type))
    xmlAddChild(declNode, this.renameRole(branchNode, AstNodeKind.Field))
    for (*field in task.fields) {
        var fieldNode: AstXmlNode = AstXmlNode(
            AstNodeKind.Field, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>()
        )
        fieldNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, field.member))
        xmlAddChild(fieldNode, this.renameRole(field.typeNode, AstNodeKind.Type))
        xmlAddChild(declNode, this.renameRole(fieldNode, AstNodeKind.Field))
    }
    this.types.insert(className, declNode)
    this.machineDecl = declNode
}

// The factory: build the task, hand it the loop, fill its parameters from the call's. It does
// not run the body - the loop does, once - and does not park the task: `tasksSuspendAt` does,
// because only the caller knows the branch to resume at.
fun Emitter.emitTaskFactory(fn: *CgFn, decl: *AstXmlNode, className: *Str, task: *TskTask): Unit {
    val params: List<Str> = this.parameterList(fn, decl)
    if (this.failed) {
        return
    }
    // The task handle the caller holds is opaque: a `RawPtr`, never the task type.
    this.line(0, fmtStr("RawPtr |(|) {", this.asyncFactory(fn), cgJoin(params, ", ")))
    this.line(1, fmtStr("|* task = new |();", className, className))
    this.line(1, "task->loop = &simse_tasks::taskLoop();")
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        this.line(1, fmtStr("task->| = |;", task.memberOf(name), name))
    }
    this.line(1, "return (RawPtr) task;")
    this.line(0, "}")
}

// The value accessor: the caller reads the child's `result` through it, because it holds the
// handle opaquely (a `RawPtr`) and never the task type.
fun Emitter.emitTaskAccessor(fn: *CgFn, className: *Str, task: *TskTask): Unit {
    var ret: AstXmlNode = this.asyncReturn(fn.name)
    if (xmlIsEmpty(ret)) {
        return
    }
    val retText: Str = this.type(ret)
    if (this.failed) {
        return
    }
    this.line(0, fmtStr("| |(RawPtr _sm_h) {", retText, this.asyncResult(fn)))
    this.line(1, fmtStr("return ((|*) _sm_h)->|;", className, task.memberOf("result")))
    this.line(0, "}")
}

// A suspending `main` is the root task: build it, hand it to the loop, drive until it is done,
// and answer its result as the exit code.
fun Emitter.emitAsyncMain(fn: *CgFn, className: *Str, task: *TskTask): Unit {
    var ret: AstXmlNode = this.asyncReturn(fn.name)
    this.line(0, "")
    this.line(0, "int main() {")
    if (this.hasStaticInit()) {
        this.line(1, "simse_initStatics();")
    }
    this.line(1, fmtStr("|* simse_root = (|*) |();", className, className, this.asyncFactory(fn)))
    this.line(1, "simse_tasksStart(simse_root);")
    this.line(1, "simse_tasksRunLoop();")
    this.line(1, "int simse_rc = 0;")
    if (!xmlIsEmpty(ret)) {
        this.line(1, fmtStr("simse_rc = (int) simse_root->|;", task.memberOf("result")))
    }
    this.line(1, "simse_root->release();")
    this.line(1, "return simse_rc;")
    this.line(0, "}")
    this.referencedNames.insert("simse_tasksFinish", true)
}

fun Emitter.run(): Res<Str> {
    this.collect()
    // Reachability before the coloring: `collectAsync` keeps only the suspending declarations
    // the program reaches, which is what tells a prelude `suspend fun` from one of the
    // program's own.
    this.collectProgramNames()
    this.collectAsync()
    // The program's constant globals (`optimizations/FoldGlobals.kt`), a property of the
    // whole program: declarations first (the borrow rule asks which functions take a handle
    // parameter), then bodies.
    linConstGlobalsReset()
    for (*input in this.inputs) {
        foldGlobalScanDecls(*input.module)
    }
    for (*input in this.inputs) {
        foldGlobalScanBodies(*input.module)
    }
    linConstGlobalsBuild()
    // The facts the semantic step reads (sema/TypeInfer.kt), one value per program; they
    // are threaded to the emitters rather than stored on the emitter.
    val facts: SemFacts = this.collectFacts()
    // The assembly phases, in order (impl_specs/generators.md): includes, support,
    // profile, strings, resources, forward, types, statics, prototypes, init, bodies.
    // `support` and `forward` are not begun here because a generator writes them.
    this.sections.begin("includes")
    this.preludeText()
    this.emitNativeDeclarations()
    // Sorting is what makes the indices canonical, so the table does not depend on the
    // order the walk met literals in. The resources are literals too, pooled before the sort.
    this.collectResourceLiterals()
    this.literals.sort()
    this.sections.begin("profile")
    this.emitProfileText()
    this.sections.begin("strings")
    this.emitStringTable()
    this.sections.begin("resources")
    this.emitResourceTable()
    val emittedTypes: Dictionary<Str, Bool> = this.computeEmittedTypes()
    this.sections.begin("types")
    this.emitForwardTypes(emittedTypes)
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.emitTypes(emittedTypes)
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.sections.begin("statics")
    this.emitStatics()
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.sections.begin("prototypes")
    this.emitFunctions(true, facts)
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.sections.begin("init")
    this.emitStaticInit()
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.sections.begin("bodies")
    this.emitFunctions(false, facts)
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    this.emitProfileNames()
    // The generators last (impl_specs/generators.md): their text goes into named sections,
    // so when this runs does not decide where it renders, and the reachability rule sees
    // every body. `this.sections` *is* the pointer - `*this.sections` would fill a copy.
    val generated: Res<Str> = sourceGenEmit(this.sections, *this.referencedNames)
    if (!generated.isOk()) {
        this.fail(xmlEmptyNode(), generated.Error)
        return Res<Str>.err(this.error)
    }
    if (this.failed) {
        return Res<Str>.err(this.error)
    }
    return Res<Str>.ok(this.sections.render())
}
