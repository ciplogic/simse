// CgEmitType.kt
//
// Emitting the declarations: forward types, classes, enums, type aliases, name
// collection, and the native/static/resource/string tables. Extension methods on
// `Emitter` (Codegen.kt).

package codegen

import sema
import common
import linear
import optimizations
import profiling
import resources
import sourcegen


// Storage for every file-level static, value-initialized so a read before the generated
// pass below fills in the initializers yields the empty value, not indeterminate data
// (specs/statics.md).
fun Emitter.emitStatics(): Unit {
    for (*entry in this.statics) {
        this.curFile = entry.file
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        val storage: Str = this.qualify(entry.packageName, xmlAttr(entry.decl, AstNodeAttributeKind.Name))
        if (this.failed) {
            return
        }
        this.sourceComment(entry.decl)
        this.line(0, fmtStr("| |{};", this.type(typeNode), storage))
        if (this.failed) {
            return
        }
    }
}

// Whether any static has an initializer, i.e. whether the pass is needed.
fun Emitter.hasStaticInit(): Bool {
    for (*entry in this.statics) {
        if (!xmlIsEmpty(xmlChildPtr(entry.decl, AstNodeKind.Init))) {
            return true
        }
    }
    return false
}

// The generated initialization pass (specs/statics.md), run before `main`'s body so the
// language owns the order; a program must not depend on one static initializing before
// another.
fun Emitter.emitStaticInit(): Unit {
    if (!this.hasStaticInit()) {
        return
    }
    this.line(0, "// File-level static storage (specs/statics.md): initialized before main's body.")
    this.line(0, "void simse_initStatics() {")
    for (*entry in this.statics) {
        val init: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Init)
        if (xmlIsEmpty(init)) {
            continue
        }
        this.curFile = entry.file
        val storage: Str = this.qualify(entry.packageName, xmlAttr(entry.decl, AstNodeAttributeKind.Name))
        this.line(
            1,
            fmtStr(
                "| = |;",
                storage, this.expr(init, 0, xmlChildPtr(entry.decl, AstNodeKind.Type))
            )
        )
        if (this.failed) {
            return
        }
    }
    this.line(0, "}")
}

// Every aggregate the program declares, named before any is defined: packages are
// emitted in source order and a generated struct may hold a pointer to another package's
// type, so the definition would otherwise come too late.
// A type whose C++ is hand-written: a prelude declaration carrying the cpp generator (a
// header, already included) or the res generator (a resource section). The emitter skips
// it and the header/section defines it. An unmarked type is generated from its
// declaration, like a program type (specs/attributes.md).
fun Emitter.typeIsRaw(decl: *AstXmlNode): Bool {
    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    return generator == "cpp" || generator == "res"
}

fun Emitter.emitForwardTypes(emitted: *Dictionary<Str, Bool>): Unit {
    for (*input in this.inputs) {
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
        if (decl.name != AstNodeKind.DataClass) {
            continue
        }
        if (this.typeIsRaw(decl)) {
            continue
        }
        val fwdName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        if (input.prelude && !emitted.has(fwdName)) {
            continue
        }
        val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        this.line(0, fmtStr("struct |;", this.qualify(this.typePackage(name), name)))
    }
    }
}

fun Emitter.emitTypes(emitted: *Dictionary<Str, Bool>): Unit {
    for (*input in this.inputs) {
        this.curFile = input.fileName
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
        val tname: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val reachable: Bool = !input.prelude || emitted.has(tname)
        if (decl.name == AstNodeKind.DataClass) {
            if (reachable && !this.typeIsRaw(decl)) {
                this.emitDataClass(decl)
            }
        } else if (decl.name == AstNodeKind.Enum) {
            if (reachable && !this.typeIsRaw(decl)) {
                this.emitEnum(decl)
                this.emitEnumConversion(decl)
            }
        } else if (decl.name == AstNodeKind.TypeAlias) {
            // A prelude alias (StrView) is the header one; a program alias is emitted.
            if (!input.prelude) {
                this.emitTypeAlias(decl)
            }
        }
        if (this.failed) {
            return
        }
    }
    }
}

