// Sema.kt
//
// The name/type-resolution pass, ported from cppsrc/sema/Sema.cpp. It consumes the
// AstXmlNode AST (schema in impl_specs/ast-xmlnode.md) and returns the same
// diagnostics, in the same order, as the C++ `sema::analyze`.
//
// Positional information comes from the `line`/`column` attributes; the port
// mirrors the C++ traversal exactly (hoisting, scopes, conservative checks).
//
// Iteration is a `while` with List indexing where the index is needed, and the
// pointer form of `for` where it is not. Kind dispatch is a `when`, which the parser
// lowers to the `if`/`else` chain the emitted C++ uses (`switch` cannot switch on a
// `Str`).

package sema

import parser
import common

// The read-only AstXmlNode accessors (xmlAttr, xmlChild, xmlChildren, ...) live in
// cppsrc/common/xmlutil.kt and arrive through `import common`. The AstXmlNode and
// AstNodeAttribute types come from the implicit RTL prelude. Only the sema-specific
// logic stays here.

// A value binding in scope: mutability, whether assignment is checked, and the
// declared/inferred type (an empty AstXmlNode when unknown).
data class ValueBinding(
    var isMutable: Bool,

    var checkAssign: Bool,
    var type: AstXmlNode
)

// ---- built-in type knowledge ----------------------------------------------

