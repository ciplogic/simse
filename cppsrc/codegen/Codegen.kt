// Codegen.kt
//
// The C++ emitter: the AstXmlNode AST (impl_specs/ast-xmlnode.md) goes in, one
// amalgamated translation unit comes out. An empty type node means "no/unknown
// type".

package codegen

import sema
import common
import linear
import optimizations
import profiling
import resources
import sourcegen

// One parsed input. `prelude` inputs are collected but emitted only when they carry a
// body (the RTL's declarations are natives, whose C++ is the header's).
data class CgInput(
    var fileName: Str,

    var module: AstXmlNode,
    var prelude: Bool
)

// A function/method to emit, with its receiver type (empty for plain functions).
// `package` picks the emitted-symbol prefix: `ns<index>_`, or none for `rtl`.
data class CgFn(
    var decl: AstXmlNode,

    var receiver: AstXmlNode,
    var file: Str,
    var templateParams: List<Str>,
    var prelude: Bool,
    var packageName: Str,
    var isMethod: Bool,

    // Read *once* at collection, not walked from the node on every lookup walk (every
    // call site tests these three on each candidate): a declaration does not change
    // while emission reads it.
    var name: Str,
    var isNative: Bool,
    var hasBody: Bool,

    // The `data` modifier: the writer's claim that the function is pure (no side effects,
    // the result a function of its arguments). `pureCallees` collects those names.
    var isPure: Bool
)

// A `native fun` declaration to emit once at the top (and call by symbol).
data class CgNativeDecl(
    var decl: AstXmlNode,

    var file: Str,
    var symbol: Str,
    var prelude: Bool
)

// An explicit-`this` native extension; the receiver pattern selects the overload.
data class CgNativeExt(
    var symbol: Str,

    var receiver: AstXmlNode,
    var returnType: AstXmlNode,
    var typeParams: List<Str>
)

// A file-level static (`Var`, specs/statics.md): storage plus an optional
// initializer, emitted under its package's prefix like any other declaration.
data class CgStatic(
    var decl: AstXmlNode,

    var packageName: Str,
    var file: Str
)


// How a name's storage is reached, for `.` vs `->`, `*x` vs `x.get()`, `copy`.
enum class NameKind { Value, Shared, Pointer }

// Reads the parts only; each is appended through the borrow the pointer `for` hands
// out (`appendStrPtr`), so no element is copied.
fun cgJoin(parts: *List<Str>, separator: *Str): Str {
    if (separator.size() == 1) {
        return cgJoinChar(parts, separator[0])
    }
    var out: Str = ""
    if (parts.size() == 0) {
        return out
    }
    out.reserve(cgJoinLength(parts, separator.size()))
    var first: Bool = true
    for (*part in parts) {
        if (!first) {
            out.appendStr(separator)
        }
        out.appendStrPtr(part)
        first = false
    }
    return out
}

fun cgJoinChar(parts: *List<Str>, separator: Char): Str {
    var out: Str = ""
    if (parts.size() == 0) {
        return out
    }
    out.reserve(cgJoinLength(parts, 1))
    var first: Bool = true
    for (*part in parts) {
        if (!first) {
            out.append(separator)
        }
        out.appendStrPtr(part)
        first = false
    }
    return out
}

fun cgJoinLength(parts: *List<Str>, separatorLen: Int): Int {
    val count: Int = parts.size()
    var len: Int = 0
    if (count > 1) {
        len = separatorLen * (count - 1)
    }
    for (*part in parts) {
        len += part.size()
    }
    return len
}

fun cgIndent(level: Int): Str {
    var out: Str = ""
    out.reserve(level * 4)
    var i: Int = 0
    while (i < level) {
        out.appendStr("    ")
        i = i + 1
    }
    return out
}

// The RTL type-name list lives with the semantics that share it: `semIsRtlTypeName`
// (sema/TypeInfer.kt).

fun cgUnquote(text: Str): Str {
    if (text.size() >= 2 && text[0] == '\"' && text[text.size() - 1] == '\"') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// One argument of a `@SmGen` attribute, from the comma-joined `GeneratorArgs`; an index
// outside the list is the empty string.
fun cgGeneratorArg(args: *Str, index: Int): Str {
    if (args.size() == 0 || index < 0) {
        return ""
    }
    val parts: List<Str> = args.split(",")
    if (index >= parts.size()) {
        return ""
    }
    return parts[index]
}

// Binary operator precedence for wrapping. The numbers are a scale, not levels - their
// order is what matters, and `12` is the postfix position (at or above it needs no
// parentheses); the parser's `binaryBindingPower` has the same order.
fun cgPrecedence(e: *AstXmlNode): Int {
    if (xmlKind(e) == AstNodeCategory.ExprBinary) {
        val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
        when (op) {
            "||" -> {
                return 1
            }

            "&&" -> {
                return 2
            }

            "==", "!=" -> {
                return 3
            }

            "<", ">", "<=", ">=" -> {
                return 4
            }

            "|" -> {
                return 5
            }

            "^" -> {
                return 6
            }

            "&" -> {
                return 7
            }

            "<<", ">>" -> {
                return 8
            }

            "+", "-" -> {
                return 9
            }

            "*", "/", "%" -> {
                return 10
            }
        }
        return 1
    }
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprUnary, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
            return 11
        }

        AstNodeCategory.ExprRef, AstNodeCategory.ExprCall, AstNodeCategory.ExprIndex,
        AstNodeCategory.ExprMember -> {
            return 12
        }
    }
    return 13
}

