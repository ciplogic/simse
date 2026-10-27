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
import parser


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
    for (typeParam in typeParams) {
        var arg: AstXmlNode = this.namedTypeExpr(typeParam)
        arg.name = AstNodeKind.TypeArg
        args.append(arg)
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
        if (pkg != "rtl" && pkg != "" && pkg !in names) {
            names.append(pkg)
        }
    }
    names.sort(compareLessThan)
    var next: Int = 1
    i = 0
    while (i < names.size()) {
        this.nsPrefixes.insert(names[i], `ns@(next)_`)
        next++
        i++
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
            val baseName: Str = semaBaseName(decl)
            if (baseName != "") {
                this.typeBases.insert(declName, baseName)
            }
            if (this.typeIsRaw(decl)) {
                continue
            }
            this.dataClassNames.insert(declName, true)
            val receiver: AstXmlNode = this.classReceiver(decl)
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            val classParams: List<Str> = xmlTypeParamNames(decl)
            for (*method in methods) {
                var methodParams: List<Str> = List<Str>()
                for (classParam in classParams) {
                    methodParams.append(classParam)
                }
                val methodTypeParams: List<Str> = xmlTypeParamNames(method)
                for (methodTypeParam in methodTypeParams) {
                    methodParams.append(methodTypeParam)
                }
                this.addFunction(method, receiver, input.fileName, methodParams, input.prelude, pkg, true)
            }
        }
    }
    }
    this.collectSretFns()
    this.collectFreshHandles()
}

// Whether a return type is one the out-parameter ABI pays for: a *class* value (a user
// `data class`, `Str`, a container, `Opt`/`Res`) whose return crosses the boundary as a
// copy. A scalar returns in a register, a `Span`/`Array` view copies two words, a pointer
// or a callable is one word - none of them is worth the out parameter.
fun Emitter.sretHeavyReturn(node: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.TypeGeneric) {
        val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        return name != "Span" && name != "Array"
    }
    if (kind != AstNodeCategory.TypeNamed) {
        return false
    }
    val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
    return name == "Str" || this.types.has(name)
}

// One function declaration's eligibility for the out-parameter ABI: a plain (non-generic,
// non-operator, non-suspending, non-machine) function with a body, whose C++ the emitter
// writes here, returning one heavy value - and whose name no code holds as a *value* (a
// comparator, a `Func<...>` argument), because those calls must keep the C++ signature the
// callable's type expects.
fun Emitter.sretEligible(fn: *CgFn): Bool {
    val decl: *AstXmlNode = *fn.decl
    if (!fn.hasBody || fn.isNative) {
        return false
    }
    // Prelude functions keep their authored signature: hand-written C++ (a `_res.md`
    // section, a header) may call them, and it is compiled against the by-value form -
    // the same guard the borrow pass makes with `bpCppCalled`.
    if (fn.prelude) {
        return false
    }
    // Methods are eligible too: their C++ is the free function `qualify(pkg, name)`
    // taking the receiver first, and the call-site rewrite spells the by-value text with
    // the destination inserted before the arguments (`IlEmit.ilSretKey` resolves the call).
    if (xmlAttr(decl, AstNodeAttributeKind.IsOperator) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsSuspend) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsUnionGenerated) == "true"
    ) {
        return false
    }
    if (fn.name == "main" || fn.name.endsWith("_invoke")) {
        return false
    }
    if (xmlCount(decl, AstNodeKind.TypeParam) > 0) {
        return false
    }
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (xmlIsEmpty(returnNode) || !this.sretHeavyReturn(returnNode)) {
        return false
    }
    return true
}

// Every name under `node`, whatever position: the static-initializer walk, whose callees
// are spelled by a path the IL rewrite does not reach.
fun Emitter.sretMarkAllNames(node: *AstXmlNode, used: *Dictionary<Str, Bool>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        used.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    for (*child in node.Children) {
        this.sretMarkAllNames(child, used)
    }
}

// A bare name anywhere in the program or prelude that is not a call's callee: a function
// held as a value. Held functions keep the by-value signature (`std::function` and the
// emitter's forwarding overloads convert against it), so the ABI cannot change under them.
fun Emitter.sretMarkValueUses(node: *AstXmlNode, used: *Dictionary<Str, Bool>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName && node.name != AstNodeKind.Callee) {
        used.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    for (*child in node.Children) {
        this.sretMarkValueUses(child, used)
    }
}