fun semaIsBuiltinType(name: Str): Bool {
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

// The number of type parameters a built-in generic expects, or -1 when the name
// is not a built-in generic.
fun semaBuiltinGenericArity(name: Str): Int {
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

// Whether a type is reached through a handle (`&T`, `*T`, or the `PList<T>` alias of
// `&List<T>`): the C++ ring's `sema::isHandleType` (cppsrc/sema/TypeInfer.cpp), which
// this ring has no other home for. The extractor spells the same rule as
// `ilIsHandleType` and the emitter as `Emitter.isHandleType`; the checker asks it too.
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

// ---- receiver unification -------------------------------------------------

// Simple structural unification of an extension receiver pattern (which may
// mention the extension's type parameters) against the actual receiver type.
// References/pointers on the actual side are auto-dereferenced, matching the
// member-call decision.
fun semaUnifyReceiver(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: AstXmlNode = copy(actual)
    val pk: AstNodeCategory = xmlKind(pattern)
    if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
        while ((xmlKind(actualPtr) == AstNodeCategory.TypeReference || xmlKind(actualPtr) == AstNodeCategory.TypePointer)
            && !xmlIsEmpty(xmlChild(actualPtr, AstNodeKind.Inner))
        ) {
            actualPtr = xmlChild(actualPtr, AstNodeKind.Inner)
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
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChild(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChild(pattern, AstNodeKind.Inner))
            ) {
                return semaUnifyReceiver(
                    xmlChild(pattern, AstNodeKind.Inner),
                    xmlChild(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypePointer -> {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChild(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChild(pattern, AstNodeKind.Inner))
            ) {
                return semaUnifyReceiver(
                    xmlChild(pattern, AstNodeKind.Inner),
                    xmlChild(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
    }
    return false
}

// Whether a name is one the `for` desugaring made. The generated names are per-file
// counters (`_sm_for1`, `_sm_step1`, `_sm_index1`), like the lowering's own slots:
// recognizable, and documented as not a user's to take.
fun semaIsForTemplateName(name: Str): Bool {
    return name.startsWith("_sm_for")
}

// The schema's spelling of a type node ("List<Int>", "*Str", "(Int) -> Bool"), for
// diagnostics that have to name one. The C++ ring's dump spells types through
// `ast::typeToString`; this is the same function over `AstXmlNode`, and the two are
// kept in step by hand - a divergence surfaces in the differentials.
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
            return xmlAttr(node, AstNodeAttributeKind.Name) + "<" + semaTypeTextList(
                xmlChildren(
                    node,
                    AstNodeKind.TypeArg
                )
            ) + ">"
        }

        AstNodeCategory.TypeReference -> {
            return "&" + semaTypeText(xmlChild(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            return "*" + semaTypeText(xmlChild(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            return "(" + semaTypeTextList(xmlChildren(node, AstNodeKind.ParamType)) + ") -> "
            +semaTypeText(xmlChild(node, AstNodeKind.ReturnType))
        }
    }
    return "?"
}

fun semaTypeTextList(types: *List<AstXmlNode>): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < types.size()) {
        if (i > 0) {
            out = out + ", "
        }
        out = out + semaTypeText(types[i])
        i = i + 1
    }
    return out
}

// ---- the analyzer ---------------------------------------------------------

// One participating file: its name (for diagnostics) and its parsed Module node.
// The Module carries the declared package, which namespaces its declarations.
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

    // ---- entry ------------------------------------------------------------

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

    fun diag(line: Int, column: Int, message: Str): Unit {
        this.diags.append(
            this.file + ":" + line.toString() + ":" + column.toString()
                    + ": " + message
        )
    }

    // ---- symbol collection ------------------------------------------------

    // "<a>.<b>" for the module's package; the empty string is the root package.
    fun packageOf(module: *AstXmlNode): Str {
        return xmlAttr(module, AstNodeAttributeKind.Package)
    }

    // Appends `decl` under `key` in `map`, which is keyed by "pkg|name" (global)
    // or by bare name (visible). Operators are direct field mutations because
    // List/Dictionary are value types.
    fun appendGlobalFunction(key: Str, decl: *AstXmlNode): Unit {
        if (this.globalFunctions.has(key)) {
            var existing: List<AstXmlNode> = this.globalFunctions.get(key).value()
            existing.append(copy(decl))
            this.globalFunctions.insert(key, existing)
        } else {
            var fresh: List<AstXmlNode> = List<AstXmlNode>()
            fresh.append(copy(decl))
            this.globalFunctions.insert(key, fresh)
        }
    }

    fun appendPackageDecl(pkg: Str, decl: *AstXmlNode): Unit {
        if (this.packageDecls.has(pkg)) {
            var existing: List<AstXmlNode> = this.packageDecls.get(pkg).value()
            existing.append(copy(decl))
            this.packageDecls.insert(pkg, existing)
        } else {
            var fresh: List<AstXmlNode> = List<AstXmlNode>()
            fresh.append(copy(decl))
            this.packageDecls.insert(pkg, fresh)
        }
    }

    fun appendVisibleFunction(name: Str, decl: *AstXmlNode): Unit {
        if (this.functions.has(name)) {
            var existing: List<AstXmlNode> = this.functions.get(name).value()
            existing.append(copy(decl))
            this.functions.insert(name, existing)
        } else {
            var fresh: List<AstXmlNode> = List<AstXmlNode>()
            fresh.append(copy(decl))
            this.functions.insert(name, fresh)
        }
    }

    // Collects every declaration into its package scope, reporting a duplicate
    // top-level name within one package, including across two files that declare
    // the same package. File-level statics (`Var`, specs/statics.md) share the
    // namespace with the other declarations but are collected separately: they
    // are values, not types.
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
                        this.diag(xmlLine(decl), xmlColumn(decl), "duplicate declaration '" + name + "'")
                    } else {
                        this.appendGlobalFunction(key, decl)
                    }
                } else if (isStatic) {
                    if (nameTaken) {
                        this.diag(xmlLine(decl), xmlColumn(decl), "duplicate declaration '" + name + "'")
                    } else {
                        this.globalStatics.insert(key, decl)
                    }
                } else {
                    if (nameTaken) {
                        this.diag(xmlLine(decl), xmlColumn(decl), "duplicate declaration '" + name + "'")
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

    // Reports an import whose package no participating file declares.
    fun validateImports(module: *AstXmlNode): Unit {
        val imports: List<AstXmlNode> = xmlChildren(module, AstNodeKind.Import)
        for (*importDecl in imports) {
            val dotted: Str = xmlAttr(importDecl, AstNodeAttributeKind.Path)
            if (!this.declaredPackages.contains(dotted)) {
                this.diag(
                    xmlLine(importDecl), xmlColumn(importDecl),
                    "cannot resolve import '" + dotted + "': no file declares package '" + dotted + "'"
                )
            }
        }
    }

    // Builds the unqualified scope of one file: its own package, then each
    // imported package, then the implicit `rtl` prelude. It also pushes the
    // module scope the file's declarations are analyzed in, where the visible
    // file-level statics live as values (specs/statics.md); `run` pops it.
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
                if (this.packageDecls.has(pkg)) {
                    val decls: List<AstXmlNode> = this.packageDecls.get(pkg).value()
                    var d: Int = 0
                    while (d < decls.size()) {
                        val decl: AstXmlNode = decls[d]
                        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
                        if (xmlKind(decl) == AstNodeCategory.Function) {
                            this.appendVisibleFunction(name, decl)
                        } else if (xmlKind(decl) == AstNodeCategory.Var) {
                            this.declareValue(
                                name, xmlAttr(decl, AstNodeAttributeKind.IsVar) == "true", true,
                                xmlChild(decl, AstNodeKind.Type)
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

    // ---- scopes -----------------------------------------------------------

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

    fun declareType(name: Str): Unit {
        if (this.typeScopes.size() == 0) {
            return
        }
        this.typeScopes[this.typeScopes.size() - 1].append(name)
    }

    fun declareValue(name: Str, isMutable: Bool, checkAssign: Bool, type: *AstXmlNode): Unit {
        if (this.scopes.size() == 0) {
            return
        }
        this.scopes[this.scopes.size() - 1].insert(name, ValueBinding(isMutable, checkAssign, copy(type)))
    }

    fun lookupValue(name: Str): Opt<ValueBinding> {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            if (this.scopes[i].has(name)) {
                return this.scopes[i].get(name)
            }
            i = i - 1
        }
        return Opt<ValueBinding>.none()
    }

    fun typeParamVisible(name: Str): Bool {
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

    // ---- type resolution --------------------------------------------------

    fun checkTypeName(name: Str, line: Int, column: Int): Unit {
        if (semaIsBuiltinType(name) || this.types.has(name) || this.typeParamVisible(name)) {
            return
        }
        this.diag(line, column, "unknown type '" + name + "'")
    }

    fun checkInstantiationArity(name: Str, argCount: Int, line: Int, column: Int): Unit {
        var expected: Int = -1
        if (this.types.has(name)) {
            expected = xmlCount(this.types.get(name).value(), AstNodeKind.TypeParam)
        } else {
            expected = semaBuiltinGenericArity(name)
        }
        if (expected >= 0 && argCount != expected) {
            this.diag(
                line, column, "'" + name + "' expects " + expected.toString()
                        + " type argument(s) but got " + argCount.toString()
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
                val inner: AstXmlNode = xmlChild(type, AstNodeKind.Inner)
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
                val ret: AstXmlNode = xmlChild(type, AstNodeKind.ReturnType)
                if (!xmlIsEmpty(ret)) {
                    this.resolveType(ret)
                }
                return
            }
        }
    }

    // ---- declaration analysis ---------------------------------------------

    fun analyzeDecl(decl: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(decl)
        when (kind) {
            AstNodeCategory.Var -> {
                // Static storage: the type in the file's module scope, and the
                // initializer as an ordinary expression. The initializer may name any
                // hoisted declaration, including another static (specs/statics.md:
                // the name is visible everywhere, the initialization order is not
                // specified).
                val staticType: AstXmlNode = xmlChild(decl, AstNodeKind.Type)
                if (!xmlIsEmpty(staticType)) {
                    this.resolveType(staticType)
                }
                val init: AstXmlNode = xmlChild(decl, AstNodeKind.Init)
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
                    val fieldType: AstXmlNode = xmlChild(field, AstNodeKind.Type)
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
                val target: AstXmlNode = xmlChild(decl, AstNodeKind.TargetType)
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
            val receiver: AstXmlNode = xmlChild(decl, AstNodeKind.Receiver)
            if (!xmlIsEmpty(receiver)) {
                this.resolveType(receiver)
            }
        }
        val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        for (*param in params) {
            val paramType: AstXmlNode = xmlChild(param, AstNodeKind.Type)
            if (!xmlIsEmpty(paramType)) {
                this.resolveType(paramType)
            }
            // Parameters are not `val` declarations, so reassigning one is never
            // reported (under-report rather than risk a false hit).
            this.declareValue(xmlAttr(param, AstNodeAttributeKind.Name), true, false, paramType)
        }
        val returnType: AstXmlNode = xmlChild(decl, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(returnType)) {
            this.resolveType(returnType)
        }

        val savedLoopDepth: Int = this.loopDepth
        this.loopDepth = 0
        val body: List<AstXmlNode> = xmlChildren(xmlChild(decl, AstNodeKind.Body), AstNodeKind.Stmt)
        for (*stmtNode in body) {
            this.analyzeStmt(stmtNode)
        }
        this.loopDepth = savedLoopDepth

        this.popScope()
        this.popTypeScope()
    }

    // ---- statement analysis -----------------------------------------------

    fun analyzeStmt(stmt: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtVarDecl -> {
                val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
                if (!xmlIsEmpty(init)) {
                    this.analyzeExpr(init)
                }
                val declaredType: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
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
                val target: AstXmlNode = xmlChild(stmt, AstNodeKind.Target)
                if (!xmlIsEmpty(target)) {
                    this.analyzeExpr(target)
                }
                val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
                if (!xmlIsEmpty(value)) {
                    this.analyzeExpr(value)
                }
                if (!xmlIsEmpty(target) && xmlKind(target) == AstNodeCategory.ExprName) {
                    val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(target, AstNodeAttributeKind.Name))
                    if (binding.hasValue() && binding.value().checkAssign && !binding.value().isMutable) {
                        this.diag(
                            xmlLine(target), xmlColumn(target),
                            "cannot assign to val '" + xmlAttr(target, AstNodeAttributeKind.Name) + "'"
                        )
                    }
                }
                return
            }

            AstNodeCategory.StmtIf -> {
                val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
                if (!xmlIsEmpty(cond)) {
                    this.analyzeExpr(cond)
                }
                this.pushScope()
                val thenBody: List<AstXmlNode> = xmlChildren(xmlChild(stmt, AstNodeKind.Then), AstNodeKind.Stmt)
                for (*thenStmt in thenBody) {
                    this.analyzeStmt(thenStmt)
                }
                this.popScope()
                val elseBlock: AstXmlNode = xmlChild(stmt, AstNodeKind.Else)
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
                val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
                if (!xmlIsEmpty(cond)) {
                    this.analyzeExpr(cond)
                }
                this.pushScope()
                this.loopDepth = this.loopDepth + 1
                val body: List<AstXmlNode> = xmlChildren(xmlChild(stmt, AstNodeKind.Body), AstNodeKind.Stmt)
                for (*bodyStmt in body) {
                    this.analyzeStmt(bodyStmt)
                }
                this.loopDepth = this.loopDepth - 1
                this.popScope()
                return
            }

            AstNodeCategory.StmtReturn -> {
                val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
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
                val expr: AstXmlNode = xmlChild(stmt, AstNodeKind.Expr)
                if (!xmlIsEmpty(expr)) {
                    this.analyzeExpr(expr)
                }
                return
            }
        }
    }

    // ---- expression analysis ----------------------------------------------

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
                val receiver: AstXmlNode = xmlChild(expr, AstNodeKind.Receiver)
                if (!xmlIsEmpty(receiver)) {
                    this.analyzeExpr(receiver)
                }
                return
            }

            AstNodeCategory.ExprCall -> {
                val callee: AstXmlNode = xmlChild(expr, AstNodeKind.Callee)
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
                val receiver: AstXmlNode = xmlChild(expr, AstNodeKind.Receiver)
                if (!xmlIsEmpty(receiver)) {
                    this.analyzeExpr(receiver)
                }
                val index: AstXmlNode = xmlChild(expr, AstNodeKind.Index)
                if (!xmlIsEmpty(index)) {
                    this.analyzeExpr(index)
                }
                return
            }

            AstNodeCategory.ExprUnary, AstNodeCategory.ExprRef, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
                val operand: AstXmlNode = xmlChild(expr, AstNodeKind.Operand)
                if (!xmlIsEmpty(operand)) {
                    this.analyzeExpr(operand)
                }
                return
            }

            AstNodeCategory.ExprBinary -> {
                val lhs: AstXmlNode = xmlChild(expr, AstNodeKind.Lhs)
                if (!xmlIsEmpty(lhs)) {
                    this.analyzeExpr(lhs)
                }
                val rhs: AstXmlNode = xmlChild(expr, AstNodeKind.Rhs)
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
                val body: List<AstXmlNode> = xmlChildren(xmlChild(expr, AstNodeKind.Body), AstNodeKind.Stmt)
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
        if (this.functions.has(name)) {
            val overloads: List<AstXmlNode> = this.functions.get(name).value()
            for (*overload in overloads) {
                if (xmlCount(overload, AstNodeKind.TypeParam) == argCount) {
                    return
                }
            }
            this.diag(
                xmlLine(expr), xmlColumn(expr), "no overload of '" + name + "' takes "
                        + argCount.toString() + " type argument(s)"
            )
            return
        }
        this.checkInstantiationArity(name, argCount, xmlLine(expr), xmlColumn(expr))
    }

    // What the checker has to say about one argument of an accepted call. One rule
    // so far: a **raw pointer cannot become a counted reference in place**. The
    // language shares a *box* (`&x` is the counted reference to a copy of `x`),
    // and a `*T` argument is a pointer to somebody's storage - the compiler would
    // have to guess whether the call wants a copy of that storage or a share of a
    // box that does not exist. So the writer says it, one line before the call:
    //
    //     var boxed: &Int = &v      // a reference to a copy of v
    //     printRef(boxed)
    //
    // A report rather than a silent copy, because the two spellings mean
    // different things. Every *other* handle conversion is inferred
    // (`convertArgument` in the extractor, `specs/functions.md`).
    fun checkHandleArgument(callee: Str, function: *AstXmlNode, index: Int, arg: *AstXmlNode): Unit {
        val params: List<AstXmlNode> = xmlChildren(function, AstNodeKind.Param)
        if (index >= params.size()) {
            return
        }
        val param: AstXmlNode = xmlChild(params[index], AstNodeKind.Type)
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
            xmlLine(arg), xmlColumn(arg), "'" + callee + "' takes a counted reference ('&"
                    + pointeeText + "') and the argument is a raw pointer: a pointer cannot"
                    + " become a reference in place - make a reference variable one line"
                    + " before the call (var ref: &" + pointeeText + " = &value)"
        )
    }

    fun checkCallArity(call: *AstXmlNode): Unit {
        val callee: AstXmlNode = xmlChild(call, AstNodeKind.Callee)
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

        // A call to a known data class is a constructor call: the argument count
        // must match the declared field count exactly.
        if (this.types.has(name)) {
            val decl: AstXmlNode = this.types.get(name).value()
            if (xmlKind(decl) == AstNodeCategory.DataClass) {
                val fieldCount: Int = xmlCount(decl, AstNodeKind.Field)
                if (fieldCount != argCount) {
                    this.diag(
                        xmlLine(call), xmlColumn(call), "data class '" + name
                                + "' expects " + fieldCount.toString() + " field(s) but got "
                                + argCount.toString()
                    )
                }
                return
            }
        }

        if (!this.functions.has(name)) {
            return
        }
        var typeArgCount: Int = 0
        if (generic) {
            typeArgCount = xmlCount(callee, AstNodeKind.TypeArg)
        }
        val overloads: List<AstXmlNode> = this.functions.get(name).value()
        var i: Int = 0
        while (i < overloads.size()) {
            val overload: AstXmlNode = overloads[i]
            if (generic && xmlCount(overload, AstNodeKind.TypeParam) != typeArgCount) {
                i = i + 1
                continue
            }
            val paramCount: Int = xmlCount(overload, AstNodeKind.Param)
            if (paramCount == argCount) {
                val args: List<AstXmlNode> = xmlChildren(call, AstNodeKind.Arg)
                var a: Int = 0
                while (a < argCount) {
                    this.checkHandleArgument(name, overload, a, args[a])
                    a = a + 1
                }
                return
            }
            // The trailing arguments may *pack* into a last parameter that is a list
            // (`fun addAll(values: *List<Int>)` called as `addAll(1, 2, 3)`), so a call
            // with more arguments than parameters is legal when the last parameter takes
            // a pack - and one with fewer, which is the same call with no elements
            // (`specs/functions.md`). A construction is not a call here: it takes one
            // argument per field, always.
            if (paramCount > 0) {
                val params: List<AstXmlNode> = xmlChildren(overload, AstNodeKind.Param)
                val lastType: AstXmlNode = xmlChild(params[paramCount - 1], AstNodeKind.Type)
                if (semIsPackTarget(lastType) && argCount >= paramCount - 1) {
                    return
                }
            }
            i = i + 1
        }
        this.diag(
            xmlLine(call), xmlColumn(call), "no overload of '" + name + "' takes "
                    + argCount.toString() + " argument(s)"
        )
    }

    // The receiver's type, when the checker tracks it (a local/parameter name or
    // a generic construction). Empty when unknown.
    fun exprType(expr: *AstXmlNode): AstXmlNode {
        if (xmlKind(expr) == AstNodeCategory.ExprName) {
            val binding: Opt<ValueBinding> = this.lookupValue(xmlAttr(expr, AstNodeAttributeKind.Name))
            if (binding.hasValue()) {
                return binding.value().type
            }
            return xmlEmptyNode()
        }
        if (xmlKind(expr) == AstNodeCategory.ExprCall) {
            val callee: AstXmlNode = xmlChild(expr, AstNodeKind.Callee)
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

    // `for` is lowered in the parser into the declaration of the machine it iterates
    // (`_sm_for<n>`, impl_specs/for.md), whose initializer is the invisible `smToYield()`
    // / `smToYieldPtr()` wrap, so the checker sees the template rather than the construct,
    // and the template's names are the one marker that says "this came from a `for`".
    // Something is iterable when that wrap resolves: a machine is (the identity), and
    // anything else needs the wrap in scope - the prelude has one per container, in both
    // flavours. Say so here, where the `for` still has a position: the C++ the template
    // would otherwise emit does not compile, and its error would name a generated
    // statement instead of the line the user wrote.
    fun checkForIterable(stmt: *AstXmlNode): Unit {
        val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
        if (xmlIsEmpty(init) || !semaIsForTemplateName(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
            return
        }
        if (xmlKind(init) != AstNodeCategory.ExprCall) {
            return
        }
        val callee: AstXmlNode = xmlChild(init, AstNodeKind.Callee)
        if (xmlKind(callee) != AstNodeCategory.ExprMember) {
            return
        }
        val wrap: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        val receiver: AstXmlNode = xmlChild(callee, AstNodeKind.Receiver)
        val receiverType: AstXmlNode = this.iteratedType(receiver)
        if (xmlIsEmpty(receiverType)) {
            return
        }
        if (xmlKind(receiverType) == AstNodeCategory.TypeYield) {
            // A machine *is* the identity for `smToYield` - it hands out values, not
            // places, so it has no pointer form.
            if (wrap == "smToYield") {
                return
            }
            this.diag(
                xmlLine(stmt), xmlColumn(stmt),
                "a `for (*x in m)` needs a `smToYieldPtr`, and a machine yields values "
                        + "rather than places: iterate it with `for (x in m)`"
            )
            return
        }
        if (this.hasWrap(wrap, receiverType)) {
            return
        }
        this.diag(
            xmlLine(stmt), xmlColumn(stmt),
            "a `for` iterates a machine (`..T`) or a type with a `" + wrap + "`, and "
                    + semaTypeText(receiverType)
                    + " has neither; iterate a container with `while` and an index"
        )
    }

    // Whether a wrap (`smToYield`, `smToYieldPtr`) takes this receiver: the convention the
    // parser's wrap calls through (`specs/functions.md`, impl_specs/for.md). The receiver's
    // *name* is what is compared - `List<T>` takes any `List<...>`, and a pattern type
    // parameter takes anything - which is all the gate needs; the emitted call is resolved
    // with the full unification (in `codegen`), and a name this cannot decide stays silent.
    fun hasWrap(wrap: Str, receiverType: *AstXmlNode): Bool {
        if (!this.functions.has(wrap)) {
            return false
        }
        val overloads: List<AstXmlNode> = this.functions.get(wrap).value()
        var i: Int = 0
        while (i < overloads.size()) {
            val candidate: *AstXmlNode = *overloads[i]
            i = i + 1
            val receiverPattern: AstXmlNode = xmlChild(candidate, AstNodeKind.Receiver)
            if (xmlIsEmpty(receiverPattern)) {
                continue
            }
            if (this.semaReceiverNameMatches(receiverPattern, receiverType, xmlTypeParamNames(candidate))) {
                return true
            }
        }
        return false
    }

    // The receiver's outer type, ignoring handles and type arguments: `*List<Int>` and
    // `List<Str>` are the same receiver for this purpose.
    fun semaReceiverNameMatches(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
        var actualPtr: AstXmlNode = copy(actual)
        while (true) {
            val kind: AstNodeCategory = xmlKind(actualPtr)
            if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
                break
            }
            val inner: AstXmlNode = xmlChild(actualPtr, AstNodeKind.Inner)
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

    // The type of the expression a `for` iterates, for the shapes the checker can name
    // without walking anything: a binding it tracks, a type construction, or a call of
    // a declared function. Anything else stays unknown, and unknown stays silent - the
    // C++ compiler gets the last word there, as it does for any other member it
    // resolves.
    fun iteratedType(expr: *AstXmlNode): AstXmlNode {
        if (xmlKind(expr) == AstNodeCategory.ExprName) {
            return this.exprType(expr)
        }
        if (xmlKind(expr) != AstNodeCategory.ExprCall) {
            return xmlEmptyNode()
        }
        val callee: AstXmlNode = xmlChild(expr, AstNodeKind.Callee)
        var name: Str = ""
        if (xmlKind(callee) == AstNodeCategory.ExprGenericName) {
            name = xmlAttr(callee, AstNodeAttributeKind.Name)
            // `List<Int>()` builds a value of the name it calls, so it is the type;
            // `f<Int>(x)` calls the function, and its signature answers.
            if (this.types.has(name)) {
                return this.exprType(expr)
            }
        } else if (xmlKind(callee) == AstNodeCategory.ExprName) {
            name = xmlAttr(callee, AstNodeAttributeKind.Name)
        } else {
            return xmlEmptyNode()
        }
        if (!this.functions.has(name)) {
            return xmlEmptyNode()
        }
        val overloads: List<AstXmlNode> = this.functions.get(name).value()
        var known: AstXmlNode = xmlEmptyNode()
        for (*overload in overloads) {
            val declared: AstXmlNode = xmlChild(overload, AstNodeKind.ReturnType)
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

    // Reports an arity mismatch only when a receiver-compatible extension method
    // exists but no overload takes the given value-argument count. Unknown
    // receiver types stay silent (conservative).
    fun checkExtensionCallArity(call: *AstXmlNode): Unit {
        val callee: AstXmlNode = xmlChild(call, AstNodeKind.Callee)
        if (xmlKind(callee) != AstNodeCategory.ExprMember) {
            return
        }
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        if (!this.functions.has(name)) {
            return
        }
        val actual: AstXmlNode = this.exprType(xmlChild(callee, AstNodeKind.Receiver))
        if (xmlIsEmpty(actual)) {
            return
        }
        val argCount: Int = xmlCount(call, AstNodeKind.Arg)
        var compatible: Bool = false
        val overloads: List<AstXmlNode> = this.functions.get(name).value()
        var i: Int = 0
        while (i < overloads.size()) {
            val fn: AstXmlNode = overloads[i]
            var receiver: AstXmlNode = xmlEmptyNode()
            var valueParamCount: Int = 0
            var hasRecv: Bool = false
            val params: List<AstXmlNode> = xmlChildren(fn, AstNodeKind.Param)
            if (xmlAttr(fn, AstNodeAttributeKind.HasReceiver) == "true" && !xmlIsEmpty(
                    xmlChild(
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
                        xmlChild(
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
            i = i + 1
        }
        if (compatible) {
            this.diag(
                xmlLine(call), xmlColumn(call), "no overload of '" + name + "' takes "
                        + argCount.toString() + " argument(s)"
            )
        }
    }
}

// ---- entry point ----------------------------------------------------------

fun newAnalyzer(inputs: List<SemaInput>): Analyzer {
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

// Analyzes the whole compilation (every participating file plus the implicit
// prelude). Returns diagnostics of the form "<fileName>:<line>:<col>: <message>".
// An empty list means the compilation is clean.
fun analyze(inputs: List<SemaInput>): List<Str> {
    var analyzer: Analyzer = newAnalyzer(inputs)
    analyzer.run()
    return analyzer.diags
}
