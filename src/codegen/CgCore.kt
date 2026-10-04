// CgCore.kt
//
// The emitter's core: diagnostics, the source-map line, the node builders it makes for
// itself, the name/package tables, and the collection pass. Extension methods on
// `Emitter` (Codegen.kt).

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources


fun Emitter.fail(posNode: *AstXmlNode, message: *Str): Unit {
    if (this.failed) {
        return
    }
    this.failed = true
    // A node the IL reconstructed carries no position; the declaration being emitted is
    // the closest thing to it that a reader can act on.
    var at: *AstXmlNode = posNode
    if (xmlIsEmpty(at) || xmlLine(at) == 0) {
        at = * this.curDecl
    }
    val curFileText: Str = this.curFile
    val xmlLineText: Str = xmlLine(at).toString()
    val xmlColumnText: Str = xmlColumn(at).toString()
    this.error = `@curFileText:@xmlLineText:@xmlColumnText: @message`
}

fun Emitter.line(level: Int, text: Str): Unit {
    // In place into the current section: appending never re-copies the accumulated
    // output, which would be quadratic in the size of the generated file.
    this.sections.appendLine(cgIndent(level), text)
}

// The amalgamation's source map, one comment per emitted declaration: the *file* a body
// came from, not the line. The line made the comment move whenever any body above it moved
// a line, so a small edit to one file rewrote thousands of lines of the emitted C++ (and of
// every `expected.cpp` golden); the file alone is what a reader of the one emitted file
// needs, and it keeps the amalgamation stable across edits that do not change a declaration.
fun Emitter.sourceComment(posNode: *AstXmlNode): Unit {
    // A prelude body is the compiler's own RTL, not the program being built: naming its
    // source would put a machine-specific path in the user's file.
    if (this.curPrelude) {
        return
    }
    this.line(0, "// " + this.curFile)
}

fun Emitter.namedTypeExpr(name: *Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

fun Emitter.genericTypeExpr(name: *Str, args: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeGeneric, List<AstNodeAttribute>(), args.toArray())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// The receiver type of a class method: `Name<A, B>` for a generic class. Each argument
// takes the `TypeArg` role a written one has, so the receiver unifies with a concrete
// `Name<Int>` (`unifyType` reads the arguments by role, and a `Type`-roled one would look
// like a receiver with no arguments at all).
fun Emitter.classReceiver(decl: *AstXmlNode): AstXmlNode {
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    if (typeParams.size() == 0) {
        return this.namedTypeExpr(xmlAttr(decl, AstNodeAttributeKind.Name))
    }
    var args: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < typeParams.size()) {
        var arg: AstXmlNode = this.namedTypeExpr(typeParams[i])
        arg.name = AstNodeKind.TypeArg
        args.append(arg)
        i = i + 1
    }
    return this.genericTypeExpr(xmlAttr(decl, AstNodeAttributeKind.Name), args)
}

// The class's `unInit`, if it declares one: the method that is the type's C++ destructor
// (`emitUninit`). Empty for a class without one.
fun Emitter.cgUninitMethod(decl: *AstXmlNode): AstXmlNode {
    for (*member in xmlChildren(decl, AstNodeKind.Function)) {
        if (xmlAttr(member, AstNodeAttributeKind.Name) == "unInit") {
            return member
        }
    }
    return xmlEmptyNode()
}

