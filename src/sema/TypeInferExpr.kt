// TypeInferExpr.kt
//
// `SemInfer`'s inference: scopes and lookups, the statement walk, and the expression and
// call return types. Extension methods on `SemInfer` (TypeInfer.kt has the class).

package sema
import compiler

import common

fun SemInfer.pushScope(): Unit {
    this.scopes.append(Dictionary<Str, AstXmlNode>())
}

fun SemInfer.popScope(): Unit {
    this.scopes.removeAt(this.scopes.size() - 1)
}

// One expression, typed against the caller's (flat) frame `names`; the scope is pushed
// and popped so the call leaves nothing behind, and `names` is borrowed, not copied.
fun SemInfer.typeOf(e: *AstXmlNode, names: *Dictionary<Str, AstXmlNode>): AstXmlNode {
    this.pushScope()
    this.baseScope = names
    val result: AstXmlNode = this.infer(e)
    this.popScope()
    return result
}

// Records a binding in the scope being built *and* in the flat record.
fun SemInfer.mark(name: *Str, typeNode: *AstXmlNode): Unit {
    if (this.scopes.size() == 0) {
        return
    }
    this.scopes[this.scopes.size() - 1].insert(name, typeNode)
    this.types.insert(name, typeNode)
}

fun SemInfer.lookup(name: *Str): AstXmlNode {
    var i: Int = this.scopes.size() - 1
    while (i >= 0) {
        val declared: *AstXmlNode = this.scopes[i].getPtr(name)
        if (declared != null) {
            return * declared
        }
        i = i - 1
    }
    val base: *AstXmlNode = this.baseScope.getPtr(name)
    if (base != null) {
        return * base
    }
    return xmlEmptyNode()
}

// A declaration is annotated only when the *value* of its initializer has a C++ type:
// a lambda needs its expected callable type and `null` has none. Everything else,
// including `&x`/`*x`/`copy(x)`, is typed below.
fun SemInfer.declarable(init: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(init)
    if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.ExprNullLit) {
        return false
    }
    return true
}

fun SemInfer.spellableName(name: *Str): Bool {
    if (name == "Unit") {
        return false // `void` has no values
    }
    if (xmlIsTypeParam(name, this.body.typeParams)) {
        return true
    }
    if (this.facts.types.has(name)) {
        return true
    }
    return semIsRtlTypeName(name)
}

// Whether the emitter can spell this type in this body: an out-of-scope type parameter
// or an undeclared name would not compile, so both mean "leave it to `auto`".
fun SemInfer.spellable(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return true
        }

        AstNodeCategory.TypeNamed -> {
            return this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))
        }

        AstNodeCategory.TypeGeneric -> {
            if (!this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))) {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*arg in args) {
                if (!this.spellable(arg)) {
                    return false
                }
            }
            return true
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            return this.spellable(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            if (!this.spellable(xmlChildPtr(typeNode, AstNodeKind.ReturnType))) {
                return false
            }
            val params: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            for (*param in params) {
                if (!this.spellable(param)) {
                    return false
                }
            }
            return true
        }

        AstNodeCategory.TypeYield -> {
            // A machine (`..T`) is spellable only once `semMachineType` named its class;
            // an anonymous one has no C++ spelling, so its declaration stays an `auto`.
            if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "") {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*arg in args) {
                if (!this.spellable(arg)) {
                    return false
                }
            }
            return true
        }
    }
    return false
}

fun SemInfer.isTypeName(name: *Str): Bool {
    return this.facts.types.has(name) || semIsRtlTypeName(name)
}

// Whether the type `typeName` declares an `initByValue` extension (the construction
// convention): the receiver is the fact's own, or the explicit `this` parameter's type.
fun SemInfer.isInitByValueType(typeName: *Str): Bool {
    var i: Int = 0
    while (i < this.facts.functions.size()) {
        val fn: *SemFnFact = *this.facts.functions[i]
        i = i + 1
        if (fn.name != "initByValue") {
            continue
        }
        var pattern: AstXmlNode = fn.receiver
        if (xmlIsEmpty(pattern)) {
            pattern = semExtensionReceiver(fn.decl)
        }
        if (xmlAttr(pattern, AstNodeAttributeKind.Name) == typeName) {
            return true
        }
    }
    return false
}