// A `&T`-returning free function whose every return builds the value it hands back -
// `return &Ctor(...)` - is a *fresh-handle* one: the box exists only because the authored
// ABI returns a handle. For one of those the emitter also writes a value twin
// `void <name>_v(T* _sm_out, ...)` - the same body with every construction stored into the
// caller's storage - which a call whose handle never escapes takes
// (`PromoteRefs.ilPromoteFind`'s fresh-call arm rewrites the call); the authored `Ref<T>`
// ABI stays for every other call, so both spellings of one function live in one program.
fun Emitter.freshHandleEligible(fn: *CgFn): Bool {
    val decl: *AstXmlNode = *fn.decl
    if (!fn.hasBody || fn.isNative || fn.prelude) {
        return false
    }
    if (!xmlIsEmpty(fn.receiver)) {
        return false // methods take their receiver through the twin's free spelling; later
    }
    if (xmlAttr(decl, AstNodeAttributeKind.IsOperator) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsSuspend) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsUnionGenerated) == "true"
    ) {
        return false
    }
    if (fn.name == "main" || fn.name.endsWith("_invoke")) {
        return false
    }
    if (xmlCount(decl, AstNodeKind.TypeParam) > 0) {
        return false
    }
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (xmlIsEmpty(returnNode) || xmlKind(returnNode) != AstNodeCategory.TypeReference) {
        return false
    }
    // The payload must be a class the store can spell: a `union class` has no aggregate
    // form, and a payload with an `unInit` would run its destructor twice - the same
    // refusal the promotion makes at the call (`ilPromotePayloadRefused`).
    val inner: *AstXmlNode = xmlChildPtr(*returnNode, AstNodeKind.Inner)
    if (xmlIsEmpty(inner)) {
        return false
    }
    val innerName: Str = xmlAttr(*this.resolveAlias(*inner), AstNodeAttributeKind.Name)
    val innerDecl: *AstXmlNode = this.types.getPtr(innerName)
    if (innerDecl == null || innerDecl.name != AstNodeKind.DataClass) {
        return false
    }
    if (xmlAttr(*innerDecl, AstNodeAttributeKind.IsUnionClass) == "true") {
        return false
    }
    for (*member in xmlChildren(*innerDecl, AstNodeKind.Function)) {
        if (xmlAttr(member, AstNodeAttributeKind.Name) == "unInit") {
            return false
        }
    }
    val body: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return false
    }
    val returns: Int = this.freshReturnCount(body)
    return returns > 0
}

// The number of `return` statements under `node` when every one is `&Ctor(...)` and no
// lambda hides anywhere (its returns are its own, and a capture could hold the very
// handle the twin removes the box from); -1 the moment either refuses.
fun Emitter.freshReturnCount(node: *AstXmlNode): Int {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprLambda) {
        return -1
    }
    var count: Int = 0
    if (kind == AstNodeCategory.StmtReturn) {
        count = 1
        val value: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Value)
        if (xmlKind(value) != AstNodeCategory.ExprRef) {
            return -1
        }
        val built: *AstXmlNode = xmlChildPtr(value, AstNodeKind.Operand)
        if (xmlKind(built) != AstNodeCategory.ExprCall) {
            return -1
        }
        val callee: *AstXmlNode = xmlChildPtr(built, AstNodeKind.Callee)
        val calleeKind: AstNodeCategory = xmlKind(callee)
        if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprGenericName) {
            return -1
        }
    }
    for (*child in node.Children) {
        val sub: Int = this.freshReturnCount(child)
        if (sub < 0) {
            return -1
        }
        count = count + sub
    }
    return count
}

// The fresh-handle twins: eligibility over the collected functions, each twin registered
// in the out-parameter set under its own key (so a retargeted call resolves through
// `IlEmit`'s sret arm) and the `pkg|name` pair recorded for the promotion pass. A program
// that already declares the twin's name keeps the authored ABI only - a synthetic name
// must never capture a real declaration.
fun Emitter.collectFreshHandles(): Unit {
    this.freshHandles = Dictionary<Str, Bool>()
    if (epNoEscape()) {
        // The escape hatch: no twins at all, the authored box ABI everywhere - the
        // emitted C++ is what the structural rules alone allow.
        return
    }
    var taken: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*fn in this.functions) {
        taken.insert(fn.name, true)
    }
    for (*fn in this.functions) {
        if (!this.freshHandleEligible(fn)) {
            continue
        }
        val twinName: Str = fn.name + "_v"
        if (taken.has(twinName)) {
            continue
        }
        val returnNode: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        val inner: *AstXmlNode = xmlChildPtr(*returnNode, AstNodeKind.Inner)
        val savedFailed: Bool = this.failed
        val savedError: Str = this.error
        val text: Str = this.type(*inner)
        this.failed = savedFailed
        this.error = savedError
        if (this.failed || text == "") {
            continue
        }
        val twinKey: Str = this.qualify(fn.packageName, twinName)
        this.sretFns.insert(twinKey, true)
        this.sretRets.insert(twinKey, text)
        this.freshHandles.insert(fn.packageName + "|" + fn.name, true)
    }
}

