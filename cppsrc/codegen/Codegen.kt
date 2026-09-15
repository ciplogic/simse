// Codegen.kt
//
// The C++ emitter, ported from cppsrc/codegen/Codegen.cpp. It consumes the
// AstXmlNode AST (schema in impl_specs/ast-xmlnode.md) and amalgamates one or more
// modules into a single translation unit, byte-for-byte identical to the C++
// `codegen::emitProgram`.
//
// The C++ emitter's `ast::TypePtr` layer (inferType/unifyType/pointee/
// isHandleType and the per-function localTypes/nameKinds tables) is re-expressed
// over the AstXmlNode type nodes; an empty node means "no/unknown type".
//
// `import sema`, `import common` and `import linear` bring the packages the
// emitted file uses (the xml accessors and the linear lowering) into unqualified
// scope. Under the modules-and-packages
// model an import never adds files; the driver scans the module roots, so the
// generated file is a self-contained front end for the differential harness.
// Emission itself does not call sema.
//
// Kind dispatch is an if-chain, not a `switch`, because the emitted C++ `switch`
// cannot switch on a `Str`. Method overloading is avoided (method dispatch is by
// name and receiver, not arity).

package codegen

import sema
import common
import linear

// One parsed input. `prelude` inputs participate in symbol collection and are emitted
// only when they carry a body: the RTL's declarations are natives (whose C++ is the
// header's), and a prelude `fun` with a body is a function the language itself provides.
data class CgInput(var fileName: Str;

var module: AstXmlNode;
var prelude: Bool)

// A function/method to emit, with its receiver type (empty for plain functions).
// `package` picks the emitted-symbol prefix: `ns<index>_`, or none for `rtl`.
data class CgFn(var decl: AstXmlNode;

var receiver: AstXmlNode;
var file: Str;
var templateParams: List<Str>;
var prelude: Bool;
var packageName: Str;
var isMethod: Bool)

// A `native fun` declaration to emit once at the top (and call by symbol).
data class CgNativeDecl(var decl: AstXmlNode;

var file: Str;
var symbol: Str;
var prelude: Bool)

// An explicit-`this` native extension; the receiver pattern selects the overload.
data class CgNativeExt(var symbol: Str;

var receiver: AstXmlNode;
var returnType: AstXmlNode;
var typeParams: List<Str>)

// A file-level static (`Var`, specs/statics.md): storage plus an optional
// initializer, emitted under its package's prefix like any other declaration.
data class CgStatic(var decl: AstXmlNode;

var packageName: Str;
var file: Str)

// One type-table entry's node, or an empty node when the extractor had none (a
// synthesised place: it can only be *folded* into the instruction that reads it, never
// declared).
data class IlFrame(
    // Keyed by *slot index*: two scopes may declare the same name, and the frame keeps
    // them apart (the lowering gives each its own slot), so an analysis keyed by name
    // would merge two different variables.
    var defOp: Dictionary<Int, Int>;

var defineCount: Dictionary<Int, Int>;
var useCount: Dictionary<Int, Int>
)

// Where a jump crosses a declaration, C++ wants a scope: a `goto` may not skip an
// initialization ([stmt.dcl]/3, MSVC C2362). `end` is the earliest label a crossing
// jump lands on, `lastJump` the last jump that crosses.
data class IlCrossing(var end: Int;

var lastJump: Int)

// One open block of the flat form, and the label it ends before.
data class IlScope(var start: Int;

var end: Int)

// The text one body's instructions spell, or why they could not be spelled.
data class IlText(var ok: Bool;

var text: Str;
var reason: Str)

// How a name's storage is reached, for `.` vs `->`, `*x` vs `x.get()`, `copy`.
enum NameKind { Value, Shared, Pointer }

// ---- helpers --------------------------------------------------------------

// Reads the parts only; a `*List<Str>` avoids copying the caller's list. Builds
// the result in place: `out = out + part` would copy the whole buffer per part.
fun cgJoin(parts: *List<Str>, separator: Str): Str {
    var out: Str = ""
    var i: Int = 0
    while (i < parts.size()) {
        if (i > 0) {
            out.appendStr(separator)
        }
        out.appendStr(parts[i])
        i = i + 1
    }
    return out
}

fun cgIndent(level: Int): Str {
    var out: Str = ""
    var i: Int = 0
    while (i < level * 4) {
        out.append(' ')
        i = i + 1
    }
    return out
}

// The emitter no longer keeps its own copy of the RTL type names: the semantics that
// share the list own it (`semIsRtlTypeName`, sema/TypeInfer.kt).