// Whether `typeNode` names a `union class` declaration (a generic instantiation included):
// a construction of one routes through the generated `initByValue` arms even in the
// explicit-type form (`var x: U = U(1)`), which is otherwise left as a plain constructor
// call.
fun SemInfer.isUnionClassType(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return false
    }
    val decl: *AstXmlNode = this.facts.types.getPtr(xmlAttr(typeNode, AstNodeAttributeKind.Name))
    if (decl == null) {
        return false
    }
    return xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true"
}

// Whether `init` is `T(args)` for a type `T` that declares an `initByValue` extension:
// the construction convention (`var x = T(a)`), as against a plain constructor call
// (`var x: T = T(a)`, the explicit-type form, which is left alone).
fun SemInfer.isInitByValueCtor(init: *AstXmlNode): Bool {
    if (xmlKind(init) != AstNodeCategory.ExprCall) {
        return false
    }
    val callee: *AstXmlNode = xmlChildPtr(init, AstNodeKind.Callee)
    val calleeKind: AstNodeCategory = xmlKind(callee)
    if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprGenericName) {
        return false
    }
    return this.isInitByValueType(xmlAttr(callee, AstNodeAttributeKind.Name))
}

// Whether `stmt` is the `initByValue` call of a `_sm_ctor` temp (`t.initByValue(...)`).
fun SemInfer.isCtorCall(stmt: AstXmlNode, name: *Str): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtExprStmt) {
        return false
    }
    val call: AstXmlNode = xmlChild(stmt, AstNodeKind.Expr)
    if (xmlKind(call) != AstNodeCategory.ExprCall) {
        return false
    }
    val callee: AstXmlNode = xmlChild(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return false
    }
    if (xmlAttr(callee, AstNodeAttributeKind.Name) != "initByValue") {
        return false
    }
    val recv: AstXmlNode = xmlChild(callee, AstNodeKind.Receiver)
    return xmlKind(recv) == AstNodeCategory.ExprName
        && xmlAttr(recv, AstNodeAttributeKind.Name) == name
}

// Whether `stmt` is `return <name>`.
fun SemInfer.isCtorReturn(stmt: AstXmlNode, name: *Str): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtReturn) {
        return false
    }
    val value: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
    return xmlKind(value) == AstNodeCategory.ExprName
        && xmlAttr(value, AstNodeAttributeKind.Name) == name
}

// A parenthesized `return (...)` desugars to a `_sm_ctor<n>` temp, its `initByValue` call
// and the return (src/parser/Parser.kt) - the lowering may have put argument temporaries
// between them. When the temp's type declares no `initByValue` the parenthesized form is an
// ordinary value, so the construction collapses to a plain `return e` (only a single
// expression has such a spelling). Marks the temp and the call for dropping and the return
// for rewriting; leaves them all alone when the construction stands.
fun SemInfer.markCtorFallback(list: *List<AstXmlNode>, i: Int, drop: *List<Bool>, rewrite: *List<AstXmlNode>): Unit {
    val decl: AstXmlNode = list[i]
    if (xmlKind(decl) != AstNodeCategory.StmtVarDecl) {
        return
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (!name.startsWith("_sm_ctor")) {
        return
    }
    if (this.isInitByValueType(xmlAttr(xmlChild(decl, AstNodeKind.Type), AstNodeAttributeKind.Name))) {
        return
    }
    var callIndex: Int = -1
    var retIndex: Int = -1
    var j: Int = i + 1
    while (j < list.size()) {
        if (callIndex < 0 && this.isCtorCall(list[j], name)) {
            callIndex = j
        }
        if (callIndex >= 0 && this.isCtorReturn(list[j], name)) {
            retIndex = j
            break
        }
        j = j + 1
    }
    if (callIndex < 0 || retIndex < 0) {
        return
    }
    val args: List<AstXmlNode> = xmlChildren(xmlChild(list[callIndex], AstNodeKind.Expr), AstNodeKind.Arg)
    if (args.size() != 1) {
        return
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, list[retIndex].attributes, Array<AstXmlNode>())
    var value: AstXmlNode = args[0]
    value.name = AstNodeKind.Value
    xmlAddChild(node, value)
    drop[i] = true
    drop[callIndex] = true
    rewrite[retIndex] = node
}

fun SemInfer.stmts(list: *List<AstXmlNode>): List<AstXmlNode> {
    var drop: List<Bool> = List<Bool>()
    var rewrite: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < list.size()) {
        drop.append(false)
        rewrite.append(xmlEmptyNode())
        i = i + 1
    }
    i = 0
    while (i < list.size()) {
        this.markCtorFallback(list, i, drop, rewrite)
        i = i + 1
    }
    var out: List<AstXmlNode> = List<AstXmlNode>()
    i = 0
    while (i < list.size()) {
        if (!drop[i]) {
            if (xmlIsEmpty(rewrite[i])) {
                out.append(this.stmt(list[i]))
            } else {
                out.append(rewrite[i])
            }
        }
        i = i + 1
    }
    return out
}

