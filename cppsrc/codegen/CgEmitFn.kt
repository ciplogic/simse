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
import sourcegen


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
    return fmtStr("|_|", outer, name)
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
    var changed: Bool = true
    while (changed) {
        changed = false
        val names: List<Str> = out.keys()
        for (*name in names) {
            if (this.typePackage(name) != "rtl") {
                continue
            }
            val decl: *AstXmlNode = this.types.getPtr(*name)
            if (decl == null || decl.name != AstNodeKind.DataClass || this.typeIsRaw(decl)) {
                continue
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
    if (*name != "") {
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
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    // A yielding body is lowered to a state machine and the function to a factory for it
    // (impl_specs/yield.md): the emitted return type is the machine's class, not `..T`;
    // for a generic function the class is a template, so its name carries the parameters.
    val yielding: Bool = !xmlIsEmpty(returnNode) && xmlKind(returnNode) == AstNodeCategory.TypeYield
    val yieldClass: Str = this.qualify(fn.packageName, this.machineName(decl)) + "_yieldable"
    var yieldType: Str = yieldClass
    if (yielding && fn.templateParams.size() > 0) {
        yieldType = fmtStr("|<|>", yieldClass, cgJoin(fn.templateParams, ", "))
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
                this.fail(
                    param,
                    fmtStr("unsupported: parameter '|' without a type", xmlAttr(param, AstNodeAttributeKind.Name))
                )
                return
            }
            if (xmlAttr(param, AstNodeAttributeKind.Name) == "this" && !hasSelf) {
                params.append(this.receiverParam(paramType))
                hasSelf = true
                selfK = this.kindOf(paramType)
                selfTypePtr = paramType
            } else {
                params.append(fmtStr("| |", this.type(paramType), xmlAttr(param, AstNodeAttributeKind.Name)))
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
    var signature: Str = fmtStr("| |(|)", ret, fnName, cgJoin(params, ", "))
    if (mainArgs) {
        signature = "int main(int argc, char** argv)"
    }
    val tmpl: Str = this.templateClause(fn.templateParams)
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
    if (isMain && this.hasStaticInit()) {
        // Static storage is initialized before the body runs (specs/statics.md).
        this.line(1, "simse_initStatics();")
    }
    if (mainArgs) {
        val argName: Str = xmlAttr(params0[0], AstNodeAttributeKind.Name)
        // The argument list is built here, not at a program call site, so the reach is
        // recorded as well as spelled: `append`'s C++ is a generated section.
        val appendSymbol: Str = "simse_list_append"
        this.referencedNames.insert(appendSymbol, true)
        this.line(1, fmtStr("List<Str> | = List<Str>();", argName))
        this.line(1, "int simse_argIndex = 1;")
        this.line(1, "while (simse_argIndex < argc) {")
        this.line(2, fmtStr("|(|, Str(argv[simse_argIndex]));", appendSymbol, argName))
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
}

// The instruction-list backend lives in `cppsrc/codegen/IlCodeGen.kt`: the IL's types and
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
        this.fail(decl, fmtStr("unInit: no declaration of '|'", className))
        return
    }
    val classDecl: AstXmlNode = *classPtr
    val emittedName: Str = this.qualify(this.typePackage(className), className)
    var qualified: Str = emittedName
    if (fn.templateParams.size() > 0) {
        qualified = fmtStr("|<|>", emittedName, cgJoin(fn.templateParams, ", "))
    }
    this.setActiveTypeParams(fn.templateParams)
    val tmpl: Str = this.templateClause(fn.templateParams)
    this.sourceComment(decl)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, fmtStr("|::~|() {", qualified, emittedName))

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
        this.ilDestructorFor(fn, decl, classDecl, fmtStr("|::~|()", qualified, emittedName), emittedName, facts, inferred),
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