fun Emitter.emitDataClass(decl: *AstXmlNode): Unit {
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    for (*field in fields) {
        if (xmlIsEmpty(xmlChildPtr(field, AstNodeKind.Type))) {
            this.fail(
                field,
                fmtStr("unsupported: field '|' without a type", xmlAttr(field, AstNodeAttributeKind.Name))
            )
            return
        }
    }
    if (this.failed) {
        return
    }

    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val emittedName: Str = this.qualify(this.typePackage(name), name)
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    this.sourceComment(decl)
    val tmpl: Str = this.templateClause(typeParams)
    // Generated aggregates follow the language's 4-byte packing rule
    // (specs/memory-model.md).
    this.line(0, "SIMSE_PACK_PUSH")
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, fmtStr("struct | {", emittedName))
    for (*field in fields) {
        this.line(
            1,
            fmtStr(
                "| |;",
                this.type(xmlChildPtr(field, AstNodeKind.Type)),
                xmlAttr(field, AstNodeAttributeKind.Name)
            )
        )
    }
    if (!xmlIsEmpty(this.cgUninitMethod(decl))) {
        // A class with `unInit` has a real destructor: declared here, defined with the
        // bodies (`emitUninit`), so its body may call anything the prototypes declare.
        this.line(1, fmtStr("~|();", emittedName))
        if (this.failed) {
            return
        }
    }
    this.line(0, "};")
    this.line(0, "SIMSE_PACK_POP")
}

fun Emitter.emitEnum(decl: *AstXmlNode): Unit {
    this.sourceComment(decl)
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    // One line: a member with an explicit value keeps it (`Green = 4`).
    var parts: List<Str> = List<Str>()
    val members: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.EnumMember)
    for (*member in members) {
        if (xmlAttr(member, AstNodeAttributeKind.HasValue) == "true") {
            parts.append(
                fmtStr(
                    "| = |",
                    xmlAttr(member, AstNodeAttributeKind.Name),
                    xmlAttr(member, AstNodeAttributeKind.Value)
                )
            )
        } else {
            parts.append(xmlAttr(member, AstNodeAttributeKind.Name))
        }
    }
    this.line(
        0,
        fmtStr(
            "enum class | { | };", this.qualify(this.typePackage(name), name),
            cgJoin(*parts, ", ")
        )
    )
}

// The enum's one emitted conversion: `fromInt` is the direct cast back
// (`specs/declarations.md`); `toInt` needs no helper - the call site spells
// `static_cast<Int>(x)` (`Emitter.call`).
fun Emitter.emitEnumConversion(decl: *AstXmlNode): Unit {
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    if (typeParams.size() > 0) {
        return
    }
    val enumName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val emittedName: Str = this.qualify(this.typePackage(enumName), enumName)
    val fromIntName: Str =
        this.qualify(this.typePackage(enumName), "simse_" + enumName + "_fromInt")
    this.line(
        0,
        fmtStr("inline | |(Int value) { return (|) value; }", emittedName, fromIntName, emittedName)
    )
}

fun Emitter.emitTypeAlias(decl: *AstXmlNode): Unit {
    val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
    if (xmlIsEmpty(target)) {
        this.fail(
            decl,
            fmtStr("unsupported: typealias '|' without a target type", xmlAttr(decl, AstNodeAttributeKind.Name))
        )
        return
    }
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    val targetText: Str = this.type(target)
    if (this.failed) {
        return
    }
    this.sourceComment(decl)
    val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(
        0,
        fmtStr(
            "using | = |;",
            this.qualify(
                this.typePackage(xmlAttr(decl, AstNodeAttributeKind.Name)),
                xmlAttr(decl, AstNodeAttributeKind.Name)
            ),
            targetText
        )
    )
}

fun Emitter.emitNativeDeclarations(): Unit {
    this.setActiveTypeParams(List<Str>())
    for (*nativeInfo in this.nativeDecls) {
        if (nativeInfo.prelude) {
            continue
        }
        this.curFile = nativeInfo.file
        val decl: AstXmlNode = nativeInfo.decl
        this.setActiveTypeParams(xmlTypeParamNames(decl))
        if (nativeInfo.symbol.find("::") != -1) {
            this.fail(
                decl,
                fmtStr("unsupported: namespaced symbol '|' needs a global wrapper", nativeInfo.symbol)
            )
            return
        }
        val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
        var ret: Str = "void"
        if (!xmlIsEmpty(returnNode)) {
            ret = this.type(returnNode)
        }
        if (this.failed) {
            return
        }
        val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        var paramTexts: List<Str> = List<Str>()
        for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            this.fail(
                param,
                fmtStr(
                    "unsupported: generated parameter '|' without a type",
                    xmlAttr(param, AstNodeAttributeKind.Name)
                )
            )
            return
        }
        val mapped: Str = this.type(paramType)
        if (this.failed) {
            return
        }
        var name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            name = "self"
        }
        val pk: AstNodeCategory = xmlKind(paramType)
        if (pk == AstNodeCategory.TypePointer || pk == AstNodeCategory.TypeReference) {
            paramTexts.append(fmtStr("| |", mapped, name))
        } else {
            paramTexts.append(fmtStr("const |& |", mapped, name))
        }
    }
        this.sourceComment(decl)
        val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(
            0,
            fmtStr("| |(|);", ret, nativeInfo.symbol, cgJoin(paramTexts, ", "))
        )
    }
}