fun Emitter.addFunction(
    decl: *AstXmlNode,
    receiver: *AstXmlNode,
    file: *Str,
    templateParams: *List<Str>,
    prelude: Bool,
    packageName: *Str,
    isMethod: Bool
): Unit {
    // Read once here rather than on every lookup walk (`CgFn` documents why).
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val isNative: Bool = xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true"
    val isPure: Bool = xmlAttr(decl, AstNodeAttributeKind.IsPure) == "true"
    val index: Int = this.functions.size()
    this.functions.append(
        CgFn(
            decl,
            receiver,
            file,
            templateParams,
            prelude,
            packageName,
            isMethod,
            name,
            isNative,
            xmlAttr(decl, AstNodeAttributeKind.HasBody) == "true",
            isPure,
            xmlCount(decl, AstNodeKind.Param) - semReceiverParams(decl),
            ""
        )
    )
    // The name index beside the list, and the resolution tables the call sites read
    // instead of walking `functions` (`Emitter`'s field comment).
    val named: *List<Int> = this.functionsByName.getPtr(name)
    if (named != null) {
        named.append(index)
    } else {
        var fresh: List<Int> = List<Int>()
        fresh.append(index)
        this.functionsByName.insert(name, fresh)
    }
    if (!isNative) {
        this.plainFunctionNames.insert(name, true)
        if (!isMethod && !this.functionPackages.has(name)) {
            this.functionPackages.insert(name, packageName)
        }
    }
    if (!xmlIsEmpty(receiver)) {
        this.receiverFnNames.insert(name, true)
        if (semMachineReceiver(receiver)) {
            this.machineReceiverFnNames.insert(name, true)
        }
    }
    if (isPure) {
        this.pureCallees.insert(name, true)
    }
}

fun Emitter.addNativeExt(name: *Str, ext: *CgNativeExt): Unit {
    val existing: *List<CgNativeExt> = this.nativeExtensions.getPtr(name)
    if (existing != null) {
        existing.append(ext)
    } else {
        var fresh: List<CgNativeExt> = List<CgNativeExt>()
        fresh.append(ext)
        this.nativeExtensions.insert(name, fresh)
    }
}

// Every declaration carries its package's prefix: `rtl` (the built-in namespace,
// specs/modules.md) is bare, and every other package gets `ns<index>_`. The indices
// are assigned in sorted package order, so numbering never depends on discovery order.

fun Emitter.inputPackage(input: *CgInput): Str {
    return xmlAttr(input.module, AstNodeAttributeKind.Package)
}

fun Emitter.collectPackages(): Unit {
    var names: List<Str> = List<Str>()
    var i: Int = 0
    for (*input in this.inputs) {
        val pkg: Str = this.inputPackage(input)
        // `rtl` is the built-in namespace and an empty package a programmatically
        // built module; neither is indexed, so neither is ever prefixed.
        if (pkg != "rtl" && pkg != "" && !names.contains(pkg)) {
            names.append(pkg)
        }
    }
    names.sort(compareLessThan)
    var next: Int = 1
    i = 0
    while (i < names.size()) {
        this.nsPrefixes.insert(names[i], `ns@(next)_`)
        next = next + 1
        i = i + 1
    }
}

// The prefix of a package; empty for `rtl` and for a name no package can be
// attributed to.
fun Emitter.nsPrefix(packageName: *Str): Str {
    val prefix: *Str = this.nsPrefixes.getPtr(packageName)
    if (prefix != null) {
        return *prefix
    }
    return ""
}

fun Emitter.qualify(packageName: *Str, name: *Str): Str {
    return this.nsPrefix(packageName) + name
}

// The package a declared type (data class, enum, typealias) came from.
fun Emitter.typePackage(name: *Str): Str {
    val packageName: *Str = this.typePackages.getPtr(name)
    if (packageName != null) {
        return *packageName
    }
    return ""
}

// The package of the plain (non-native) function `name`, or "". A method is not a
// plain function, so a call by name cannot resolve to one. The table is the first such
// declaration in collection order (`addFunction`), which is the declaration the walk
// that used to run here returned.
fun Emitter.functionPackage(name: *Str): Str {
    val packageName: *Str = this.functionPackages.getPtr(name)
    if (packageName != null) {
        return *packageName
    }
    return ""
}

// The declared type of a file-level static, for expression inference.
fun Emitter.staticType(name: *Str): AstXmlNode {
    val entry: *CgStatic = this.staticsByName.getPtr(name)
    if (entry != null) {
        return xmlChild(entry.decl, AstNodeKind.Type)
    }
    return xmlEmptyNode()
}

