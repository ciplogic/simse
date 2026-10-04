// SemaCollect.kt
//
// `Analyzer`'s collection and scope setup: the global tables, imports, the visible set and
// the scope stack. Extension methods on `Analyzer` (Sema.kt).

package sema
import compiler

import parser
import common

fun Analyzer.run(): Unit {
    this.checkPreludeTypeNames()
    this.collectGlobal()
    this.checkOperators()
    this.collectUninitTypes()
    var n: Int = 0
    while (n < this.inputs.size()) {
        val input: SemaInput = this.inputs[n]
        this.file = input.fileName
        this.validateImports(input.module)
        this.buildVisible(input.module)
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
            this.analyzeDecl(decl)
        }
        this.popScope()
        n = n + 1
    }
}

// The operator functions (`operator fun get`/`set`, specs/functions.md): the index syntax
// resolves them, so their shape is fixed - `get` takes the index, `set` the index and the
// value - and only the names with a lowering are let through. A top-level one must have a
// receiver (a class-body method has its class), because the syntax needs a value to index.
fun Analyzer.checkOperators(): Unit {
    var n: Int = 0
    while (n < this.inputs.size()) {
        val input: SemaInput = this.inputs[n]
        this.file = input.fileName
        for (*decl in xmlDecls(input.module)) {
            if (decl.name == AstNodeKind.Function) {
                this.checkOperatorDecl(decl, false)
            }
            if (decl.name == AstNodeKind.DataClass) {
                for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                    this.checkOperatorDecl(method, true)
                }
            }
        }
        n = n + 1
    }
}

// The operator names the language gives a lowering, and the number of parameters each takes
// (the receiver is not one: a class body supplies it, an extension names it). `get`/`set`
// are the index syntax; `compareTo`/`equals`/`plus` are the Kotlin operators behind
// `<`/`<=`/`>`/`>=`, `==`/`!=` and `+` (specs/functions.md, "Operator functions"). -1 for a
// name with no lowering.
fun semOperatorArity(name: *Str): Int {
    if (name == "get") {
        return 1
    }
    if (name == "set") {
        return 2
    }
    if (name == "compareTo" || name == "equals" || name == "plus") {
        return 1
    }
    return -1
}

// The operator a binary syntax resolves to, or "": `a < b` is `a.compareTo(b)` - and `<=`,
// `>`, `>=` likewise, each derived from the Int it answers - `a == b` is `a.equals(b)` and
// `a + b` is `a.plus(b)`. The right operand is the operator's one parameter.
fun semBinaryOperatorName(op: *Str): Str {
    if (op == "<" || op == "<=" || op == ">" || op == ">=") {
        return "compareTo"
    }
    if (op == "==" || op == "!=") {
        return "equals"
    }
    if (op == "+") {
        return "plus"
    }
    return ""
}