// The out-parameter set, keyed by the qualified C++ name every call site's method table
// carries. A name joins only when *every* declaration of it is eligible - a caller cannot
// know which overload a call resolves to, so one ineligible twin keeps the name's ABI as
// written (`collectNames` fills `this.functions` before this runs).
fun Emitter.collectSretFns(): Unit {
    var used: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*input in this.inputs) {
        this.sretMarkValueUses(input.module, used)
        // A file-level static's initializer is spelled by the statics emitter, a path the
        // IL call rewrite does not touch: a name a static initializer *calls* keeps its
        // by-value ABI - every name in it, callee or not.
        for (*decl in xmlDecls(input.module)) {
            if (decl.name == AstNodeKind.Var) {
                val init: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Init)
                if (!xmlIsEmpty(init)) {
                    this.sretMarkAllNames(init, used)
                }
            }
        }
    }
    var byName: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var bareOf: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var retNodeOf: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*fn in this.functions) {
        val key: Str = this.qualify(fn.packageName, fn.name)
        if (!seen.has(key)) {
            seen.insert(key, true)
            byName.insert(key, this.sretEligible(fn))
            bareOf.insert(key, fn.name)
            val declReturn: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(declReturn)) {
                retNodeOf.insert(key, *declReturn)
            }
        } else {
            if (byName.has(key) && !this.sretEligible(fn)) {
                byName.insert(key, false)
            }
        }
    }
    val names: List<Str> = byName.keys()
    for (*name in names) {
        val eligible: *Bool = byName.getPtr(name)
        if (eligible == null || !*eligible) {
            continue
        }
        // A held value names the function unqualified (`items.sort(compareLessThan)`);
        // the set is keyed qualified, so the bare name rides along.
        val bare: *Str = bareOf.getPtr(name)
        if (bare == null || used.has(*bare)) {
            continue
        }
        this.sretFns.insert(name, true)
        // The text is spelled here, for the eligible name only: `type` on an ineligible
        // twin's return (a machine factory's, a generic's) is what this pass must never
        // run, its context is the collect walk's, not the declaration's.
        val retNode: *AstXmlNode = retNodeOf.getPtr(name)
        if (retNode != null && !xmlIsEmpty(*retNode)) {
            val savedFailed: Bool = this.failed
            val savedError: Str = this.error
            val text: Str = this.type(*retNode)
            this.failed = savedFailed
            this.error = savedError
            if (!this.failed && text != "") {
                this.sretRets.insert(name, text)
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
                count++
            }
        }
        if (count > 1) {
            fn.machineSuffix = semMachineParamSuffix(fn.decl)
        }
    }
}

fun Emitter.fillFacts(facts: *SemFacts): Unit {
    val typeNames: List<Str> = this.types.keys()
    for (typeName in typeNames) {
        val node: *AstXmlNode = this.types.getPtr(typeName)
        facts.types.insert(typeName, node)
    }
    val enumTypeNames: List<Str> = this.enumNames.keys()
    for (enumTypeName in enumTypeNames) {
        facts.enumNames.insert(enumTypeName, true)
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
    for (extensionName in extensionNames) {
        val overloads: *List<CgNativeExt> = this.nativeExtensions.getPtr(extensionName)
        var mapped: List<SemExtFact> = List<SemExtFact>()
        for (*ext in overloads) {
            mapped.append(SemExtFact(copy(ext.receiver), copy(ext.returnType), ext.typeParams))
        }
        facts.nativeExtensions.insert(extensionName, mapped)
    }
    val staticNames: List<Str> = this.staticsByName.keys()
    for (staticName in staticNames) {
        val entry: *CgStatic = this.staticsByName.getPtr(staticName)
        facts.statics.insert(staticName, xmlChild(entry.decl, AstNodeKind.Type))
    }
}

fun Emitter.setActiveTypeParams(params: *List<Str>): Unit {
    this.activeTypeParams.clear()
    for (param in params) {
        this.activeTypeParams.insert(param, true)
    }
}

fun Emitter.templateClause(params: *List<Str>): Str {
    if (params.size() == 0) {
        return ""
    }
    var parts: List<Str> = List<Str>()
    for (param in params) {
        parts.append("class " + param)
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
    for (templateParam in fn.templateParams) {
        params.append(templateParam)
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
        guard++
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