// The symbol a call on a *type name* reaches (`Resources.get(k)`): the declaration's own
// symbol, looked up among the explicit-`this` natives; empty when there is none.
fun Emitter.staticCallSymbol(receiverName: *Str, calleeName: *Str): Str {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeName)
    if (extensions == null) {
        return ""
    }
    for (*ext in extensions) {
        if (this.outerTypeName(ext.receiver) == receiverName) {
            return ext.symbol
        }
    }
    return ""
}

// Every call name in a node's subtree; a call also records the symbol it reaches,
// because that symbol may name C++ emitted elsewhere (a `res` declaration's text is
// emitted only when its section is reached).
fun Emitter.collectNames(node: *AstXmlNode, names: *Dictionary<Str, Bool>): Unit {
    // The string literals ride the same walk, the emitter's one pass over the whole
    // program; a literal the *lowering* invents keeps its own spelling.
    if (xmlKind(node) == AstNodeCategory.ExprStrLit) {
        this.literals.add(xmlAttr(node, AstNodeAttributeKind.Text))
    }
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        if (name != "") {
            names.insert(name, true)
            if (this.initByValueTypes.has(name)) {
                names.insert("initByValue", true)
            }
            val named: Opt<Str> = this.nativeSymbols.get(name)
            if (named.hasValue()) {
                names.insert(named.value(), true)
            }
        }
        if (xmlKind(callee) == AstNodeCategory.ExprMember) {
            val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (xmlKind(recv) == AstNodeCategory.ExprName) {
                val symbol: Str = this.staticCallSymbol(
                    xmlAttr(recv, AstNodeAttributeKind.Name), name
                )
                if (symbol != "") {
                    names.insert(symbol, true)
                }
            }
        }
        // A function passed by *name* (a comparator, `items.sort(compareLessThan)`) is a
        // bare name in an argument position, not a call - but it is reached the same way,
        // so its own prelude body has to be emitted or the emitted C++ calls a function
        // nothing declared.
        for (*arg in node.Children) {
            if (arg.name == AstNodeKind.Arg && xmlKind(arg) == AstNodeCategory.ExprName) {
                names.insert(xmlAttr(arg, AstNodeAttributeKind.Name), true)
            }
        }
    }
    this.collectTypeNames(node)
    var i: Int = 0
    while (i < node.Children.count()) {
        this.collectNames(node.Children[i], names)
        i = i + 1
    }
}

// The resources pool with the program's literals (`specs/resources.md`): the list already
// arrives spelled as C++ literals (`resources.resStoredLiterals`), so pooling is an append.
fun Emitter.collectResourceLiterals(): Unit {
    var i: Int = 0
    while (i < this.resourceStored.size()) {
        this.literals.add(this.resourceStored[i])
        i = i + 1
    }
}

// The resource table: a string-table index per key and per value, and the installer that
// hands them to `Resources` at start-up. Written after the string table, which it reads.
fun Emitter.emitResourceTable(): Unit {
    if (this.resourceStored.size() == 0) {
        return
    }
    var indices: List<Int> = List<Int>()
    for (*text in this.resourceStored) {
        indices.append(this.literals.indexOf(text))
    }
    this.line(0, "// The resources the compiler read from `_res.md` files (specs/resources.md):")
    this.line(0, "// string-table indices, key then value, and the one installer that builds")
    this.line(0, "// them into the program's `Resources` table before `main`.")
    this.line(0, fmtStr("static const Int __sm_resourceIndex[] = |;", cgIntListText(indices)))
    this.line(
        0,
        fmtStr(
            "static const Int __sm_resourceCount = |;",
            (this.resourceStored.size() / 2).toString()
        )
    )
    this.line(0, "namespace {")
    this.line(1, "struct __SmResourceInit {")
    this.line(2, "__SmResourceInit() {")
    this.line(
        3,
        "Resources::install(__sm_stringTable, __sm_resourceIndex, __sm_resourceCount);"
    )
    this.line(2, "}")
    this.line(1, "} __sm_resourceInit;")
    this.line(0, "}")
    this.line(0, "")
}