// One declaration, when it carries `operator`. `inClass` says a class body supplies the
// receiver; a top-level operator must name its own (`fun T.get(...)` or `this: T`).
fun Analyzer.checkOperatorDecl(decl: *AstXmlNode, inClass: Bool): Unit {
    if (xmlAttr(decl, AstNodeAttributeKind.IsOperator) != "true") {
        return
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val line: Int = xmlLine(decl)
    val column: Int = xmlColumn(decl)
    val arity: Int = semOperatorArity(name)
    if (arity < 0) {
        this.diag(
            line, column,
            `'@name' is not an operator: 'get', 'set', 'compareTo', 'equals' and 'plus' are the operators`
        )
        return
    }
    if (!inClass && xmlAttr(decl, AstNodeAttributeKind.HasReceiver) != "true"
        && xmlIsEmpty(semExtensionReceiver(decl))
    ) {
        this.diag(line, column, `an operator function needs a receiver: 'fun T.@name(...)'`)
        return
    }
    // The receiver is not a parameter in either spelling: an explicit `this` is skipped by
    // `semReceiverParams`, a Kotlin-style receiver is not a `Param` at all.
    val count: Int = xmlCount(decl, AstNodeKind.Param) - semReceiverParams(decl)
    if (count != arity) {
        if (name == "get") {
            this.diag(line, column, "'get' takes one index parameter")
        } else if (name == "set") {
            this.diag(line, column, "'set' takes an index and a value")
        } else {
            this.diag(line, column, `'@name' takes the other operand`)
        }
        return
    }
    this.checkOperatorReturn(decl, name, line, column)
}

// The contract the syntax leans on: the four comparisons derive from the Int `compareTo`
// answers, and `==`/`!=` use `equals`'s Bool; `plus`'s result is the expression's type, so
// anything fits it. An alias is followed the way receiver matching follows one, and a name
// that resolves to nothing (an unresolved alias) stays silent rather than guessing.
fun Analyzer.checkOperatorReturn(decl: *AstXmlNode, name: *Str, line: Int, column: Int): Unit {
    var want: Str = ""
    if (name == "compareTo") {
        want = "Int"
    }
    if (name == "equals") {
        want = "Bool"
    }
    if (want == "") {
        return
    }
    val ret: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (xmlIsEmpty(ret)) {
        return
    }
    val resolved: AstXmlNode = this.unionResolveAlias(ret)
    if (xmlIsEmpty(resolved) || xmlKind(resolved) != AstNodeCategory.TypeNamed) {
        return
    }
    val got: Str = xmlAttr(resolved, AstNodeAttributeKind.Name)
    val target: *AstXmlNode = this.types.getPtr(got)
    if (target != null && target.name == AstNodeKind.TypeAlias) {
        return
    }
    if (got != want) {
        this.diag(line, column, `'@name' must answer @want`)
    }
}

// `x[i] = v` on a type that declares `operator get` but no `operator set`: a getter answers
// a *value*, so there is no place to write (specs/functions.md). The write needs its own
// operator; the diagnostic lives here because only the checker can see this much before
// the body is lowered.
fun Analyzer.checkOperatorIndexWrite(target: *AstXmlNode): Unit {
    val actual: AstXmlNode = this.exprType(xmlChildPtr(target, AstNodeKind.Receiver))
    if (xmlIsEmpty(actual)) {
        return
    }
    if (!this.hasOperatorFn("get", actual) || this.hasOperatorFn("set", actual)) {
        return
    }
    this.diag(
        xmlLine(target), xmlColumn(target),
        "'set' is not declared for this receiver: an index write needs 'operator set(index, value)'"
    )
}

// Whether the type `actual` declares the operator `name`, in either spelling: a class-body
// method (found through the type's declaration) or a visible extension (found through the
// functions table). `semaReceiverOuter` follows aliases, so `StrView` matches a `Span`
// operator too.
fun Analyzer.hasOperatorFn(name: *Str, actual: AstXmlNode): Bool {
    val outer: AstXmlNode = this.semaReceiverOuter(actual)
    val decl: *AstXmlNode = this.types.getPtr(xmlAttr(outer, AstNodeAttributeKind.Name))
    if (decl != null) {
        for (*method in xmlChildren(*decl, AstNodeKind.Function)) {
            if (xmlAttr(method, AstNodeAttributeKind.Name) == name && xmlAttr(
                    method,
                    AstNodeAttributeKind.IsOperator
                ) == "true"
            ) {
                return true
            }
        }
    }
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return false
    }
    for (*fn in overloads) {
        if (xmlAttr(fn, AstNodeAttributeKind.IsOperator) != "true") {
            continue
        }
        var receiver: AstXmlNode = xmlEmptyNode()
        val params: List<AstXmlNode> = xmlChildren(fn, AstNodeKind.Param)
        if (xmlAttr(fn, AstNodeAttributeKind.HasReceiver) == "true" && !xmlIsEmpty(
                xmlChildPtr(fn, AstNodeKind.Receiver)
            )
        ) {
            receiver = xmlChild(fn, AstNodeKind.Receiver)
        } else if (params.size() > 0 && xmlAttr(params[0], AstNodeAttributeKind.Name) == "this"
            && !xmlIsEmpty(xmlChildPtr(params[0], AstNodeKind.Type))
        ) {
            receiver = xmlChild(params[0], AstNodeKind.Type)
        }
        if (xmlIsEmpty(receiver)) {
            continue
        }
        val typeParams: List<Str> = xmlTypeParamNames(fn)
        if (semaUnifyReceiver(*receiver, *actual, *typeParams)) {
            return true
        }
    }
    return false
}

