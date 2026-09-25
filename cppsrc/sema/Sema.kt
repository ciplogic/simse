// Sema.kt
//
// The name/type-resolution pass: it consumes the AstXmlNode AST (schema in
// impl_specs/ast-xmlnode.md). Positional information comes from the
// `line`/`column` attributes.

package sema

import parser
import common

// The read-only AstXmlNode accessors (xmlAttr, xmlChild, ...) live in
// cppsrc/common/xmlutil.kt and arrive through `import common`.

// `type` is an empty AstXmlNode when unknown.
data class ValueBinding(
    var isMutable: Bool,

    var checkAssign: Bool,
    var type: AstXmlNode
)

fun semaIsBuiltinType(name: *Str): Bool {
    if (name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
        || name == "Int64" || name == "Float32" || name == "Float64" || name == "Char"
        || name == "Str" || name == "Bool" || name == "Unit" || name == "List"
        || name == "Array" || name == "RawArray" || name == "Opt" || name == "Res"
        || name == "Dictionary" || name == "SmallVector" || name == "PList"
    ) {
        return true
    }
    return false
}

fun semaBuiltinGenericArity(name: *Str): Int {
    when (name) {
        "List", "Array", "RawArray", "Opt", "Res", "PList" -> {
            return 1
        }

        "Dictionary", "SmallVector" -> {
            return 2
        }
    }
    return -1
}

// A handle: `&T`, `*T`, or the `PList<T>` alias of `&List<T>`. The extractor spells
// the same rule as `ilIsHandleType`, the emitter as `Emitter.isHandleType`.
fun semaIsHandleType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    return kind == AstNodeCategory.TypeGeneric
            && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList"
}

// A *view*: `Span<T>` (`StrView` is its `Span<Char>` alias, cppsrc/rtl/StrView.kt), which
// owns no storage, so the C++ has no conversion from an owned value (`checkViewArgument`).
fun semaIsSpanType(node: *AstXmlNode): Bool {
    return xmlKind(node) == AstNodeCategory.TypeGeneric
            && xmlAttr(node, AstNodeAttributeKind.Name) == "Span"
}

// An owned value a view cannot be handed as it stands: `Str` or `List<T>`, after
// unwrapping handles (`*Str` names the same owned storage).
fun semaIsOwnedViewSource(actual: *AstXmlNode): Bool {
    var base: *AstXmlNode = actual
    var guard: Int = 0
    while (guard < 64) {
        guard = guard + 1
        val kind: AstNodeCategory = xmlKind(base)
        if (kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference) {
            val inner: *AstXmlNode = xmlChildPtr(base, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return false
            }
            base = inner
            continue
        }
        if (kind == AstNodeCategory.TypeGeneric
            && xmlAttr(base, AstNodeAttributeKind.Name) == "PList"
        ) {
            val args: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
            if (args.size() == 0) {
                return false
            }
            base = * args [0]
            continue
        }
        break
    }
    if (xmlKind(base) == AstNodeCategory.TypeNamed
        && xmlAttr(base, AstNodeAttributeKind.Name) == "Str"
    ) {
        return true
    }
    return xmlKind(base) == AstNodeCategory.TypeGeneric
            && xmlAttr(base, AstNodeAttributeKind.Name) == "List"
}