fun cgUnquote(text: Str): Str {
    if (text.size() >= 2 && text.substr(0, 1) == "\"" && text.substr(text.size() - 1, 1) == "\"") {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// Binary operator precedence for wrapping; see Codegen.cpp precedence().
fun cgPrecedence(e: *AstXmlNode): Int {
    if (xmlKind(e) == AstNodeCategory.ExprBinary) {
        val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
        if (op == "||") {
            return 1
        }
        if (op == "&&") {
            return 2
        }
        if (op == "==" || op == "!=") {
            return 3
        }
        if (op == "<" || op == ">" || op == "<=" || op == ">=") {
            return 4
        }
        if (op == "+" || op == "-") {
            return 5
        }
        if (op == "*" || op == "/" || op == "%") {
            return 6
        }
        return 1
    }
    val kind: AstNodeCategory = xmlKind(e)
    if (kind == AstNodeCategory.ExprUnary || kind == AstNodeCategory.ExprDeref || kind == AstNodeCategory.ExprCopy) {
        return 7
    }
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprCall || kind == AstNodeCategory.ExprIndex
        || kind == AstNodeCategory.ExprMember
    ) {
        return 9
    }
    return 10
}

// Whether a top-level `main` takes the argv form: a single `List<Str>` parameter.
fun cgIsMainArgs(decl: *AstXmlNode): Bool {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (params.size() != 1) {
        return false
    }
    val paramType: AstXmlNode = xmlChild(*params[0], AstNodeKind.Type)
    if (xmlIsEmpty(*paramType)) {
        return false
    }
    if (xmlKind(*paramType) != AstNodeCategory.TypeGeneric || xmlAttr(
            *paramType,
            AstNodeAttributeKind.Name
        ) != "List"
    ) {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(*paramType, AstNodeKind.TypeArg)
    if (args.size() != 1) {
        return false
    }
    return xmlKind(*args[0]) == AstNodeCategory.TypeNamed && xmlAttr(*args[0], AstNodeAttributeKind.Name) == "Str"
}

// ---- the emitter ----------------------------------------------------------

data class Emitter(
    var inputs: List<CgInput>;

var out: Str;
var failed: Bool;
var error: Str;
var curFile: Str;
var curPrelude: Bool;

// The names the program calls, for the prelude rule in `emitFunctions`.
var referencedNames: Dictionary<Str, Bool>;

// The types the program names, for the same rule's per-container part: the prelude has
// a `smToYield` per container (`List`, `Array`, `Span`), and a program that iterates
// one of them should not carry the others' machines.
var referencedTypes: Dictionary<Str, Bool>;
var types: Dictionary<Str, AstXmlNode>;
var enumNames: Dictionary<Str, Bool>;
var dataClassNames: Dictionary<Str, Bool>;
var functions: List<CgFn>;
var receiverFnNames: Dictionary<Str, Bool>;
var nativeDecls: List<CgNativeDecl>;
var nativeSymbols: Dictionary<Str, Str>;
var nativeExtensions: Dictionary<Str, List<CgNativeExt>>;
var activeTypeParams: Dictionary<Str, Bool>;
var nameKinds: Dictionary<Str, NameKind>;
var localTypes: Dictionary<Str, AstXmlNode>;
var selfKind: NameKind;
var selfType: AstXmlNode;
var curReturnType: AstXmlNode;
var nsPrefixes: Dictionary<Str, Str>;
var typePackages: Dictionary<Str, Str>;
var statics: List<CgStatic>;
var staticsByName: Dictionary<Str, CgStatic>;

// ---- the IL path (impl_specs/linear-il.md) ----------------------------
// The classes this unit constructs, and the ones already written out: a closure
// class is emitted just above the body that builds it, once.
var closureSymbols: Dictionary<Str, Bool>;
var emittedClosures: Dictionary<Str, Bool>;
var emittedYieldables: Dictionary<Str, Bool>;

// Why an instruction could not be expressed: set where the attempt gives up, read
// by the caller that turns it into a reason line.
var ilWhy: Str;

// Inside a closure class's method (or a machine's): the receiver is C++'s `this`,
// because a member function has no `self` parameter.
var inClosureMethod: Bool
) {

    // ---- diagnostics and output -------------------------------------------

    fun fail(posNode: *AstXmlNode, message: Str): Unit {
        if (this.failed) {
            return
        }
        this.failed = true
        this.error = this.curFile + ":" + xmlLine(posNode).toString() + ":"
        +xmlColumn(posNode).toString() + ": " + message
    }

    fun line(level: Int, text: Str): Unit {
        // In place: `this.out = this.out + ...` copies the whole accumulated
        // output on every line (quadratic in the size of the generated file).
        this.out.appendStr(cgIndent(level))
        this.out.appendStr(text)
        this.out.append('\n')
    }

    fun sourceComment(posNode: *AstXmlNode): Unit {
        // A prelude function *with a body* is emitted now (`List<T>.smToYield`), and where
        // it came from is the compiler's own RTL, not the program the user is building:
        // naming it would put a machine-specific path in their file.
        if (this.curPrelude) {
            return
        }
        this.line(0, "// " + this.curFile + ":" + xmlLine(posNode).toString())
    }

    // ---- symbol collection ------------------------------------------------

    fun namedTypeExpr(name: Str): AstXmlNode {
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return node
    }

    fun genericTypeExpr(name: Str, args: *List<AstXmlNode>): AstXmlNode {
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
        return this.genericTypeExpr(xmlAttr(decl, AstNodeAttributeKind.Name), *args)
    }

    fun addFunction(
        decl: *
        AstXmlNode,
        receiver: *
        AstXmlNode,
        file: Str,
        templateParams: List<Str>,
        prelude: Bool,
        packageName: Str,
        isMethod: Bool
    ): Unit {
        this.functions.append(CgFn(copy(decl), copy(receiver), file, templateParams, prelude, packageName, isMethod))
        if (!xmlIsEmpty(receiver)) {
            this.receiverFnNames.insert(xmlAttr(decl, AstNodeAttributeKind.Name), true)
        }
    }

    fun addNativeExt(name: Str, ext: CgNativeExt): Unit {
        if (this.nativeExtensions.has(name)) {
            var existing: List<CgNativeExt> = this.nativeExtensions.get(name).value()
            existing.append(ext)
            this.nativeExtensions.insert(name, existing)
        } else {
            var fresh: List<CgNativeExt> = List<CgNativeExt>()
            fresh.append(ext)
            this.nativeExtensions.insert(name, fresh)
        }
    }

    // ---- package qualification --------------------------------------------
    //
    // Every declaration is emitted under its package's prefix: `rtl` - the
    // namespace the built-in types live in (specs/modules.md, the implicit
    // import) - is emitted bare, and every other package gets `ns<index>_` from
    // the global dictionary below. The dictionary assigns indices in sorted
    // package order, so the numbering never depends on discovery order and the
    // output stays reproducible. This is what keeps two packages' same-named
    // declarations apart in the amalgamated translation unit without spelling a
    // package name out in the generated code.

    fun inputPackage(input: *CgInput): Str {
        return xmlAttr(*input.module, AstNodeAttributeKind.Package)
    }

    fun collectPackages(): Unit {
        var names: List<Str> = List<Str>()
        var i: Int = 0
        while (i < this.inputs.size()) {
            val pkg: Str = this.inputPackage(*this.inputs[i])
            // `rtl` is the built-in namespace, and an empty package is a
            // programmatically built module (the merged prelude); neither is
            // indexed, so neither is ever prefixed.
            if (pkg != "rtl" && pkg != "" && !names.contains(pkg)) {
                names.append(pkg)
            }
            i = i + 1
        }
        names.sort((left: Str, right: Str) -> left < right)
        var next: Int = 1
        i = 0
        while (i < names.size()) {
            this.nsPrefixes.insert(names[i], "ns" + next.toString() + "_")
            next = next + 1
            i = i + 1
        }
    }

    // The prefix of a package: empty for `rtl`, and for a name the emitter cannot
    // attribute to any package (leaving it alone beats mangling it into a symbol
    // that does not exist).
    fun nsPrefix(packageName: Str): Str {
        if (this.nsPrefixes.has(packageName)) {
            return this.nsPrefixes.get(packageName).value()
        }
        return ""
    }

    fun qualify(packageName: Str, name: Str): Str {
        return this.nsPrefix(packageName) + name
    }

    // The package a declared type (data class, enum, typealias) came from.
    fun typePackage(name: Str): Str {
        if (this.typePackages.has(name)) {
            return this.typePackages.get(name).value()
        }
        return ""
    }

    // The package of the plain (non-native) function `name`, or "". Matches by name
    // only, like the `hasPlainFunction` probes at the call sites - and a method is not a
    // plain function, so a call by name cannot resolve to one (two packages may spell
    // the same method name).
    fun functionPackage(name: Str): Str {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || fn.isMethod) {
                continue
            }
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) == name) {
                return fn.packageName
            }
        }
        return ""
    }

    // The declared type of a file-level static, for expression inference.
    fun staticType(name: Str): AstXmlNode {
        if (this.staticsByName.has(name)) {
            return xmlChild(*this.staticsByName.get(name).value().decl, AstNodeKind.Type)
        }
        return xmlEmptyNode()
    }

    fun collect(): Unit {
        this.collectPackages()
        var i: Int = 0
        while (i < this.inputs.size()) {
            val input: CgInput = this.inputs[i]
            val pkg: Str = this.inputPackage(*this.inputs[i])
            val decls: List<AstXmlNode> = xmlDecls(*input.module)
            var d: Int = 0
            while (d < decls.size()) {
                val decl: AstXmlNode = decls[d]
                d = d + 1
                val declName: Str = xmlAttr(*decl, AstNodeAttributeKind.Name)
                if (decl.name == AstNodeKind.Var) {
                    // A file-level static: storage and an initializer for the
                    // generated pass (specs/statics.md). Prelude inputs declare the
                    // runtime surface, not program statics, so they are skipped.
                    if (!input.prelude) {
                        val entry: CgStatic = CgStatic(copy(decl), pkg, input.fileName)
                        this.statics.append(entry)
                        this.staticsByName.insert(declName, entry)
                    }
                    continue
                }
                if (decl.name == AstNodeKind.Function) {
                    if (xmlAttr(*decl, AstNodeAttributeKind.IsNative) == "true") {
                        var symbol: Str = declName
                        if (xmlAttr(*decl, AstNodeAttributeKind.HasNativeSymbol) == "true") {
                            symbol = cgUnquote(xmlAttr(*decl, AstNodeAttributeKind.NativeSymbol))
                        }
                        this.nativeDecls.append(CgNativeDecl(decl, input.fileName, symbol, input.prelude))
                        this.nativeSymbols.insert(declName, symbol)
                        val params: List<AstXmlNode> = xmlChildren(*decl, AstNodeKind.Param)
                        if (params.size() > 0 && xmlAttr(*params[0], AstNodeAttributeKind.Name) == "this") {
                            val ext: CgNativeExt = CgNativeExt(
                                symbol, xmlChild(*params[0], AstNodeKind.Type),
                                xmlChild(*decl, AstNodeKind.ReturnType),
                                xmlTypeParamNames(*decl)
                            )
                            this.addNativeExt(declName, ext)
                        }
                    }
                    var recv: AstXmlNode = xmlEmptyNode()
                    if (xmlAttr(*decl, AstNodeAttributeKind.HasReceiver) == "true") {
                        recv = xmlChild(*decl, AstNodeKind.Receiver)
                    }
                    this.addFunction(*decl, *recv, input.fileName, xmlTypeParamNames(*decl), input.prelude, pkg, false)
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
                    val receiver: AstXmlNode = this.classReceiver(*decl)
                    val methods: List<AstXmlNode> = xmlChildren(*decl, AstNodeKind.Function)
                    val classParams: List<Str> = xmlTypeParamNames(*decl)
                    var m: Int = 0
                    while (m < methods.size()) {
                        val method: AstXmlNode = methods[m]
                        var methodParams: List<Str> = List<Str>()
                        var p: Int = 0
                        while (p < classParams.size()) {
                            methodParams.append(classParams[p])
                            p = p + 1
                        }
                        val methodTypeParams: List<Str> = xmlTypeParamNames(*method)
                        var q: Int = 0
                        while (q < methodTypeParams.size()) {
                            methodParams.append(methodTypeParams[q])
                            q = q + 1
                        }
                        this.addFunction(*method, *receiver, input.fileName, methodParams, input.prelude, pkg, true)
                        m = m + 1
                    }
                }
            }
            i = i + 1
        }
    }

    // The program-level facts the semantic step on the lowered body reads
    // (sema/TypeInfer.kt), built from the tables `collect` filled. Filling them
    // copies no declarations: the nodes are shared.
    fun collectFacts(): SemFacts {
        val facts: SemFacts = semNewFacts()
        this.fillFacts(*facts)
        return facts
    }

    // Copies the collected tables into `facts` (a structural copy of the keys; the
    // declarations and type nodes themselves are shared).
    fun fillFacts(facts: *SemFacts): Unit {
        val typeNames: List<Str> = this.types.keys()
        var t: Int = 0
        while (t < typeNames.size()) {
            facts.types.insert(typeNames[t], this.types.get(typeNames[t]).value())
            t = t + 1
        }
        val enumTypeNames: List<Str> = this.enumNames.keys()
        var e: Int = 0
        while (e < enumTypeNames.size()) {
            facts.enumNames.insert(enumTypeNames[e], true)
            e = e + 1
        }
        var f: Int = 0
        while (f < this.functions.size()) {
            val fn: *CgFn = *this.functions[f]
            facts.functions.append(SemFnFact(copy(fn.decl), copy(fn.receiver), fn.templateParams))
            f = f + 1
        }
        val extensionNames: List<Str> = this.nativeExtensions.keys()
        var x: Int = 0
        while (x < extensionNames.size()) {
            val overloads: List<CgNativeExt> = this.nativeExtensions.get(extensionNames[x]).value()
            var mapped: List<SemExtFact> = List<SemExtFact>()
            var o: Int = 0
            while (o < overloads.size()) {
                val ext: *CgNativeExt = *overloads[o]
                mapped.append(SemExtFact(copy(ext.receiver), copy(ext.returnType), ext.typeParams))
                o = o + 1
            }
            facts.nativeExtensions.insert(extensionNames[x], mapped)
            x = x + 1
        }
        val staticNames: List<Str> = this.staticsByName.keys()
        var s: Int = 0
        while (s < staticNames.size()) {
            val entry: *CgStatic = *this.staticsByName.get(staticNames[s]).value()
            facts.statics.insert(staticNames[s], xmlChild(*entry.decl, AstNodeKind.Type))
            s = s + 1
        }
    }

    // ---- type mapping -----------------------------------------------------

    fun setActiveTypeParams(params: List<Str>): Unit {
        this.activeTypeParams.clear()
        var i: Int = 0
        while (i < params.size()) {
            this.activeTypeParams.insert(params[i], true)
            i = i + 1
        }
    }

    fun templateClause(params: List<Str>): Str {
        if (params.size() == 0) {
            return ""
        }
        var parts: List<Str> = List<Str>()
        var i: Int = 0
        while (i < params.size()) {
            parts.append("class " + params[i])
            i = i + 1
        }
        return "template <" + cgJoin(*parts, ", ") + ">"
    }

    fun typeArgsString(baseName: Str, args: *List<AstXmlNode>): Str {
        var rendered: List<Str> = List<Str>()
        var i: Int = 0
        while (i < args.size()) {
            rendered.append(this.type(*args[i]))
            i = i + 1
        }
        if (baseName == "SmallVector" && rendered.size() == 2) {
            val first: Str = rendered[0]
            rendered[0] = rendered[1]
            rendered[1] = first
        }
        return cgJoin(*rendered, ", ")
    }

    fun typeName(name: Str, posNode: *AstXmlNode): Str {
        if (name == "Unit") {
            return "void"
        }
        if (this.activeTypeParams.has(name)) {
            return name
        }
        // A declared type shadows an RTL type *name*: a compiler-side view type of
        // the same name is a different type from the RTL's. The RTL's own prelude
        // types keep their C++ spelling unprefixed.
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
        this.fail(posNode, "unsupported type '" + name + "'")
        return "/*unsupported*/"
    }

    fun type(typeExpr: *AstXmlNode): Str {
        val kind: AstNodeCategory = xmlKind(typeExpr)
        if (kind == AstNodeCategory.TypeIntLit) {
            return xmlAttr(typeExpr, AstNodeAttributeKind.Text)
        }
        if (kind == AstNodeCategory.TypeNamed) {
            return this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr)
        }
        if (kind == AstNodeCategory.TypeGeneric) {
            return this.typeName(xmlAttr(typeExpr, AstNodeAttributeKind.Name), typeExpr)
            +"<" + this.typeArgsString(
                xmlAttr(typeExpr, AstNodeAttributeKind.Name),
                *xmlChildren(typeExpr, AstNodeKind.TypeArg)
            ) + ">"
        }
        if (kind == AstNodeCategory.TypeReference) {
            val inner: AstXmlNode = xmlChild(typeExpr, AstNodeKind.Inner)
            if (xmlIsEmpty(*inner)) {
                return "std::shared_ptr<void>"
            }
            return "std::shared_ptr<" + this.type(*inner) + ">"
        }
        if (kind == AstNodeCategory.TypePointer) {
            val inner: AstXmlNode = xmlChild(typeExpr, AstNodeKind.Inner)
            if (xmlIsEmpty(*inner)) {
                return "void*"
            }
            return this.type(*inner) + "*"
        }
        if (kind == AstNodeCategory.TypeFunction) {
            val retNode: AstXmlNode = xmlChild(typeExpr, AstNodeKind.ReturnType)
            var ret: Str = "void"
            if (!xmlIsEmpty(*retNode)) {
                ret = this.type(*retNode)
            }
            val paramNodes: List<AstXmlNode> = xmlChildren(typeExpr, AstNodeKind.ParamType)
            var params: List<Str> = List<Str>()
            var i: Int = 0
            while (i < paramNodes.size()) {
                params.append(this.type(*paramNodes[i]))
                i = i + 1
            }
            return "Func<" + ret + "(" + cgJoin(*params, ", ") + ")>"
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

    // ---- declarations -----------------------------------------------------

    // Storage for every file-level static, value-initialized so it starts empty:
    // the generated pass below fills in the initializers, and a read that happens
    // first yields the empty value rather than indeterminate data
    // (specs/statics.md).
    fun emitStatics(): Unit {
        var i: Int = 0
        while (i < this.statics.size()) {
            val entry: CgStatic = this.statics[i]
            i = i + 1
            this.curFile = entry.file
            val typeNode: AstXmlNode = xmlChild(*entry.decl, AstNodeKind.Type)
            val storage: Str = this.qualify(entry.packageName, xmlAttr(*entry.decl, AstNodeAttributeKind.Name))
            if (this.failed) {
                return
            }
            this.sourceComment(*entry.decl)
            this.line(0, this.type(*typeNode) + " " + storage + "{};")
            if (this.failed) {
                return
            }
        }
    }

    // Whether any static has an initializer, i.e. whether the pass is needed.
    fun hasStaticInit(): Bool {
        var i: Int = 0
        while (i < this.statics.size()) {
            if (!xmlIsEmpty(*xmlChild(*this.statics[i].decl, AstNodeKind.Init))) {
                return true
            }
            i = i + 1
        }
        return false
    }

    // The generated initialization pass (specs/statics.md): the initializers of the
    // file-level statics, run before the body of `main`. It is emitted here rather
    // than as C++ static initialization so that the language owns the order, and it
    // is emitted in declaration order - which the language does not guarantee, so a
    // program must not depend on one static being initialized before another.
    fun emitStaticInit(): Unit {
        if (!this.hasStaticInit()) {
            return
        }
        this.line(0, "// File-level static storage (specs/statics.md): initialized before main's body.")
        this.line(0, "void simse_initStatics() {")
        var i: Int = 0
        while (i < this.statics.size()) {
            val entry: CgStatic = this.statics[i]
            i = i + 1
            val init: AstXmlNode = xmlChild(*entry.decl, AstNodeKind.Init)
            if (xmlIsEmpty(*init)) {
                continue
            }
            this.curFile = entry.file
            val storage: Str = this.qualify(entry.packageName, xmlAttr(*entry.decl, AstNodeAttributeKind.Name))
            this.line(1, storage + " = " + this.expr(*init, 0, *xmlChild(*entry.decl, AstNodeKind.Type)) + ";")
            if (this.failed) {
                return
            }
        }
        this.line(0, "}")
    }

    // Every aggregate the program declares, named once *before* any of them is defined.
    // A generated struct may hold a *pointer* to a type from another package
    // (`IlFunction`'s facts), and packages are emitted in source order, so the
    // definition would otherwise be used before it exists. A forward declaration is all a
    // pointer, a reference or a parameter needs - and it is what lets a generated data
    // class name another package's type at all.
    fun emitForwardTypes(): Unit {
        var i: Int = 0
        while (i < this.inputs.size()) {
            val input: CgInput = this.inputs[i]
            i = i + 1
            if (input.prelude) {
                continue
            }
            val decls: List<AstXmlNode> = xmlDecls(*input.module)
            var d: Int = 0
            while (d < decls.size()) {
                val decl: AstXmlNode = decls[d]
                d = d + 1
                if (decl.name != AstNodeKind.DataClass) {
                    continue
                }
                val tmpl: Str = this.templateClause(xmlTypeParamNames(*decl))
                if (tmpl != "") {
                    this.line(0, tmpl)
                }
                val name: Str = xmlAttr(*decl, AstNodeAttributeKind.Name)
                this.line(0, "struct " + this.qualify(this.typePackage(name), name) + ";")
            }
        }
    }

    fun emitTypes(): Unit {
        var i: Int = 0
        while (i < this.inputs.size()) {
            val input: CgInput = this.inputs[i]
            i = i + 1
            if (input.prelude) {
                continue
            }
            this.curFile = input.fileName
            val decls: List<AstXmlNode> = xmlDecls(*input.module)
            var d: Int = 0
            while (d < decls.size()) {
                val decl: AstXmlNode = decls[d]
                d = d + 1
                if (decl.name == AstNodeKind.DataClass) {
                    this.emitDataClass(*decl)
                } else if (decl.name == AstNodeKind.Enum) {
                    this.emitEnum(*decl)
                    this.emitEnumConversion(*decl)
                } else if (decl.name == AstNodeKind.TypeAlias) {
                    this.emitTypeAlias(*decl)
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
        var params: List<Str> = List<Str>()
        var values: List<Str> = List<Str>()
        var i: Int = 0
        while (i < fields.size()) {
            val field: AstXmlNode = fields[i]
            val fieldType: AstXmlNode = xmlChild(*field, AstNodeKind.Type)
            if (xmlIsEmpty(*fieldType)) {
                this.fail(
                    *field,
                    "unsupported: field '" + xmlAttr(*field, AstNodeAttributeKind.Name) + "' without a type"
                )
                return
            }
            params.append(this.type(*fieldType) + " " + xmlAttr(*field, AstNodeAttributeKind.Name))
            values.append(xmlAttr(*field, AstNodeAttributeKind.Name))
            i = i + 1
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
        // (specs/memory-model.md); the macros come from rtl/types.hpp.
        this.line(0, "SIMSE_PACK_PUSH")
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, "struct " + emittedName + " {")
        var f: Int = 0
        while (f < fields.size()) {
            val field: AstXmlNode = fields[f]
            this.line(
                1,
                this.type(*xmlChild(*field, AstNodeKind.Type)) + " " + xmlAttr(*field, AstNodeAttributeKind.Name) + ";"
            )
            f = f + 1
        }
        this.line(0, "};")
        this.line(0, "SIMSE_PACK_POP")

        // The struct stays an aggregate; construction goes through a
        // `_make_<Name>` factory so callers keep the `Name(args)` shape without
        // an emitted constructor.
        var target: Str = emittedName
        if (typeParams.size() > 0) {
            target = emittedName + "<" + cgJoin(*typeParams, ", ") + ">"
        }
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(
            0, target + " " + this.qualify(this.typePackage(name), "_make_" + name)
                    + "(" + cgJoin(*params, ", ") + ") {"
        )
        this.line(1, "return " + target + "{" + cgJoin(*values, ", ") + "};")
        this.line(0, "}")
    }

    fun emitEnum(decl: *AstXmlNode): Unit {
        this.sourceComment(decl)
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, "enum class " + this.qualify(this.typePackage(name), name) + " {")
        val members: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.EnumMember)
        var i: Int = 0
        while (i < members.size()) {
            val member: AstXmlNode = members[i]
            var text: Str = xmlAttr(*member, AstNodeAttributeKind.Name)
            if (xmlAttr(*member, AstNodeAttributeKind.HasValue) == "true") {
                text = text + " = " + xmlAttr(*member, AstNodeAttributeKind.Value)
            }
            this.line(1, text + ",")
            i = i + 1
        }
        this.line(0, "};")
    }

    // A checked `Enum.fromInt(Int): Opt<Enum>` helper (an if-chain, not a switch).
    fun emitEnumConversion(decl: *AstXmlNode): Unit {
        val typeParams: List<Str> = xmlTypeParamNames(decl)
        if (typeParams.size() > 0) {
            return
        }
        val enumName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val emittedName: Str = this.qualify(this.typePackage(enumName), enumName)
        val members: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.EnumMember)
        var values: List<Int> = List<Int>()
        var names: List<Str> = List<Str>()
        var next: Int = 0
        var i: Int = 0
        while (i < members.size()) {
            val member: AstXmlNode = members[i]
            if (xmlAttr(*member, AstNodeAttributeKind.HasValue) == "true") {
                next = xmlIntAttr(*member, AstNodeAttributeKind.Value, 0)
            }
            values.append(next)
            names.append(xmlAttr(*member, AstNodeAttributeKind.Name))
            next = next + 1
            i = i + 1
        }
        this.line(
            0, "inline Opt<" + emittedName + "> "
                    + this.qualify(this.typePackage(enumName), "simse_" + enumName + "_fromInt")
                    + "(Int value) {"
        )
        var k: Int = 0
        while (k < names.size()) {
            this.line(
                1, "if (value == " + values[k].toString() + ") return Opt<"
                        + emittedName + ">::some(" + emittedName + "::"
                        + names[k] + ");"
            )
            k = k + 1
        }
        this.line(1, "return Opt<" + emittedName + ">::none();")
        this.line(0, "}")
    }

    fun emitTypeAlias(decl: *AstXmlNode): Unit {
        val target: AstXmlNode = xmlChild(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(*target)) {
            this.fail(
                decl,
                "unsupported: typealias '" + xmlAttr(decl, AstNodeAttributeKind.Name) + "' without a target type"
            )
            return
        }
        this.setActiveTypeParams(xmlTypeParamNames(decl))
        val targetText: Str = this.type(*target)
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
            "using " + this.qualify(
                this.typePackage(xmlAttr(decl, AstNodeAttributeKind.Name)),
                xmlAttr(decl, AstNodeAttributeKind.Name)
            )
                    + " = " + targetText + ";"
        )
    }

    fun emitNativeDeclarations(): Unit {
        this.setActiveTypeParams(List<Str>())
        var i: Int = 0
        while (i < this.nativeDecls.size()) {
            val nativeInfo: CgNativeDecl = this.nativeDecls[i]
            i = i + 1
            if (nativeInfo.prelude) {
                continue
            }
            this.curFile = nativeInfo.file
            val decl: AstXmlNode = nativeInfo.decl
            this.setActiveTypeParams(xmlTypeParamNames(*decl))
            if (nativeInfo.symbol.find("::") != -1) {
                this.fail(
                    *decl, "unsupported: namespaced native symbol '" + nativeInfo.symbol
                            + "' needs a global wrapper"
                )
                return
            }
            val returnNode: AstXmlNode = xmlChild(*decl, AstNodeKind.ReturnType)
            var ret: Str = "void"
            if (!xmlIsEmpty(*returnNode)) {
                ret = this.type(*returnNode)
            }
            if (this.failed) {
                return
            }
            val params: List<AstXmlNode> = xmlChildren(*decl, AstNodeKind.Param)
            var paramTexts: List<Str> = List<Str>()
            var p: Int = 0
            while (p < params.size()) {
                val param: AstXmlNode = params[p]
                val paramType: AstXmlNode = xmlChild(*param, AstNodeKind.Type)
                if (xmlIsEmpty(*paramType)) {
                    this.fail(
                        *param, "unsupported: native parameter '" + xmlAttr(*param, AstNodeAttributeKind.Name)
                                + "' without a type"
                    )
                    return
                }
                val mapped: Str = this.type(*paramType)
                if (this.failed) {
                    return
                }
                var name: Str = xmlAttr(*param, AstNodeAttributeKind.Name)
                if (name == "this") {
                    name = "self"
                }
                val pk: AstNodeCategory = xmlKind(*paramType)
                if (pk == AstNodeCategory.TypePointer || pk == AstNodeCategory.TypeReference) {
                    paramTexts.append(mapped + " " + name)
                } else {
                    paramTexts.append("const " + mapped + "& " + name)
                }
                p = p + 1
            }
            this.sourceComment(*decl)
            val tmpl: Str = this.templateClause(xmlTypeParamNames(*decl))
            if (tmpl != "") {
                this.line(0, tmpl)
            }
            this.line(0, ret + " " + nativeInfo.symbol + "(" + cgJoin(*paramTexts, ", ") + ");")
        }
    }

    // ---- prelude reachability ---------------------------------------------

    // Every call name in a node's subtree: a callee is a name (`f(x)`), a generic name
    // (`f<Int>(x)`) or a member (`x.m(...)`), and in all three the call site spells it as
    // the `Name` attribute of the callee node.
    fun collectNames(node: AstXmlNode, names: *Dictionary<Str, Bool>): Unit {
        if (xmlKind(*node) == AstNodeCategory.ExprCall) {
            val callee: AstXmlNode = xmlChild(*node, AstNodeKind.Callee)
            val name: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
            if (name != "") {
                names.insert(name, true)
            }
        }
        this.collectTypeNames(node)
        var i: Int = 0
        while (i < node.Children.count()) {
            this.collectNames(node.Children[i], names)
            i = i + 1
        }
    }

    // Whether a prelude body is one the program reaches: its name is called, and - for an
    // extension - the program names the receiver's type as well. The prelude has one
    // `smToYield` per container (impl_specs/for.md), each container's machine is that
    // container's only, and the class name is the receiver's (`outerTypeName`).
    fun reachesPreludeBody(fn: *CgFn): Bool {
        if (xmlAttr(*fn.decl, AstNodeAttributeKind.HasBody) != "true") {
            return false
        }
        if (!this.referencedNames.has(xmlAttr(*fn.decl, AstNodeAttributeKind.Name))) {
            return false
        }
        val receiverName: Str = this.outerTypeName(*fn.receiver)
        if (receiverName == "") {
            return true
        }
        // A receiver that is the function's own type parameter says nothing - any type can
        // be one.
        if (xmlIsTypeParam(receiverName, *fn.templateParams)) {
            return true
        }
        return this.referencedTypes.has(receiverName)
    }

    // Fills `referencedNames` and `referencedTypes` from the program - never from the
    // prelude's own unused bodies - and closes both over the prelude the program reaches:
    // an emitted body may call another, and a native's signature is what says which types
    // a call reaches (`xs.toArray()` reaches an `Array`).
    fun collectProgramNames(): Unit {
        var i: Int = 0
        while (i < this.inputs.size()) {
            val input: CgInput = this.inputs[i]
            i = i + 1
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
                if (xmlAttr(*fn.decl, AstNodeAttributeKind.HasBody) == "true") {
                    if (!this.reachesPreludeBody(fn)) {
                        continue
                    }
                    this.collectNames(fn.decl, *this.referencedNames)
                    continue
                }
                if (!this.referencedNames.has(xmlAttr(*fn.decl, AstNodeAttributeKind.Name))) {
                    continue
                }
                this.collectTypeNames(fn.receiver)
                this.collectTypeNames(xmlChild(*fn.decl, AstNodeKind.ReturnType))
                val params: List<AstXmlNode> = xmlChildren(*fn.decl, AstNodeKind.Param)
                var p: Int = 0
                while (p < params.size()) {
                    this.collectTypeNames(xmlChild(*params[p], AstNodeKind.Type))
                    p = p + 1
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
            // A pointer into `functions`: `CgFn` carries two XmlNodes, so a copy
            // here would deep-copy the whole declaration per function.
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            // A prelude input is declarations-only *unless it has a body*: the RTL
            // declares natives, whose C++ is the header's (a native is skipped inside
            // `emitFunction`), and a prelude `fun` with a body is a function the language
            // itself provides - `List<T>.smToYield(): ..T` is the first one
            // (impl_specs/for.md). It is emitted when the program reaches it
            // (`reachesPreludeBody`), so a prelude body costs a program only what it
            // uses.
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
        this.selfType = copy(selfTypePtr)
        if (!xmlIsEmpty(*fn.receiver)) {
            this.nameKinds.insert("self", selfK)
        }
        val params: List<AstXmlNode> = xmlChildren(*fn.decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            val param: AstXmlNode = params[i]
            val paramType: AstXmlNode = xmlChild(*param, AstNodeKind.Type)
            if (!xmlIsEmpty(*paramType)) {
                this.nameKinds.insert(xmlAttr(*param, AstNodeAttributeKind.Name), this.kindOf(*paramType))
                this.localTypes.insert(xmlAttr(*param, AstNodeAttributeKind.Name), paramType)
            }
            i = i + 1
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

    // The name a machine's class is derived from: the function's own, prefixed with the
    // receiver's outer type name when the function is an extension (`List<T>`'s
    // `smToYield` is `List_smToYield`). The prelude provides a `smToYield` per container
    // (impl_specs/for.md), so the function name alone would give every container's machine
    // the same class name.
    fun machineName(decl: *AstXmlNode): Str {
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val receiver: AstXmlNode = xmlChild(decl, AstNodeKind.Receiver)
        val outer: Str = this.outerTypeName(*receiver)
        if (outer == "") {
            return name
        }
        return outer + "_" + name
    }

    // The outer name of a type, ignoring handles and arguments: `*List<Int>` and
    // `List<Str>` are both `List` - the name a receiver and a machine class are spelled
    // with.
    fun outerTypeName(typeNode: *AstXmlNode): Str {
        if (xmlIsEmpty(typeNode)) {
            return ""
        }
        var node: AstXmlNode = copy(typeNode)
        while (true) {
            val kind: AstNodeCategory = xmlKind(*node)
            if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
                break
            }
            val inner: AstXmlNode = xmlChild(*node, AstNodeKind.Inner)
            if (xmlIsEmpty(*inner)) {
                break
            }
            node = inner
        }
        val outerKind: AstNodeCategory = xmlKind(*node)
        if (outerKind != AstNodeCategory.TypeNamed && outerKind != AstNodeCategory.TypeGeneric) {
            return ""
        }
        return xmlAttr(*node, AstNodeAttributeKind.Name)
    }

    // Every type name in a type node, nesting included: `List<Array<Int>>` names both.
    // The roles are the positions a type occupies (`Type` for a declaration's, `Inner`
    // for a handle's pointee, ...), which is what makes this the same set in both rings.
    fun collectTypeNames(node: AstXmlNode): Unit {
        val role: AstNodeKind = node.name
        if (role != AstNodeKind.Type && role != AstNodeKind.Inner && role != AstNodeKind.TypeArg
            && role != AstNodeKind.ReturnType && role != AstNodeKind.TargetType
            && role != AstNodeKind.ParamType && role != AstNodeKind.Receiver
        ) {
            return
        }
        val name: Str = xmlAttr(*node, AstNodeAttributeKind.Name)
        if (name != "") {
            this.referencedTypes.insert(name, true)
        }
        var i: Int = 0
        while (i < node.Children.count()) {
            this.collectTypeNames(node.Children[i])
            i = i + 1
        }
    }

    // The names a body's own C++ scope already has: the parameters (and `self`, and the
    // one the argv form of `main` writes). A hoisted declaration may not collide with one
    // of them, so the hoisting renames it away (`linFinishForEmission`'s `reserved`).
    fun cgReservedNames(decl: *AstXmlNode, hasSelf: Bool, argv: Bool): List<Str> {
        var names: List<Str> = List<Str>()
        if (hasSelf) {
            names.append("self")
        }
        val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            names.append(xmlAttr(*params[i], AstNodeAttributeKind.Name))
            i = i + 1
        }
        if (argv) {
            names.append("simse_argIndex")
        }
        return names
    }

    fun emitFunction(fn: *CgFn, prototypeOnly: Bool, facts: *SemFacts): Unit {
        // The declaration is read-only here, so borrow it instead of copying the
        // whole function AST (params, body, ...) out of the CgFn.
        val decl: *AstXmlNode = *fn.decl
        if (xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true") {
            return
        }
        val isMain: Bool = xmlIsEmpty(*fn.receiver) && xmlAttr(decl, AstNodeAttributeKind.Name) == "main"
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
        val returnNode: AstXmlNode = xmlChild(decl, AstNodeKind.ReturnType)
        // A body that yields is lowered to a state machine, and the function to a factory
        // for it (impl_specs/yield.md) - so the return type of the emitted function is the
        // machine's class, not the `..T` the source wrote. For a generic function the
        // class is a template, so its *name* carries the type parameters wherever it is a
        // type (inside the class the injected-class-name covers `self`).
        val yielding: Bool = !xmlIsEmpty(*returnNode) && xmlKind(*returnNode) == AstNodeCategory.TypeYield
        val yieldClass: Str = this.qualify(fn.packageName, this.machineName(decl)) + "_yieldable"
        var yieldType: Str = yieldClass
        if (yielding && fn.templateParams.size() > 0) {
            yieldType = yieldClass + "<" + cgJoin(*fn.templateParams, ", ") + ">"
        }
        var ret: Str = "void"
        if (isMain) {
            ret = "int"
        } else if (yielding) {
            ret = yieldType
        } else if (!xmlIsEmpty(*returnNode)) {
            ret = this.type(*returnNode)
        }
        if (this.failed) {
            return
        }

        var params: List<Str> = List<Str>()
        var hasSelf: Bool = false
        var selfK: NameKind = NameKind.Value
        var selfTypePtr: AstXmlNode = xmlEmptyNode()
        if (!xmlIsEmpty(*fn.receiver)) {
            params.append(this.receiverParam(*fn.receiver))
            hasSelf = true
            selfK = this.kindOf(*fn.receiver)
            selfTypePtr = fn.receiver
            if (this.failed) {
                return
            }
        }
        // The argv form's parameter is built from argc/argv, not passed.
        if (!mainArgs) {
            var i: Int = 0
            while (i < params0.size()) {
                val param: AstXmlNode = params0[i]
                val paramType: AstXmlNode = xmlChild(*param, AstNodeKind.Type)
                if (xmlIsEmpty(*paramType)) {
                    this.fail(
                        *param,
                        "unsupported: parameter '" + xmlAttr(*param, AstNodeAttributeKind.Name) + "' without a type"
                    )
                    return
                }
                if (xmlAttr(*param, AstNodeAttributeKind.Name) == "this" && !hasSelf) {
                    params.append(this.receiverParam(*paramType))
                    hasSelf = true
                    selfK = this.kindOf(*paramType)
                    selfTypePtr = paramType
                } else {
                    params.append(this.type(*paramType) + " " + xmlAttr(*param, AstNodeAttributeKind.Name))
                }
                if (this.failed) {
                    return
                }
                i = i + 1
            }
        }
        if (!hasSelf) {
            selfK = NameKind.Value
        }

        // The entry point keeps its unprefixed name; every other function is
        // emitted under its package's prefix (package qualification above).
        var fnName: Str = "main"
        if (!isMain) {
            fnName = this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
        }
        var signature: Str = ret + " " + fnName + "(" + cgJoin(*params, ", ") + ")"
        if (mainArgs) {
            signature = "int main(int argc, char** argv)"
        }
        val tmpl: Str = this.templateClause(fn.templateParams)
        if (yielding) {
            // The machine + the factory, and nothing else: the body of the source function
            // *is* the machine.
            this.emitYieldable(fn, decl, yieldClass, yieldType, prototypeOnly, selfK, *selfTypePtr, facts)
            return
        }
        if (prototypeOnly) {
            if (tmpl != "") {
                this.line(0, tmpl)
            }
            this.line(0, signature + ";")
            return
        }
        if (xmlAttr(decl, AstNodeAttributeKind.HasBody) != "true") {
            return
        }

        this.sourceComment(decl)
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, signature + " {")
        this.beginScope(fn, selfK, *selfTypePtr)
        if (isMain && this.hasStaticInit()) {
            // Static storage is initialized before the body runs (specs/statics.md).
            this.line(1, "simse_initStatics();")
        }
        if (mainArgs) {
            val argName: Str = xmlAttr(*params0[0], AstNodeAttributeKind.Name)
            this.line(1, "List<Str> " + argName + " = List<Str>();")
            this.line(1, "int simse_argIndex = 1;")
            this.line(1, "while (simse_argIndex < argc) {")
            this.line(2, "simse_list_append(" + argName + ", Str(argv[simse_argIndex]));")
            this.line(2, "simse_argIndex = simse_argIndex + 1;")
            this.line(1, "}")
        }
        this.curReturnType = returnNode
        // Structured control flow is lowered to labels/gotos, its expressions are
        // extracted into temporaries, and the blocks the lowering wrapped around a
        // declaration are folded again - in a loop, because each stage can leave work
        // for the others (impl_specs/linear-lowering.md). The type pass then spells
        // the declarations, which is what lets their own storage move to the top of
        // the body (`linFinishForEmission`) and the folding finish the job: the
        // emitter below knows the linear forms only.
        var lowered: List<AstXmlNode> =
            linLowerForEmission(xmlChildren(*xmlChild(decl, AstNodeKind.Body), AstNodeKind.Stmt))
        val semantics: SemBody = SemBody(
            copy(decl), fn.templateParams, copy(selfTypePtr),
            List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
        )
        // The proof of the pass, kept: it is what tells the backend a slot holds a
        // machine, which a declaration can never say (`..T` is not spellable).
        var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        lowered = semInferTypes(*lowered, facts, *semantics, *inferred)
        val finalBody: List<AstXmlNode> = linFinishForEmission(
            lowered,
            this.cgReservedNames(decl, !xmlIsEmpty(*fn.receiver), mainArgs)
        )
        this.dumpIl(fn, decl, finalBody, facts, *inferred)
        this.emitBodyAt(this.ilFunctionFor(fn, decl, facts, *inferred), finalBody, fn.file, 1)
        if (this.failed) {
            return
        }
        this.line(0, "}")
    }

    // ---- the linear IL ----------------------------------------------------

    // `--showLinearRepresentation`: the IL of the body the emitter is about to read, on
    // stderr (impl_specs/linear-il.md). The extraction is pure, so the emitted C++ is the
    // same with and without it.
    fun dumpIl(
        fn: *CgFn, decl: *AstXmlNode, body: List<AstXmlNode>, facts: *SemFacts,
        inferred: *Dictionary<Str, AstXmlNode>
    ): Unit {
        if (!ilShow()) {
            return
        }
        val unit: IlUnit = ilExtractUnit(
            this.ilFunctionFor(fn, decl, facts, inferred), body, fn.file
        )
        val text: Str = printIlUnit(*unit)
        // `eprintln` is the one stderr write the prelude has, and it adds the newline the
        // dump already ends with: drop that one byte so the rings' dumps compare byte for
        // byte.
        if (text.size() > 0) {
            eprintln(text.substr(0, text.size() - 1))
        }
    }

    // What the extractor needs to know about the body's function: the declaration (name,
    // parameters, return type), the receiver, the emitted symbol, and the file-level
    // statics the body may name.
    fun ilFunctionFor(
        fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts,
        inferred: *Dictionary<Str, AstXmlNode>
    ): IlFunction {
        var symbol: Str = this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
        if (xmlIsEmpty(*fn.receiver) && xmlAttr(decl, AstNodeAttributeKind.Name) == "main") {
            symbol = "main"
        }
        var info: IlFunction = IlFunction(
            copy(decl), fn.receiver, symbol,
            Dictionary<Str, Str>(), List<Str>(), List<AstXmlNode>(),
            "", Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
            facts, fn.templateParams, inferred
        )
        var i: Int = 0
        while (i < this.statics.size()) {
            val entry: CgStatic = this.statics[i]
            val typeNode: AstXmlNode = xmlChild(*entry.decl, AstNodeKind.Type)
            if (!xmlIsEmpty(*typeNode)) {
                info.statics.insert(
                    xmlAttr(*entry.decl, AstNodeAttributeKind.Name),
                    ilTypeText(*typeNode)
                )
            }
            i = i + 1
        }
        return info
    }

    // ---- emitting from the IL ---------------------------------------------
    //
    // The C++ of a body comes from its instruction list - the IL is the *only* codegen
    // (impl_specs/linear-il.md). The IL is not a second language with a second spelling: an
    // operand becomes a leaf `AstXmlNode` - a slot is a name, a constant is its literal, a
    // place is the path it came from, folded back out of the instruction that built it -
    // and the helpers above write the text.

    fun ilIntAt(map: *Dictionary<Int, Int>, key: Int, fallback: Int): Int {
        if (map.has(key)) {
            return map.get(key).value()
        }
        return fallback
    }

    fun ilBump(map: *Dictionary<Int, Int>, key: Int): Unit {
        map.insert(key, this.ilIntAt(map, key, 0) + 1)
    }

    fun ilOperandAt(operands: *List<Int>, index: Int): Int {
        if (index >= 0 && index < operands.size()) {
            return operands[index]
        }
        return -1
    }

    fun ilNameNode(text: Str): AstXmlNode {
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
        return node
    }

    // The destination slot of an instruction, or -1 when it writes memory or jumps
    // instead (`ilWritesDestination` is the one place that is stated).
    fun ilDst(op: *IlOp): Int {
        if (!ilWritesDestination(op.name) || op.operands.size() == 0) {
            return -1
        }
        return op.operands[0]
    }

    // The frame's types, from the body's own tables - no statement tree is read. First
    // what the *type pass* proved for every name in the body, then the slots' declared
    // types (which win: they are the spelled ones, and the map may still hold a name the
    // shadowing pass renamed). The pass's record is what carries a machine: a slot holding
    // one is `..T`, a declaration is never written with that (the emitted C++ uses
    // `auto`), so the frame is the only place the type survives - and the emitter needs
    // it, because `x.smToYield()` on a machine *is* `x`, an identity decided from the
    // receiver's type (see `call`, impl_specs/for.md).
    fun ilSeedFrameTypes(il: *IlBody): Unit {
        val proven: List<Str> = il.inferredTypes.keys()
        var i: Int = 0
        while (i < proven.size()) {
            val typeNode: AstXmlNode = il.inferredTypes.get(proven[i]).value()
            this.localTypes.insert(proven[i], typeNode)
            this.nameKinds.insert(proven[i], this.kindOf(*typeNode))
            i = i + 1
        }
        i = 0
        while (i < il.vars.size()) {
            val slotType: AstXmlNode = ilVarType(il, i)
            if (!xmlIsEmpty(*slotType)) {
                this.localTypes.insert(il.vars[i].name, slotType)
                this.nameKinds.insert(il.vars[i].name, this.kindOf(*slotType))
            }
            i = i + 1
        }
    }

    fun ilAnalyze(il: *IlBody, frame: *IlFrame): Unit {
        var i: Int = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            // A declaration reads nothing.
            if (op.name != "Declare") {
                // An instruction that writes memory or jumps has no destination, but its
                // operands are reads like any other - so the two are counted apart, and
                // the first operand is only skipped when it is in fact the destination.
                val dst: Int = this.ilDst(op)
                if (dst >= 0 && dst < il.vars.size()) {
                    // The *first* write is what initialises a slot: a loop target is
                    // written again every iteration, and the declaration that spells it
                    // wants the initializer, not the increment.
                    if (!frame.defOp.has(dst)) {
                        frame.defOp.insert(dst, i)
                    }
                    this.ilBump(*frame.defineCount, dst)
                }
                var j: Int = 0
                while (j < op.operands.size()) {
                    var read: Bool = true
                    if (j == 0 && dst >= 0) {
                        read = false
                    }
                    val kind: IlOperandKind = ilOperandKind(op, j)
                    if (kind != IlOperandKind.Var && kind != IlOperandKind.Value) {
                        // A label, a pool entry, a type, a callee.
                        read = false
                    }
                    if (read) {
                        val operand: Int = op.operands[j]
                        if (operand >= 0 && operand < il.vars.size()) {
                            this.ilBump(*frame.useCount, operand)
                        }
                    }
                    j = j + 1
                }
            }
            i = i + 1
        }
    }

    // Whether an instruction's result is inlined at its use instead of being assigned to
    // a slot: the extractor's own temporaries (a place, a constant expression) with a
    // single definition and a single use are skipped, and the expression stands where
    // they were read - which is where the statement path inlined it.
    fun ilFolded(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
        if (slot < 0 || slot >= il.vars.size()) {
            return false
        }
        if (il.vars[slot].kind != IlVarKind.Temp) {
            return false
        }
        if (this.ilIntAt(*frame.defineCount, slot, 0) != 1) {
            return false
        }
        if (this.ilIntAt(*frame.useCount, slot, 0) != 1) {
            return false
        }
        // A closure is an aggregate, not an expression: it keeps its slot.
        return !this.ilSlotHoldsClosure(il, frame, slot)
    }

    // Whether an instruction builds a closure class instance, which is an *aggregate* and
    // not an expression: it cannot stand inside another expression, so its slot is never
    // folded away.
    fun ilConstructsClosure(op: *IlOp, il: *IlBody): Bool {
        if (op.name != "CallCtor") {
            return false
        }
        val typeAt: Int = this.ilOperandAt(*op.operands, 1)
        if (typeAt < 0 || typeAt >= il.types.size()) {
            return false
        }
        return this.closureSymbols.has(il.types[typeAt])
    }

    // The same question, asked about a slot: is the instruction that defines it a closure
    // construction?
    fun ilSlotHoldsClosure(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
        val def: Int = this.ilIntAt(*frame.defOp, slot, -1)
        if (def < 0 || def >= il.ops.size()) {
            return false
        }
        return this.ilConstructsClosure(*il.ops[def], il)
    }

    // The C++ of a slot's declared type. A closure class is spelled by its own name: it is
    // emitted just above the body that constructs it, so no type dictionary knows it.
    fun ilDeclTypeText(il: *IlBody, slot: Int): Str {
        val slotType: AstXmlNode = ilVarType(il, slot)
        if (xmlIsEmpty(*slotType)) {
            return ""
        }
        val typeIndex: Int = il.vars[slot].typeIndex
        if (typeIndex >= 0 && typeIndex < il.types.size() && this.closureSymbols.has(il.types[typeIndex])) {
            return il.types[typeIndex]
        }
        return this.type(*slotType)
    }

    // A constant operand: the pool holds the text the C++ prints, so all that is left is
    // to give it the node kind the emitter expects (`true` is a BoolLit, `"abc"` a
    // StrLit, a digit run an IntLit or a FloatLit).
    fun ilLiteralNode(text: Str): AstXmlNode {
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIntLit, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
        var dot: Bool = false
        var i: Int = 0
        while (i < text.size()) {
            if (text[i] == '.') {
                dot = true
            }
            i = i + 1
        }
        if (text.isEmpty() || text[0] == '"') {
            node.kind = AstNodeCategory.ExprStrLit
        } else if (text[0] == '\'') {
            node.kind = AstNodeCategory.ExprCharLit
        } else if (text == "true" || text == "false") {
            node.kind = AstNodeCategory.ExprBoolLit
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Value, text))
        } else if (dot) {
            node.kind = AstNodeCategory.ExprFloatLit
        }
        return node
    }

    fun ilSlotNode(il: *IlBody, frame: *IlFrame, slot: Int, depth: Int): AstXmlNode {
        if (slot < 0 || slot >= il.vars.size() || depth > 24) {
            return xmlEmptyNode()
        }
        val name: Str = il.vars[slot].name
        // The receiver slot is the language's `this`, which the emitter spells `(*self)`
        // (`(*this)` inside a closure class).
        if (name == "self") {
            return this.ilNameNode("this")
        }
        if (this.ilFolded(il, frame, slot)) {
            return this.ilOpValueNode(il, frame, this.ilIntAt(*frame.defOp, slot, -1), depth + 1)
        }
        return this.ilNameNode(name)
    }

    fun ilOperandNode(il: *IlBody, frame: *IlFrame, operand: Int, depth: Int): AstXmlNode {
        if (operand < 0) {
            val index: Int = -1 - operand
            if (index >= il.pool.size()) {
                return xmlEmptyNode()
            }
            return this.ilLiteralNode(il.pool[index])
        }
        return this.ilSlotNode(il, frame, operand, depth)
    }

    fun ilMemberNode(base: *AstXmlNode, name: Str): AstXmlNode {
        if (xmlIsEmpty(base)) {
            return xmlEmptyNode()
        }
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        xmlAddChild(*node, this.renameRole(base, AstNodeKind.Receiver))
        return node
    }

    fun ilBinaryNode(lhs: *AstXmlNode, op: Str, rhs: *AstXmlNode): AstXmlNode {
        if (xmlIsEmpty(lhs) || xmlIsEmpty(rhs)) {
            return xmlEmptyNode()
        }
        var node: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, List<AstNodeAttribute>(), Array<AstXmlNode>())
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
        xmlAddChild(*node, this.renameRole(lhs, AstNodeKind.Lhs))
        xmlAddChild(*node, this.renameRole(rhs, AstNodeKind.Rhs))
        return node
    }

    // The last `.` in `text`, or -1.
    fun ilLastDot(text: Str): Int {
        var dot: Int = -1
        var i: Int = 0
        while (i < text.size()) {
            if (text[i] == '.') {
                dot = i
            }
            i = i + 1
        }
        return dot
    }

    // A name in a value position that is not a local: an enum member (`Color.Red`,
    // spelled `ns1_Color::Red`) or a file-level `var`.
    fun ilGetStaticNode(il: *IlBody, op: *IlOp): AstXmlNode {
        val textIndex: Int = this.ilOperandAt(*op.operands, 1)
        if (textIndex < 0 || textIndex >= il.pool.size()) {
            return xmlEmptyNode()
        }
        val text: Str = il.pool[textIndex]
        val dot: Int = this.ilLastDot(text)
        if (dot <= 0) {
            return this.ilNameNode(text)
        }
        val base: AstXmlNode = this.ilNameNode(text.substr(0, dot))
        return this.ilMemberNode(*base, text.substr(dot + 1, text.size() - dot - 1))
    }

    // The node for the type a *static* call is reached through: `Color.fromInt` keeps its
    // name, `Res<Str>.ok` its type arguments.
    //
    // `asGenericName` is for a construction, whose callee the parser always produced as
    // `Name<T>` (that is what makes it a `CallCtor`); a static call's base is a generic
    // name only when the source wrote one.
    fun ilTypeBaseNode(il: *IlBody, typeIndex: Int, asGenericName: Bool): AstXmlNode {
        val baseType: AstXmlNode = ilTypeNode(il, typeIndex)
        if (xmlIsEmpty(*baseType)) {
            return xmlEmptyNode()
        }
        val args: List<AstXmlNode> = xmlChildren(*baseType, AstNodeKind.TypeArg)
        if (xmlKind(*baseType) == AstNodeCategory.TypeGeneric && (asGenericName || args.size() > 0)) {
            var node: AstXmlNode = AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprGenericName,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            node.attributes.append(
                AstNodeAttribute(
                    AstNodeAttributeKind.Name,
                    xmlAttr(*baseType, AstNodeAttributeKind.Name)
                )
            )
            var i: Int = 0
            while (i < args.size()) {
                xmlAddChild(*node, this.renameRole(*args[i], AstNodeKind.TypeArg))
                i = i + 1
            }
            return node
        }
        return this.ilNameNode(xmlAttr(*baseType, AstNodeAttributeKind.Name))
    }

    // A call instruction as the expression the emitter spells: the callee from the method
    // table, the arguments from the operands.
    fun ilCallNode(il: *IlBody, frame: *IlFrame, op: *IlOp): AstXmlNode {
        val hasDst: Bool = this.ilDst(op) >= 0
        var methodAt: Int = 0
        if (hasDst) {
            methodAt = 1
        }
        val methodIndex: Int = this.ilOperandAt(*op.operands, methodAt)
        if (methodIndex < 0 || methodIndex >= il.methods.size()) {
            this.ilWhy = "a call with no callee"
            return xmlEmptyNode()
        }
        val method: *IlMethod = *il.methods[methodIndex]

        var call: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>())
        var first: Int = methodAt + 1
        var callee: AstXmlNode = xmlEmptyNode()
        if (method.kind == IlMethodKind.Method) {
            val recv: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, first), 0)
            if (xmlIsEmpty(*recv)) {
                this.ilWhy = "the receiver of '" + method.name + "'"
                return xmlEmptyNode()
            }
            callee = this.ilMemberNode(*recv, method.name)
            first = first + 1
        } else if (method.staticBase >= 0) {
            val base: AstXmlNode = this.ilTypeBaseNode(il, method.staticBase, false)
            if (xmlIsEmpty(*base)) {
                this.ilWhy = "the type '" + method.name + "' is reached through"
                return xmlEmptyNode()
            }
            callee = this.ilMemberNode(*base, method.name)
        } else {
            callee = this.ilNameNode(method.name)
        }
        if (xmlIsEmpty(*callee)) {
            return xmlEmptyNode()
        }
        xmlAddChild(*call, this.renameRole(*callee, AstNodeKind.Callee))
        var i: Int = first
        while (i < op.operands.size()) {
            val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
            if (xmlIsEmpty(*arg)) {
                this.ilWhy = "an argument of '" + method.name + "'"
                return xmlEmptyNode()
            }
            xmlAddChild(*call, this.renameRole(*arg, AstNodeKind.Arg))
            i = i + 1
        }
        return call
    }

    // The expression a value-producing instruction computes, as a node. This is both the
    // right-hand side of the instruction and what a *folded* slot stands for wherever it
    // is read.
    fun ilOpValueNode(il: *IlBody, frame: *IlFrame, opIndex: Int, depth: Int): AstXmlNode {
        if (opIndex < 0 || opIndex >= il.ops.size() || depth > 24) {
            return xmlEmptyNode()
        }
        val op: *IlOp = *il.ops[opIndex]
        val name: Str = op.name
        if (name == "SetVar") {
            return this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 1), depth)
        }
        if (name == "SetVar_Null") {
            return AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprNullLit,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
        }
        if (name == "BinaryOp") {
            val opIndex2: Int = this.ilOperandAt(*op.operands, 1)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val lhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 2), depth)
            val rhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 3), depth)
            return this.ilBinaryNode(*lhs, il.pool[opIndex2], *rhs)
        }
        if (name == "UnaryOp") {
            val opIndex2: Int = this.ilOperandAt(*op.operands, 1)
            val operand: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 2), depth)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size() || xmlIsEmpty(*operand)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, List<AstNodeAttribute>(), Array<AstXmlNode>())
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, il.pool[opIndex2]))
            xmlAddChild(*node, this.renameRole(*operand, AstNodeKind.Operand))
            return node
        }
        if (name == "GetField" || name == "FieldAddr") {
            val textIndex: Int = this.ilOperandAt(*op.operands, 2)
            if (textIndex < 0 || textIndex >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 1), depth)
            return this.ilMemberNode(*base, il.pool[textIndex])
        }
        if (name == "IndexAddr" || name == "GetIndex") {
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 1), depth)
            val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 2), depth)
            if (xmlIsEmpty(*base) || xmlIsEmpty(*index)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIndex, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(*node, this.renameRole(*base, AstNodeKind.Receiver))
            xmlAddChild(*node, this.renameRole(*index, AstNodeKind.Index))
            return node
        }
        if (name == "Deref" || name == "CopyValue" || name == "Box") {
            val operand: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 1), depth)
            if (xmlIsEmpty(*operand)) {
                return xmlEmptyNode()
            }
            var category: AstNodeCategory = AstNodeCategory.ExprDeref
            if (name == "CopyValue") {
                category = AstNodeCategory.ExprCopy
            } else if (name == "Box") {
                category = AstNodeCategory.ExprRef
            }
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, category, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(*node, this.renameRole(*operand, AstNodeKind.Operand))
            return node
        }
        if (name == "GetStatic") {
            return this.ilGetStaticNode(il, op)
        }
        if (name == "Call" || name == "CallVoid") {
            return this.ilCallNode(il, frame, op)
        }
        if (name == "CallCtor") {
            val callee: AstXmlNode = this.ilTypeBaseNode(il, this.ilOperandAt(*op.operands, 1), true)
            if (xmlIsEmpty(*callee)) {
                return xmlEmptyNode()
            }
            var call: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(*call, this.renameRole(*callee, AstNodeKind.Callee))
            var i: Int = 2
            while (i < op.operands.size()) {
                val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
                if (xmlIsEmpty(*arg)) {
                    return xmlEmptyNode()
                }
                xmlAddChild(*call, this.renameRole(*arg, AstNodeKind.Arg))
                i = i + 1
            }
            return call
        }
        // `Cast` (no source node), `CallIndirect` (a callable slot), a lambda body, and
        // anything the extractor marked: not expressible yet.
        return xmlEmptyNode()
    }

    // Where a jump crosses a declaration, C++ wants a scope: a `goto` may not skip an
    // initialization ([stmt.dcl]/3, MSVC C2362). The flat form has no scopes of its own,
    // so the backend opens the *one* block that keeps the declaration legal - the same one
    // the statement path keeps - and closes it at the label the jump lands on.
    fun ilJumpCrossing(il: *IlBody, position: Int): IlCrossing {
        var crossing: IlCrossing = IlCrossing(-1, -1)
        var labelPos: Dictionary<Int, Int> = Dictionary<Int, Int>()
        var i: Int = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            if (op.name == "Label" && op.operands.size() > 0) {
                labelPos.insert(op.operands[0], i)
            }
            i = i + 1
        }
        i = 0
        while (i < position) {
            val op: *IlOp = *il.ops[i]
            var labelAt: Int = -1
            if (op.name == "Goto") {
                labelAt = 0
            } else if (op.name == "IfTrue" || op.name == "IfFalse") {
                labelAt = 1
            }
            if (labelAt >= 0) {
                val target: Int = this.ilOperandAt(*op.operands, labelAt)
                if (labelPos.has(target)) {
                    val at: Int = labelPos.get(target).value()
                    if (at >= position) {
                        if (crossing.end < 0 || at < crossing.end) {
                            crossing.end = at
                        }
                        crossing.lastJump = i
                    }
                }
            }
            i = i + 1
        }
        return crossing
    }

    // The right-hand side of one instruction, as C++: the expression the instruction
    // computes, or - for a construction that is an aggregate - the brace form, which has
    // no expression node.
    fun ilValueText(il: *IlBody, frame: *IlFrame, opIndex: Int, expected: *AstXmlNode): Opt<Str> {
        if (opIndex < 0 || opIndex >= il.ops.size()) {
            return Opt<Str>.none()
        }
        val op: *IlOp = *il.ops[opIndex]
        if (op.name == "CallCtor") {
            val typeAt: Int = this.ilOperandAt(*op.operands, 1)
            if (typeAt >= 0 && typeAt < il.types.size() && this.closureSymbols.has(il.types[typeAt])) {
                var captured: List<Str> = List<Str>()
                var j: Int = 2
                while (j < op.operands.size()) {
                    val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[j], 0)
                    if (xmlIsEmpty(*arg)) {
                        return Opt<Str>.none()
                    }
                    captured.append(this.expr(*arg, 0, *xmlEmptyNode()))
                    j = j + 1
                }
                return Opt<Str>.some(il.types[typeAt] + "{" + cgJoin(*captured, ", ") + "}")
            }
        }
        val node: AstXmlNode = this.ilOpValueNode(il, frame, opIndex, 0)
        if (xmlIsEmpty(*node)) {
            return Opt<Str>.none()
        }
        return Opt<Str>.some(this.expr(*node, 0, expected))
    }

    fun ilLine(out: *Str, level: Int, text: Str): Unit {
        out.appendStr(cgIndent(level))
        out.appendStr(text)
        out.append('\n')
    }

    // One body, instruction by instruction. Each instruction is one statement of the
    // emitted C++ (a `Declare` pairs with the instruction that writes it), which is the
    // whole point of the form.
    fun ilEmitOps(il: *IlBody, frame: *IlFrame, level: Int): IlText {
        // A declaration a jump can cross needs a block around it: the jump may not enter
        // the declaration's scope past it.
        var blockEnd: Dictionary<Int, Int> = Dictionary<Int, Int>()
        var scan: Int = 0
        while (scan < il.ops.size()) {
            val op: *IlOp = *il.ops[scan]
            if (op.name == "Declare" || op.name == "DeclareInit") {
                // A declaration that prints nothing - the folding inlines it at its use -
                // keeps nothing legal, so it asks for no block either.
                var folded: Bool = false
                if (op.operands.size() > 0) {
                    folded = this.ilFolded(il, frame, op.operands[0])
                }
                if (!folded) {
                    val crossing: IlCrossing = this.ilJumpCrossing(il, scan)
                    if (crossing.end >= 0) {
                        blockEnd.insert(scan, crossing.end)
                    }
                }
            }
            scan = scan + 1
        }
        var scopes: List<IlScope> = List<IlScope>()
        var consumedByDeclare: Int = -1
        var lvl: Int = level
        var text: Str = Str()

        var i: Int = 0
        while (i < il.ops.size()) {
            // A block ends where its jump lands: the target stays outside it.
            while (scopes.size() > 0 && scopes[scopes.size() - 1].end == i) {
                scopes.removeAt(scopes.size() - 1)
                lvl = lvl - 1
                this.ilLine(*text, lvl, "}")
            }
            if (i == consumedByDeclare) {
                i = i + 1
                continue
            }
            val op: *IlOp = *il.ops[i]
            val name: Str = op.name
            val dst: Int = this.ilDst(op)

            if (blockEnd.has(i)) {
                val end: Int = blockEnd.get(i).value()
                val lastJump: Int = this.ilJumpCrossing(il, i).lastJump
                var covered: Bool = false
                var s: Int = 0
                while (s < scopes.size()) {
                    if (scopes[s].start > lastJump && scopes[s].end >= end) {
                        covered = true
                    }
                    s = s + 1
                }
                if (!covered) {
                    this.ilLine(*text, lvl, "{")
                    lvl = lvl + 1
                    scopes.append(IlScope(i, end))
                }
            }

            if (name == "Declare" || name == "DeclareInit") {
                val slot: Int = this.ilOperandAt(*op.operands, 0)
                if (slot < 0 || slot >= il.vars.size()) {
                    return IlText(false, "", "a declare with no slot")
                }
                if (this.ilFolded(il, frame, slot)) {
                    i = i + 1
                    continue // inlined at its use
                }
                val slotType: AstXmlNode = ilVarType(il, slot)
                // A slot the type pass could not spell is still declarable when the
                // declaration initialises it: `auto`, exactly as the statement path
                // writes it.
                val initialized: Bool = name == "DeclareInit"
                if (xmlIsEmpty(*slotType) && !initialized) {
                    return IlText(
                        false, "", "the slot '" + il.vars[slot].name
                                + "' has neither a type nor an initializer"
                    )
                }
                var decl: Str = "auto " + il.vars[slot].name
                if (!xmlIsEmpty(*slotType)) {
                    decl = this.ilDeclTypeText(il, slot) + " " + il.vars[slot].name
                }
                if (initialized) {
                    // An initialised declaration is one line, exactly as the statement
                    // path writes it (`Str out = "";`) - and the instruction that
                    // computes the value can be a few further down, with the
                    // initializer's own temporaries in between (which are folded away,
                    // so they print nothing).
                    val def: Int = this.ilIntAt(*frame.defOp, slot, -1)
                    val valueText: Opt<Str> = this.ilValueText(il, frame, def, *slotType)
                    if (!valueText.hasValue()) {
                        // The initializer has no expression form - a closure is an
                        // aggregate, not a call - so the slot is declared bare and the
                        // instruction assigns it.
                        this.ilLine(*text, lvl, decl + ";")
                        i = i + 1
                        continue
                    }
                    this.ilLine(*text, lvl, decl + " = " + valueText.value() + ";")
                    consumedByDeclare = def
                    i = i + 1
                    continue
                }
                this.ilLine(*text, lvl, decl + ";")
                i = i + 1
                continue
            }
            if (name == "Label") {
                val label: Int = this.ilOperandAt(*op.operands, 0)
                if (label < 0 || label >= il.labels.size()) {
                    return IlText(false, "", "a label with no name")
                }
                this.ilLine(*text, lvl, il.labels[label] + ":;")
                i = i + 1
                continue
            }
            if (name == "Goto" || name == "IfTrue" || name == "IfFalse") {
                var labelAt: Int = 1
                if (name == "Goto") {
                    labelAt = 0
                }
                val label: Int = this.ilOperandAt(*op.operands, labelAt)
                if (label < 0 || label >= il.labels.size()) {
                    return IlText(false, "", "a jump with no label")
                }
                val target: Str = il.labels[label]
                if (name == "Goto") {
                    this.ilLine(*text, lvl, "goto " + target + ";")
                    i = i + 1
                    continue
                }
                val cond: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 0), 0)
                if (xmlIsEmpty(*cond)) {
                    return IlText(false, "", "a jump with no condition")
                }
                val test: Str = this.expr(*cond, 0, *xmlEmptyNode())
                var jumpLine: Str = "if (!(" + test + ")) goto " + target + ";"
                if (name == "IfTrue") {
                    jumpLine = "if (" + test + ") goto " + target + ";"
                }
                this.ilLine(*text, lvl, jumpLine)
                i = i + 1
                continue
            }
            if (dst >= 0 && this.ilFolded(il, frame, dst)) {
                i = i + 1
                continue // inlined at its use
            }
            if (dst >= 0) {
                val slotType: AstXmlNode = ilVarType(il, dst)
                if (xmlIsEmpty(*slotType)) {
                    return IlText(
                        false, "", "the slot '" + il.vars[dst].name
                                + "' has no type to assign"
                    )
                }
                val valueText: Opt<Str> = this.ilValueText(il, frame, i, *slotType)
                if (!valueText.hasValue()) {
                    if (this.ilWhy.isEmpty()) {
                        return IlText(false, "", "'" + name + "' cannot be expressed yet")
                    }
                    return IlText(false, "", "cannot express " + this.ilWhy)
                }
                this.ilLine(*text, lvl, il.vars[dst].name + " = " + valueText.value() + ";")
                i = i + 1
                continue
            }
            if (name == "Store") {
                val ptr: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 0), 0)
                val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 1), 0)
                if (xmlIsEmpty(*ptr) || xmlIsEmpty(*value)) {
                    return IlText(false, "", "a store with no pointer")
                }
                var target: AstXmlNode = AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprDeref,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
                xmlAddChild(*target, this.renameRole(*ptr, AstNodeKind.Operand))
                val targetType: AstXmlNode = this.inferType(*target)
                this.ilLine(
                    *text, lvl, this.expr(*target, 0, *xmlEmptyNode()) + " = "
                            + this.expr(*value, 0, *targetType) + ";"
                )
                i = i + 1
                continue
            }
            if (name == "SetField" || name == "SetIndex") {
                var target: AstXmlNode = xmlEmptyNode()
                if (name == "SetField") {
                    val textIndex: Int = this.ilOperandAt(*op.operands, 1)
                    if (textIndex < 0 || textIndex >= il.pool.size()) {
                        return IlText(false, "", "a field write with no name")
                    }
                    val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 0), 0)
                    target = this.ilMemberNode(*base, il.pool[textIndex])
                } else {
                    val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOperandAt(*op.operands, 0), 0)
                    val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 1), 0)
                    if (!xmlIsEmpty(*base) && !xmlIsEmpty(*index)) {
                        target = AstXmlNode(
                            AstNodeKind.Expr,
                            AstNodeCategory.ExprIndex,
                            List<AstNodeAttribute>(),
                            Array<AstXmlNode>()
                        )
                        xmlAddChild(*target, this.renameRole(*base, AstNodeKind.Receiver))
                        xmlAddChild(*target, this.renameRole(*index, AstNodeKind.Index))
                    }
                }
                val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 2), 0)
                if (xmlIsEmpty(*target) || xmlIsEmpty(*value)) {
                    return IlText(false, "", "a write with no target")
                }
                val targetType: AstXmlNode = this.inferType(*target)
                this.ilLine(
                    *text, lvl, this.expr(*target, 0, *xmlEmptyNode()) + " = "
                            + this.expr(*value, 0, *targetType) + ";"
                )
                i = i + 1
                continue
            }
            if (name == "SetStatic") {
                val textIndex: Int = this.ilOperandAt(*op.operands, 0)
                val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 1), 0)
                if (textIndex < 0 || textIndex >= il.pool.size() || xmlIsEmpty(*value)) {
                    return IlText(false, "", "a static write with no target")
                }
                val staticText: Str = il.pool[textIndex]
                val dot: Int = this.ilLastDot(staticText)
                var target: AstXmlNode = this.ilNameNode(staticText)
                if (dot > 0) {
                    val base: AstXmlNode = this.ilNameNode(staticText.substr(0, dot))
                    target = this.ilMemberNode(*base, staticText.substr(dot + 1, staticText.size() - dot - 1))
                }
                val targetType: AstXmlNode = this.inferType(*target)
                this.ilLine(
                    *text, lvl, this.expr(*target, 0, *xmlEmptyNode()) + " = "
                            + this.expr(*value, 0, *targetType) + ";"
                )
                i = i + 1
                continue
            }
            if (name == "CallVoid" || name == "CallIndirectVoid") {
                val call: AstXmlNode = this.ilCallNode(il, frame, op)
                if (xmlIsEmpty(*call)) {
                    if (this.ilWhy.isEmpty()) {
                        return IlText(false, "", "a void call")
                    }
                    return IlText(false, "", "cannot express " + this.ilWhy)
                }
                this.ilLine(*text, lvl, this.expr(*call, 0, *xmlEmptyNode()) + ";")
                i = i + 1
                continue
            }
            if (name == "Return" || name == "ReturnVoid") {
                if (name == "ReturnVoid") {
                    this.ilLine(*text, lvl, "return;")
                    i = i + 1
                    continue
                }
                val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOperandAt(*op.operands, 0), 0)
                if (xmlIsEmpty(*value)) {
                    return IlText(false, "", "a return with no value")
                }
                this.ilLine(*text, lvl, "return " + this.expr(*value, 0, *this.curReturnType) + ";")
                i = i + 1
                continue
            }
            if (name == "Lambda") {
                return IlText(false, "", "a lambda body")
            }
            if (name == "Unsupported") {
                return IlText(false, "", "an unsupported shape")
            }
            return IlText(false, "", "the instruction '" + name + "'")
        }
        while (scopes.size() > 0) {
            scopes.removeAt(scopes.size() - 1)
            lvl = lvl - 1
            this.ilLine(*text, lvl, "}")
        }
        return IlText(true, text, "")
    }

    // One body's text, with its frame analysed and the type pass's failure state kept out
    // of the caller's: a body the IL cannot spell is a *report*, not an error.
    fun ilEmitOpsChecked(il: *IlBody, level: Int): IlText {
        var frame: IlFrame = IlFrame(Dictionary<Int, Int>(), Dictionary<Int, Int>(), Dictionary<Int, Int>())
        this.ilAnalyze(il, *frame)
        val savedFailed: Bool = this.failed
        val savedError: Str = this.error
        val result: IlText = this.ilEmitOps(il, *frame, level)
        var final: IlText = result
        if (result.ok && this.failed) {
            final = IlText(false, "", "the emitter reported: " + this.error)
        }
        this.failed = savedFailed
        this.error = savedError
        return final
    }

    // The C++ of one function body, from its IL. The frame's types are installed so the
    // spelling helpers (`memberAccess`, the call resolution) see the same world the type
    // pass gave them; nothing that survived a previous body is left behind.
    fun emitIlBodyText(unit: *IlUnit, level: Int): IlText {
        val il: *IlBody = *unit.body
        val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
        val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
        val savedClosures: Dictionary<Str, Bool> = this.closureSymbols
        this.ilSeedFrameTypes(il)
        var closures: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var i: Int = 0
        while (i < unit.closures.size()) {
            closures.insert(unit.closures[i].symbol, true)
            i = i + 1
        }
        this.closureSymbols = closures
        val result: IlText = this.ilEmitOpsChecked(il, level)
        this.nameKinds = savedKinds
        this.localTypes = savedTypes
        this.closureSymbols = savedClosures
        return result
    }

    // C++ of a lambda's body, as the class's method: the frame is the lambda's (its
    // parameters and the closure instance), and `self` is C++'s `this`.
    fun emitClosureMethodText(unit: *IlUnit, closure: *IlClosure, level: Int): IlText {
        if (closure.bodyIndex < 0 || closure.bodyIndex >= unit.lambdas.size()) {
            return IlText(false, "", "a closure with no body")
        }
        val body: *IlBody = *unit.lambdas[closure.bodyIndex]
        val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
        val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
        val savedSelfKind: NameKind = this.selfKind
        val savedSelfType: AstXmlNode = this.selfType
        val savedClosure: Bool = this.inClosureMethod
        this.nameKinds.clear()
        this.localTypes.clear()
        this.ilSeedFrameTypes(body)
        var classType: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
        classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, closure.symbol))
        this.selfKind = NameKind.Value
        this.selfType = classType
        this.inClosureMethod = true
        val result: IlText = this.ilEmitOpsChecked(body, level)
        this.nameKinds = savedKinds
        this.localTypes = savedTypes
        this.selfKind = savedSelfKind
        this.selfType = savedSelfType
        this.inClosureMethod = savedClosure
        return result
    }

    // The class a lambda is: one field per captured variable, and one method - the
    // language's `invoke`, which C++ spells `operator()`. A `[=]` capture list becomes an
    // explicit struct, which is what lets a lambda live in the instruction list (and what
    // `&lambda` counts references to).
    fun emitClosureClass(unit: *IlUnit, closure: *IlClosure): IlText {
        var text: Str = "struct " + closure.symbol + " {\n"
        var i: Int = 0
        while (i < closure.captures.size()) {
            var fieldType: AstXmlNode = xmlEmptyNode()
            if (i < closure.captureTypes.size()) {
                fieldType = closure.captureTypes[i]
            }
            if (xmlIsEmpty(*fieldType)) {
                return IlText(false, "", "the capture '" + closure.captures[i] + "' has no type")
            }
            text.appendStr("    " + this.type(*fieldType) + " " + closure.captures[i] + ";\n")
            i = i + 1
        }
        var params: List<Str> = List<Str>()
        i = 0
        while (i < closure.params.size()) {
            val param: *IlVar = *closure.params[i]
            val paramType: AstXmlNode = ilTypeNode(*unit.lambdas[closure.bodyIndex], param.typeIndex)
            if (xmlIsEmpty(*paramType)) {
                return IlText(false, "", "the lambda parameter '" + param.name + "' has no type")
            }
            params.append(this.type(*paramType) + " " + param.name)
            i = i + 1
        }
        text.appendStr("    auto operator()(" + cgJoin(*params, ", ") + ") {\n")
        val bodyText: IlText = this.emitClosureMethodText(unit, closure, 2)
        if (!bodyText.ok) {
            return bodyText
        }
        text.appendStr(bodyText.text)
        text.appendStr("    }\n")
        text.appendStr("};\n\n")
        return IlText(true, text, "")
    }

    // Writes out the classes of every lambda this body constructs, the first time one is
    // needed. A definition has to precede its construction, and the functions are emitted
    // in a fixed order, so "just before the body" is both legal and reproducible.
    fun emitClosureClasses(unit: *IlUnit): IlText {
        var text: Str = Str()
        var i: Int = 0
        while (i < unit.closures.size()) {
            val closure: *IlClosure = *unit.closures[i]
            if (!this.emittedClosures.has(closure.symbol)) {
                val classText: IlText = this.emitClosureClass(unit, closure)
                if (!classText.ok) {
                    return classText
                }
                text.appendStr(classText.text)
                this.emittedClosures.insert(closure.symbol, true)
            }
            i = i + 1
        }
        return IlText(true, text, "")
    }

    // A yielding function, emitted as the state machine it was lowered to
    // (impl_specs/yield.md):
    //
    //   struct evens_yieldable { Int n; Int i; Int branch; Opt<Int> next() {...} };
    //   ns1_evens_yieldable ns1_evens(Int n) { ...machine.n = n; ... }
    //
    // The function builds a machine on the stack and returns it by value, so a local
    // iterator is a local struct; `&evens(n)` boxes a copy for a life that outlives the
    // frame (the language's `&T`, as everywhere else).
    fun emitYieldable(
        fn: *
        CgFn,
        decl: *
        AstXmlNode,
        className: Str,
        classType: Str,
        prototypeOnly: Bool,
        selfK: NameKind,
        selfTypePtr: *
        AstXmlNode,
        facts: *
        SemFacts
    ): Unit {
        val returnNode: AstXmlNode = xmlChild(decl, AstNodeKind.ReturnType)
        val elementType: AstXmlNode = xmlChild(*returnNode, AstNodeKind.Inner)
        if (xmlIsEmpty(*elementType)) {
            this.fail(decl, "unsupported: '..' without an element type")
            return
        }

        // A machine is emitted once: the prototype pass writes the class and the factory's
        // declaration, the definition pass only fills the factory in.
        if (!this.emittedYieldables.has(className)) {
            this.emittedYieldables.insert(className, true)
            // The linear body first: `yield` is a *lowering*, and it trades on the control
            // flow being labels and gotos with the value already one operand
            // (impl_specs/yield.md).
            var lowered: List<AstXmlNode> =
                linLowerForEmission(xmlChildren(*xmlChild(decl, AstNodeKind.Body), AstNodeKind.Stmt))
            val semantics: SemBody = SemBody(
                copy(decl), fn.templateParams, copy(selfTypePtr),
                List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
            )
            // The map is the machine's methods' frame too: the bodies are the same
            // statements (the lowering rewrites them in place), so the names the pass
            // proved are the names they carry.
            var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            lowered = semInferTypes(*lowered, facts, *semantics, *inferred)
            // The machine's methods: the body's storage is the machine's fields (`this->`),
            // and the names a method has of its own are the pointer `advance` writes
            // through, the dispatcher's branch and the receiver field - a local of any of
            // those names would alias one of them.
            var machineReserved: List<Str> = List<Str>()
            machineReserved.append("value")
            machineReserved.append("branch")
            machineReserved.append("_sm_self")
            val finalBody: List<AstXmlNode> = linFinishForEmission(lowered, machineReserved)
            val machine: Yielded = linLowerYield(decl, elementType, finalBody, "advance")
            if (!machine.error.isEmpty()) {
                this.fail(decl, machine.error)
                return
            }
            this.emitMachine(fn, decl, className, elementType, *machine, facts, *inferred)
            if (this.failed) {
                return
            }
        }

        val factory: Str = classType + " " + this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
        val tmpl: Str = this.templateClause(fn.templateParams)
        val factoryParams: List<Str> = this.parameterList(fn, decl)
        if (prototypeOnly) {
            if (tmpl != "") {
                this.line(0, tmpl)
            }
            this.line(0, factory + "(" + cgJoin(*factoryParams, ", ") + ");")
            return
        }
        this.sourceComment(decl)
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, factory + "(" + cgJoin(*factoryParams, ", ") + ") {")
        this.line(1, classType + " machine{};")
        val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < declared.size()) {
            val name: Str = xmlAttr(*declared[i], AstNodeAttributeKind.Name)
            this.line(1, "machine." + name + " = " + name + ";")
            i = i + 1
        }
        if (!xmlIsEmpty(*xmlChild(decl, AstNodeKind.Receiver))) {
            // The receiver of an extension function crosses a yield like any other value,
            // so it is a field and the factory fills it from its own `self` parameter
            // (`yldReceiverField`).
            this.line(1, "machine." + yldReceiverField() + " = self;")
        }
        this.line(1, "machine.branch = 0;")
        this.line(1, "return machine;")
        this.line(0, "}")
    }

    // The parameters of the factory: the receiver first when the function has one (an
    // extension function's receiver is an ordinary parameter in the emitted C++, `T* self`
    // for a value receiver), then the declaration's own.
    fun parameterList(fn: *CgFn, decl: *AstXmlNode): List<Str> {
        var params: List<Str> = List<Str>()
        if (!xmlIsEmpty(*fn.receiver)) {
            params.append(this.receiverParam(*fn.receiver))
            if (this.failed) {
                return params
            }
        }
        val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < declared.size()) {
            val param: *AstXmlNode = *declared[i]
            val paramType: AstXmlNode = xmlChild(param, AstNodeKind.Type)
            if (xmlIsEmpty(*paramType)) {
                this.fail(
                    param,
                    "unsupported: parameter '" + xmlAttr(param, AstNodeAttributeKind.Name) + "' without a type"
                )
                return params
            }
            params.append(this.type(*paramType) + " " + xmlAttr(param, AstNodeAttributeKind.Name))
            if (this.failed) {
                return params
            }
            i = i + 1
        }
        return params
    }

    // The machine is a class the type pass never saw - it is the lowering's own output -
    // so its fields are registered as a data class here. That is what tells the spelling
    // helpers what `this.<field>` is: a receiver field is a *pointer* (`T* self`), so
    // `this._sm_self.size()` reaches through it rather than taking its address, and a
    // method's parameter that shares a field's name still resolves to the parameter (the
    // frame, not this table, decides names).
    fun registerMachineType(className: Str, machine: *Yielded): Unit {
        var declNode: AstXmlNode =
            AstXmlNode(AstNodeKind.DataClass, AstNodeCategory.DataClass, List<AstNodeAttribute>(), Array<AstXmlNode>())
        declNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
        var i: Int = 0
        while (i < machine.fields.size()) {
            val field: YldField = machine.fields[i]
            var fieldNode: AstXmlNode =
                AstXmlNode(AstNodeKind.Field, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
            fieldNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, field.name))
            xmlAddChild(*fieldNode, this.renameRole(*field.typeNode, AstNodeKind.Type))
            xmlAddChild(*declNode, this.renameRole(*fieldNode, AstNodeKind.Field))
            i = i + 1
        }
        this.types.insert(className, declNode)
    }

    // The machine itself: the fields, then one method per way of advancing it.
    fun emitMachine(
        fn: *CgFn, decl: *AstXmlNode, className: Str, elementType: AstXmlNode,
        machine: *Yielded, facts: *SemFacts, inferred: *Dictionary<Str, AstXmlNode>
    ): Unit {
        this.sourceComment(decl)
        this.registerMachineType(className, machine)
        // A generic function's machine is a class template: its fields are typed with the
        // function's type parameters, so the emitted C++ has to declare them where it uses
        // them (impl_specs/yield.md).
        val tmpl: Str = this.templateClause(fn.templateParams)
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, "struct " + className + " {")
        var i: Int = 0
        while (i < machine.fields.size()) {
            val field: YldField = machine.fields[i]
            if (xmlIsEmpty(*field.typeNode)) {
                this.fail(decl, "yield: the field '" + field.name + "' has no type")
                return
            }
            this.line(1, this.type(*field.typeNode) + " " + field.name + "{};")
            if (this.failed) {
                return
            }
            i = i + 1
        }
        i = 0
        while (i < machine.methods.size()) {
            val method: *YldMethod = *machine.methods[i]
            var params: List<Str> = List<Str>()
            var p: Int = 0
            while (p < method.params.size()) {
                val param: YldParam = method.params[p]
                params.append(this.type(*param.typeNode) + " " + param.name)
                if (this.failed) {
                    return
                }
                p = p + 1
            }
            var result: Str = "Opt<" + this.type(*elementType) + ">"
            if (method.name == "advance") {
                result = "Bool"
            }
            this.line(1, result + " " + method.name + "(" + cgJoin(*params, ", ") + ") {")
            // The method is a C++ member function, so the machine's own values are
            // reached through `this` - the same spelling the closure classes use.
            val savedClosure: Bool = this.inClosureMethod
            val savedSelfKind: NameKind = this.selfKind
            val savedSelfType: AstXmlNode = this.selfType
            val savedReturn: AstXmlNode = this.curReturnType
            val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
            val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
            this.inClosureMethod = true
            this.selfKind = NameKind.Value
            var classType: AstXmlNode =
                AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
            classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
            this.selfType = classType
            this.curReturnType = elementType
            // The method's own parameters are what tells a pointer receiver from a value
            // one (`*value = x` writes through it).
            p = 0
            while (p < method.params.size()) {
                val param: YldParam = method.params[p]
                if (!xmlIsEmpty(*param.typeNode)) {
                    this.nameKinds.insert(param.name, this.kindOf(*param.typeNode))
                    this.localTypes.insert(param.name, param.typeNode)
                }
                p = p + 1
            }
            // The body goes through the same two paths as any other (the IL is what a
            // machine's methods must be expressible in, since the machine *is* the
            // lowering's output): the frame is the machine's, so its fields are read and
            // written through `self`, exactly as a lambda body reads its captures.
            this.emitBodyAt(
                this.ilMachineMethod(className, method, facts, inferred), method.body,
                fn.file, 2
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
            this.line(1, "}")
            i = i + 1
        }
        this.line(0, "};")
        this.line(0, "")
    }

    // A state machine's method, as the extractor's body context: no declaration, the
    // method's parameters, and - like a lambda - a class whose fields the body reaches
    // through `this`. The *fields* are deliberately not captures: the lowering already
    // spelled every field read and write as an explicit `this.x` member, so a bare name in
    // the body is the method's own - and a parameter that happens to share a field's name
    // (`advance(value: *T)` against a field `value`) must resolve to the parameter.
    fun ilMachineMethod(
        className: Str, method: *YldMethod, facts: *SemFacts,
        inferred: *Dictionary<Str, AstXmlNode>
    ): IlFunction {
        var info: IlFunction = IlFunction(
            xmlEmptyNode(), xmlEmptyNode(),
            className + "::" + method.name, Dictionary<Str, Str>(),
            List<Str>(), List<AstXmlNode>(), className,
            Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
            facts, List<Str>(), inferred
        )
        var i: Int = 0
        while (i < method.params.size()) {
            info.paramNames.append(method.params[i].name)
            info.paramTypes.append(method.params[i].typeNode)
            i = i + 1
        }
        i = 0
        while (i < this.statics.size()) {
            val entry: CgStatic = this.statics[i]
            val typeNode: AstXmlNode = xmlChild(*entry.decl, AstNodeKind.Type)
            if (!xmlIsEmpty(*typeNode)) {
                info.statics.insert(xmlAttr(*entry.decl, AstNodeAttributeKind.Name), ilTypeText(*typeNode))
            }
            i = i + 1
        }
        return info
    }

    // A failure with a position when the frame has a declaration, and position-less for a
    // synthesized body (a lambda's, a machine's method).
    fun failFromInfo(info: IlFunction, message: Str): Unit {
        var node: AstXmlNode = xmlEmptyNode()
        if (!xmlIsEmpty(*info.decl)) {
            node = copy(*info.decl)
        }
        this.fail(*node, message)
    }

    // A body is emitted from its instruction list - the IL is the *only* codegen
    // (impl_specs/linear-il.md). A body the IL cannot spell is a bug in the extractor, not
    // something to fall back from: it fails with the reason.
    fun emitBodyAt(info: IlFunction, body: List<AstXmlNode>, file: Str, level: Int): Unit {
        val unit: IlUnit = ilExtractUnit(info, body, file)
        val emitted: IlText = this.emitIlBodyText(*unit, level)
        if (!emitted.ok) {
            this.failFromInfo(
                info, "internal: the body of '" + info.symbol
                        + "' is not expressible in the IL (" + emitted.reason + ")"
            )
            return
        }
        // A lambda is a closure class, which the text above *constructs* but does not
        // define: the class goes just above the body that builds it.
        var classes: IlText = IlText(true, "", "")
        if (unit.closures.size() > 0) {
            classes = this.emitClosureClasses(*unit)
            if (!classes.ok) {
                this.failFromInfo(
                    info, "internal: a closure class could not be written ("
                            + classes.reason + ")"
                )
                return
            }
        }
        this.out.appendStr(classes.text)
        this.out.appendStr(emitted.text)
    }

    // ---- expressions ------------------------------------------------------

    fun expr(e: *AstXmlNode, minPrec: Int, expected: *AstXmlNode): Str {
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

    fun operandKind(e: *AstXmlNode): NameKind {
        if (xmlKind(e) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return this.selfKind
            }
            if (this.nameKinds.has(name)) {
                return this.nameKinds.get(name).value()
            }
        }
        return NameKind.Value
    }

    // ---- lightweight type inference ---------------------------------------

    fun namedType(name: Str): AstXmlNode {
        return this.namedTypeExpr(name)
    }

    fun pointee(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = copy(typeNode)
        while (!xmlIsEmpty(*current)) {
            val kind: AstNodeCategory = xmlKind(*current)
            if ((kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer)) {
                val inner: AstXmlNode = xmlChild(*current, AstNodeKind.Inner)
                if (xmlIsEmpty(*inner)) {
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
        if (kind == AstNodeCategory.TypeNamed) {
            return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Str"
        }
        if (kind == AstNodeCategory.TypeGeneric) {
            val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
            return name == "List" || name == "Array" || name == "Dictionary" || name == "SmallVector" || name == "Span"
        }
        return false
    }

    fun unifyType(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
        var actualPtr: AstXmlNode = copy(actual)
        val pk: AstNodeCategory = xmlKind(pattern)
        if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
            while (true) {
                val ak0: AstNodeCategory = xmlKind(*actualPtr)
                if ((ak0 == AstNodeCategory.TypeReference || ak0 == AstNodeCategory.TypePointer)) {
                    val inner: AstXmlNode = xmlChild(*actualPtr, AstNodeKind.Inner)
                    if (xmlIsEmpty(*inner)) {
                        break
                    }
                    actualPtr = inner
                } else {
                    break
                }
            }
        }
        val ak: AstNodeCategory = xmlKind(*actualPtr)
        if (pk == AstNodeCategory.TypeIntLit) {
            return ak == AstNodeCategory.TypeIntLit && xmlAttr(
                *actualPtr,
                AstNodeAttributeKind.Text
            ) == xmlAttr(pattern, AstNodeAttributeKind.Text)
        }
        if (pk == AstNodeCategory.TypeNamed) {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            return ak == AstNodeCategory.TypeNamed && xmlAttr(*actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Name
            )
        }
        if (pk == AstNodeCategory.TypeGeneric) {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            if (ak != AstNodeCategory.TypeGeneric) {
                return false
            }
            val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
            val actualName: Str = xmlAttr(*actualPtr, AstNodeAttributeKind.Name)
            if (actualName != patternName
                && !(patternName == "List" && actualName == "PList")
                && !(patternName == "PList" && actualName == "List")
            ) {
                return false
            }
            val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
            val actualArgs: List<AstXmlNode> = xmlChildren(*actualPtr, AstNodeKind.TypeArg)
            if (patternArgs.size() != actualArgs.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternArgs.size()) {
                if (!this.unifyType(*patternArgs[i], *actualArgs[i], typeParams)) {
                    return false
                }
                i = i + 1
            }
            return true
        }
        if (pk == AstNodeCategory.TypeReference) {
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    *xmlChild(pattern, AstNodeKind.Inner),
                    *xmlChild(*actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
        if (pk == AstNodeCategory.TypePointer) {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    *xmlChild(pattern, AstNodeKind.Inner),
                    *xmlChild(*actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
        // `..T` is a state machine (impl_specs/yield.md) and carries its element type the
        // way a pointer carries its pointee, so a pattern `..T` matches `..Int`
        // element-wise - which is what lets the prelude's `fun ..T.smToYield(): ..T` be
        // found for a machine.
        if (pk == AstNodeCategory.TypeYield) {
            if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    *xmlChild(pattern, AstNodeKind.Inner),
                    *xmlChild(*actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
        return false
    }

    fun functionReturn(name: Str): AstXmlNode {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) == name) {
                val ret: AstXmlNode = xmlChild(*fn.decl, AstNodeKind.ReturnType)
                if (!xmlIsEmpty(*ret)) {
                    return ret
                }
            }
            i = i + 1
        }
        return xmlEmptyNode()
    }

    fun memberCallReturn(callee: *AstXmlNode): AstXmlNode {
        val receiverType: AstXmlNode = this.inferType(*xmlChild(callee, AstNodeKind.Receiver))
        val recv: AstXmlNode = this.pointee(*receiverType)
        val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || xmlIsEmpty(*fn.receiver)) {
                continue
            }
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) != calleeText) {
                continue
            }
            val ret: AstXmlNode = xmlChild(*fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(*recv) && this.unifyType(*fn.receiver, *recv, *fn.templateParams)
                && !xmlIsEmpty(*ret)
            ) {
                return ret
            }
        }
        if (this.nativeExtensions.has(calleeText)) {
            val extensions: List<CgNativeExt> = this.nativeExtensions.get(calleeText).value()
            var e: Int = 0
            while (e < extensions.size()) {
                val ext: *CgNativeExt = *extensions[e]
                if (!xmlIsEmpty(*recv) && !xmlIsEmpty(*ext.receiver)
                    && this.unifyType(*ext.receiver, *recv, *ext.typeParams)
                    && !xmlIsEmpty(*ext.returnType)
                ) {
                    return ext.returnType
                }
                e = e + 1
            }
        }
        if (!xmlIsEmpty(*recv) && xmlKind(*recv) == AstNodeCategory.TypeGeneric) {
            val typeArgs: List<AstXmlNode> = xmlChildren(*recv, AstNodeKind.TypeArg)
            if (calleeText == "value" && xmlAttr(*recv, AstNodeAttributeKind.Name) == "Opt" && typeArgs.size() > 0) {
                return typeArgs[0]
            }
        }
        if (!xmlIsEmpty(*recv) && (calleeText == "size" || calleeText == "count")) {
            val recvName: Str = xmlAttr(*recv, AstNodeAttributeKind.Name)
            if (recvName == "List" || recvName == "Str" || recvName == "Array"
                || recvName == "Dictionary" || recvName == "SmallVector"
            ) {
                return this.namedType("Int")
            }
        }
        if (calleeText == "isOk" || calleeText == "hasValue") {
            return this.namedType("Bool")
        }
        return xmlEmptyNode()
    }

    fun inferType(e: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        if (kind == AstNodeCategory.ExprIntLit) {
            return this.namedType("Int")
        }
        if (kind == AstNodeCategory.ExprFloatLit) {
            return this.namedType("Float64")
        }
        if (kind == AstNodeCategory.ExprStrLit) {
            return this.namedType("Str")
        }
        if (kind == AstNodeCategory.ExprCharLit) {
            return this.namedType("Char")
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            return this.namedType("Bool")
        }
        if (kind == AstNodeCategory.ExprNullLit) {
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return this.selfType
            }
            if (this.localTypes.has(name)) {
                return this.localTypes.get(name).value()
            }
            val staticNode: AstXmlNode = this.staticType(name)
            if (!xmlIsEmpty(*staticNode)) {
                return staticNode
            }
            if (this.enumNames.has(name)) {
                return this.namedType(name)
            }
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprGenericName) {
            return this.genericTypeExpr(xmlAttr(e, AstNodeAttributeKind.Name), *xmlChildren(e, AstNodeKind.TypeArg))
        }
        if (kind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
            if (xmlKind(*lhs) == AstNodeCategory.ExprName && this.enumNames.has(
                    xmlAttr(
                        *lhs,
                        AstNodeAttributeKind.Name
                    )
                )
            ) {
                return this.namedType(xmlAttr(*lhs, AstNodeAttributeKind.Name))
            }
            val baseType: AstXmlNode = this.inferType(*lhs)
            val base: AstXmlNode = this.pointee(*baseType)
            if (xmlIsEmpty(*base)) {
                return xmlEmptyNode()
            }
            val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (xmlKind(*base) == AstNodeCategory.TypeGeneric && xmlAttr(*base, AstNodeAttributeKind.Name) == "Res") {
                val typeArgs: List<AstXmlNode> = xmlChildren(*base, AstNodeKind.TypeArg)
                if (memberText == "value" && typeArgs.size() > 0) {
                    return typeArgs[0]
                }
                if (memberText == "error") {
                    return this.namedType("Str")
                }
            }
            val baseKind: AstNodeCategory = xmlKind(*base)
            if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                val baseName: Str = xmlAttr(*base, AstNodeAttributeKind.Name)
                if (this.types.has(baseName)) {
                    val decl: AstXmlNode = this.types.get(baseName).value()
                    if (decl.name == AstNodeKind.DataClass) {
                        val fields: List<AstXmlNode> = xmlChildren(*decl, AstNodeKind.Field)
                        var i: Int = 0
                        while (i < fields.size()) {
                            if (xmlAttr(*fields[i], AstNodeAttributeKind.Name) == memberText) {
                                return xmlChild(*fields[i], AstNodeKind.Type)
                            }
                            i = i + 1
                        }
                    }
                }
            }
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprCall) {
            val callee: AstXmlNode = xmlChild(e, AstNodeKind.Callee)
            val calleeKind: AstNodeCategory = xmlKind(*callee)
            if (calleeKind == AstNodeCategory.ExprGenericName) {
                val name: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
                if (this.types.has(name) || semIsRtlTypeName(name)) {
                    return this.genericTypeExpr(name, *xmlChildren(*callee, AstNodeKind.TypeArg))
                }
                return this.functionReturn(name)
            }
            if (calleeKind == AstNodeCategory.ExprName) {
                val name: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
                if (this.types.has(name) || semIsRtlTypeName(name)) {
                    return this.namedType(name)
                }
                return this.functionReturn(name)
            }
            if (calleeKind == AstNodeCategory.ExprMember) {
                return this.memberCallReturn(*callee)
            }
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprIndex) {
            val baseType: AstXmlNode = this.inferType(*xmlChild(e, AstNodeKind.Receiver))
            val base: AstXmlNode = this.pointee(*baseType)
            if (xmlIsEmpty(*base)) {
                return xmlEmptyNode()
            }
            if (xmlKind(*base) == AstNodeCategory.TypeNamed && xmlAttr(*base, AstNodeAttributeKind.Name) == "Str") {
                return this.namedType("Char")
            }
            if (xmlKind(*base) != AstNodeCategory.TypeGeneric) {
                return xmlEmptyNode()
            }
            val typeArgs: List<AstXmlNode> = xmlChildren(*base, AstNodeKind.TypeArg)
            if (typeArgs.size() == 0) {
                return xmlEmptyNode()
            }
            val baseName: Str = xmlAttr(*base, AstNodeAttributeKind.Name)
            if (baseName == "SmallVector" && typeArgs.size() == 2) {
                return typeArgs[1]
            }
            if (baseName == "Dictionary" && typeArgs.size() == 2) {
                return typeArgs[1]
            }
            return typeArgs[0]
        }
        if (kind == AstNodeCategory.ExprRef) {
            var node: AstXmlNode = AstXmlNode(
                AstNodeKind.Type,
                AstNodeCategory.TypeReference,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            xmlAddChild(*node, this.renameRole(*this.inferType(*xmlChild(e, AstNodeKind.Operand)), AstNodeKind.Inner))
            return node
        }
        if (kind == AstNodeCategory.ExprDeref) {
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypePointer, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(*node, this.renameRole(*this.inferType(*xmlChild(e, AstNodeKind.Operand)), AstNodeKind.Inner))
            return node
        }
        if (kind == AstNodeCategory.ExprCopy || kind == AstNodeCategory.ExprUnary) {
            return this.inferType(*xmlChild(e, AstNodeKind.Operand))
        }
        if (kind == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                || op == "&&" || op == "||"
            ) {
                return this.namedType("Bool")
            }
            return this.inferType(*xmlChild(e, AstNodeKind.Lhs))
        }
        if (kind == AstNodeCategory.ExprLambda) {
            var fnType: AstXmlNode = AstXmlNode(
                AstNodeKind.Type,
                AstNodeCategory.TypeFunction,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            val paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
            xmlAddChildren(*fnType, *paramTypes)
            return fnType
        }
        return xmlEmptyNode()
    }

    // Re-roots `child` under `role` (a shallow copy whose element name changes).
    fun renameRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
        var renamed: AstXmlNode = copy(child)
        renamed.name = role
        return renamed
    }

    // The receiver argument for a lowered *Simse* call: a value receiver is a raw
    // pointer in the emitted code, so the argument is the receiver object's address.
    // `simse_addressOf` covers both a place (`&x`) and a temporary, whose pointer is
    // valid for the call it is passed to (cppsrc/rtl/types.hpp); a counted reference
    // is unwrapped with `.get()`, and a raw pointer is already that address. A handle
    // receiver keeps its form: a counted reference stays a counted reference, so a
    // method that takes `this: &T` can store `self` and keep its refcount.
    fun receiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
        if (this.isHandleType(pattern)) {
            return this.expr(recv, 9, *xmlEmptyNode())
        }
        val recvType: AstXmlNode = this.inferType(recv)
        if (!xmlIsEmpty(*recvType)) {
            val kind: AstNodeCategory = xmlKind(*recvType)
            if (kind == AstNodeCategory.TypeReference
                || (kind == AstNodeCategory.TypeGeneric && xmlAttr(*recvType, AstNodeAttributeKind.Name) == "PList")
            ) {
                return "(" + this.expr(recv, 9, *xmlEmptyNode()) + ").get()"
            }
            if (kind == AstNodeCategory.TypePointer) {
                return this.expr(recv, 9, *xmlEmptyNode())
            }
        }
        return "simse_addressOf(" + this.expr(recv, 9, *xmlEmptyNode()) + ")"
    }

    // The receiver argument for a lowered *native* call: the host's own signature
    // decides whether it wants a value, a reference or a pointer, so the receiver
    // expression is passed as it is - dereferenced through a handle, because the
    // RTL's value receivers are written `T&` there.
    fun nativeReceiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
        if (this.isHandleType(pattern)) {
            return this.expr(recv, 9, *xmlEmptyNode())
        }
        val recvType: AstXmlNode = this.inferType(recv)
        if (this.isHandleType(*recvType)) {
            return "(*" + this.expr(recv, 9, *xmlEmptyNode()) + ")"
        }
        return this.expr(recv, 9, *xmlEmptyNode())
    }

    // Index into `functions` of the first Simse-declared receiver function with this
    // name, or -1: used when the receiver's own type could not be inferred but the
    // callee is known.
    fun findReceiverFnByName(name: Str): Int {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || xmlIsEmpty(*fn.receiver)) {
                continue
            }
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) == name) {
                return i - 1
            }
        }
        return -1
    }

    // Index into `functions` of a Simse extension matching the receiver, or -1.
    fun findExtensionFn(name: Str, recvExpr: *AstXmlNode): Int {
        val recvType: AstXmlNode = this.inferType(recvExpr)
        val recv: AstXmlNode = this.pointee(*recvType)
        if (xmlIsEmpty(*recv)) {
            return -1
        }
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || xmlIsEmpty(*fn.receiver)) {
                i = i + 1
                continue
            }
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) == name && this.unifyType(
                    *fn.receiver,
                    *recv,
                    *fn.templateParams
                )
            ) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    // Index into `nativeExtensions[name]` of a matching receiver, or -1.
    fun findNativeExt(name: Str, recvExpr: *AstXmlNode): Int {
        if (!this.nativeExtensions.has(name)) {
            return -1
        }
        val recvType: AstXmlNode = this.inferType(recvExpr)
        val recv: AstXmlNode = this.pointee(*recvType)
        if (xmlIsEmpty(*recv)) {
            return -1
        }
        val extensions: List<CgNativeExt> = this.nativeExtensions.get(name).value()
        var i: Int = 0
        while (i < extensions.size()) {
            val ext: *CgNativeExt = *extensions[i]
            if (!xmlIsEmpty(*ext.receiver) && this.unifyType(*ext.receiver, *recv, *ext.typeParams)) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    fun memberAccess(base: *AstXmlNode, name: Str): Str {
        var arrow: Bool = false
        val baseType: AstXmlNode = this.inferType(base)
        if (xmlKind(base) == AstNodeCategory.ExprName
            && xmlAttr(base, AstNodeAttributeKind.Name) == "this" && this.selfKind == NameKind.Value
        ) {
            // A value receiver is a raw pointer in the emitted code (`T* self`), so its
            // members are reached with `->` whatever the language type of the receiver
            // is.
            arrow = true
        } else if (!xmlIsEmpty(*baseType)) {
            arrow = this.isHandleType(*baseType)
        } else if (xmlKind(base) == AstNodeCategory.ExprName) {
            val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
            if (baseName == "this") {
                arrow = this.selfKind != NameKind.Value
            } else if (this.nameKinds.has(baseName) && this.nameKinds.get(baseName).value() == NameKind.Shared) {
                arrow = true
            }
        }
        var field: Str = name
        val recv: AstXmlNode = this.pointee(*baseType)
        if (!xmlIsEmpty(*recv) && xmlKind(*recv) == AstNodeCategory.TypeGeneric && xmlAttr(
                *recv,
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
        // A value receiver's own member access reads through its pointer
        // (`self->field`). The bare name `this` reads as the object (`(*self)`),
        // which is what a *value* use of the receiver needs, so this one spot spells
        // the pointer instead.
        if (xmlKind(base) == AstNodeCategory.ExprName && xmlAttr(base, AstNodeAttributeKind.Name) == "this"
            && this.selfKind == NameKind.Value
        ) {
            if (this.inClosureMethod) {
                return "this->" + field
            }
            return "self->" + field
        }
        return this.expr(base, 9, *xmlEmptyNode()) + op + field
    }

    fun nullTo(expected: *AstXmlNode): Str {
        if (!xmlIsEmpty(expected) && xmlKind(expected) == AstNodeCategory.TypeGeneric && xmlAttr(
                expected,
                AstNodeAttributeKind.Name
            ) == "Opt"
        ) {
            return "Opt<" + this.typeArgsString("Opt", *xmlChildren(expected, AstNodeKind.TypeArg)) + ">()"
        }
        return "nullptr"
    }

    fun resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = copy(typeNode)
        var guard: Int = 0
        while (!xmlIsEmpty(*current) && xmlKind(*current) == AstNodeCategory.TypeNamed) {
            guard = guard + 1
            if (guard >= 100) {
                break
            }
            val name: Str = xmlAttr(*current, AstNodeAttributeKind.Name)
            if (!this.types.has(name)) {
                break
            }
            val decl: AstXmlNode = this.types.get(name).value()
            if (decl.name != AstNodeKind.TypeAlias) {
                break
            }
            val target: AstXmlNode = xmlChild(*decl, AstNodeKind.TargetType)
            if (xmlIsEmpty(*target)) {
                break
            }
            current = target
        }
        return current
    }

    fun expectedCallable(expected: *AstXmlNode): AstXmlNode {
        val resolved: AstXmlNode = this.resolveAlias(expected)
        if (!xmlIsEmpty(*resolved) && xmlKind(*resolved) == AstNodeCategory.TypeFunction) {
            return resolved
        }
        return xmlEmptyNode()
    }

    // A non-native function with the given name and parameter count. A method is not
    // one: a plain call reaches only what the module declares.
    fun findFunction(name: Str, argCount: Int): AstXmlNode {
        var i: Int = 0
        while (i < this.functions.size()) {
            val fn: *CgFn = *this.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || fn.isMethod
                || xmlAttr(*fn.decl, AstNodeAttributeKind.Name) != name
            ) {
                continue
            }
            if (xmlCount(*fn.decl, AstNodeKind.Param) == argCount) {
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
        if (kind == AstNodeCategory.ExprIntLit || kind == AstNodeCategory.ExprFloatLit || kind == AstNodeCategory.ExprStrLit
            || kind == AstNodeCategory.ExprCharLit
        ) {
            return xmlAttr(e, AstNodeAttributeKind.Text)
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            return xmlAttr(e, AstNodeAttributeKind.Value)
        }
        if (kind == AstNodeCategory.ExprNullLit) {
            return this.nullTo(expected)
        }
        if (kind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                // The receiver is the *object* in the language and a raw pointer in the
                // emitted code when it is a value receiver (`T* self`), so reading it
                // reads through the pointer; a handle receiver is its handle. Inside a
                // closure class the receiver is C++'s `this`.
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
            // A file-level static is emitted under its package's prefix; a bare
            // name that is not a local is otherwise a reference to a top-level
            // function used as a value (e.g. a callable argument), so it carries
            // that function's prefix, and a known prelude native resolves to its
            // symbol instead.
            if (this.staticsByName.has(name)) {
                return this.qualify(this.staticsByName.get(name).value().packageName, name)
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
        if (kind == AstNodeCategory.ExprGenericName) {
            this.fail(
                e,
                "unsupported: generic-qualified expression '" + xmlAttr(e, AstNodeAttributeKind.Name) + "<...>'"
            )
            return "/*unsupported*/"
        }
        if (kind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
            if (xmlKind(*lhs) == AstNodeCategory.ExprName && this.enumNames.has(
                    xmlAttr(
                        *lhs,
                        AstNodeAttributeKind.Name
                    )
                )
            ) {
                val enumName: Str = xmlAttr(*lhs, AstNodeAttributeKind.Name)
                return this.qualify(this.typePackage(enumName), enumName) + "::" + xmlAttr(e, AstNodeAttributeKind.Name)
            }
            return this.memberAccess(*lhs, xmlAttr(e, AstNodeAttributeKind.Name))
        }
        if (kind == AstNodeCategory.ExprCall) {
            return this.call(e)
        }
        if (kind == AstNodeCategory.ExprIndex) {
            val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
            val baseExpr: Str = this.expr(*lhs, 9, *xmlEmptyNode())
            val baseType: AstXmlNode = this.inferType(*lhs)
            var deref: Bool = false
            if (!xmlIsEmpty(*baseType)) {
                if (this.isHandleType(*baseType) && xmlKind(*baseType) != AstNodeCategory.TypePointer) {
                    deref = true
                } else if (xmlKind(*baseType) == AstNodeCategory.TypePointer) {
                    deref = this.isIndexableContainer(*xmlChild(*baseType, AstNodeKind.Inner))
                }
            }
            if (deref) {
                return "(*" + baseExpr + ")[" + this.expr(*xmlChild(e, AstNodeKind.Index), 0, *xmlEmptyNode()) + "]"
            }
            return baseExpr + "[" + this.expr(*xmlChild(e, AstNodeKind.Index), 0, *xmlEmptyNode()) + "]"
        }
        if (kind == AstNodeCategory.ExprUnary) {
            return xmlAttr(e, AstNodeAttributeKind.Op) + this.expr(
                *xmlChild(e, AstNodeKind.Operand),
                7,
                *xmlEmptyNode()
            )
        }
        if (kind == AstNodeCategory.ExprBinary) {
            val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Lhs)
            val rhs: AstXmlNode = xmlChild(e, AstNodeKind.Rhs)
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (xmlKind(*lhs) == AstNodeCategory.ExprNullLit || xmlKind(*rhs) == AstNodeCategory.ExprNullLit) {
                var other: AstXmlNode = lhs
                if (xmlKind(*lhs) == AstNodeCategory.ExprNullLit) {
                    other = rhs
                }
                val otherType: AstXmlNode = this.pointee(*this.inferType(*other))
                if (!xmlIsEmpty(*otherType) && xmlKind(*otherType) == AstNodeCategory.TypeGeneric
                    && xmlAttr(*otherType, AstNodeAttributeKind.Name) == "Opt"
                ) {
                    val hasValue: Str = this.expr(*other, 9, *xmlEmptyNode()) + ".hasValue()"
                    if (op == "==") {
                        return "!(" + hasValue + ")"
                    }
                    if (op == "!=") {
                        return "(" + hasValue + ")"
                    }
                    this.fail(e, "unsupported: Opt-vs-null comparison '" + op + "'")
                    return "/*unsupported*/"
                }
            }
            val p: Int = cgPrecedence(e)
            var lhsExpected: AstXmlNode = xmlEmptyNode()
            if (xmlKind(*lhs) == AstNodeCategory.ExprNullLit) {
                lhsExpected = this.inferType(*rhs)
            }
            var rhsExpected: AstXmlNode = xmlEmptyNode()
            if (xmlKind(*rhs) == AstNodeCategory.ExprNullLit) {
                rhsExpected = this.inferType(*lhs)
            }
            return this.expr(*lhs, p, *lhsExpected) + " " + op + " " + this.expr(*rhs, p + 1, *rhsExpected)
        }
        if (kind == AstNodeCategory.ExprLambda) {
            // A lambda is a closure *class* here, built by the instruction list; an
            // expression node reaching this point means the lowering did not turn it into
            // one (impl_specs/linear-il.md).
            this.fail(e, "unsupported: a lambda outside a closure construction")
            return "/*unsupported*/"
        }
        if (kind == AstNodeCategory.ExprRef) {
            val operandNode: AstXmlNode = xmlChild(e, AstNodeKind.Operand)
            if (xmlKind(*operandNode) == AstNodeCategory.ExprCall) {
                val callee: AstXmlNode = xmlChild(*operandNode, AstNodeKind.Callee)
                if (xmlKind(*callee) == AstNodeCategory.ExprGenericName && xmlAttr(
                        *callee,
                        AstNodeAttributeKind.Name
                    ) == "List"
                    && xmlCount(*operandNode, AstNodeKind.Arg) == 0
                ) {
                    return "makeList<" + this.typeArgsString("List", *xmlChildren(*callee, AstNodeKind.TypeArg)) + ">()"
                }
            }
            val operand: Str = this.expr(*operandNode, 0, *xmlEmptyNode())
            return "std::make_shared<std::remove_cvref_t<decltype((" + operand + "))>>(" + operand + ")"
        }
        if (kind == AstNodeCategory.ExprDeref) {
            val operandNode: AstXmlNode = xmlChild(e, AstNodeKind.Operand)
            val operand: Str = this.expr(*operandNode, 7, *xmlEmptyNode())
            val operandType: AstXmlNode = this.inferType(*operandNode)
            var nameKind: NameKind = NameKind.Value
            if (!xmlIsEmpty(*operandType)) {
                nameKind = this.kindOf(*operandType)
            } else {
                nameKind = this.operandKind(*operandNode)
            }
            if (nameKind == NameKind.Shared) {
                return "(" + operand + ").get()"
            }
            if (nameKind == NameKind.Value) {
                // `*value` is the raw-pointer form: the address of the value, no
                // copy (specs/memory-model.md). A plain name is an lvalue, so
                // `&name`; anything else may be a temporary, which
                // simse_addressOf binds for the call.
                if (xmlKind(*operandNode) == AstNodeCategory.ExprName) {
                    return "&" + operand
                }
                return "simse_addressOf(" + operand + ")"
            }
            return "*" + operand
        }
        if (kind == AstNodeCategory.ExprCopy) {
            val operandNode: AstXmlNode = xmlChild(e, AstNodeKind.Operand)
            val operand: Str = this.expr(*operandNode, 0, *xmlEmptyNode())
            val operandType: AstXmlNode = this.inferType(*operandNode)
            var nameKind: NameKind = NameKind.Value
            if (!xmlIsEmpty(*operandType)) {
                nameKind = this.kindOf(*operandType)
            } else {
                nameKind = this.operandKind(*operandNode)
            }
            if (nameKind == NameKind.Shared || nameKind == NameKind.Pointer) {
                return "*(" + operand + ")"
            }
            return "(" + operand + ")"
        }
        return "/*unsupported*/"
    }

    fun call(e: *AstXmlNode): Str {
        val callee: AstXmlNode = xmlChild(e, AstNodeKind.Callee)
        val calleeKind: AstNodeCategory = xmlKind(*callee)
        val argNodes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)

        if (calleeKind == AstNodeCategory.ExprGenericName) {
            var args: List<Str> = List<Str>()
            var i: Int = 0
            while (i < argNodes.size()) {
                args.append(this.expr(*argNodes[i], 0, *xmlEmptyNode()))
                i = i + 1
            }
            val name: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
            var calleeName: Str = this.qualify(this.functionPackage(name), name)
            val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
            var hasPlainFunction: Bool = false
            var f: Int = 0
            while (f < this.functions.size()) {
                val candidate: *CgFn = *this.functions[f]
                if (xmlAttr(*candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                    && xmlAttr(*candidate.decl, AstNodeAttributeKind.Name) == name
                ) {
                    hasPlainFunction = true
                }
                f = f + 1
            }
            if (!hasPlainFunction && nativeOpt.hasValue()) {
                calleeName = nativeOpt.value()
            }
            if (this.dataClassNames.has(name)) {
                calleeName = this.qualify(this.typePackage(name), "_make_" + name)
            }
            return calleeName + "<" + this.typeArgsString(name, *xmlChildren(*callee, AstNodeKind.TypeArg))
            +">(" + cgJoin(*args, ", ") + ")"
        }
        if (calleeKind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
            if (name == "println" || name == "print") {
                var arg: Str = ""
                if (argNodes.size() > 0) {
                    arg = this.expr(*argNodes[0], 0, *xmlEmptyNode())
                }
                var s: Str = "std::cout << std::boolalpha << (" + arg + ")"
                if (name == "println") {
                    s = s + " << std::endl"
                }
                return s
            }
            val target: AstXmlNode = this.findFunction(name, argNodes.size())
            var args: List<Str> = List<Str>()
            val targetParams: List<AstXmlNode> = xmlChildren(*target, AstNodeKind.Param)
            val targetReceiver: AstXmlNode = xmlChild(*target, AstNodeKind.Receiver)
            var i: Int = 0
            while (i < argNodes.size()) {
                var expectedArg: AstXmlNode = xmlEmptyNode()
                if (!xmlIsEmpty(*target) && i < targetParams.size()) {
                    expectedArg = xmlChild(*targetParams[i], AstNodeKind.Type)
                }
                // A receiver function called by name takes the receiver first, and a
                // value receiver is a raw pointer in the emitted code (`T* self`), so
                // that argument is the object's address.
                if (i == 0 && !xmlIsEmpty(*targetReceiver)
                    && xmlAttr(*target, AstNodeAttributeKind.HasReceiver) == "true"
                    && !this.isHandleType(*targetReceiver)
                ) {
                    args.append(this.receiverArg(*targetReceiver, *argNodes[0]))
                } else {
                    args.append(this.expr(*argNodes[i], 0, *expectedArg))
                }
                i = i + 1
            }
            val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
            var hasPlainFunction: Bool = false
            var f: Int = 0
            while (f < this.functions.size()) {
                val candidate: *CgFn = *this.functions[f]
                if (xmlAttr(*candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                    && xmlAttr(*candidate.decl, AstNodeAttributeKind.Name) == name
                ) {
                    hasPlainFunction = true
                }
                f = f + 1
            }
            var calleeName: Str = this.qualify(this.functionPackage(name), name)
            if (!hasPlainFunction && nativeOpt.hasValue()) {
                calleeName = nativeOpt.value()
            }
            if (this.dataClassNames.has(name)) {
                calleeName = this.qualify(this.typePackage(name), "_make_" + name)
            }
            return calleeName + "(" + cgJoin(*args, ", ") + ")"
        }
        if (calleeKind == AstNodeCategory.ExprMember) {
            var args: List<Str> = List<Str>()
            var i: Int = 0
            while (i < argNodes.size()) {
                args.append(this.expr(*argNodes[i], 0, *xmlEmptyNode()))
                i = i + 1
            }
            val calleeText: Str = xmlAttr(*callee, AstNodeAttributeKind.Name)
            val receiverExpr: AstXmlNode = xmlChild(*callee, AstNodeKind.Receiver)

            // Machine identity: `x.smToYield()` on a machine *is* `x`. That is the wrap a
            // `for` puts around what it iterates, and `..T` is not a spellable type, so the
            // identity is the backend's rather than a function's (impl_specs/for.md).
            if (calleeText == "smToYield") {
                val identityRecv: AstXmlNode = this.pointee(*this.inferType(*receiverExpr))
                if (!xmlIsEmpty(*identityRecv) && xmlKind(*identityRecv) == AstNodeCategory.TypeYield) {
                    return this.expr(*receiverExpr, 0, *xmlEmptyNode())
                }
            }

            // Enum conversions: `x.toInt()` and `Enum.fromInt(v)`.
            if (calleeText == "toInt") {
                val enumReceiver: AstXmlNode = this.pointee(*this.inferType(*receiverExpr))
                if (!xmlIsEmpty(*enumReceiver) && xmlKind(*enumReceiver) == AstNodeCategory.TypeNamed
                    && this.enumNames.has(xmlAttr(*enumReceiver, AstNodeAttributeKind.Name))
                ) {
                    return "static_cast<Int>(" + this.expr(*receiverExpr, 9, *xmlEmptyNode()) + ")"
                }
            }
            if (calleeText == "fromInt" && xmlKind(*receiverExpr) == AstNodeCategory.ExprName
                && this.enumNames.has(xmlAttr(*receiverExpr, AstNodeAttributeKind.Name))
            ) {
                val enumName: Str = xmlAttr(*receiverExpr, AstNodeAttributeKind.Name)
                return this.qualify(this.typePackage(enumName), "simse_" + enumName + "_fromInt")
                +"(" + cgJoin(*args, ", ") + ")"
            }
            if (xmlKind(*receiverExpr) == AstNodeCategory.ExprGenericName) {
                val genericName: Str = xmlAttr(*receiverExpr, AstNodeAttributeKind.Name)
                return this.qualify(this.typePackage(genericName), genericName) + "<"
                +this.typeArgsString(genericName, *xmlChildren(*receiverExpr, AstNodeKind.TypeArg))
                +">::" + calleeText + "(" + cgJoin(*args, ", ") + ")"
            }

            val receiverType: AstXmlNode = this.inferType(*receiverExpr)
            val receiver: AstXmlNode = this.pointee(*receiverType)
            if (!xmlIsEmpty(*receiver)) {
                val fnIndex: Int = this.findExtensionFn(calleeText, *receiverExpr)
                if (fnIndex >= 0) {
                    val fn: *CgFn = *this.functions[fnIndex]
                    var all: Str = this.receiverArg(*fn.receiver, *receiverExpr)
                    var a: Int = 0
                    while (a < args.size()) {
                        all = all + ", " + args[a]
                        a = a + 1
                    }
                    return this.qualify(fn.packageName, xmlAttr(*fn.decl, AstNodeAttributeKind.Name)) + "(" + all + ")"
                }
                val extIndex: Int = this.findNativeExt(calleeText, *receiverExpr)
                if (extIndex >= 0) {
                    val extensions: List<CgNativeExt> = this.nativeExtensions.get(calleeText).value()
                    val ext: *CgNativeExt = *extensions[extIndex]
                    var all: Str = this.nativeReceiverArg(*ext.receiver, *receiverExpr)
                    var a: Int = 0
                    while (a < args.size()) {
                        all = all + ", " + args[a]
                        a = a + 1
                    }
                    return ext.symbol + "(" + all + ")"
                }
                return this.memberAccess(*receiverExpr, calleeText) + "(" + cgJoin(*args, ", ") + ")"
            }

            if (this.receiverFnNames.has(calleeText)) {
                val byName: Int = this.findReceiverFnByName(calleeText)
                var all: Str = ""
                if (byName >= 0) {
                    val caller: *CgFn = *this.functions[byName]
                    all = this.receiverArg(*caller.receiver, *receiverExpr)
                } else {
                    all = this.expr(*receiverExpr, 9, *xmlEmptyNode())
                }
                var a: Int = 0
                while (a < args.size()) {
                    all = all + ", " + args[a]
                    a = a + 1
                }
                return this.qualify(this.functionPackage(calleeText), calleeText) + "(" + all + ")"
            }
            if (this.nativeExtensions.has(calleeText)) {
                val extensions: List<CgNativeExt> = this.nativeExtensions.get(calleeText).value()
                if (extensions.size() > 0) {
                    var all: Str = this.expr(*receiverExpr, 9, *xmlEmptyNode())
                    var a: Int = 0
                    while (a < args.size()) {
                        all = all + ", " + args[a]
                        a = a + 1
                    }
                    return extensions[0].symbol + "(" + all + ")"
                }
            }
            return this.memberAccess(*receiverExpr, calleeText) + "(" + cgJoin(*args, ", ") + ")"
        }
        this.fail(e, "unsupported: call target")
        return "/*unsupported*/"
    }

    // ---- prelude ----------------------------------------------------------

    fun preludeText(): Unit {
        var text: Str = "// Generated by simse_transpile. Do not edit.\n"
        text = text + "#include \"cppsrc/rtl/simse.hpp\"\n"
        text = text + "#include <iostream>\n"
        text = text + "#include <type_traits>\n"
        text = text + "\n"
        this.out = text
    }

    fun run(): Res<Str> {
        this.collect()
        this.collectProgramNames()
        // The semantic step on the lowered body reads these (sema/TypeInfer.kt). They
        // are built here and threaded to the emitters rather than stored on the emitter:
        // the facts are one value per program, and a body's emitter only borrows it.
        val facts: SemFacts = this.collectFacts()
        this.preludeText()
        this.emitNativeDeclarations()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitForwardTypes()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitTypes()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitStatics()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitFunctions(true, *facts)
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitStaticInit()
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        this.emitFunctions(false, *facts)
        if (this.failed) {
            return Res<Str>.err(this.error)
        }
        return Res<Str>.ok(this.out)
    }
}

// ---- entry point ----------------------------------------------------------

fun newEmitter(inputs: List<CgInput>): Emitter {
    return Emitter(
        inputs,
        "",
        false,
        "",
        "",
        false,
        Dictionary<Str, Bool>(),
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
        "",
        false
    )
}

// Amalgamates every input into one C++ translation unit. Deterministic: the same
// inputs always produce byte-identical output. On failure the error is formatted
// as "<file>:<line>:<col>: <message>".
fun emitProgram(inputs: List<CgInput>): Res<Str> {
    var emitter: Emitter = newEmitter(inputs)
    return emitter.run()
}