// Every class that declares `unInit`, before anything is analyzed: the declarations are
// still checked first, whichever order they come in, and a declaration may name a type from
// any module. A destructor makes a class handle-only, and that is `ref class`'s word - a
// `data class` with an `unInit` is a diagnostic, so one declaration carries both the
// destructor and the no-value rule. The holder rule itself (`checkUninitHolder`) reads the
// declaration a name resolves to, not a global set of names.
fun Analyzer.collectUninitTypes(): Unit {
    var n: Int = 0
    while (n < this.inputs.size()) {
        val input: *SemaInput = *this.inputs[n]
        for (*decl in xmlDecls(input.module)) {
            if (decl.name != AstNodeKind.DataClass) {
                continue
            }
            val className: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            var seen: Bool = false
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                if (xmlAttr(method, AstNodeAttributeKind.Name) != "unInit") {
                    continue
                }
                // The shape a destructor has: no parameters, nothing returned, once.
                if (seen) {
                    this.diag(
                        xmlLine(method), xmlColumn(method),
                        `'@className' declares unInit twice: a type has one destructor`
                    )
                }
                seen = true
                if (xmlCount(method, AstNodeKind.Param) > 0) {
                    this.diag(xmlLine(method), xmlColumn(method), "unInit takes no parameters")
                }
                // An explicit `: Unit` is "nothing" too; anything else is a mistake.
                val ret: *AstXmlNode = xmlChildPtr(method, AstNodeKind.ReturnType)
                if (!xmlIsEmpty(ret) && semaTypeText(ret) != "Unit") {
                    this.diag(xmlLine(method), xmlColumn(method), "unInit returns nothing")
                }
                // A destructor means the class is handle-only, and that is `ref class`'s word.
                if (xmlAttr(decl, AstNodeAttributeKind.IsRefClass) != "true") {
                    this.diag(
                        xmlLine(method), xmlColumn(method),
                        `'@className' declares unInit: a class with a destructor must be a 'ref class'`
                    )
                }
            }
        }
        n = n + 1
    }
}

// Whether the class declares `unInit` (its destructor): read from the declaration itself,
// so the holder rule follows what the type name resolves to in the file being analyzed.
fun semaDeclaresUninit(decl: AstXmlNode): Bool {
    if (decl.name != AstNodeKind.DataClass) {
        return false
    }
    for (*method in xmlChildren(decl, AstNodeKind.Function)) {
        if (xmlAttr(method, AstNodeAttributeKind.Name) == "unInit") {
            return true
        }
    }
    return false
}

// `unInit` is a destructor, not a callable (`specs/declarations.md`): `x.unInit()` is the
// mistake the name invites, and the emitted C++ would name a function never emitted.
fun Analyzer.checkUninitCall(call: *AstXmlNode, callee: *AstXmlNode): Unit {
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    if (xmlAttr(callee, AstNodeAttributeKind.Name) != "unInit") {
        return
    }
    this.diag(
        xmlLine(call), xmlColumn(call),
        "unInit is a type's destructor: it is not called - the value's last owner destroys it"
    )
}

// A `ref class` has no value form (`specs/declarations.md`): the boxed `&C(...)`
// (`analyzeCall`'s `boxed` flag marks it) is the one construction allowed, so any other
// construction - a local, an argument, a field - is a value and a diagnostic. A class that
// declares `unInit` must be a ref class (`collectUninitTypes`), so its constructions land
// here too; the holder rule (`checkUninitHolder`) covers the *declared* value positions.
fun Analyzer.checkValueConstruction(call: *AstXmlNode, callee: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(callee)
    if (kind != AstNodeCategory.ExprName && kind != AstNodeCategory.ExprGenericName) {
        return
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    val decl: *AstXmlNode = this.types.getPtr(name)
    if (decl == null || decl.name != AstNodeKind.DataClass) {
        return
    }
    if (xmlAttr(decl, AstNodeAttributeKind.IsRefClass) != "true") {
        return
    }
    this.diag(
        xmlLine(call), xmlColumn(call),
        `'@name' is a ref class: build it as '&@name(...)' - a ref class is held by '&@name' or '*@name'`
    )
}

// A `*T` or `&T` holds a type with a destructor; a value does not. A `T` value is a copy,
// and every copy runs `~T()` - the resource closed once per copy - which is what the
// handle is for (`&T` destroys the box when its last owner goes). A `*`/`&` anywhere in
// the type is the escape: `List<&T>` is fine, `List<T>` is not.
fun Analyzer.checkUninitHolder(typeNode: *AstXmlNode, line: Int, column: Int): Unit {
    if (xmlIsEmpty(typeNode)) {
        return
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference) {
        return
    }
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        // The declaration the name actually resolves to decides: a program type may shadow
        // a prelude name (`ref class Res`), and only its own uses are value-unsafe. A
        // global set of names would mark the prelude `Res` for the *program's* unInit too -
        // the prelude union's generated C++ has a destructor (the managed storage) but no
        // language-level `unInit`.
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl != null && semaDeclaresUninit(*decl)) {
            this.diag(
                line, column,
                `'@name' has an unInit: hold it by '*@name' or '&@name' - a value copy would run its destructor too`
            )
            return
        }
    }
    var i: Int = 0
    while (i < typeNode.Children.count()) {
        this.checkUninitHolder(typeNode.Children[i], line, column)
        i = i + 1
    }
}