fun Emitter.collect(): Unit {
    this.collectPackages()
    // The pointer form on both lists: a declaration is a value, so the index walk would
    // copy one per iteration.
    for (*input in this.inputs) {
        val pkg: Str = this.inputPackage(input)
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
        // A `protocol` is a signature for the checker, not a declaration to emit: no struct,
        // no prototype, no body. Collecting it here is what the dispatch generation
        // (`CgProtocol.kt`) reads to find a constrained call's overload set.
        if (xmlIsProtocolDecl(decl)) {
            val protocolName: Str = xmlAttr(decl, AstNodeAttributeKind.Protocol)
            this.protocols.insert(protocolName, decl)
            this.protocolPackages.insert(protocolName, pkg)
            continue
        }
        val declName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        if (decl.name == AstNodeKind.Var) {
            // A file-level static: storage and an initializer for the generated pass
            // (specs/statics.md). A prelude static (the resources table's storage) is
            // collected too - the rules type it the same - and its emission is
            // reach-gated instead (`Emitter.staticReached`).
            val entry: CgStatic = CgStatic(decl, pkg, input.fileName, input.prelude)
            this.statics.append(entry)
            this.staticsByName.insert(declName, entry)
            continue
        }
        if (decl.name == AstNodeKind.Function) {
            if (xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true") {
                // A declaration whose implementation an attribute selects
                // (impl_specs/generators.md); a generator is handed data, never an API.
                val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
                if (!sourceGenHas(generator)) {
                    this.fail(decl, `unknown source generator '@generator'`)
                    return
                }
                val declared: Res<Str> = sourceGenDeclare(decl, input.fileName, input.prelude)
                if (!declared.isOk()) {
                    this.fail(decl, declared.Error)
                    return
                }
                val symbol: Str = declared.Value
                this.nativeSymbols.insert(declName, symbol)
                // The C++ is elsewhere but linked in already, so the declaration is what
                // call sites need; a generator whose text is emitted declares the symbol
                // itself.
                if (sourceGenDeclaresPrototype(generator)) {
                    this.nativeDecls.append(CgNativeDecl(decl, input.fileName, symbol, input.prelude))
                }
                val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
                if (sourceGenRegistersReceiver(generator) && params.size() > 0
                    && xmlAttr(params[0], AstNodeAttributeKind.Name) == "this"
                ) {
                    val ext: CgNativeExt = CgNativeExt(
                        symbol, xmlChild(params[0], AstNodeKind.Type),
                        xmlChild(decl, AstNodeKind.ReturnType),
                        xmlTypeParamNames(decl),
                        xmlCount(decl, AstNodeKind.Param) - 1
                    )
                    this.addNativeExt(declName, ext)
                }
            }
            var recv: AstXmlNode = xmlEmptyNode()
            if (xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true") {
                recv = xmlChild(decl, AstNodeKind.Receiver)
            }
            this.addFunction(decl, recv, input.fileName, xmlTypeParamNames(decl), input.prelude, pkg, false)
            continue
        }
        this.types.insert(declName, decl)
        this.typePackages.insert(declName, pkg)
        if (decl.name == AstNodeKind.Enum) {
            this.enumNames.insert(declName, true)
        }
        if (decl.name == AstNodeKind.DataClass) {
            if (this.typeIsRaw(decl)) {
                continue
            }
            this.dataClassNames.insert(declName, true)
            val receiver: AstXmlNode = this.classReceiver(decl)
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            val classParams: List<Str> = xmlTypeParamNames(decl)
            for (*method in methods) {
                var methodParams: List<Str> = List<Str>()
                var p: Int = 0
                while (p < classParams.size()) {
                    methodParams.append(classParams[p])
                    p = p + 1
                }
                val methodTypeParams: List<Str> = xmlTypeParamNames(method)
                var q: Int = 0
                while (q < methodTypeParams.size()) {
                    methodParams.append(methodTypeParams[q])
                    q = q + 1
                }
                this.addFunction(method, receiver, input.fileName, methodParams, input.prelude, pkg, true)
            }
        }
    }
    }
}

