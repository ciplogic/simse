// SemaCollect.kt
//
// `Analyzer`'s collection and scope setup: the global tables, imports, the visible set and
// the scope stack. Extension methods on `Analyzer` (Sema.kt).

package sema

import parser
import common

fun Analyzer.run(): Unit {
    this.collectGlobal()
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

// Every class that declares `unInit`, before anything is analyzed: the rule below reads
// the set, and a declaration may name a type from any module.
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
                        fmtStr("'|' declares unInit twice: a type has one destructor", className)
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
                this.uninitTypes.insert(className, true)
            }
        }
        n = n + 1
    }
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
        if (this.uninitTypes.has(name)) {
            this.diag(
                line, column,
                fmtStr(
                    "'|' has an unInit: hold it by '*|' or '&|' - a value copy would run its destructor too",
                    name, name, name
                )
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
    this.diags.append(
        fmtStr("|:|:|: |", this.file, line.toString(), column.toString(), message)
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
            val isFunction: Bool = kind == AstNodeCategory.Function
            val isStatic: Bool = kind == AstNodeCategory.Var
            val nameTaken: Bool = this.globalTypes.has(key) || this.globalFunctions.has(key)
                    || this.globalStatics.has(key)
            if (isFunction) {
                if (this.globalTypes.has(key) || this.globalStatics.has(key)) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        fmtStr("duplicate declaration '|'", name)
                    )
                } else {
                    this.appendGlobalFunction(key, decl)
                }
            } else if (isStatic) {
                if (nameTaken) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        fmtStr("duplicate declaration '|'", name)
                    )
                } else {
                    this.globalStatics.insert(key, decl)
                }
            } else {
                if (nameTaken) {
                    this.diag(
                        xmlLine(decl), xmlColumn(decl),
                        fmtStr("duplicate declaration '|'", name)
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
                fmtStr("cannot resolve import '|': no file declares package '|'", dotted, dotted)
            )
        }
    }
}

// Builds the unqualified scope of one file: its own package, then imports, then the
// implicit `rtl` prelude. Pushes the module scope (with file-level statics as values,
// specs/statics.md) that `run` pops.
fun Analyzer.buildVisible(module: *AstXmlNode): Unit {
    this.types = Dictionary<Str, AstXmlNode>()
    this.functions = Dictionary<Str, List<AstXmlNode>>()
    var packages: List<Str> = List<Str>()
    packages.append(this.packageOf(module))
    val imports: List<AstXmlNode> = xmlChildren(module, AstNodeKind.Import)
    for (*importDecl in imports) {
        packages.append(xmlAttr(importDecl, AstNodeAttributeKind.Path))
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
    this.diag(line, column, fmtStr("unknown type '|'", name))
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
            line, column, fmtStr(
                "'|' expects | type argument(s) but got |",
                name, expected.toString(), argCount.toString()
            )
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