// One pool and two run-length encoded indexes, expanded and decoded before `main` runs
// (`impl_specs/rtl-abi.md`, "String literals"): each index is "what to subtract from the
// previous value" with an implicit 0 before the first entry (`cgRunLengthEncode`,
// `strtable.hpp`). The `static_assert` is the check that the pool and the lengths agree.
fun Emitter.emitStringTable(): Unit {
    if (this.literals.count() == 0) {
        return
    }
    val count: Int = this.literals.count()

    // `value[i] = value[i-1] - series[i]`, with an implicit 0 before the first entry. The
    // offset increment is the previous entry's length while no two literals share text.
    var starts: List<Int> = List<Int>()
    var lengths: List<Int> = List<Int>()
    var total: Int = 0
    var previousStart: Int = 0
    var previousLength: Int = 0
    var i: Int = 0
    while (i < count) {
        val length: Int = cgLiteralByteLength(this.literals.entry(i))
        val start: Int = previousLength
        starts.append(previousStart - start)
        lengths.append(previousLength - length)
        previousStart = start
        previousLength = length
        total = total + length
        i = i + 1
    }
    val startStream: List<Int> = cgRunLengthEncode(starts)
    val lengthStream: List<Int> = cgRunLengthEncode(lengths)
    var worst: Int = 0
    for (value in startStream) {
        if (cgMagnitudeOf(value) > worst) {
            worst = cgMagnitudeOf(value)
        }
    }
    for (value in lengthStream) {
        if (cgMagnitudeOf(value) > worst) {
            worst = cgMagnitudeOf(value)
        }
    }
    var element: Str = "Int"
    if (worst <= 32767) {
        element = "Int16"
    }

    this.line(0, "// The program's string literals: one pool, and two run-length encoded index")
    this.line(0, "// series (offsets as deltas, then lengths), each as what to subtract from the")
    this.line(0, "// previous value; strtable.hpp has the stream format.")
    this.line(0, fmtStr("static const Int __sm_stringCount = |;", count.toString()))
    this.line(0, "static const char __sm_stringPool[] =")

    // The literals again, adjacent, a space between so they stay separate tokens; wrapped
    // short of the standard's 65 536-character logical source line limit.
    var packed: Str = "    "
    i = 0
    while (i < count) {
        val literal: Str = this.literals.entry(i)
        if (packed.size() + 1 + literal.size() > 60000) {
            this.line(0, packed)
            packed = "    "
        }
        packed.appendStr(literal)
        packed.append(' ')
        i = i + 1
    }
    this.line(0, packed)
    this.line(0, ";")
    this.line(
        0,
        fmtStr(
            "static const | __sm_stringStarts[] = |;",
            element,
            cgIntListText(startStream)
        )
    )
    this.line(
        0,
        fmtStr(
            "static const | __sm_stringLens[] = |;",
            element,
            cgIntListText(lengthStream)
        )
    )
    this.line(
        0,
        fmtStr(
            "static_assert(sizeof(__sm_stringPool) - 1 == |, \"the string pool and its length index disagree\");",
            total.toString()
        )
    )
    this.line(0, "static StrView __sm_stringTable[__sm_stringCount];")
    this.line(0, "static struct __SmStringTableInitType {")
    this.line(1, "__SmStringTableInitType() {")
    this.line(2, "Int starts[__sm_stringCount];")
    this.line(2, "Int lens[__sm_stringCount];")
    this.line(2, "simse_strTableExpand(__sm_stringStarts, starts, __sm_stringCount);")
    this.line(2, "simse_strTableExpand(__sm_stringLens, lens, __sm_stringCount);")
    this.line(2, "simse_strTableDecode(__sm_stringPool, starts, lens, __sm_stringTable,")
    this.line(3, "__sm_stringCount);")
    this.line(1, "}")
    this.line(0, "} __sm_stringTableInit;")
    this.line(0, "")
}