// The program-level facts the semantic step reads (sema/TypeInfer.kt), built from the
// tables `collect` filled; the nodes are shared, not copied.
fun Emitter.collectFacts(): SemFacts {
    val facts: SemFacts = semNewFacts()
    this.computeMachineSuffixes()
    this.fillFacts(facts)
    return facts
}

// A machine class is named after its creating function (`Str_splitIter_yieldable`), so two
// yielding overloads of one name would claim one class and the second factory would write
// fields the first typed (`splitIter(StrView)` / `splitIter(Char)`). For an overloaded name
// the parameter types are appended (`semMachineParamSuffix`); the field is what
// `emitFunction` and `semMachineType` both read, so a slot's class is the struct's name. Runs
// before anything reads a suffix; idempotent, so a second `collectFacts` recomputes it.
fun Emitter.computeMachineSuffixes(): Unit {
    for (*fn in this.functions) {
        fn.machineSuffix = ""
        if (!semMachineYielder(fn.decl)) {
            continue
        }
        val outer: Str = semOuterTypeName(fn.receiver)
        var count: Int = 0
        val named: *List<Int> = this.functionsByName.getPtr(fn.name)
        if (named == null) {
            continue
        }
        for (other in named) {
            val candidate: *CgFn = *this.functions[other]
            if (semMachineYielder(candidate.decl)
                && semOuterTypeName(candidate.receiver) == outer
            ) {
                count = count + 1
            }
        }
        if (count > 1) {
            fn.machineSuffix = semMachineParamSuffix(fn.decl)
        }
    }
}

fun Emitter.fillFacts(facts: *SemFacts): Unit {
    val typeNames: List<Str> = this.types.keys()
    var t: Int = 0
    while (t < typeNames.size()) {
        val node: *AstXmlNode = this.types.getPtr(typeNames[t])
        facts.types.insert(typeNames[t], node)
        t = t + 1
    }
    val enumTypeNames: List<Str> = this.enumNames.keys()
    var e: Int = 0
    while (e < enumTypeNames.size()) {
        facts.enumNames.insert(enumTypeNames[e], true)
        e = e + 1
    }
    for (*fn in this.functions) {
        val index: Int = facts.functions.size()
        facts.functions.append(
            semFnFact(
                copy(fn.decl), copy(fn.receiver), fn.templateParams, fn.name,
                fn.packageName, fn.isNative, fn.machineSuffix
            )
        )
        // The name index beside the list (`SemFacts.functionsByName`).
        val named: *List<Int> = facts.functionsByName.getPtr(fn.name)
        if (named != null) {
            named.append(index)
        } else {
            var fresh: List<Int> = List<Int>()
            fresh.append(index)
            facts.functionsByName.insert(fn.name, fresh)
        }
    }
    val extensionNames: List<Str> = this.nativeExtensions.keys()
    var x: Int = 0
    while (x < extensionNames.size()) {
        val overloads: *List<CgNativeExt> = this.nativeExtensions.getPtr(extensionNames[x])
        var mapped: List<SemExtFact> = List<SemExtFact>()
        for (*ext in overloads) {
            mapped.append(SemExtFact(copy(ext.receiver), copy(ext.returnType), ext.typeParams))
        }
        facts.nativeExtensions.insert(extensionNames[x], mapped)
        x = x + 1
    }
    val staticNames: List<Str> = this.staticsByName.keys()
    var s: Int = 0
    while (s < staticNames.size()) {
        val entry: *CgStatic = this.staticsByName.getPtr(staticNames[s])
        facts.statics.insert(staticNames[s], xmlChild(entry.decl, AstNodeKind.Type))
        s = s + 1
    }
}