fun Analyzer.diag(line: Int, column: Int, message: *Str): Unit {
    val fileText: Str = this.file
    val lineText: Str = line.toString()
    val columnText: Str = column.toString()
    this.diags.append(
        `@fileText:@lineText:@columnText: @message`
    )
}

// "<a>.<b>" for the module's package; the empty string is the root package.
fun Analyzer.packageOf(module: *AstXmlNode): Str {
    return xmlAttr(module, AstNodeAttributeKind.Package)
}

// Appends `decl` under `key` in `map`, keyed by "pkg|name" (global) or bare name
// (visible); `getPtr` gives the dictionary's own list, so the append needs no write-back.
fun Analyzer.appendGlobalFunction(key: *Str, decl: *AstXmlNode): Unit {
    val existing: *List<AstXmlNode> = this.globalFunctions.getPtr(key)
    if (existing != null) {
        existing.append(decl)
    } else {
        var fresh: List<AstXmlNode> = List<AstXmlNode>()
        fresh.append(decl)
        this.globalFunctions.insert(key, fresh)
    }
}

fun Analyzer.appendPackageDecl(pkg: *Str, decl: *AstXmlNode): Unit {
    val existing: *List<AstXmlNode> = this.packageDecls.getPtr(pkg)
    if (existing != null) {
        existing.append(decl)
    } else {
        var fresh: List<AstXmlNode> = List<AstXmlNode>()
        fresh.append(decl)
        this.packageDecls.insert(pkg, fresh)
    }
}

fun Analyzer.appendVisibleFunction(name: *Str, decl: *AstXmlNode): Unit {
    val existing: *List<AstXmlNode> = this.functions.getPtr(name)
    if (existing != null) {
        existing.append(decl)
    } else {
        var fresh: List<AstXmlNode> = List<AstXmlNode>()
        fresh.append(decl)
        this.functions.insert(name, fresh)
    }
}

// A program may not redeclare a *prelude type* (`data class Res`, `enum class Span`, ...):
// the prelude's types are emitted under their bare names, and the prelude's own generated
// code names them (`Opt<Int> simse_str_toInt(...)`), so a second declaration of the name
// would make the emitter's name -> package table pick one of the two and misname the other.
// A *function* may still shadow a prelude function; only declared types are checked, and a
// builtin (`Str`, `Int`) is no declaration to collide with.
fun Analyzer.checkPreludeTypeNames(): Unit {
    var preludeTypes: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*input in this.inputs) {
        if (!input.prelude) {
            continue
        }
        for (*decl in xmlDecls(input.module)) {
            if (decl.name == AstNodeKind.DataClass || decl.name == AstNodeKind.Enum
                || decl.name == AstNodeKind.TypeAlias
            ) {
                preludeTypes.insert(xmlAttr(decl, AstNodeAttributeKind.Name), true)
            }
        }
    }
    if (preludeTypes.size() == 0) {
        return
    }
    for (*input in this.inputs) {
        if (input.prelude) {
            continue
        }
        this.file = input.fileName
        for (*decl in xmlDecls(input.module)) {
            if (decl.name != AstNodeKind.DataClass && decl.name != AstNodeKind.Enum
                && decl.name != AstNodeKind.TypeAlias
            ) {
                continue
            }
            val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            if (preludeTypes.has(name)) {
                this.diag(
                    xmlLine(decl), xmlColumn(decl),
                    `'@name' is a prelude type: give the declaration another name (the prelude's types are emitted by name)`
                )
            }
        }
    }
}