fun SemInfer.stmt(stmtNode: AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(stmtNode)
    when (kind) {
        AstNodeCategory.StmtVarDecl -> {
            val declared: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Type)
            val init: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Init)
            var typeNode: AstXmlNode = declared
            if (xmlIsEmpty(typeNode) && !xmlIsEmpty(init)) {
                typeNode = this.infer(init)
            }
            val name: Str = xmlAttr(stmtNode, AstNodeAttributeKind.Name)
            if (!xmlIsEmpty(typeNode)) {
                this.mark(name, typeNode)
            }
            // `var x: U = U(a)` on a `union class` is a construction like the inferred form:
            // its aggregate spelling would put the argument in the tag, so the declaration is
            // marked and the backend routes it through the generated `initByValue` arm.
            if (!xmlIsEmpty(declared) && !xmlIsEmpty(init) && this.isUnionClassType(declared)
                && this.isInitByValueCtor(init)
            ) {
                val typedDecl: AstXmlNode = semWithType(stmtNode, semReRole(declared, AstNodeKind.Type))
                typedDecl.attributes.append(AstNodeAttribute(AstNodeAttributeKind.InitByValue, "true"))
                return typedDecl
            }
            if (!xmlIsEmpty(declared) || xmlIsEmpty(typeNode) || xmlIsEmpty(init)
                || !this.declarable(init) || !this.spellable(typeNode)
            ) {
                return stmtNode
            }
            val typed: AstXmlNode = semWithType(stmtNode, semReRole(typeNode, AstNodeKind.Type))
            // `var x = T(a)` with no declared type constructs through `initByValue`: mark it
            // so the lowering keeps the declaration for the backend to route.
            if (this.isInitByValueCtor(init)) {
                typed.attributes.append(AstNodeAttribute(AstNodeAttributeKind.InitByValue, "true"))
            }
            return typed
        }

        AstNodeCategory.StmtBlock -> {
            this.pushScope()
            val inner: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmtNode, AstNodeKind.Body), AstNodeKind.Stmt)
            val rebuilt: List<AstXmlNode> = this.stmts(inner)
            this.popScope()
            var body: AstXmlNode =
                AstXmlNode(AstNodeKind.Body, AstNodeCategory.None, List<AstNodeAttribute>(), rebuilt.toArray())
            return semReplaceRole(stmtNode, AstNodeKind.Body, semOne(body))
        }
    }
    return stmtNode
}

fun SemInfer.handle(kind: AstNodeCategory, inner: *AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(inner)) {
        return xmlEmptyNode()
    }
    return AstXmlNode(
        AstNodeKind.Type, kind, List<AstNodeAttribute>(),
        semOne(semReRole(inner, AstNodeKind.Inner)).toArray()
    )
}

// A data class member type with the class's type parameters bound to the
// receiver's arguments (`Box<Int>.value` with `value: T` is `Int`).
fun SemInfer.instantiate(decl: *AstXmlNode, base: *AstXmlNode, memberType: AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(memberType)) {
        return xmlEmptyNode()
    }
    val classParams: List<Str> = xmlTypeParamNames(decl)
    if (classParams.size() == 0) {
        return memberType
    }
    if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
        return memberType
    }
    val args: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
    if (args.size() != classParams.size()) {
        return memberType
    }
    var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var i: Int = 0
    while (i < classParams.size()) {
        bindings.insert(classParams[i], args[i])
        i = i + 1
    }
    return semSubstitute(memberType, bindings, classParams)
}