fun Emitter.setActiveTypeParams(params: *List<Str>): Unit {
    this.activeTypeParams.clear()
    var i: Int = 0
    while (i < params.size()) {
        this.activeTypeParams.insert(params[i], true)
        i = i + 1
    }
}

fun Emitter.templateClause(params: *List<Str>): Str {
    if (params.size() == 0) {
        return ""
    }
    var parts: List<Str> = List<Str>()
    var i: Int = 0
    while (i < params.size()) {
        parts.append("class " + params[i])
        i = i + 1
    }
    val cgJoinText: Str = cgJoin(parts, ", ")
    return `template <@cgJoinText>`
}

// The C++ template parameter standing for a machine receiver's class: the receiver's class
// is the *caller's* (`Span_iter_yieldable<Int>`), so a function over a machine pattern
// takes it as a parameter and the call site spells it (`emitFunction`, `ilCallNode`).
fun smMachineIterName(): Str {
    return "_SmIter"
}

// The C++ template parameters a function is emitted with: its own, then the machine's class
// when its receiver is a machine pattern (`this.machineIter`).
fun Emitter.fnTemplateParams(fn: *CgFn): List<Str> {
    var params: List<Str> = List<Str>()
    var i: Int = 0
    while (i < fn.templateParams.size()) {
        params.append(fn.templateParams[i])
        i = i + 1
    }
    if (this.machineIter) {
        params.append(smMachineIterName())
    }
    return params
}

fun Emitter.typeArgsString(baseName: *Str, args: *List<AstXmlNode>): Str {
    var rendered: List<Str> = List<Str>()
    for (*arg in args) {
        rendered.append(this.type(arg))
    }
    if (baseName == "SmallVector" && rendered.size() == 2) {
        val first: Str = rendered[0]
        rendered[0] = rendered[1]
        rendered[1] = first
    }
    return cgJoin(rendered, ", ")
}

fun Emitter.typeName(name: *Str, posNode: *AstXmlNode): Str {
    if (name == "Unit") {
        return "void"
    }
    if (this.activeTypeParams.has(name)) {
        return name
    }
    // A declared type shadows an RTL type *name*: a compiler-side view type of the same
    // name is a different type. The RTL's own prelude types keep their C++ spelling.
    if (this.types.has(name)) {
        val packageName: Str = this.typePackage(name)
        if (packageName == "rtl") {
            return name
        }
        // A *materialized* type (`@SmGen("cpp")`/`@SmGen("res")`) keeps its bare name in any
        // package: its C++ is hand-written and cannot know the per-compilation `nsN_` prefix,
        // so a module can carry one under its declared name (`streams`' `FileStream`) exactly
        // as the prelude does.
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl != null && this.typeIsRaw(decl)) {
            return name
        }
        return this.qualify(packageName, name)
    }
    if (semIsRtlTypeName(name)) {
        return name
    }
    this.fail(posNode, `unsupported type '@name'`)
    return "/*unsupported*/"
}

// The target of a non-generic `typealias` a spelling resolves through: the alias is a
// language-level name, and the emitted C++ spells what it names (`StrView` is
// `Span<Char>` everywhere), so no `using` has to be carried for it. A *generic* alias keeps
// its name (the emitted `using` template) - substituting its parameters here is a job of its
// own. Empty when the type is not such an alias.
fun Emitter.typeAliasTarget(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var resolved: Bool = false
    var guard: Int = 0
    while (guard < 64 && xmlKind(current) == AstNodeCategory.TypeNamed) {
        guard = guard + 1
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null || decl.name != AstNodeKind.TypeAlias
            || xmlTypeParamNames(decl).size() > 0
        ) {
            break
        }
        val target: AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            break
        }
        current = target
        resolved = true
    }
    if (!resolved) {
        return xmlEmptyNode()
    }
    return current
}