// Collects every declaration into its package scope, reporting a duplicate top-level
// name within one package (across two files too). File-level statics (`Var`,
// specs/statics.md) share that namespace but are collected separately: they are
// values, not types.
fun Analyzer.collectGlobal(): Unit {
    var n: Int = 0
    while (n < this.inputs.size()) {
        val input: SemaInput = this.inputs[n]
        this.file = input.fileName
        val pkg: Str = this.packageOf(input.module)
        if (!this.declaredPackages.contains(pkg)) {
            this.declaredPackages.append(pkg)
        }
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        var i: Int = 0
        while (i < decls.size()) {
            val decl: AstXmlNode = decls[i]
            val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            val key: Str = pkg + "|" + name
            val kind: AstNodeCategory = xmlKind(decl)
            // A protocol is a Function node but not a callable one: it is registered under
            // its *protocol* name and never enters the function or type tables.
            if (xmlIsProtocolDecl(decl)) {
                val protocolName: Str = xmlAttr(decl, AstNodeAttributeKind.Protocol)
                if (this.globalProtocols.has(protocolName)) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        `duplicate protocol '@protocolName'`
                    )
                } else {
                    this.globalProtocols.insert(protocolName, decl)
                }
                i = i + 1
                continue
            }
            val isFunction: Bool = kind == AstNodeCategory.Function
            val isStatic: Bool = kind == AstNodeCategory.Var
            val nameTaken: Bool = this.globalTypes.has(key) || this.globalFunctions.has(key)
                    || this.globalStatics.has(key)
            if (isFunction) {
                if (this.globalTypes.has(key) || this.globalStatics.has(key)) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        `duplicate declaration '@name'`
                    )
                } else {
                    this.appendGlobalFunction(key, decl)
                }
            } else if (isStatic) {
                if (nameTaken) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        `duplicate declaration '@name'`
                    )
                } else {
                    this.globalStatics.insert(key, decl)
                }
            } else {
                if (nameTaken) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        `duplicate declaration '@name'`
                    )
                } else {
                    this.globalTypes.insert(key, decl)
                }
            }
            this.appendPackageDecl(pkg, decl)
            i = i + 1
        }
        n = n + 1
    }
}

fun Analyzer.validateImports(module: *AstXmlNode): Unit {
    val imports: List<AstXmlNode> = xmlChildren(module, AstNodeKind.Import)
    for (*importDecl in imports) {
        val dotted: Str = xmlAttr(importDecl, AstNodeAttributeKind.Path)
        if (!this.declaredPackages.contains(dotted)) {
            this.diag(
                xmlLine(importDecl), xmlColumn(importDecl),
                `cannot resolve import '@dotted': no file declares package '@dotted'`
            )
        }
    }
}

// Builds the unqualified scope of one file, in decreasing precedence: its own package, then
// its imports with the *last* written ahead of the earlier ones, then the implicit `rtl`
// prelude. The first declaration of a name in this order wins, so a later import shadows an
// earlier one and an explicit import shadows `rtl` (specs/modules.md, "Shadowing"). Pushes the
// module scope (with file-level statics as values, specs/statics.md) that `run` pops.
fun Analyzer.buildVisible(module: *AstXmlNode): Unit {
    this.types = Dictionary<Str, AstXmlNode>()
    this.functions = Dictionary<Str, List<AstXmlNode>>()
    var packages: List<Str> = List<Str>()
    packages.append(this.packageOf(module))
    val imports: List<AstXmlNode> = xmlChildren(module, AstNodeKind.Import)
    var importAt: Int = imports.size() - 1
    while (importAt >= 0) {
        packages.append(xmlAttr(imports[importAt], AstNodeAttributeKind.Path))
        importAt = importAt - 1
    }
    packages.append("rtl")

    this.pushScope()
    var seen: List<Str> = List<Str>()
    for (pkg in packages) {
        if (!seen.contains(pkg)) {
            seen.append(pkg)
            val decls: *List<AstXmlNode> = this.packageDecls.getPtr(pkg)
            if (decls != null) {
                for (decl in decls) {
                    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
                    if (xmlIsProtocolDecl(decl)) {
                        // A protocol never enters a file's type/function scope; a constraint
                        // names it through the global protocol table.
                        continue
                    }
                    if (xmlKind(decl) == AstNodeCategory.Function) {
                        this.appendVisibleFunction(name, decl)
                    } else if (xmlKind(decl) == AstNodeCategory.Var) {
                        this.declareValue(
                            name, xmlAttr(decl, AstNodeAttributeKind.IsVar) == "true", true,
                            xmlChildPtr(decl, AstNodeKind.Type)
                        )
                    } else if (!this.types.has(name)) {
                        this.types.insert(name, decl)
                    }
                }
            }
        }
    }
}