// Structural unification of an extension receiver pattern against the actual type;
// references/pointers on the actual side are auto-dereferenced.
fun semaUnifyReceiver(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: *AstXmlNode = actual
    val pk: AstNodeCategory = xmlKind(pattern)
    if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
        while ((xmlKind(actualPtr) == AstNodeCategory.TypeReference || xmlKind(actualPtr) == AstNodeCategory.TypePointer)
            && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
        ) {
            actualPtr = xmlChildPtr(actualPtr, AstNodeKind.Inner)
        }
    }
    val ak: AstNodeCategory = xmlKind(actualPtr)
    when (pk) {
        AstNodeCategory.TypeIntLit -> {
            return ak == AstNodeCategory.TypeIntLit && xmlAttr(actualPtr, AstNodeAttributeKind.Text) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Text
            )
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
            if (ak != AstNodeCategory.TypeGeneric || xmlAttr(actualPtr, AstNodeAttributeKind.Name) != xmlAttr(
                    pattern,
                    AstNodeAttributeKind.Name
                )
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
                if (!semaUnifyReceiver(patternArgs[i], actualArgs[i], typeParams)) {
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
                return semaUnifyReceiver(
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
                return semaUnifyReceiver(
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

// Whether a name is one the `for` desugaring made: the `_sm_for1`/`_sm_index1`
// counters, which are not a user's to take.
fun semaIsForTemplateName(name: *Str): Bool {
    return name.startsWith("_sm_for")
}

// The schema's spelling of a type node ("List<Int>", "*Str", "(Int) -> Bool"), for
// diagnostics that have to name one.
fun semaTypeText(node: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(node)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return xmlAttr(node, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            return xmlAttr(node, AstNodeAttributeKind.Name)
        }

        AstNodeCategory.TypeGeneric -> {
            return fmtStr(
                "|<|>",
                xmlAttr(node, AstNodeAttributeKind.Name),
                semaTypeTextList(xmlChildren(node, AstNodeKind.TypeArg))
            )
        }

        AstNodeCategory.TypeReference -> {
            return "&" + semaTypeText(xmlChildPtr(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            return "*" + semaTypeText(xmlChildPtr(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            return fmtStr(
                "(|) -> |",
                semaTypeTextList(xmlChildren(node, AstNodeKind.ParamType)),
                semaTypeText(xmlChildPtr(node, AstNodeKind.ReturnType))
            )
        }
    }
    return "?"
}

fun semaTypeTextList(types: *List<AstXmlNode>): Str {
    var out: Str = Str()
    val count: Int = types.size()
    if (count == 0) {
        return out
    }
    // Parts are rendered first so the buffer can be reserved once: `out + part` copies.
    var parts: List<Str> = List<Str>()
    var len: Int = 2 * (count - 1)
    var i: Int = 0
    while (i < count) {
        val text: Str = semaTypeText(types[i])
        len += text.size()
        parts.append(text)
        i = i + 1
    }
    out.reserve(len)
    i = 0
    while (i < count) {
        if (i > 0) {
            out.appendStr(", ")
        }
        out.appendStr(parts[i])
        i = i + 1
    }
    return out
}

// One participating file and its parsed Module; the Module carries the package that
// namespaces its declarations.
data class SemaInput(
    var fileName: Str,

    var module: AstXmlNode
)

data class Analyzer(
    var inputs: List<SemaInput>,

    var file: Str,
    var types: Dictionary<Str, AstXmlNode>,
    var functions: Dictionary<Str, List<AstXmlNode>>,
    var globalTypes: Dictionary<Str, AstXmlNode>,
    var globalFunctions: Dictionary<Str, List<AstXmlNode>>,
    var globalStatics: Dictionary<Str, AstXmlNode>,
    var packageDecls: Dictionary<Str, List<AstXmlNode>>,
    var declaredPackages: List<Str>,
    var scopes: List<Dictionary<Str, ValueBinding>>,
    var typeScopes: List<List<Str>>,
    var loopDepth: Int,
    var diags: List<Str>
) {

    fun run(): Unit {
        this.collectGlobal()
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

    fun diag(line: Int, column: Int, message: *Str): Unit {
        this.diags.append(
            fmtStr("|:|:|: |", this.file, line.toString(), column.toString(), message)
        )
    }

    // "<a>.<b>" for the module's package; the empty string is the root package.
    fun packageOf(module: *AstXmlNode): Str {
        return xmlAttr(module, AstNodeAttributeKind.Package)
    }

    // Appends `decl` under `key` in `map`, keyed by "pkg|name" (global) or bare name
    // (visible); `getPtr` gives the dictionary's own list, so the append needs no write-back.
    fun appendGlobalFunction(key: *Str, decl: *AstXmlNode): Unit {
        val existing: *List<AstXmlNode> = this.globalFunctions.getPtr(key)
        if (existing != null) {
            existing.append(decl)
        } else {
            var fresh: List<AstXmlNode> = List<AstXmlNode>()
            fresh.append(decl)
            this.globalFunctions.insert(key, fresh)
        }
    }

    fun appendPackageDecl(pkg: *Str, decl: *AstXmlNode): Unit {
        val existing: *List<AstXmlNode> = this.packageDecls.getPtr(pkg)
        if (existing != null) {
            existing.append(decl)
        } else {
            var fresh: List<AstXmlNode> = List<AstXmlNode>()
            fresh.append(decl)
            this.packageDecls.insert(pkg, fresh)
        }
    }

    fun appendVisibleFunction(name: *Str, decl: *AstXmlNode): Unit {
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
    fun collectGlobal(): Unit {
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

    fun validateImports(module: *AstXmlNode): Unit {
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
    fun buildVisible(module: *AstXmlNode): Unit {
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
        var p: Int = 0
        while (p < packages.size()) {
            val pkg: Str = packages[p]
            if (!seen.contains(pkg)) {
                seen.append(pkg)
                val decls: *List<AstXmlNode> = this.packageDecls.getPtr(pkg)
                if (decls != null) {
                    var d: Int = 0
                    while (d < decls.size()) {
                        val decl: AstXmlNode = decls[d]
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
                        d = d + 1
                    }
                }
            }
            p = p + 1
        }
    }

    fun pushScope(): Unit {
        this.scopes.append(Dictionary<Str, ValueBinding>())
    }

    fun popScope(): Unit {
        this.scopes.removeAt(this.scopes.size() - 1)
    }

    fun pushTypeScope(): Unit {
        this.typeScopes.append(List<Str>())
    }

    fun popTypeScope(): Unit {
        this.typeScopes.removeAt(this.typeScopes.size() - 1)
    }

    fun declareType(name: *Str): Unit {
        if (this.typeScopes.size() == 0) {
            return
        }
        this.typeScopes[this.typeScopes.size() - 1].append(name)
    }

    fun declareValue(name: *Str, isMutable: Bool, checkAssign: Bool, type: *AstXmlNode): Unit {
        if (this.scopes.size() == 0) {
            return
        }
        this.scopes[this.scopes.size() - 1].insert(name, ValueBinding(isMutable, checkAssign, type))
    }

    fun lookupValue(name: *Str): Opt<ValueBinding> {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            if (this.scopes[i].has(name)) {
                return this.scopes[i].get(name)
            }
            i = i - 1
        }
        return Opt<ValueBinding>.none()
    }

    fun typeParamVisible(name: *Str): Bool {
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

    fun checkTypeName(name: *Str, line: Int, column: Int): Unit {
        if (semaIsBuiltinType(name) || this.types.has(name) || this.typeParamVisible(name)) {
            return
        }
        this.diag(line, column, fmtStr("unknown type '|'", name))
    }

    fun checkInstantiationArity(name: *Str, argCount: Int, line: Int, column: Int): Unit {
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

    fun resolveType(type: *AstXmlNode): Unit {
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

    fun analyzeDecl(decl: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(decl)
        when (kind) {
            AstNodeCategory.Var -> {
                // Static storage: the initializer may name any hoisted declaration,
                // including another static (specs/statics.md; init order unspecified).
                val staticType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Type)
                if (!xmlIsEmpty(staticType)) {
                    this.resolveType(staticType)
                }
                val init: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Init)
                if (!xmlIsEmpty(init)) {
                    this.analyzeExpr(init)
                }
                return
            }

            AstNodeCategory.DataClass -> {
                this.pushTypeScope()
                val typeParams: List<Str> = xmlTypeParamNames(decl)
                var i: Int = 0
                while (i < typeParams.size()) {
                    this.declareType(typeParams[i])
                    i = i + 1
                }
                this.pushScope()
                this.declareValue("this", true, false, xmlEmptyNode())
                val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
                for (*field in fields) {
                    val fieldType: *AstXmlNode = xmlChildPtr(field, AstNodeKind.Type)
                    if (!xmlIsEmpty(fieldType)) {
                        this.resolveType(fieldType)
                    }
                }
                val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
                for (*method in methods) {
                    this.analyzeFunction(method)
                }
                this.popScope()
                this.popTypeScope()
                return
            }

            AstNodeCategory.Enum -> {
                return
            }

            AstNodeCategory.TypeAlias -> {
                this.pushTypeScope()
                val typeParams: List<Str> = xmlTypeParamNames(decl)
                var i: Int = 0
                while (i < typeParams.size()) {
                    this.declareType(typeParams[i])
                    i = i + 1
                }
                val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
                if (!xmlIsEmpty(target)) {
                    this.resolveType(target)
                }
                this.popTypeScope()
                return
            }

            AstNodeCategory.Function -> {
                this.analyzeFunction(decl)
                return
            }
        }
    }

    fun analyzeFunction(decl: *AstXmlNode): Unit {
        this.pushTypeScope()
        val typeParams: List<Str> = xmlTypeParamNames(decl)
        var i: Int = 0
        while (i < typeParams.size()) {
            this.declareType(typeParams[i])
            i = i + 1
        }
        this.pushScope()
        this.declareValue("this", true, false, xmlEmptyNode())
        if (xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true") {
            val receiver: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Receiver)
            if (!xmlIsEmpty(receiver)) {
                this.resolveType(receiver)
            }
        }
        val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        for (*param in params) {
            val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
            if (!xmlIsEmpty(paramType)) {
                this.resolveType(paramType)
            }
            // Parameters are not `val` declarations, so reassigning one is never reported.
            this.declareValue(xmlAttr(param, AstNodeAttributeKind.Name), true, false, paramType)
        }
        val returnType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(returnType)) {
            this.resolveType(returnType)
        }

        val savedLoopDepth: Int = this.loopDepth
        this.loopDepth = 0
        val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt)
        for (*stmtNode in body) {
            this.analyzeStmt(stmtNode)
        }
        this.loopDepth = savedLoopDepth

        this.popScope()
        this.popTypeScope()
    }

    fun analyzeStmt(stmt: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtVarDecl -> {
                val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
                if (!xmlIsEmpty(init)) {
                    this.analyzeExpr(init)
                }
                val declaredType: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
                if (!xmlIsEmpty(declaredType)) {
                    this.resolveType(declaredType)
                }
                var type: AstXmlNode = declaredType
                if (xmlIsEmpty(type) && !xmlIsEmpty(init)) {
                    type = this.exprType(init)
                }
                this.checkForIterable(stmt)
                this.declareValue(
                    xmlAttr(stmt, AstNodeAttributeKind.Name),
                    xmlAttr(stmt, AstNodeAttributeKind.IsVar) == "true",
                    true,
                    type
                )
                return
            }

            AstNodeCategory.StmtAssign -> {
                val target: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
                if (!xmlIsEmpty(target)) {
                    this.analyzeExpr(target)
                }
                val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                if (!xmlIsEmpty(value)) {
                    this.analyzeExpr(value)
                }
                if (!xmlIsEmpty(target) && xmlKind(target) == AstNodeCategory.ExprName) {
                    val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(target, AstNodeAttributeKind.Name))
                    if (binding.hasValue() && binding.value().checkAssign && !binding.value().isMutable) {
                        this.diag(
                            xmlLine(target), xmlColumn(target),
                            fmtStr("cannot assign to val '|'", xmlAttr(target, AstNodeAttributeKind.Name))
                        )
                    }
                }
                return
            }

            AstNodeCategory.StmtIf -> {
                val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
                if (!xmlIsEmpty(cond)) {
                    this.analyzeExpr(cond)
                }
                this.pushScope()
                val thenBody: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmt, AstNodeKind.Then), AstNodeKind.Stmt)
                for (*thenStmt in thenBody) {
                    this.analyzeStmt(thenStmt)
                }
                this.popScope()
                val elseBlock: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Else)
                if (!xmlIsEmpty(elseBlock)) {
                    this.pushScope()
                    val elseBody: List<AstXmlNode> = xmlChildren(elseBlock, AstNodeKind.Stmt)
                    for (*elseStmt in elseBody) {
                        this.analyzeStmt(elseStmt)
                    }
                    this.popScope()
                }
                return
            }

            AstNodeCategory.StmtWhile -> {
                val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
                if (!xmlIsEmpty(cond)) {
                    this.analyzeExpr(cond)
                }
                this.pushScope()
                this.loopDepth = this.loopDepth + 1
                val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmt, AstNodeKind.Body), AstNodeKind.Stmt)
                for (*bodyStmt in body) {
                    this.analyzeStmt(bodyStmt)
                }
                this.loopDepth = this.loopDepth - 1
                this.popScope()
                return
            }

            AstNodeCategory.StmtReturn -> {
                val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                if (!xmlIsEmpty(value)) {
                    this.analyzeExpr(value)
                }
                return
            }

            AstNodeCategory.StmtBreak -> {
                if (this.loopDepth == 0) {
                    this.diag(xmlLine(stmt), xmlColumn(stmt), "'break' outside a loop")
                }
                return
            }

            AstNodeCategory.StmtContinue -> {
                if (this.loopDepth == 0) {
                    this.diag(xmlLine(stmt), xmlColumn(stmt), "'continue' outside a loop")
                }
                return
            }

            AstNodeCategory.StmtExprStmt -> {
                val expr: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Expr)
                if (!xmlIsEmpty(expr)) {
                    this.analyzeExpr(expr)
                }
                return
            }
        }
    }

    fun analyzeExpr(expr: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(expr)
        when (kind) {
            AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit, AstNodeCategory.ExprStrLit, AstNodeCategory.ExprCharLit,
            AstNodeCategory.ExprBoolLit, AstNodeCategory.ExprNullLit, AstNodeCategory.ExprName -> {
                return
            }

            AstNodeCategory.ExprGenericName -> {
                this.checkGenericNameArity(expr)
                val args: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.TypeArg)
                for (*arg in args) {
                    this.resolveType(arg)
                }
                return
            }

            AstNodeCategory.ExprMember -> {
                val receiver: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Receiver)
                if (!xmlIsEmpty(receiver)) {
                    this.analyzeExpr(receiver)
                }
                return
            }

            AstNodeCategory.ExprCall -> {
                val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
                if (!xmlIsEmpty(callee)) {
                    this.analyzeExpr(callee)
                }
                val args: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.Arg)
                for (*arg in args) {
                    this.analyzeExpr(arg)
                }
                this.checkCallArity(expr)
                this.checkExtensionCallArity(expr)
                return
            }

            AstNodeCategory.ExprIndex -> {
                val receiver: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Receiver)
                if (!xmlIsEmpty(receiver)) {
                    this.analyzeExpr(receiver)
                }
                val index: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Index)
                if (!xmlIsEmpty(index)) {
                    this.analyzeExpr(index)
                }
                return
            }

            AstNodeCategory.ExprUnary, AstNodeCategory.ExprRef, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
                val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
                if (!xmlIsEmpty(operand)) {
                    this.analyzeExpr(operand)
                }
                return
            }

            AstNodeCategory.ExprBinary -> {
                val lhs: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Lhs)
                if (!xmlIsEmpty(lhs)) {
                    this.analyzeExpr(lhs)
                }
                val rhs: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Rhs)
                if (!xmlIsEmpty(rhs)) {
                    this.analyzeExpr(rhs)
                }
                return
            }

            AstNodeCategory.ExprLambda -> {
                this.pushScope()
                val names: List<Str> = xmlLambdaParams(expr)
                val paramTypes: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.ParamType)
                var i: Int = 0
                while (i < names.size()) {
                    var type: AstXmlNode = xmlEmptyNode()
                    if (paramTypes.size() == names.size()) {
                        type = paramTypes[i]
                    }
                    if (!xmlIsEmpty(type)) {
                        this.resolveType(type)
                    }
                    this.declareValue(names[i], true, false, type)
                    i = i + 1
                }
                if (paramTypes.size() != names.size()) {
                    for (*paramType in paramTypes) {
                        this.resolveType(paramType)
                    }
                }
                val savedLoopDepth: Int = this.loopDepth
                this.loopDepth = 0
                val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(expr, AstNodeKind.Body), AstNodeKind.Stmt)
                for (*stmtNode in body) {
                    this.analyzeStmt(stmtNode)
                }
                this.loopDepth = savedLoopDepth
                this.popScope()
                return
            }
        }
    }

    fun checkGenericNameArity(expr: *AstXmlNode): Unit {
        val argCount: Int = xmlCount(expr, AstNodeKind.TypeArg)
        val name: Str = xmlAttr(expr, AstNodeAttributeKind.Name)
        val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
        if (overloads != null) {
            for (*overload in overloads) {
                if (xmlCount(overload, AstNodeKind.TypeParam) == argCount) {
                    return
                }
            }
            this.diag(
                xmlLine(expr), xmlColumn(expr),
                fmtStr("no overload of '|' takes | type argument(s)", name, argCount.toString())
            )
            return
        }
        this.checkInstantiationArity(name, argCount, xmlLine(expr), xmlColumn(expr))
    }

    // A raw pointer cannot become a counted reference in place: `&x` shares a box, while
    // `*T` points into somebody's storage. Every other handle conversion is inferred
    // (`convertArgument`, `specs/functions.md`).
    fun checkHandleArgument(callee: *Str, function: *AstXmlNode, index: Int, arg: *AstXmlNode): Unit {
        val params: List<AstXmlNode> = xmlChildren(function, AstNodeKind.Param)
        if (index >= params.size()) {
            return
        }
        val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
        if (xmlIsEmpty(param)) {
            return
        }
        if (xmlKind(param) == AstNodeCategory.TypePointer) {
            return // a borrow takes anything
        }
        if (!semaIsHandleType(param)) {
            return // a by-value parameter reads through
        }
        val actual: AstXmlNode = this.exprType(arg)
        if (xmlIsEmpty(actual) || xmlKind(actual) != AstNodeCategory.TypePointer) {
            return
        }
        val pointee: AstXmlNode = semPointeeOf(param)
        var pointeeText: Str = "T"
        if (!xmlIsEmpty(pointee)) {
            pointeeText = semaTypeText(pointee)
        }
        this.diag(
            xmlLine(arg), xmlColumn(arg), fmtStr(
                "'|' takes a counted reference ('&|') and the argument is a raw pointer: a pointer cannot become a reference in place - make a reference variable one line before the call (var ref: &| = &value)",
                callee, pointeeText, pointeeText
            )
        )
    }

    // Whether a parameter type is a *view*: `Span<T>`, `StrView`, or a program
    // `typealias` reaching one (followed one hop at a time, as `Emitter.resolveAlias`).
    fun isViewType(typeNode: *AstXmlNode): Bool {
        if (semaIsSpanType(typeNode)) {
            return true
        }
        var name: Str = ""
        if (xmlKind(typeNode) == AstNodeCategory.TypeNamed) {
            name = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        }
        var guard: Int = 0
        while (name != "" && guard < 64) {
            guard = guard + 1
            if (name == "StrView") {
                return true
            }
            val decl: *AstXmlNode = this.types.getPtr(name)
            if (decl == null) {
                return false
            }
            if (xmlKind(decl) != AstNodeCategory.TypeAlias) {
                return false
            }
            val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
            if (xmlIsEmpty(target)) {
                return false
            }
            if (semaIsSpanType(target)) {
                return true
            }
            if (xmlKind(target) != AstNodeCategory.TypeNamed) {
                return false
            }
            name = xmlAttr(target, AstNodeAttributeKind.Name)
        }
        return false
    }

    // `exprType` looked through the wrappers whose value is their operand's (`copy(x)`,
    // `*x`, `&x`): peeling to the operand is what the view check wants.
    fun viewArgBase(arg: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(arg)
        if (kind == AstNodeCategory.ExprDeref || kind == AstNodeCategory.ExprRef
            || kind == AstNodeCategory.ExprCopy || kind == AstNodeCategory.ExprUnary
        ) {
            val operand: *AstXmlNode = xmlChildPtr(arg, AstNodeKind.Operand)
            if (xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            return this.viewArgBase(operand)
        }
        return this.exprType(arg)
    }

    // Whether some overload of `callee` with `argCount` parameters would take `arg` for
    // parameter `index`: a non-view parameter takes the argument, and a view parameter
    // takes it when the argument is (or may be) a view. `checkCallArity` sees only the
    // first arity match, but two overloads may differ in parameter shape.
    fun viewArgHasOverload(callee: *Str, argCount: Int, index: Int, arg: *AstXmlNode): Bool {
        val overloads: *List<AstXmlNode> = this.functions.getPtr(callee)
        if (overloads == null) {
            return false
        }
        for (*overload in overloads) {
            if (xmlCount(overload, AstNodeKind.Param) != argCount) {
                continue
            }
            val params: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
            if (index >= params.size()) {
                continue
            }
            val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
            if (xmlIsEmpty(param) || !this.isViewType(param)) {
                return true
            }
            if (xmlKind(arg) == AstNodeCategory.ExprStrLit) {
                return true
            }
            val actual: AstXmlNode = this.viewArgBase(arg)
            if (xmlIsEmpty(actual) || !semaIsOwnedViewSource(actual)) {
                return true
            }
        }
        return false
    }

    // A view parameter takes a view: `Span<T>` owns nothing, so the C++ has no conversion
    // from a `Str` or `List<T>` and the emitter prints the call as written. A string
    // literal is already a view (`__sm_stringTable[k]`).
    fun checkViewArgument(callee: *Str, function: *AstXmlNode, index: Int, arg: *AstXmlNode): Unit {
        val params: List<AstXmlNode> = xmlChildren(function, AstNodeKind.Param)
        if (index >= params.size()) {
            return
        }
        val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
        if (xmlIsEmpty(param) || !this.isViewType(param)) {
            return
        }
        if (xmlKind(arg) == AstNodeCategory.ExprStrLit) {
            return
        }
        val actual: AstXmlNode = this.viewArgBase(arg)
        if (xmlIsEmpty(actual) || !semaIsOwnedViewSource(actual)) {
            return
        }
        if (this.viewArgHasOverload(callee, params.size(), index, arg)) {
            return
        }
        this.diag(
            xmlLine(arg), xmlColumn(arg), fmtStr(
                "types are not compatible: '|' takes '|' and the argument is '|'",
                callee, semaTypeText(param), semaTypeText(actual)
            )
        )
    }

    fun checkCallArity(call: *AstXmlNode): Unit {
        val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
        if (xmlIsEmpty(callee)) {
            return
        }
        val kind: AstNodeCategory = xmlKind(callee)
        val generic: Bool = kind == AstNodeCategory.ExprGenericName
        if (kind != AstNodeCategory.ExprName && !generic) {
            return
        }
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        if (this.lookupValue(name).hasValue()) {
            return // shadowed by a local/param
        }
        val argCount: Int = xmlCount(call, AstNodeKind.Arg)

        // A construction is not a call: the argument count must match the field count.
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl != null) {
            if (xmlKind(decl) == AstNodeCategory.DataClass) {
                val fieldCount: Int = xmlCount(decl, AstNodeKind.Field)
                if (fieldCount != argCount) {
                    this.diag(
                        xmlLine(call), xmlColumn(call), fmtStr(
                            "data class '|' expects | field(s) but got |",
                            name, fieldCount.toString(), argCount.toString()
                        )
                    )
                }
                return
            }
        }

        val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
        if (overloads == null) {
            return
        }
        var typeArgCount: Int = 0
        if (generic) {
            typeArgCount = xmlCount(callee, AstNodeKind.TypeArg)
        }
        for (*overload in overloads) {
            if (generic && xmlCount(overload, AstNodeKind.TypeParam) != typeArgCount) {
                continue
            }
            val paramCount: Int = xmlCount(overload, AstNodeKind.Param)
            if (paramCount == argCount) {
                val args: List<AstXmlNode> = xmlChildren(call, AstNodeKind.Arg)
                var a: Int = 0
                while (a < argCount) {
                    this.checkHandleArgument(name, overload, a, args[a])
                    this.checkViewArgument(name, overload, a, args[a])
                    a = a + 1
                }
                return
            }
            // Trailing arguments may pack into a final list parameter, so counts both above
            // and below the parameter count are legal (`specs/functions.md`).
            if (paramCount > 0) {
                val params: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
                val lastType: *AstXmlNode = xmlChildPtr(params[paramCount-1], AstNodeKind.Type)
                if (semIsPackTarget(lastType) && argCount >= paramCount - 1) {
                    return
                }
            }
        }
        this.diag(
            xmlLine(call), xmlColumn(call),
            fmtStr("no overload of '|' takes | argument(s)", name, argCount.toString())
        )
    }

    // The receiver's type when the checker tracks it (a local/parameter or a generic
    // construction); empty when unknown.
    fun exprType(expr: *AstXmlNode): AstXmlNode {
        if (xmlKind(expr) == AstNodeCategory.ExprName) {
            val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(expr, AstNodeAttributeKind.Name))
            if (binding.hasValue()) {
                return binding.value().type
            }
            return xmlEmptyNode()
        }
        if (xmlKind(expr) == AstNodeCategory.ExprCall) {
            val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
            if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
                var built: AstXmlNode = AstXmlNode(
                    AstNodeKind.Type,
                    AstNodeCategory.TypeGeneric,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
                built.attributes.append(
                    AstNodeAttribute(
                        AstNodeAttributeKind.Name,
                        xmlAttr(callee, AstNodeAttributeKind.Name)
                    )
                )
                val args: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
                xmlAddChildren(built, args)
                return built
            }
        }
        return xmlEmptyNode()
    }

    // `for` is lowered into the declaration of the machine it iterates (`_sm_for<n>`,
    // impl_specs/for.md), whose initializer is an invisible `iter()`/`iterPtr()` wrap:
    // the template names are the marker, and something is iterable when that wrap
    // resolves. Reporting here names the user's line rather than the generated statement.
    fun checkForIterable(stmt: *AstXmlNode): Unit {
        val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
        if (xmlIsEmpty(init) || !semaIsForTemplateName(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
            return
        }
        if (xmlKind(init) != AstNodeCategory.ExprCall) {
            return
        }
        val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
        if (xmlKind(callee) != AstNodeCategory.ExprMember) {
            return
        }
        val wrap: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
        val receiverType: AstXmlNode = this.iteratedType(receiver)
        if (xmlIsEmpty(receiverType)) {
            return
        }
        if (xmlKind(receiverType) == AstNodeCategory.TypeYield) {
            // A machine is the identity for `iter`: it hands out values, not places, so
            // it has no pointer form.
            if (wrap == "iter") {
                return
            }
            this.diag(
                xmlLine(stmt), xmlColumn(stmt),
                "a `for (*x in m)` needs an `iterPtr`, and a machine yields values "
                        + "rather than places: iterate it with `for (x in m)`"
            )
            return
        }
        if (this.hasWrap(wrap, receiverType)) {
            return
        }
        this.diag(
            xmlLine(stmt), xmlColumn(stmt),
            fmtStr(
                "a `for` iterates a machine (`..T`) or a type with a `|`, and | has neither; iterate a container with `while` and an index",
                wrap, semaTypeText(receiverType)
            )
        )
    }

    // Whether a wrap (`iter`, `iterPtr`) takes this receiver, by receiver *name* only:
    // `List<T>` takes any `List<...>`, a type parameter takes anything. An undecidable
    // name stays silent; the emitted call is resolved with full unification in `codegen`.
    fun hasWrap(wrap: *Str, receiverType: *AstXmlNode): Bool {
        val overloads: *List<AstXmlNode> = this.functions.getPtr(wrap)
        if (overloads == null) {
            return false
        }
        var i: Int = 0
        while (i < overloads.size()) {
            val candidate: *AstXmlNode = *overloads[i]
            i = i + 1
            val receiverPattern: *AstXmlNode = xmlChildPtr(candidate, AstNodeKind.Receiver)
            if (xmlIsEmpty(receiverPattern)) {
                continue
            }
            if (this.semaReceiverNameMatches(receiverPattern, receiverType, xmlTypeParamNames(candidate))) {
                return true
            }
        }
        return false
    }

    // The receiver's outer type, ignoring handles and type arguments.
    fun semaReceiverNameMatches(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
        var actualPtr: *AstXmlNode = actual
        while (true) {
            val kind: AstNodeCategory = xmlKind(actualPtr)
            if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
                break
            }
            val inner: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                break
            }
            actualPtr = inner
        }
        val patternKind: AstNodeCategory = xmlKind(pattern)
        if (patternKind == AstNodeCategory.TypeNamed) {
            return xmlKind(actualPtr) == AstNodeCategory.TypeNamed
                    && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(pattern, AstNodeAttributeKind.Name)
        }
        if (patternKind == AstNodeCategory.TypeGeneric) {
            val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
            if (xmlIsTypeParam(patternName, typeParams)) {
                return true
            }
            return xmlKind(actualPtr) == AstNodeCategory.TypeGeneric
                    && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == patternName
        }
        return false
    }

    // The type a `for` iterates, for the shapes the checker can name: a tracked binding,
    // a type construction, or a declared function's return type. Unknown stays silent.
    fun iteratedType(expr: *AstXmlNode): AstXmlNode {
        if (xmlKind(expr) == AstNodeCategory.ExprName) {
            return this.exprType(expr)
        }
        if (xmlKind(expr) != AstNodeCategory.ExprCall) {
            return xmlEmptyNode()
        }
        val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
        var name: Str = ""
        if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
            name = xmlAttr(callee, AstNodeAttributeKind.Name)
            // `List<Int>()` builds a value of the name it calls; `f<Int>(x)` calls f.
            if (this.types.has(name)) {
                return this.exprType(expr)
            }
        } else if (xmlKind(callee) == AstNodeCategory.ExprName) {
            name = xmlAttr(callee, AstNodeAttributeKind.Name)
        } else {
            return xmlEmptyNode()
        }
        val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
        if (overloads == null) {
            return xmlEmptyNode()
        }
        var known: AstXmlNode = xmlEmptyNode()
        for (*overload in overloads) {
            val declared: *AstXmlNode = xmlChildPtr(overload, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(declared)) {
                if (xmlKind(declared) == AstNodeCategory.TypeYield) {
                    return declared
                }
                if (xmlIsEmpty(known)) {
                    known = declared
                }
            }
        }
        return known
    }

    // Reports an arity mismatch only when a receiver-compatible extension exists but no
    // overload takes the argument count; unknown receiver types stay silent.
    fun checkExtensionCallArity(call: *AstXmlNode): Unit {
        val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
        if (xmlKind(callee) != AstNodeCategory.ExprMember) {
            return
        }
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
        if (overloads == null) {
            return
        }
        val actual: AstXmlNode = this.exprType(xmlChildPtr(callee, AstNodeKind.Receiver))
        if (xmlIsEmpty(actual)) {
            return
        }
        val argCount: Int = xmlCount(call, AstNodeKind.Arg)
        var compatible: Bool = false
        for (*fn in overloads) {
            var receiver: AstXmlNode = xmlEmptyNode()
            var valueParamCount: Int = 0
            var hasRecv: Bool = false
            val params: List<AstXmlNode> = xmlChildren(fn, AstNodeKind.Param)
            if (xmlAttr(fn, AstNodeAttributeKind.HasReceiver) == "true" && !xmlIsEmpty(
                    xmlChildPtr(
                        fn,
                        AstNodeKind.Receiver
                    )
                )
            ) {
                receiver = xmlChild(fn, AstNodeKind.Receiver)
                valueParamCount = params.size()
                hasRecv = true
            } else if (params.size() > 0) {
                val first: AstXmlNode = params[0]
                if (xmlAttr(first, AstNodeAttributeKind.Name) == "this" && !xmlIsEmpty(
                        xmlChildPtr(
                            first,
                            AstNodeKind.Type
                        )
                    )
                ) {
                    receiver = xmlChild(first, AstNodeKind.Type)
                    valueParamCount = params.size() - 1
                    hasRecv = true
                }
            }
            if (hasRecv) {
                val typeParams: List<Str> = xmlTypeParamNames(fn)
                if (semaUnifyReceiver(receiver, actual, typeParams)) {
                    compatible = true
                    if (valueParamCount == argCount) {
                        return
                    }
                }
            }
        }
        if (compatible) {
            this.diag(
                xmlLine(call), xmlColumn(call),
                fmtStr("no overload of '|' takes | argument(s)", name, argCount.toString())
            )
        }
    }
}

fun newAnalyzer(inputs: *List<SemaInput>): Analyzer {
    return Analyzer(
        inputs,
        "",
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        List<Str>(),
        List<Dictionary<Str, ValueBinding>>(),
        List<List<Str>>(),
        0,
        List<Str>()
    )
}

// Analyzes the whole compilation and returns "<fileName>:<line>:<col>: <message>"
// diagnostics; an empty list means clean.
fun analyze(inputs: *List<SemaInput>): List<Str> {
    var analyzer: Analyzer = newAnalyzer(inputs)
    analyzer.run()
    return analyzer.diags
}