fun Emitter.type(typeExpr: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(typeExpr)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return xmlAttr(typeExpr, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            val aliasTarget: AstXmlNode = this.typeAliasTarget(typeExpr)
            if (!xmlIsEmpty(aliasTarget)) {
                return this.type(aliasTarget)
            }
            return this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr)
        }

        AstNodeCategory.TypeGeneric -> {
            val typeNameText: Str = this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr)
            val typeArgsStringText: Str = this.typeArgsString(
                    xmlAttr(typeExpr, AstNodeAttributeKind.Name),
                    xmlChildren(typeExpr, AstNodeKind.TypeArg)
                )
            return `@typeNameText<@typeArgsStringText>`
        }

        AstNodeCategory.TypeReference -> {
            val inner: *AstXmlNode = xmlChildPtr(typeExpr, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                // `Ref<void>` is the fallback for a reference with no inner type; nothing
                // in the tree produces one.
                return "Ref<void>"
            }
            // `&T` is the counted reference, spelled `Ref<T>` (ref.hpp): `std::shared_ptr`
            // or `SmRef`, chosen at build time, so emitted text does not depend on it.
            val typeText: Str = this.type(inner)
            return `Ref<@typeText>`
        }

        AstNodeCategory.TypePointer -> {
            val inner: *AstXmlNode = xmlChildPtr(typeExpr, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return "void*"
            }
            // `RawPtr` is `*Unit` (the parser's desugar, specs/memory-model.md): the C++
            // `RawPtr` alias of types.hpp, so the generated text keeps the language's name.
            if (xmlIsRawPtrType(typeExpr)) {
                return "RawPtr"
            }
            return this.type(inner) + "*"
        }

        AstNodeCategory.TypeFunction -> {
            val retNode: *AstXmlNode = xmlChildPtr(typeExpr, AstNodeKind.ReturnType)
            var ret: Str = "void"
            if (!xmlIsEmpty(retNode)) {
                ret = this.type(retNode)
            }
            val paramNodes: List<AstXmlNode> = xmlChildren(typeExpr, AstNodeKind.ParamType)
            var params: List<Str> = List<Str>()
            for (*paramNode in paramNodes) {
                params.append(this.type(paramNode))
            }
            val cgJoinText2: Str = cgJoin(params, ", ")
            return `Func<@ret(@cgJoinText2)>`
        }

        AstNodeCategory.TypeYield -> {
            // A machine (`..T`): the class the lowering built for the creating function.
            // It is registered by the emitter, not declared by the program, so `typeName`
            // cannot spell it - hence the qualification here (`semMachineType`). A machine
            // *pattern* inside a machine receiver's own function spells the template
            // parameter the caller's machine arrived as.
            val name: Str = xmlAttr(typeExpr, AstNodeAttributeKind.Name)
            if (name == "") {
                if (this.machineIter) {
                    return smMachineIterName()
                }
                this.fail(typeExpr, "unsupported: a machine's type has no class to spell")
                return "/*machine*/"
            }
            val className: Str = this.qualify(xmlAttr(typeExpr, AstNodeAttributeKind.Package), name)
            val args: List<AstXmlNode> = xmlChildren(typeExpr, AstNodeKind.TypeArg)
            if (args.size() == 0) {
                return className
            }
            val typeArgsStringText2: Str = this.typeArgsString(name, args)
            return `@className<@typeArgsStringText2>`
        }
    }
    return "/*unsupported*/"
}

fun Emitter.kindOf(typeExpr: *AstXmlNode): NameKind {
    val kind: AstNodeCategory = xmlKind(typeExpr)
    if (kind == AstNodeCategory.TypeReference) {
        return NameKind.Shared
    }
    if (kind == AstNodeCategory.TypePointer) {
        return NameKind.Pointer
    }
    if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeExpr, AstNodeAttributeKind.Name) == "PList") {
        return NameKind.Shared
    }
    return NameKind.Value
}