fun Analyzer.pushScope(): Unit {
    this.scopes.append(Dictionary<Str, ValueBinding>())
}

fun Analyzer.popScope(): Unit {
    this.scopes.removeAt(this.scopes.size() - 1)
}

fun Analyzer.pushTypeScope(): Unit {
    this.typeScopes.append(List<Str>())
}

fun Analyzer.popTypeScope(): Unit {
    this.typeScopes.removeAt(this.typeScopes.size() - 1)
}

fun Analyzer.declareType(name: *Str): Unit {
    if (this.typeScopes.size() == 0) {
        return
    }
    this.typeScopes[this.typeScopes.size() - 1].append(name)
}

fun Analyzer.declareValue(name: *Str, isMutable: Bool, checkAssign: Bool, type: *AstXmlNode): Unit {
    if (this.scopes.size() == 0) {
        return
    }
    this.scopes[this.scopes.size() - 1].insert(name, ValueBinding(isMutable, checkAssign, type))
}

fun Analyzer.lookupValue(name: *Str): Opt<ValueBinding> {
    var i: Int = this.scopes.size() - 1
    while (i >= 0) {
        if (this.scopes[i].has(name)) {
            return this.scopes[i].get(name)
        }
        i = i - 1
    }
    return ()
}

fun Analyzer.typeParamVisible(name: *Str): Bool {
    for (*scope in this.typeScopes) {
        var j: Int = 0
        while (j < scope.size()) {
            if (scope[j] == name) {
                return true
            }
            j = j + 1
        }
    }
    return false
}

fun Analyzer.checkTypeName(name: *Str, line: Int, column: Int): Unit {
    if (semaIsBuiltinType(name) || this.types.has(name) || this.typeParamVisible(name)) {
        return
    }
    this.diag(line, column, `unknown type '@name'`)
}

fun Analyzer.checkInstantiationArity(name: *Str, argCount: Int, line: Int, column: Int): Unit {
    var expected: Int = -1
    val typeDecl: *AstXmlNode = this.types.getPtr(name)
    if (typeDecl != null) {
        expected = xmlCount(typeDecl, AstNodeKind.TypeParam)
    } else {
        expected = semaBuiltinGenericArity(name)
    }
    if (expected >= 0 && argCount != expected) {
        this.diag(
            line, column, `'@name' expects @expected type argument(s) but got @argCount`
        )
    }
}

fun Analyzer.resolveType(type: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(type)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return
        }

        AstNodeCategory.TypeNamed -> {
            this.checkTypeName(xmlAttr(type, AstNodeAttributeKind.Name), xmlLine(type), xmlColumn(type))
            return
        }

        AstNodeCategory.TypeGeneric -> {
            this.checkTypeName(xmlAttr(type, AstNodeAttributeKind.Name), xmlLine(type), xmlColumn(type))
            this.checkInstantiationArity(
                xmlAttr(type, AstNodeAttributeKind.Name), xmlCount(type, AstNodeKind.TypeArg),
                xmlLine(type), xmlColumn(type)
            )
            val args: List<AstXmlNode> = xmlChildren(type, AstNodeKind.TypeArg)
            for (*arg in args) {
                this.resolveType(arg)
            }
            return
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            val inner: *AstXmlNode = xmlChildPtr(type, AstNodeKind.Inner)
            if (!xmlIsEmpty(inner)) {
                this.resolveType(inner)
            }
            return
        }

        // `..T`: the machine itself is not a type to check, its *element* is (`..*T`'s `T`).
        AstNodeCategory.TypeYield -> {
            val inner: *AstXmlNode = xmlChildPtr(type, AstNodeKind.Inner)
            if (!xmlIsEmpty(inner)) {
                this.resolveType(inner)
            }
            return
        }

        AstNodeCategory.TypeFunction -> {
            val params: List<AstXmlNode> = xmlChildren(type, AstNodeKind.ParamType)
            for (*param in params) {
                this.resolveType(param)
            }
            val ret: *AstXmlNode = xmlChildPtr(type, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(ret)) {
                this.resolveType(ret)
            }
            return
        }
    }
}