// Whether a prelude body is one the program reaches: its name is called, and - for an
// extension - the program names the receiver's type too (impl_specs/for.md).
fun Emitter.reachesPreludeBody(fn: *CgFn): Bool {
    if (!fn.hasBody) {
        return false
    }
    val name: Str = fn.name
    if (!this.referencedNames.has(name)) {
        return false
    }
    val receiverName: Str = this.outerTypeName(fn.receiver)
    if (receiverName == "") {
        return true
    }
    // A receiver that is the function's own type parameter says nothing - any type can be one.
    if (xmlIsTypeParam(receiverName, fn.templateParams)) {
        return true
    }
    if (this.referencedTypes.has(receiverName)) {
        return true
    }
    // The name is called but no body of it names its receiver's type (`"".isEmpty()` need
    // never name `Str`), so the whole group is emitted - the body the call reaches must exist.
    return !this.preludeReceiverNamed(name)
}

// Whether any function named `name` is one `emitFunctions` will actually write: a program's own
// declaration always, a prelude one only when the program reaches its body. The coloring uses
// this so a prelude `suspend fun` a program never calls (`tasksSpawn`) does not pull the task
// runtime in just by being declared `suspend`.
fun Emitter.asyncEmitted(name: *Str): Bool {
    for (*fn in this.functions) {
        if (fn.name != *name) {
            continue
        }
        if (!fn.prelude) {
            return true
        }
        if (this.reachesPreludeBody(fn)) {
            return true
        }
    }
    return false
}

// Whether any prelude body of `name` has its receiver's type referenced: the per-overload
// half of the rule above, which tells `List`'s `iter` from `Span`'s.
fun Emitter.preludeReceiverNamed(name: *Str): Bool {
    for (*other in this.functions) {
        if (!other.prelude || !other.hasBody) {
            continue
        }
        if (other.name != name) {
            continue
        }
        val otherReceiver: Str = this.outerTypeName(other.receiver)
        if (otherReceiver != "" && this.referencedTypes.has(otherReceiver)) {
            return true
        }
    }
    return false
}

// The type names that declare an `initByValue` extension. A construction of one is a call
// the lowering turns into a setter, so the AST walk (below) records the convention's name
// from the constructor call itself - otherwise the prelude body a synthesized setter reaches
// would never be emitted.
fun Emitter.collectInitByValueTypes(): Unit {
    for (*fn in this.functions) {
        if (fn.name == "initByValue") {
            val recv: Str = this.outerTypeName(fn.receiver)
            if (recv != "") {
                this.initByValueTypes.insert(recv, true)
            }
        }
    }
    val exts: *List<CgNativeExt> = this.nativeExtensions.getPtr("initByValue")
    if (exts != null) {
        for (*ext in exts) {
            val recv: Str = this.outerTypeName(ext.receiver)
            if (recv != "") {
                this.initByValueTypes.insert(recv, true)
            }
        }
    }
}

// Fills `referencedNames` and `referencedTypes` from the program, never from the prelude's
// own unused bodies, then closes them over the prelude it reaches: an emitted body may
// call another, and a native's signature names the types a call reaches.
fun Emitter.collectProgramNames(): Unit {
    this.collectInitByValueTypes()
    for (*input in this.inputs) {
        if (!input.prelude) {
            this.collectNames(input.module, *this.referencedNames)
        }
    }
    var changed: Bool = true
    while (changed) {
        changed = false
        val namesBefore: Int = this.referencedNames.size()
        val typesBefore: Int = this.referencedTypes.size()
        var f: Int = 0
        while (f < this.functions.size()) {
            val fn: *CgFn = *this.functions[f]
            f = f + 1
            if (!fn.prelude) {
                continue
            }
            if (fn.hasBody) {
                if (!this.reachesPreludeBody(fn)) {
                    continue
                }
                this.collectNames(fn.decl, *this.referencedNames)
                continue
            }
            if (!this.referencedNames.has(fn.name)) {
                continue
            }
            this.collectTypeNames(fn.receiver)
            this.collectTypeNames(xmlChildPtr(fn.decl, AstNodeKind.ReturnType))
            val params: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
            for (*param in params) {
                this.collectTypeNames(xmlChildPtr(param, AstNodeKind.Type))
            }
        }
        if (this.referencedNames.size() != namesBefore
            || this.referencedTypes.size() != typesBefore
        ) {
            changed = true
        }
    }
}