// Whether a top-level `main` takes the argv form: a single `List<Str>` parameter.
fun cgIsMainArgs(decl: *AstXmlNode): Bool {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (params.size() != 1) {
        return false
    }
    val paramType: *AstXmlNode = xmlChildPtr(params[0], AstNodeKind.Type)
    if (xmlIsEmpty(paramType)) {
        return false
    }
    if (xmlKind(paramType) != AstNodeCategory.TypeGeneric || xmlAttr(
            paramType,
            AstNodeAttributeKind.Name
        ) != "List"
    ) {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(paramType, AstNodeKind.TypeArg)
    if (args.size() != 1) {
        return false
    }
    return xmlKind(args[0]) == AstNodeCategory.TypeNamed && xmlAttr(args[0], AstNodeAttributeKind.Name) == "Str"
}

data class Emitter(
    var inputs: List<CgInput>,

// The resources the program carries, each key and value already spelled as the C++ literal
// holding its bytes (`resources.resStoredLiterals`, which owns the escape rules); a
// compile-only (`!`) section is absent. A list of `Str` rather than the entries, to stay
// ahead of the resources package's own types in file order (`CgStringTable.kt`).
    var resourceStored: List<Str>,

    var sections: *Sections,
    var failed: Bool,
    var error: Str,
    var curFile: Str,
    var curPrelude: Bool,

// The names the program calls, for the prelude rule in `emitFunctions`.
    var referencedNames: Dictionary<Str, Bool>,

// The program's string literals; the walk below pools them (CgStringTable.kt).
    var literals: StringTable,

// The types the program names, for the prelude rule's per-container part: an `iter` per
// container, and a program that iterates one should not carry the others' machines.
    var referencedTypes: Dictionary<Str, Bool>,
    var types: Dictionary<Str, AstXmlNode>,
    var enumNames: Dictionary<Str, Bool>,
    var dataClassNames: Dictionary<Str, Bool>,
    var functions: List<CgFn>,
    var receiverFnNames: Dictionary<Str, Bool>,
    var nativeDecls: List<CgNativeDecl>,

// The two tables a generator's answer is registered in (impl_specs/generators.md; the
// pass lives in `cppsrc/sourcegen`).
    var nativeSymbols: Dictionary<Str, Str>,
    var nativeExtensions: Dictionary<Str, List<CgNativeExt>>,
    var activeTypeParams: Dictionary<Str, Bool>,
    var nameKinds: Dictionary<Str, NameKind>,
    var localTypes: Dictionary<Str, AstXmlNode>,
    var selfKind: NameKind,
    var selfType: AstXmlNode,
    var curReturnType: AstXmlNode,
    var nsPrefixes: Dictionary<Str, Str>,
    var typePackages: Dictionary<Str, Str>,
    var statics: List<CgStatic>,
    var staticsByName: Dictionary<Str, CgStatic>,

// The classes this unit constructs, versus the ones already written out: a closure class
// is emitted just above the body that builds it, once.
    var closureSymbols: Dictionary<Str, Bool>,
    var emittedClosures: Dictionary<Str, Bool>,
    var emittedYieldables: Dictionary<Str, Bool>,

// The decl of the machine being emitted (the last one registered): the extractor needs it
// as the class `this` is an instance of.
    var machineDecl: AstXmlNode,

// Why an instruction could not be expressed: set where the attempt gives up, read by the
// caller that turns it into a reason line.
    var ilWhy: Str,

// Inside a closure class's method (or a machine's): the receiver is C++'s `this`,
// because a member function has no `self` parameter.
    var inClosureMethod: Bool,

// The measured bodies' names, in the index order the profiler's constant table uses, and
// the memo that hands each name its index (`--profile`; empty when the flag is off).
    var profNames: List<Str>,
    var profNameIndex: Dictionary<Str, Int>,

// The callees whose repeated call the reuse pass may merge (`linear/ReusePure.kt`): the
// names of functions declared `data` (pure), filled as the declarations are collected, plus
// the language's own read-only length accessors - `size`/`count` are built-ins with no
// declaration to mark. Keyed by *name*: a `data` mark is a promise, and a call is folded
// only between two calls naming the same callee and the same argument.
    var pureCallees: Dictionary<Str, Bool>,

// The coloring pass's result (impl_specs/async.md): the names that can suspend, each to the type
// a call to it answers (an empty node for `Unit`). A call whose callee is here is a suspension,
// and the `suspend` lowering turns the body holding it into a task.
    var asyncFns: Dictionary<Str, AstXmlNode>
) {

    fun fail(posNode: *AstXmlNode, message: *Str): Unit {
        if (this.failed) {
            return
        }
        this.failed = true
        this.error = fmtStr(
            "|:|:|: |",
            this.curFile,
            xmlLine(posNode).toString(),
            xmlColumn(posNode).toString(),
            message
        )
    }

    fun line(level: Int, text: Str): Unit {
        // In place into the current section: appending never re-copies the accumulated
        // output, which would be quadratic in the size of the generated file.
        this.sections.appendLine(cgIndent(level), text)
    }

    // The amalgamation's source map, one comment per emitted declaration: the *file* a body
    // came from, not the line. The line made the comment move whenever any body above it moved
    // a line, so a small edit to one file rewrote thousands of lines of the emitted C++ (and of
    // every `expected.cpp` golden); the file alone is what a reader of the one emitted file
    // needs, and it keeps the amalgamation stable across edits that do not change a declaration.
    fun sourceComment(posNode: *AstXmlNode): Unit {
        // A prelude body is the compiler's own RTL, not the program being built: naming its
        // source would put a machine-specific path in the user's file.
        if (this.curPrelude) {
            return
        }
        this.line(0, "// " + this.curFile)
    }

    fun namedTypeExpr(name: *Str): AstXmlNode {
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return node
    }

    fun genericTypeExpr(name: *Str, args: *List<AstXmlNode>): AstXmlNode {
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeGeneric, List<AstNodeAttribute>(), args.toArray())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return node
    }

    // The receiver type of a class method: `Name<A, B>` for a generic class.
    fun classReceiver(decl: *AstXmlNode): AstXmlNode {
        val typeParams: List<Str> = xmlTypeParamNames(decl)
        if (typeParams.size() == 0) {
            return this.namedTypeExpr(xmlAttr(decl, AstNodeAttributeKind.Name))
        }
        var args: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < typeParams.size()) {
            args.append(this.namedTypeExpr(typeParams[i]))
            i = i + 1
        }
        return this.genericTypeExpr(xmlAttr(decl, AstNodeAttributeKind.Name), args)
    }

    // The class's `unInit`, if it declares one: the method that is the type's C++ destructor
    // (`emitUninit`). Empty for a class without one.
    fun cgUninitMethod(decl: *AstXmlNode): AstXmlNode {
        for (*member in xmlChildren(decl, AstNodeKind.Function)) {
            if (xmlAttr(member, AstNodeAttributeKind.Name) == "unInit") {
                return member
            }
        }
        return xmlEmptyNode()
    }

    fun addFunction(
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
                xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true",
                xmlAttr(decl, AstNodeAttributeKind.HasBody) == "true",
                xmlAttr(decl, AstNodeAttributeKind.IsPure) == "true"
            )
        )
        if (!xmlIsEmpty(receiver)) {
            this.receiverFnNames.insert(name, true)
        }
        if (xmlAttr(decl, AstNodeAttributeKind.IsPure) == "true") {
            this.pureCallees.insert(name, true)
        }
    }

    fun addNativeExt(name: *Str, ext: *CgNativeExt): Unit {
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

    fun inputPackage(input: *CgInput): Str {
        return xmlAttr(input.module, AstNodeAttributeKind.Package)
    }

    fun collectPackages(): Unit {
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
            this.nsPrefixes.insert(names[i], fmtStr("ns|_", next.toString()))
            next = next + 1
            i = i + 1
        }
    }

    // The prefix of a package; empty for `rtl` and for a name no package can be
    // attributed to.
    fun nsPrefix(packageName: *Str): Str {
        val prefix: *Str = this.nsPrefixes.getPtr(packageName)
        if (prefix != null) {
            return *prefix
        }
        return ""
    }

    fun qualify(packageName: *Str, name: *Str): Str {
        return this.nsPrefix(packageName) + name
    }

    // The package a declared type (data class, enum, typealias) came from.
    fun typePackage(name: *Str): Str {
        val packageName: *Str = this.typePackages.getPtr(name)
        if (packageName != null) {
            return *packageName
        }
        return ""
    }

    // The package of the plain (non-native) function `name`, or "". A method is not a
    // plain function, so a call by name cannot resolve to one.
    fun functionPackage(name: *Str): Str {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (fn.isNative || fn.isMethod) {
                continue
            }
            if (fn.name == name) {
                return fn.packageName
            }
        }
        return ""
    }

    // The declared type of a file-level static, for expression inference.
    fun staticType(name: *Str): AstXmlNode {
        val entry: *CgStatic = this.staticsByName.getPtr(name)
        if (entry != null) {
            return xmlChild(entry.decl, AstNodeKind.Type)
        }
        return xmlEmptyNode()
    }

    fun collect(): Unit {
        this.collectPackages()
        // The pointer form on both lists: a declaration is a value, so the index walk would
        // copy one per iteration.
        for (*input in this.inputs) {
            val pkg: Str = this.inputPackage(input)
            val decls: List<AstXmlNode> = xmlDecls(input.module)
            for (*decl in decls) {
            val declName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            if (decl.name == AstNodeKind.Var) {
                // A file-level static: storage and an initializer for the generated pass
                // (specs/statics.md); prelude inputs declare the runtime surface, not
                // program statics, so they are skipped.
                if (!input.prelude) {
                    val entry: CgStatic = CgStatic(decl, pkg, input.fileName)
                    this.statics.append(entry)
                    this.staticsByName.insert(declName, entry)
                }
                continue
            }
            if (decl.name == AstNodeKind.Function) {
                if (xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true") {
                    // A declaration whose implementation an attribute selects
                    // (impl_specs/generators.md); a generator is handed data, never an API.
                    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
                    if (!sourceGenHas(generator)) {
                        this.fail(decl, fmtStr("unknown source generator '|'", generator))
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
                            xmlTypeParamNames(decl)
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
                if (input.prelude) {
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
    fun collectFacts(): SemFacts {
        val facts: SemFacts = semNewFacts()
        this.fillFacts(facts)
        return facts
    }

    fun fillFacts(facts: *SemFacts): Unit {
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
            facts.functions.append(
                semFnFact(
                    copy(fn.decl), copy(fn.receiver), fn.templateParams, fn.name,
                    fn.packageName, fn.isNative
                )
            )
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

    fun setActiveTypeParams(params: *List<Str>): Unit {
        this.activeTypeParams.clear()
        var i: Int = 0
        while (i < params.size()) {
            this.activeTypeParams.insert(params[i], true)
            i = i + 1
        }
    }

    fun templateClause(params: *List<Str>): Str {
        if (params.size() == 0) {
            return ""
        }
        var parts: List<Str> = List<Str>()
        var i: Int = 0
        while (i < params.size()) {
            parts.append("class " + params[i])
            i = i + 1
        }
        return fmtStr("template <|>", cgJoin(parts, ", "))
    }

    fun typeArgsString(baseName: *Str, args: *List<AstXmlNode>): Str {
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

    fun typeName(name: *Str, posNode: *AstXmlNode): Str {
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
            return this.qualify(packageName, name)
        }
        if (semIsRtlTypeName(name)) {
            return name
        }
        this.fail(posNode, fmtStr("unsupported type '|'", name))
        return "/*unsupported*/"
    }

    fun type(typeExpr: *AstXmlNode): Str {
        val kind: AstNodeCategory = xmlKind(typeExpr)
        when (kind) {
            AstNodeCategory.TypeIntLit -> {
                return xmlAttr(typeExpr, AstNodeAttributeKind.Text)
            }

            AstNodeCategory.TypeNamed -> {
                return this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr)
            }

            AstNodeCategory.TypeGeneric -> {
                return fmtStr(
                    "|<|>",
                    this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr),
                    this.typeArgsString(
                        xmlAttr(typeExpr, AstNodeAttributeKind.Name),
                        xmlChildren(typeExpr, AstNodeKind.TypeArg)
                    )
                )
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
                return fmtStr("Ref<|>", this.type(inner))
            }

            AstNodeCategory.TypePointer -> {
                val inner: *AstXmlNode = xmlChildPtr(typeExpr, AstNodeKind.Inner)
                if (xmlIsEmpty(inner)) {
                    return "void*"
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
                return fmtStr("Func<|(|)>", ret, cgJoin(params, ", "))
            }

            AstNodeCategory.TypeYield -> {
                // A machine (`..T`): the class the lowering built for the creating function.
                // It is registered by the emitter, not declared by the program, so `typeName`
                // cannot spell it - hence the qualification here (`semMachineType`).
                val name: Str = xmlAttr(typeExpr, AstNodeAttributeKind.Name)
                if (name == "") {
                    this.fail(typeExpr, "unsupported: a machine's type has no class to spell")
                    return "/*machine*/"
                }
                val className: Str = this.qualify(xmlAttr(typeExpr, AstNodeAttributeKind.Package), name)
                val args: List<AstXmlNode> = xmlChildren(typeExpr, AstNodeKind.TypeArg)
                if (args.size() == 0) {
                    return className
                }
                return fmtStr("|<|>", className, this.typeArgsString(name, args))
            }
        }
        return "/*unsupported*/"
    }

    fun kindOf(typeExpr: *AstXmlNode): NameKind {
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

    // Storage for every file-level static, value-initialized so a read before the generated
    // pass below fills in the initializers yields the empty value, not indeterminate data
    // (specs/statics.md).
    fun emitStatics(): Unit {
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
    fun hasStaticInit(): Bool {
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
    fun emitStaticInit(): Unit {
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
    fun emitForwardTypes(): Unit {
        for (*input in this.inputs) {
            if (input.prelude) {
                continue
            }
            val decls: List<AstXmlNode> = xmlDecls(input.module)
            for (*decl in decls) {
            if (decl.name != AstNodeKind.DataClass) {
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

    fun emitTypes(): Unit {
        for (*input in this.inputs) {
            if (input.prelude) {
                continue
            }
            this.curFile = input.fileName
            val decls: List<AstXmlNode> = xmlDecls(input.module)
            for (*decl in decls) {
            if (decl.name == AstNodeKind.DataClass) {
                this.emitDataClass(decl)
            } else if (decl.name == AstNodeKind.Enum) {
                this.emitEnum(decl)
                this.emitEnumConversion(decl)
            } else if (decl.name == AstNodeKind.TypeAlias) {
                this.emitTypeAlias(decl)
            }
            if (this.failed) {
                return
            }
        }
        }
    }

    fun emitDataClass(decl: *AstXmlNode): Unit {
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

    fun emitEnum(decl: *AstXmlNode): Unit {
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
    fun emitEnumConversion(decl: *AstXmlNode): Unit {
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

    fun emitTypeAlias(decl: *AstXmlNode): Unit {
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

    fun emitNativeDeclarations(): Unit {
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
    fun staticCallSymbol(receiverName: *Str, calleeName: *Str): Str {
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
    fun collectNames(node: *AstXmlNode, names: *Dictionary<Str, Bool>): Unit {
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
    fun collectResourceLiterals(): Unit {
        var i: Int = 0
        while (i < this.resourceStored.size()) {
            this.literals.add(this.resourceStored[i])
            i = i + 1
        }
    }

    // The resource table: a string-table index per key and per value, and the installer that
    // hands them to `Resources` at start-up. Written after the string table, which it reads.
    fun emitResourceTable(): Unit {
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
    fun emitStringTable(): Unit {
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
    fun reachesPreludeBody(fn: *CgFn): Bool {
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
    fun asyncEmitted(name: *Str): Bool {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
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
    fun preludeReceiverNamed(name: *Str): Bool {
        var i: Int = 0
        while (i < this.functions.size()) {
            val other: *CgFn = *this.functions[i]
            i = i + 1
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

    // Fills `referencedNames` and `referencedTypes` from the program, never from the prelude's
    // own unused bodies, then closes them over the prelude it reaches: an emitted body may
    // call another, and a native's signature names the types a call reaches.
    fun collectProgramNames(): Unit {
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

    fun emitFunctions(prototypeOnly: Bool, facts: *SemFacts): Unit {
        var i: Int = 0
        while (i < this.functions.size()) {
            // A pointer into `functions`: a copy would deep-copy the declaration per function.
            val fn: *CgFn = *this.functions[i]
            i = i + 1
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

    fun beginScope(fn: *CgFn, selfK: NameKind, selfTypePtr: *AstXmlNode): Unit {
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

    fun receiverParam(receiverType: *AstXmlNode): Str {
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
    fun machineName(decl: *AstXmlNode): Str {
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
    fun outerTypeName(typeNode: *AstXmlNode): Str {
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
    fun collectTypeNames(node: *AstXmlNode): Unit {
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

    // The names a body's own C++ scope already has, which a hoisted declaration may not
    // collide with (`linFinishForEmission`'s `reserved`).
    fun cgReservedNames(decl: *AstXmlNode, hasSelf: Bool, argv: Bool): List<Str> {
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

    fun emitFunction(fn: *CgFn, prototypeOnly: Bool, facts: *SemFacts): Unit {
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

    fun expr(e: *AstXmlNode, minPrec: Int, expected: *AstXmlNode): Str {
        // The dst-driven half of the conversion table (`impl_specs/linear-il.md`): a `*T`/`&T`
        // spelled where a `T` is expected is read through, so a use need not spell the `*`.
        if (this.cgNeedsReadThrough(e, expected)) {
            return fmtStr("*(|)", this.expr(e, 0, xmlEmptyNode()))
        }
        val p: Int = cgPrecedence(e)
        var s: Str = ""
        if (p < minPrec) {
            s = s + "("
        }
        s = s + this.exprInner(e, expected)
        if (p < minPrec) {
            s = s + ")"
        }
        return s
    }

    // Whether `e` has to be read through to be spelled as `expected` - the same type modulo
    // the handle. It must not convert with no type expected, nor a value *into* a handle
    // (that `*T` binding is the writer's to spell, `specs/memory-model.md`).
    fun cgNeedsReadThrough(e: *AstXmlNode, expected: *AstXmlNode): Bool {
        if (xmlIsEmpty(expected) || ilIsHandleType(expected)) {
            return false
        }
        // `copy(v)` is the conversion already spelled, by the extractor or by the writer.
        if (xmlKind(e) == AstNodeCategory.ExprCopy) {
            return false
        }
        val have: AstXmlNode = this.inferType(e)
        if (xmlIsEmpty(have) || !ilIsHandleType(have)) {
            return false
        }
        val pointee: AstXmlNode = semPointeeOf(have)
        if (xmlIsEmpty(pointee)) {
            return false
        }
        return ilTypeText(pointee) == ilTypeText(expected)
    }

    fun operandKind(e: *AstXmlNode): NameKind {
        if (xmlKind(e) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return this.selfKind
            }
            val kind: *NameKind = this.nameKinds.getPtr(name)
            if (kind != null) {
                return *kind
            }
        }
        return NameKind.Value
    }

    fun namedType(name: *Str): AstXmlNode {
        return this.namedTypeExpr(name)
    }

    fun pointee(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = typeNode
        while (!xmlIsEmpty(current)) {
            val kind: AstNodeCategory = xmlKind(current)
            if ((kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer)) {
                val inner: AstXmlNode = xmlChild(current, AstNodeKind.Inner)
                if (xmlIsEmpty(inner)) {
                    return current
                }
                current = inner
            } else {
                return current
            }
        }
        return current
    }

    fun isHandleType(typeNode: *AstXmlNode): Bool {
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        val kind: AstNodeCategory = xmlKind(typeNode)
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            return true
        }
        if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList") {
            return true
        }
        return false
    }

    fun isIndexableContainer(typeNode: *AstXmlNode): Bool {
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        val kind: AstNodeCategory = xmlKind(typeNode)
        when (kind) {
            AstNodeCategory.TypeNamed -> {
                return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Str"
            }

            AstNodeCategory.TypeGeneric -> {
                val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
                return name == "List" || name == "Array" || name == "Dictionary" || name == "SmallVector" || name == "Span"
            }
        }
        return false
    }

    fun unifyType(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
        var actualPtr: AstXmlNode = actual
        val pk: AstNodeCategory = xmlKind(pattern)
        if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
            while (true) {
                val ak0: AstNodeCategory = xmlKind(actualPtr)
                if ((ak0 == AstNodeCategory.TypeReference || ak0 == AstNodeCategory.TypePointer)) {
                    val inner: AstXmlNode = xmlChild(actualPtr, AstNodeKind.Inner)
                    if (xmlIsEmpty(inner)) {
                        break
                    }
                    actualPtr = inner
                } else {
                    break
                }
            }
        }
        val ak: AstNodeCategory = xmlKind(actualPtr)
        when (pk) {
            AstNodeCategory.TypeIntLit -> {
                return ak == AstNodeCategory.TypeIntLit && xmlAttr(
                    actualPtr,
                    AstNodeAttributeKind.Text
                ) == xmlAttr(pattern, AstNodeAttributeKind.Text)
            }

            AstNodeCategory.TypeNamed -> {
                if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                    return true
                }
                return ak == AstNodeCategory.TypeNamed && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
                    pattern,
                    AstNodeAttributeKind.Name
                )
            }

            AstNodeCategory.TypeGeneric -> {
                if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                    return true
                }
                if (ak != AstNodeCategory.TypeGeneric) {
                    return false
                }
                val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
                val actualName: Str = xmlAttr(actualPtr, AstNodeAttributeKind.Name)
                if (actualName != patternName
                    && !(patternName == "List" && actualName == "PList")
                    && !(patternName == "PList" && actualName == "List")
                ) {
                    return false
                }
                val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
                val actualArgs: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.TypeArg)
                if (patternArgs.size() != actualArgs.size()) {
                    return false
                }
                var i: Int = 0
                while (i < patternArgs.size()) {
                    if (!this.unifyType(patternArgs[i], actualArgs[i], typeParams)) {
                        return false
                    }
                    i = i + 1
                }
                return true
            }

            AstNodeCategory.TypeReference -> {
                if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                    && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
                ) {
                    return this.unifyType(
                        xmlChildPtr(pattern, AstNodeKind.Inner),
                        xmlChildPtr(actualPtr, AstNodeKind.Inner),
                        typeParams
                    )
                }
                return false
            }

            AstNodeCategory.TypePointer -> {
                if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                    && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
                ) {
                    return this.unifyType(
                        xmlChildPtr(pattern, AstNodeKind.Inner),
                        xmlChildPtr(actualPtr, AstNodeKind.Inner),
                        typeParams
                    )
                }
                return false
            }
            // `..T` is a state machine and carries its element type like a pointer its
            // pointee, so a pattern `..T` matches `..Int` element-wise (impl_specs/yield.md).
            AstNodeCategory.TypeYield -> {
                if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                    && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
                ) {
                    return this.unifyType(
                        xmlChildPtr(pattern, AstNodeKind.Inner),
                        xmlChildPtr(actualPtr, AstNodeKind.Inner),
                        typeParams
                    )
                }
                return false
            }
        }
        return false
    }

    fun functionReturn(name: *Str): AstXmlNode {
        for (*fn in this.functions) {
            if (fn.name == name) {
                val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
                if (!xmlIsEmpty(ret)) {
                    return ret
                }
            }
        }
        return xmlEmptyNode()
    }

    fun memberCallReturn(callee: *AstXmlNode): AstXmlNode {
        val receiverType: AstXmlNode = this.inferType(xmlChildPtr(callee, AstNodeKind.Receiver))
        val recv: AstXmlNode = this.resolveAlias(this.pointee(receiverType))
        val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (fn.isNative || xmlIsEmpty(fn.receiver)) {
                continue
            }
            if (fn.name != calleeText) {
                continue
            }
            val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(recv) && this.unifyType(this.resolveAlias(fn.receiver), recv, fn.templateParams)
                && !xmlIsEmpty(ret)
            ) {
                return ret
            }
        }
        val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
        if (extensions != null) {
            for (*ext in extensions) {
                if (!xmlIsEmpty(recv) && !xmlIsEmpty(ext.receiver)
                    && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)
                    && !xmlIsEmpty(ext.returnType)
                ) {
                    return ext.returnType
                }
            }
        }
        if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric) {
            val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
            if (calleeText == "value" && xmlAttr(recv, AstNodeAttributeKind.Name) == "Opt" && typeArgs.size() > 0) {
                return typeArgs[0]
            }
        }
        if (!xmlIsEmpty(recv) && (calleeText == "size" || calleeText == "count")) {
            val recvName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
            if (recvName == "List" || recvName == "Str" || recvName == "Array"
                || recvName == "Dictionary" || recvName == "SmallVector"
            ) {
                return this.namedType("Int")
            }
        }
        if (calleeText == "isOk" || calleeText == "hasValue") {
            return this.namedType("Bool")
        }
        if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeYield) {
            // A machine's own surface (`impl_specs/for.md`): `advance()` answers whether there
            // was a value, `current` is the field holding it; the type pass answers both the
            // same way.
            if (calleeText == "advance") {
                return this.namedType("Bool")
            }
            if (calleeText == "current") {
                return xmlChild(recv, AstNodeKind.Inner)
            }
        }
        // An enum's conversions (`specs/declarations.md`): `toInt()` is the member's value,
        // `fromInt(n)` the unchecked cast back.
        if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeNamed
            && this.enumNames.has(xmlAttr(recv, AstNodeAttributeKind.Name))
        ) {
            if (calleeText == "toInt") {
                return this.namedType("Int")
            }
            if (calleeText == "fromInt") {
                return this.renameRole(recv, AstNodeKind.Type)
            }
        }
        return xmlEmptyNode()
    }

    fun inferType(e: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprIntLit -> {
                return this.namedType("Int")
            }

            AstNodeCategory.ExprFloatLit -> {
                return this.namedType("Float64")
            }

            AstNodeCategory.ExprStrLit -> {
                return this.namedType("Str")
            }

            AstNodeCategory.ExprCharLit -> {
                return this.namedType("Char")
            }

            AstNodeCategory.ExprBoolLit -> {
                return this.namedType("Bool")
            }

            AstNodeCategory.ExprNullLit -> {
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (name == "this") {
                    return this.selfType
                }
                val localType: *AstXmlNode = this.localTypes.getPtr(name)
                if (localType != null) {
                    return *localType
                }
                val staticNode: AstXmlNode = this.staticType(name)
                if (!xmlIsEmpty(staticNode)) {
                    return staticNode
                }
                if (this.enumNames.has(name)) {
                    return this.namedType(name)
                }
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprGenericName -> {
                return this.genericTypeExpr(xmlAttr(e, AstNodeAttributeKind.Name), xmlChildren(e, AstNodeKind.TypeArg))
            }

            AstNodeCategory.ExprMember -> {
                val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
                if (xmlKind(lhs) == AstNodeCategory.ExprName && this.enumNames.has(
                        copy(
                            xmlAttr(
                                lhs,
                                AstNodeAttributeKind.Name
                            )
                        )
                    )
                ) {
                    return this.namedType(xmlAttr(lhs, AstNodeAttributeKind.Name))
                }
                val baseType: AstXmlNode = this.inferType(lhs)
                val base: AstXmlNode = this.pointee(baseType)
                if (xmlIsEmpty(base)) {
                    return xmlEmptyNode()
                }
                val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (xmlKind(base) == AstNodeCategory.TypeYield) {
                    // A machine's own field (`impl_specs/for.md`): what it last yielded.
                    if (memberText == "current") {
                        return xmlChild(base, AstNodeKind.Inner)
                    }
                }
                if (xmlKind(base) == AstNodeCategory.TypeGeneric && xmlAttr(
                        base,
                        AstNodeAttributeKind.Name
                    ) == "Res"
                ) {
                    val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                    if (memberText == "value" && typeArgs.size() > 0) {
                        return typeArgs[0]
                    }
                    if (memberText == "error") {
                        return this.namedType("Str")
                    }
                }
                val baseKind: AstNodeCategory = xmlKind(base)
                if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                    val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                    val decl: *AstXmlNode = this.types.getPtr(baseName)
                    if (decl != null) {
                        if (decl.name == AstNodeKind.DataClass) {
                            val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
                            for (*field in fields) {
                                if (xmlAttr(field, AstNodeAttributeKind.Name) == memberText) {
                                    return xmlChild(field, AstNodeKind.Type)
                                }
                            }
                        }
                    }
                }
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprCall -> {
                val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
                val calleeKind: AstNodeCategory = xmlKind(callee)
                if (calleeKind == AstNodeCategory.ExprGenericName) {
                    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                    if (this.types.has(name) || semIsRtlTypeName(name)) {
                        return this.genericTypeExpr(name, xmlChildren(callee, AstNodeKind.TypeArg))
                    }
                    return this.functionReturn(name)
                }
                if (calleeKind == AstNodeCategory.ExprName) {
                    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                    if (this.types.has(name) || semIsRtlTypeName(name)) {
                        return this.namedType(name)
                    }
                    return this.functionReturn(name)
                }
                if (calleeKind == AstNodeCategory.ExprMember) {
                    return this.memberCallReturn(callee)
                }
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprIndex -> {
                val baseType: AstXmlNode = this.inferType(xmlChildPtr(e, AstNodeKind.Receiver))
                val base: AstXmlNode = this.pointee(baseType)
                if (xmlIsEmpty(base)) {
                    return xmlEmptyNode()
                }
                if (xmlKind(base) == AstNodeCategory.TypeNamed && xmlAttr(base, AstNodeAttributeKind.Name) == "Str") {
                    return this.namedType("Char")
                }
                if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
                    return xmlEmptyNode()
                }
                val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                if (typeArgs.size() == 0) {
                    return xmlEmptyNode()
                }
                val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                if (baseName == "SmallVector" && typeArgs.size() == 2) {
                    return typeArgs[1]
                }
                if (baseName == "Dictionary" && typeArgs.size() == 2) {
                    return typeArgs[1]
                }
                return typeArgs[0]
            }

            AstNodeCategory.ExprRef -> {
                var node: AstXmlNode = AstXmlNode(
                    AstNodeKind.Type,
                    AstNodeCategory.TypeReference,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
                xmlAddChild(
                    node,
                    this.renameRole(this.inferType(xmlChildPtr(e, AstNodeKind.Operand)), AstNodeKind.Inner)
                )
                return node
            }

            AstNodeCategory.ExprDeref -> {
                // `*x` is the address of what `x` denotes: a value's storage (`&x`), a counted
                // reference's pointee (`x.get()`), or the pointer itself - in which case the
                // type is the pointee. `SemInfer.infer` spells the same three cases.
                val operand: AstXmlNode = this.inferType(xmlChildPtr(e, AstNodeKind.Operand))
                if (xmlIsEmpty(operand)) {
                    return xmlEmptyNode()
                }
                val operandKind: AstNodeCategory = xmlKind(operand)
                if (operandKind == AstNodeCategory.TypePointer) {
                    val pointee: *AstXmlNode = xmlChildPtr(operand, AstNodeKind.Inner)
                    if (xmlIsEmpty(pointee)) {
                        return xmlEmptyNode()
                    }
                    return this.renameRole(pointee, AstNodeKind.Type)
                }
                var node: AstXmlNode =
                    AstXmlNode(
                        AstNodeKind.Type,
                        AstNodeCategory.TypePointer,
                        List<AstNodeAttribute>(),
                        Array<AstXmlNode>()
                    )
                var inner: AstXmlNode = operand
                if (operandKind == AstNodeCategory.TypeReference) {
                    inner = xmlChild(operand, AstNodeKind.Inner)
                }
                if (xmlIsEmpty(inner)) {
                    return xmlEmptyNode()
                }
                xmlAddChild(node, this.renameRole(inner, AstNodeKind.Inner))
                return node
            }

            AstNodeCategory.ExprCopy, AstNodeCategory.ExprUnary -> {
                return this.inferType(xmlChildPtr(e, AstNodeKind.Operand))
            }

            AstNodeCategory.ExprBinary -> {
                val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
                if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                    || op == "&&" || op == "||"
                ) {
                    return this.namedType("Bool")
                }
                // The operation is on values: a handle operand is read through to its pointee -
                // the `*T -> T` row, spelled at the operand (`binaryOperand`).
                return this.pointee(this.inferType(xmlChildPtr(e, AstNodeKind.Lhs)))
            }

            AstNodeCategory.ExprLambda -> {
                var fnType: AstXmlNode = AstXmlNode(
                    AstNodeKind.Type,
                    AstNodeCategory.TypeFunction,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
                val paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
                xmlAddChildren(fnType, paramTypes)
                return fnType
            }
        }
        return xmlEmptyNode()
    }

    // Re-roots `child` under `role` (a shallow copy whose element name changes).
    fun renameRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
        var renamed: AstXmlNode = child
        renamed.name = role
        return renamed
    }

    // The receiver argument for a lowered Simse call: a value receiver is a raw pointer, so
    // the argument is the receiver's address (`simse_addressOf`, cppsrc/rtl/types.hpp); a
    // counted reference is unwrapped with `.get()`, and a bare `this` is already that pointer.
    fun receiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
        if (this.isHandleType(pattern)) {
            return this.expr(recv, 12, xmlEmptyNode())
        }
        if (this.selfKind == NameKind.Value && xmlKind(recv) == AstNodeCategory.ExprName
            && xmlAttr(recv, AstNodeAttributeKind.Name) == "this"
        ) {
            return this.selfPointer()
        }
        val recvType: AstXmlNode = this.inferType(recv)
        if (!xmlIsEmpty(recvType)) {
            val kind: AstNodeCategory = xmlKind(recvType)
            if (kind == AstNodeCategory.TypeReference
                || (kind == AstNodeCategory.TypeGeneric && xmlAttr(recvType, AstNodeAttributeKind.Name) == "PList")
            ) {
                return fmtStr("(|).get()", this.expr(recv, 12, xmlEmptyNode()))
            }
            if (kind == AstNodeCategory.TypePointer) {
                return this.expr(recv, 12, xmlEmptyNode())
            }
        }
        return fmtStr("simse_addressOf(|)", this.expr(recv, 12, xmlEmptyNode()))
    }

    // The emitted receiver as the raw pointer it already is: `self`, or C++'s `this` inside a
    // closure class. That pointer is the receiver's address, so a borrow of `this` spells no
    // dereference.
    fun selfPointer(): Str {
        if (this.inClosureMethod) {
            return "this"
        }
        return "self"
    }

    // The receiver argument for a lowered native call: the host signature decides the form, so
    // the expression passes as it is, dereferenced through a handle (the RTL's value receivers
    // are `T&`).
    fun nativeReceiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
        if (this.isHandleType(pattern)) {
            return this.expr(recv, 12, xmlEmptyNode())
        }
        val recvType: AstXmlNode = this.inferType(recv)
        if (this.isHandleType(recvType)) {
            return fmtStr("(*|)", this.expr(recv, 12, xmlEmptyNode()))
        }
        return this.expr(recv, 12, xmlEmptyNode())
    }

    // Index into `functions` of the first Simse-declared receiver function with this name, or -1.
    fun findReceiverFnByName(name: *Str): Int {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (fn.isNative || xmlIsEmpty(fn.receiver)) {
                continue
            }
            if (fn.name == name) {
                return i - 1
            }
        }
        return -1
    }

    // Index into `functions` of a Simse extension matching the receiver, or -1.
    fun findExtensionFn(name: *Str, recvExpr: *AstXmlNode): Int {
        val recvType: AstXmlNode = this.inferType(recvExpr)
        val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
        if (xmlIsEmpty(recv)) {
            return -1
        }
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            if (fn.isNative || xmlIsEmpty(fn.receiver)) {
                i = i + 1
                continue
            }
            if (fn.name == name && this.unifyType(
                    this.resolveAlias(fn.receiver),
                    recv,
                    fn.templateParams
                )
            ) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    // Index into `nativeExtensions[name]` of a matching receiver, or -1.
    fun findNativeExt(name: *Str, recvExpr: *AstXmlNode): Int {
        val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(name)
        if (extensions == null) {
            return -1
        }
        val recvType: AstXmlNode = this.inferType(recvExpr)
        val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
        if (xmlIsEmpty(recv)) {
            return -1
        }
        var i: Int = 0
        while (i < extensions.size()) {
            val ext: *CgNativeExt = *extensions[i]
            if (!xmlIsEmpty(ext.receiver) && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    fun memberAccess(base: *AstXmlNode, name: *Str): Str {
        var arrow: Bool = false
        val baseType: AstXmlNode = this.inferType(base)
        if (xmlKind(base) == AstNodeCategory.ExprName
            && xmlAttr(base, AstNodeAttributeKind.Name) == "this" && this.selfKind == NameKind.Value
        ) {
            // A value receiver is a raw pointer (`T* self`), so its members are reached with `->`.
            arrow = true
        } else if (!xmlIsEmpty(baseType)) {
            arrow = this.isHandleType(baseType)
        } else if (xmlKind(base) == AstNodeCategory.ExprName) {
            val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
            if (baseName == "this") {
                arrow = this.selfKind != NameKind.Value
            } else {
                val kind: *NameKind = this.nameKinds.getPtr(baseName)
                if (kind != null && *kind == NameKind.Shared) {
                    arrow = true
                }
            }
        }
        var field: Str = name
        val recv: AstXmlNode = this.pointee(baseType)
        if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric && xmlAttr(
                recv,
                AstNodeAttributeKind.Name
            ) == "Res"
        ) {
            if (name == "value") {
                field = "Value"
            } else if (name == "error") {
                field = "Error"
            }
        }
        var op: Str = "."
        if (arrow) {
            op = "->"
        }
        // A value receiver's own member access reads through its pointer (`self->field`); the
        // bare name `this` elsewhere reads as the object (`(*self)`).
        if (xmlKind(base) == AstNodeCategory.ExprName && xmlAttr(base, AstNodeAttributeKind.Name) == "this"
            && this.selfKind == NameKind.Value
        ) {
            if (this.inClosureMethod) {
                return "this->" + field
            }
            return "self->" + field
        }
        return this.expr(base, 12, xmlEmptyNode()) + op + field
    }

    fun nullTo(expected: *AstXmlNode): Str {
        if (!xmlIsEmpty(expected) && xmlKind(expected) == AstNodeCategory.TypeGeneric && xmlAttr(
                expected,
                AstNodeAttributeKind.Name
            ) == "Opt"
        ) {
            return fmtStr(
                "Opt<|>()",
                this.typeArgsString("Opt", xmlChildren(expected, AstNodeKind.TypeArg))
            )
        }
        return "nullptr"
    }

    // A receiver's type is resolved through a `typealias` before matching (`StrView` is
    // `Span<Char>`, cppsrc/rtl/StrView.kt), and so is the declared receiver, so the match works
    // from either side. A non-alias type is returned as it came in.
    fun resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = typeNode
        var guard: Int = 0
        while (!xmlIsEmpty(current) && xmlKind(current) == AstNodeCategory.TypeNamed) {
            guard = guard + 1
            if (guard >= 100) {
                break
            }
            val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
            val decl: *AstXmlNode = this.types.getPtr(name)
            if (decl == null) {
                break
            }
            if (decl.name != AstNodeKind.TypeAlias) {
                break
            }
            val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
            if (xmlIsEmpty(target)) {
                break
            }
            current = target
        }
        return current
    }

    fun expectedCallable(expected: *AstXmlNode): AstXmlNode {
        val resolved: AstXmlNode = this.resolveAlias(expected)
        if (!xmlIsEmpty(resolved) && xmlKind(resolved) == AstNodeCategory.TypeFunction) {
            return resolved
        }
        return xmlEmptyNode()
    }

    // A non-native function with the given name and parameter count; a method is not one.
    fun findFunction(name: *Str, argCount: Int): AstXmlNode {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (fn.isNative || fn.isMethod
                || fn.name != name
            ) {
                continue
            }
            if (xmlCount(fn.decl, AstNodeKind.Param) == argCount) {
                return fn.decl
            }
        }
        return xmlEmptyNode()
    }

    fun isUnitType(typeNode: *AstXmlNode): Bool {
        if (xmlIsEmpty(typeNode)) {
            return true
        }
        return xmlKind(typeNode) == AstNodeCategory.TypeNamed && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Unit"
    }

    fun exprInner(e: *AstXmlNode, expected: *AstXmlNode): Str {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit, AstNodeCategory.ExprCharLit -> {
                return xmlAttr(e, AstNodeAttributeKind.Text)
            }

            AstNodeCategory.ExprStrLit -> {
                // A table entry is a `const Str` glvalue: a comparison or a `const Str&`
                // parameter binds it without building anything; an owned position copies it.
                return this.literals.spelling(xmlAttr(e, AstNodeAttributeKind.Text))
            }

            AstNodeCategory.ExprBoolLit -> {
                return xmlAttr(e, AstNodeAttributeKind.Value)
            }

            AstNodeCategory.ExprNullLit -> {
                return this.nullTo(expected)
            }

            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (name == "this") {
                    // A value receiver is a raw pointer (`T* self`) in the emitted code, so
                    // reading it reads through the pointer; a handle receiver is its handle.
                    // Inside a closure class the receiver is C++'s `this`.
                    if (this.inClosureMethod) {
                        if (this.selfKind == NameKind.Value) {
                            return "(*this)"
                        }
                        return "this"
                    }
                    if (this.selfKind == NameKind.Value) {
                        return "(*self)"
                    }
                    return "self"
                }
                if (this.localTypes.has(name)) {
                    return name
                }
                // Not a local: a static carries its package's prefix, a top-level function used
                // as a value carries its function's, and a known prelude native resolves to its
                // symbol.
                val entry: *CgStatic = this.staticsByName.getPtr(name)
                if (entry != null) {
                    return this.qualify(entry.packageName, name)
                }
                val pkg: Str = this.functionPackage(name)
                if (pkg != "") {
                    return this.qualify(pkg, name)
                }
                val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
                if (nativeOpt.hasValue()) {
                    return nativeOpt.value()
                }
                return name
            }

            AstNodeCategory.ExprGenericName -> {
                this.fail(
                    e,
                    fmtStr("unsupported: generic-qualified expression '|<...>'", xmlAttr(e, AstNodeAttributeKind.Name))
                )
                return "/*unsupported*/"
            }

            AstNodeCategory.ExprMember -> {
                val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
                if (xmlKind(lhs) == AstNodeCategory.ExprName && this.enumNames.has(
                        copy(
                            xmlAttr(
                                lhs,
                                AstNodeAttributeKind.Name
                            )
                        )
                    )
                ) {
                    val enumName: Str = xmlAttr(lhs, AstNodeAttributeKind.Name)
                    return fmtStr(
                        "|::|",
                        this.qualify(this.typePackage(enumName), enumName),
                        xmlAttr(e, AstNodeAttributeKind.Name)
                    )
                }
                return this.memberAccess(lhs, xmlAttr(e, AstNodeAttributeKind.Name))
            }

            AstNodeCategory.ExprCall -> {
                return this.call(e)
            }

            AstNodeCategory.ExprIndex -> {
                val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
                val baseExpr: Str = this.expr(lhs, 12, xmlEmptyNode())
                val baseType: AstXmlNode = this.inferType(lhs)
                var deref: Bool = false
                if (!xmlIsEmpty(baseType)) {
                    if (this.isHandleType(baseType) && xmlKind(baseType) != AstNodeCategory.TypePointer) {
                        deref = true
                    } else if (xmlKind(baseType) == AstNodeCategory.TypePointer) {
                        deref = this.isIndexableContainer(xmlChildPtr(baseType, AstNodeKind.Inner))
                    }
                }
                if (deref) {
                    return fmtStr(
                        "(*|)[|]",
                        baseExpr,
                        this.expr(xmlChildPtr(e, AstNodeKind.Index), 0, xmlEmptyNode())
                    )
                }
                return fmtStr("|[|]", baseExpr, this.expr(xmlChildPtr(e, AstNodeKind.Index), 0, xmlEmptyNode()))
            }

            AstNodeCategory.ExprUnary -> {
                return xmlAttr(e, AstNodeAttributeKind.Op) + this.expr(
                    xmlChildPtr(e, AstNodeKind.Operand),
                    7,
                    xmlEmptyNode()
                )
            }

            AstNodeCategory.ExprBinary -> {
                val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Lhs)
                val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
                val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
                if (xmlKind(lhs) == AstNodeCategory.ExprNullLit || xmlKind(rhs) == AstNodeCategory.ExprNullLit) {
                    var other: AstXmlNode = lhs
                    if (xmlKind(lhs) == AstNodeCategory.ExprNullLit) {
                        other = rhs
                    }
                    val otherType: AstXmlNode = this.pointee(this.inferType(other))
                    if (!xmlIsEmpty(otherType) && xmlKind(otherType) == AstNodeCategory.TypeGeneric
                        && xmlAttr(otherType, AstNodeAttributeKind.Name) == "Opt"
                    ) {
                        val hasValue: Str = this.expr(other, 12, xmlEmptyNode()) + ".hasValue()"
                        if (op == "==") {
                            return fmtStr("(!|)", hasValue)
                        }
                        if (op == "!=") {
                            return fmtStr("(|)", hasValue)
                        }
                        this.fail(e, fmtStr("unsupported: Opt-vs-null comparison '|'", op))
                        return "/*unsupported*/"
                    }
                }
                val p: Int = cgPrecedence(e)
                var lhsExpected: AstXmlNode = xmlEmptyNode()
                if (xmlKind(lhs) == AstNodeCategory.ExprNullLit) {
                    lhsExpected = this.inferType(rhs)
                }
                var rhsExpected: AstXmlNode = xmlEmptyNode()
                if (xmlKind(rhs) == AstNodeCategory.ExprNullLit) {
                    rhsExpected = this.inferType(lhs)
                }
                return fmtStr("| | |", this.expr(lhs, p, lhsExpected), op, this.expr(rhs, p + 1, rhsExpected))
            }

            AstNodeCategory.ExprLambda -> {
                // A lambda is a closure class, built by the instruction list; reaching here
                // means the lowering did not turn it into one (impl_specs/linear-il.md).
                this.fail(e, "unsupported: a lambda outside a closure construction")
                return "/*unsupported*/"
            }

            AstNodeCategory.ExprRef -> {
                val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
                if (xmlKind(operandNode) == AstNodeCategory.ExprCall) {
                    val callee: *AstXmlNode = xmlChildPtr(operandNode, AstNodeKind.Callee)
                    if (xmlKind(callee) == AstNodeCategory.ExprGenericName && xmlAttr(
                            callee,
                            AstNodeAttributeKind.Name
                        ) == "List"
                        && xmlCount(operandNode, AstNodeKind.Arg) == 0
                    ) {
                        return fmtStr(
                            "makeList<|>()",
                            this.typeArgsString("List", xmlChildren(callee, AstNodeKind.TypeArg))
                        )
                    }
                }
                val operand: Str = this.expr(operandNode, 0, xmlEmptyNode())
                return fmtStr(
                    "makeRef<std::remove_cvref_t<decltype((|))>>(|)",
                    operand, operand
                )
            }

            AstNodeCategory.ExprDeref -> {
                val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
                val operand: Str = this.expr(operandNode, 7, xmlEmptyNode())
                val operandType: AstXmlNode = this.inferType(operandNode)
                var nameKind: NameKind = NameKind.Value
                if (!xmlIsEmpty(operandType)) {
                    nameKind = this.kindOf(operandType)
                } else {
                    nameKind = this.operandKind(operandNode)
                }
                if (nameKind == NameKind.Shared) {
                    return fmtStr("(|).get()", operand)
                }
                if (nameKind == NameKind.Value) {
                    // `*value` is the address of the value, no copy (specs/memory-model.md): a
                    // plain name is an lvalue (`&name`), anything else may be a temporary
                    // (`simse_addressOf`).
                    if (this.selfKind == NameKind.Value && xmlKind(operandNode) == AstNodeCategory.ExprName
                        && xmlAttr(operandNode, AstNodeAttributeKind.Name) == "this"
                    ) {
                        // The receiver's address is the receiver: `*this` is `self`.
                        return this.selfPointer()
                    }
                    if (xmlKind(operandNode) == AstNodeCategory.ExprName) {
                        return "&" + operand
                    }
                    return fmtStr("simse_addressOf(|)", operand)
                }
                return "*" + operand
            }

            AstNodeCategory.ExprCopy -> {
                val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
                val operand: Str = this.expr(operandNode, 0, xmlEmptyNode())
                val operandType: AstXmlNode = this.inferType(operandNode)
                var nameKind: NameKind = NameKind.Value
                if (!xmlIsEmpty(operandType)) {
                    nameKind = this.kindOf(operandType)
                } else {
                    nameKind = this.operandKind(operandNode)
                }
                if (nameKind == NameKind.Shared || nameKind == NameKind.Pointer) {
                    return fmtStr("*(|)", operand)
                }
                return fmtStr("(|)", operand)
            }
        }
        return "/*unsupported*/"
    }

    fun call(e: *AstXmlNode): Str {
        val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
        val calleeKind: AstNodeCategory = xmlKind(callee)
        val argNodes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)

        when (calleeKind) {
            AstNodeCategory.ExprGenericName -> {
                var args: List<Str> = List<Str>()
                for (*argNode in argNodes) {
                    args.append(this.expr(argNode, 0, xmlEmptyNode()))
                }
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                var calleeName: Str = this.qualify(this.functionPackage(name), name)
                val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
                var hasPlainFunction: Bool = false
                for (*candidate in this.functions) {
                    if (xmlAttr(candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                        && xmlAttr(candidate.decl, AstNodeAttributeKind.Name) == name
                    ) {
                        hasPlainFunction = true
                    }
                }
                if (!hasPlainFunction && nativeOpt.hasValue()) {
                    calleeName = nativeOpt.value()
                }
                if (this.dataClassNames.has(name)) {
                    // A construction is the aggregate's brace form, with the type arguments
                    // spelled (`ns1_Box<Int>{1}`); CTAD covers the bare-name arm below.
                    return fmtStr(
                        "|<|>{|}",
                        this.qualify(this.typePackage(name), name),
                        this.typeArgsString(name, xmlChildren(callee, AstNodeKind.TypeArg)),
                        cgJoin(args, ", ")
                    )
                }
                return fmtStr(
                    "|<|>(|)",
                    calleeName,
                    this.typeArgsString(name, xmlChildren(callee, AstNodeKind.TypeArg)),
                    cgJoin(args, ", ")
                )
            }

            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (name == "println" || name == "print") {
                    var arg: Str = ""
                    if (argNodes.size() > 0) {
                        arg = this.expr(argNodes[0], 0, xmlEmptyNode())
                    }
                    var s: Str = fmtStr("std::cout << std::boolalpha << (|)", arg)
                    if (name == "println") {
                        s = s + " << std::endl"
                    }
                    return s
                }
                val target: AstXmlNode = this.findFunction(name, argNodes.size())
                var args: List<Str> = List<Str>()
                val targetParams: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
                val targetReceiver: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Receiver)
                var i: Int = 0
                while (i < argNodes.size()) {
                    var expectedArg: AstXmlNode = xmlEmptyNode()
                    if (!xmlIsEmpty(target) && i < targetParams.size()) {
                        expectedArg = xmlChild(targetParams[i], AstNodeKind.Type)
                    }
                    // A receiver function called by name takes the receiver first, a value
                    // receiver as its address (`T* self`).
                    if (i == 0 && !xmlIsEmpty(targetReceiver)
                        && xmlAttr(target, AstNodeAttributeKind.HasReceiver) == "true"
                        && !this.isHandleType(targetReceiver)
                    ) {
                        args.append(this.receiverArg(targetReceiver, argNodes[0]))
                    } else {
                        args.append(this.expr(argNodes[i], 0, expectedArg))
                    }
                    i = i + 1
                }
                val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
                var hasPlainFunction: Bool = false
                for (*candidate in this.functions) {
                    if (xmlAttr(candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                        && xmlAttr(candidate.decl, AstNodeAttributeKind.Name) == name
                    ) {
                        hasPlainFunction = true
                    }
                }
                var calleeName: Str = this.qualify(this.functionPackage(name), name)
                if (!hasPlainFunction && nativeOpt.hasValue()) {
                    calleeName = nativeOpt.value()
                }
                if (this.dataClassNames.has(name)) {
                    // A construction without type arguments: the brace form, with C++20
                    // aggregate CTAD deducing them.
                    return fmtStr(
                        "|{|}", this.qualify(this.typePackage(name), name), cgJoin(args, ", ")
                    )
                }
                return fmtStr("|(|)", calleeName, cgJoin(args, ", "))
            }

            AstNodeCategory.ExprMember -> {
                var args: List<Str> = List<Str>()
                for (*argNode in argNodes) {
                    args.append(this.expr(argNode, 0, xmlEmptyNode()))
                }
                val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                val receiverExpr: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)

                // `x.iter()` on a machine *is* `x` - the wrap a `for` puts around what it
                // iterates; `..T` is not spellable, so the identity is the backend's
                // (impl_specs/for.md).
                if (calleeText == "iter") {
                    val identityRecv: AstXmlNode = this.pointee(this.inferType(receiverExpr))
                    if (!xmlIsEmpty(identityRecv) && xmlKind(identityRecv) == AstNodeCategory.TypeYield) {
                        return this.expr(receiverExpr, 0, xmlEmptyNode())
                    }
                }

                // Enum conversions: `x.toInt()` and `Enum.fromInt(v)`.
                if (calleeText == "toInt") {
                    val enumReceiver: AstXmlNode = this.pointee(this.inferType(receiverExpr))
                    if (!xmlIsEmpty(enumReceiver) && xmlKind(enumReceiver) == AstNodeCategory.TypeNamed
                        && this.enumNames.has(xmlAttr(enumReceiver, AstNodeAttributeKind.Name))
                    ) {
                        return fmtStr("static_cast<Int>(|)", this.expr(receiverExpr, 12, xmlEmptyNode()))
                    }
                }
                if (calleeText == "fromInt" && xmlKind(receiverExpr) == AstNodeCategory.ExprName
                    && this.enumNames.has(xmlAttr(receiverExpr, AstNodeAttributeKind.Name))
                ) {
                    val enumName: Str = xmlAttr(receiverExpr, AstNodeAttributeKind.Name)
                    return fmtStr(
                        "|(|)",
                        this.qualify(this.typePackage(enumName), fmtStr("simse_|_fromInt", enumName)),
                        cgJoin(args, ", ")
                    )
                }

                // The `Resources` API (`cppsrc/rtl/resources.kt`, specs/resources.md): a call
                // on a *type name*, like `Enum.fromInt` above; the declaration's symbol is what
                // the call reaches.
                if (xmlKind(receiverExpr) == AstNodeCategory.ExprName) {
                    val staticSymbol: Str = this.staticCallSymbol(
                        xmlAttr(receiverExpr, AstNodeAttributeKind.Name), calleeText
                    )
                    if (staticSymbol != "") {
                        return fmtStr("|(|)", staticSymbol, cgJoin(args, ", "))
                    }
                }
                if (xmlKind(receiverExpr) == AstNodeCategory.ExprGenericName) {
                    val genericName: Str = xmlAttr(receiverExpr, AstNodeAttributeKind.Name)
                    return fmtStr(
                        "|<|>::|(|)",
                        this.qualify(this.typePackage(genericName), genericName),
                        this.typeArgsString(genericName, xmlChildren(receiverExpr, AstNodeKind.TypeArg)),
                        calleeText,
                        cgJoin(args, ", ")
                    )
                }

                val receiverType: AstXmlNode = this.inferType(receiverExpr)
                val receiver: AstXmlNode = this.pointee(receiverType)
                if (!xmlIsEmpty(receiver)) {
                    val fnIndex: Int = this.findExtensionFn(calleeText, receiverExpr)
                    if (fnIndex >= 0) {
                        val fn: *CgFn = *this.functions[fnIndex]
                        var all: Str = this.receiverArg(fn.receiver, receiverExpr)
                        var a: Int = 0
                        while (a < args.size()) {
                            all = all + ", " + args[a]
                            a = a + 1
                        }
                        return fmtStr("|(|)", this.qualify(fn.packageName, fn.name), all)
                    }
                    val extIndex: Int = this.findNativeExt(calleeText, receiverExpr)
                    if (extIndex >= 0) {
                        val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
                        val ext: *CgNativeExt = *extensions[extIndex]
                        var all: Str = this.nativeReceiverArg(ext.receiver, receiverExpr)
                        var a: Int = 0
                        while (a < args.size()) {
                            all = all + ", " + args[a]
                            a = a + 1
                        }
                        return fmtStr("|(|)", ext.symbol, all)
                    }
                    return fmtStr("|(|)", this.memberAccess(receiverExpr, calleeText), cgJoin(args, ", "))
                }

                if (this.receiverFnNames.has(calleeText)) {
                    val byName: Int = this.findReceiverFnByName(calleeText)
                    var all: Str = ""
                    if (byName >= 0) {
                        val caller: *CgFn = *this.functions[byName]
                        all = this.receiverArg(caller.receiver, receiverExpr)
                    } else {
                        all = this.expr(receiverExpr, 12, xmlEmptyNode())
                    }
                    var a: Int = 0
                    while (a < args.size()) {
                        all = all + ", " + args[a]
                        a = a + 1
                    }
                    return fmtStr("|(|)", this.qualify(this.functionPackage(calleeText), calleeText), all)
                }
                val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
                if (extensions != null) {
                    if (extensions.size() > 0) {
                        var all: Str = this.expr(receiverExpr, 12, xmlEmptyNode())
                        var a: Int = 0
                        while (a < args.size()) {
                            all = all + ", " + args[a]
                            a = a + 1
                        }
                        return fmtStr("|(|)", extensions[0].symbol, all)
                    }
                }
                return fmtStr("|(|)", this.memberAccess(receiverExpr, calleeText), cgJoin(args, ", "))
            }
        }
        this.fail(e, "unsupported: call target")
        return "/*unsupported*/"
    }

    fun preludeText(): Unit {
        this.sections.appendText(`// Generated by the Simse compiler. Do not edit.
#include "cppsrc/rtl/simse.hpp"
#include <iostream>
#include <type_traits>

`)
    }

    // `--profile`: the instrumented profiler's runtime, which every emitted body measures into
    // (impl_specs/profiling.md); nothing when it is off. A section of its own so `support` text
    // can precede it.
    fun emitProfileText(): Unit {
        this.sections.appendText(profPreludeText())
    }

    // The dense index of a measured body's name (`--profile`): assigned the first time the
    // body is written, so the runtime's `FunctionData` list is indexed by a constant.
    fun profIndexOf(name: *Str): Int {
        if (!profEnabled()) {
            return 0
        }
        val found: *Int = this.profNameIndex.getPtr(name)
        if (found != null) {
            return *found
        }
        val index: Int = this.profNames.size()
        this.profNames.append(this.prettySymbol(name))
        this.profNameIndex.insert(*name, index)
        return index
    }

    // A measured body's name as a reader can place it (`--profile`). The `nsN_` prefix the
    // compiler assigns *is* its package, so `ns1_emitFunction` reads `codegen.emitFunction` -
    // Simse's separator, not C++'s (`profDots` folds any `::` left). A lambda's measured body is
    // the synthesized `<owner>_closure<N>` class whose `operator()` is the call, so it is named
    // for what the reader wrote: `ns1_partTextProcessing_closure1::operator()` reads
    // `strings.partTextProcessing.lambda1`. `rtl` and `main` are unprefixed and pass through.
    // Only the profiler's table wants this spelling; the emitted symbols stay mangled.
    fun prettySymbol(name: *Str): Str {
        val closureAt: Int = name.indexOf("_closure")
        if (closureAt > 0) {
            val rest: Str = name.substr(closureAt + 8, name.size() - closureAt - 8)
            var digits: Str = Str()
            var d: Int = 0
            while (d < rest.size()) {
                val ch: Char = rest.charAt(d)
                if (ch < '0' || ch > '9') {
                    break
                }
                digits.append(ch)
                d = d + 1
            }
            if (digits.size() > 0) {
                return fmtStr("|.lambda|", this.packageName(name.substr(0, closureAt)), digits)
            }
        }
        return this.packageName(name)
    }

    // A plain symbol with its `nsN_` package prefix spelled out (`ns1_emitFunction` is
    // `codegen.emitFunction`); an unprefixed symbol (`main`, an `rtl` native) passes through.
    fun packageName(name: *Str): Str {
        val packages: List<Str> = this.nsPrefixes.keys()
        var i: Int = 0
        while (i < packages.size()) {
            val prefix: Str = this.nsPrefix(packages[i])
            if (prefix.size() > 0 && name.startsWith(prefix)) {
                return packages[i] + "." + name.substr(prefix.size(), name.size() - prefix.size())
            }
            i = i + 1
        }
        return *name
    }

    // The name table, written with the bodies (`--profile`): the last measured body is only
    // named once every body is written. Empty when the flag is off.
    fun emitProfileNames(): Unit {
        this.sections.appendText(profNameTableText(*this.profNames))
    }

    // The async side of the program (impl_specs/async.md): which names can suspend and what a call
    // to each answers, plus the per-callee factory/result pair the lowering calls. The coloring is
    // the driver's `--showAsync` pass, run here because the lowering is the emitter's.
    fun collectAsync(): Unit {
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
    fun asyncReturn(name: *Str): AstXmlNode {
        if (!this.asyncFns.has(name)) {
            return xmlEmptyNode()
        }
        return *this.asyncFns.getPtr(name)
    }

    fun asyncBase(fn: *CgFn): Str {
        return this.qualify(fn.packageName, fn.name)
    }

    fun asyncTaskClass(fn: *CgFn): Str {
        return this.asyncBase(fn) + "_task"
    }

    fun asyncFactory(fn: *CgFn): Str {
        return this.asyncBase(fn) + "Task"
    }

    fun asyncResult(fn: *CgFn): Str {
        return this.asyncBase(fn) + "_smResult"
    }

    // The two generated symbols a suspension reaches: `<f>_smNew` (the factory) and `<f>_smResult`
    // (the value accessor, only when the callee answers one). Registered as ordinary functions so
    // `call` spells them and the prototype pass writes them; `emitTask` writes their definitions.
    fun addTaskHelpers(fn: *CgFn): Unit {
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
        var factoryReturn: AstXmlNode = ilPointerNode(this.namedTypeExpr("Int8"))
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
            xmlAddChild(handle, ilPointerNode(this.namedTypeExpr("Int8")))
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
    fun emitTask(fn: *CgFn, decl: *AstXmlNode, prototypeOnly: Bool, facts: *SemFacts): Unit {
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
    fun lowerTask(fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts): TskTask {
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
    fun registerTaskType(className: *Str, task: TskTask): Unit {
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
    fun emitTaskFactory(fn: *CgFn, decl: *AstXmlNode, className: *Str, task: *TskTask): Unit {
        val params: List<Str> = this.parameterList(fn, decl)
        if (this.failed) {
            return
        }
        this.line(0, fmtStr("Int8* |(|) {", this.asyncFactory(fn), cgJoin(params, ", ")))
        this.line(1, fmtStr("|* task = new |();", className, className))
        this.line(1, "task->loop = &simse_tasks::taskLoop();")
        for (*param in xmlChildren(decl, AstNodeKind.Param)) {
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            this.line(1, fmtStr("task->| = |;", task.memberOf(name), name))
        }
        this.line(1, "return (Int8*) task;")
        this.line(0, "}")
    }

    // The value accessor: the caller reads the child's `result` through it, because it holds the
    // handle opaquely (`*Int8`) and never the task type.
    fun emitTaskAccessor(fn: *CgFn, className: *Str, task: *TskTask): Unit {
        var ret: AstXmlNode = this.asyncReturn(fn.name)
        if (xmlIsEmpty(ret)) {
            return
        }
        val retText: Str = this.type(ret)
        if (this.failed) {
            return
        }
        this.line(0, fmtStr("| |(Int8* _sm_h) {", retText, this.asyncResult(fn)))
        this.line(1, fmtStr("return ((|*) _sm_h)->|;", className, task.memberOf("result")))
        this.line(0, "}")
    }

    // A suspending `main` is the root task: build it, hand it to the loop, drive until it is done,
    // and answer its result as the exit code.
    fun emitAsyncMain(fn: *CgFn, className: *Str, task: *TskTask): Unit {
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

    fun run(): Res<Str> {
        // The language's own read-only length accessors, which have no declaration to mark
        // `data`; a program that declares its own `size`/`count` is trusted the same way the
        // old name whitelist trusted it.
        this.pureCallees.insert("size", true)
        this.pureCallees.insert("count", true)
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
        this.sections.begin("types")
        this.emitForwardTypes()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitTypes()
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
}

fun newEmitter(inputs: *List<CgInput>, resourceStored: *List<Str>): Emitter {
    return Emitter(
        inputs,
        resourceStored,
        sourceGenSink(),
        false,
        "",
        "",
        false,
        Dictionary<Str, Bool>(),
        StringTable(List<Str>(), Dictionary<Str, Int>()),
        Dictionary<Str, Bool>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        List<CgFn>(),
        Dictionary<Str, Bool>(),
        List<CgNativeDecl>(),
        Dictionary<Str, Str>(),
        Dictionary<Str, List<CgNativeExt>>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, NameKind>(),
        Dictionary<Str, AstXmlNode>(),
        NameKind.Value,
        xmlEmptyNode(),
        xmlEmptyNode(),
        Dictionary<Str, Str>(),
        Dictionary<Str, Str>(),
        List<CgStatic>(),
        Dictionary<Str, CgStatic>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        xmlEmptyNode(),
        "",
        false,
        List<Str>(),
        Dictionary<Str, Int>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, AstXmlNode>()
    )
}

// Amalgamates every input into one translation unit, byte-identical for identical inputs; on
// failure the error is formatted "<file>:<line>:<col>: <message>".
//
// `resourceStored` is what the program carries from its `_res.md` files
// (`resources.resStoredLiterals`, specs/resources.md), already spelled as the C++ literal that
// holds its bytes, in the order the files were read.
fun emitProgram(inputs: *List<CgInput>, resourceStored: *List<Str>): Res<Str> {
    var emitter: Emitter = newEmitter(inputs, resourceStored)
    return emitter.run()
}
