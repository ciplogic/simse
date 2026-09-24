// Yield.kt
//
// `yield` is a rewrite, not a semantic feature (impl_specs/yield.md): a yielding body
// becomes a state machine of `branch`/`this.<name>` fields, run on the linear body after
// the type pass. The dispatcher is a chain of `if (branch == n) goto LYn;` jumps; a yield
// stores and returns `Opt<T>.some(e)`, and a `return` closes the machine.

package linear

import common

// One field of the machine: the values that live across a yield.
data class YldField(
    var name: Str,

    var typeNode: AstXmlNode
)

// One parameter of a machine method (`advance`'s `value: *T`).
data class YldParam(
    var name: Str,

    var typeNode: AstXmlNode
)

// One way of advancing the machine: `advance()`, which steps it and answers whether there
// was a value.
data class YldMethod(
    var name: Str,

    var params: List<YldParam>,
    var body: List<AstXmlNode>,
    var yieldCount: Int
)

data class Yielded(
    var fields: List<YldField>,

    var methods: List<YldMethod>,
    var error: Str
)

fun yldExpr(kind: AstNodeCategory): AstXmlNode {
    return AstXmlNode(AstNodeKind.Expr, kind, List<AstNodeAttribute>(), Array<AstXmlNode>())
}

fun yldStmt(kind: AstNodeCategory): AstXmlNode {
    return linStmt(kind, 0, 0)
}

fun yldIntLiteral(value: Int): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprIntLit)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Text, value.toString()))
    return node
}

fun yldBoolLiteral(value: Bool): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprBoolLit)
    var text: Str = "false"
    if (value) {
        text = "true"
    }
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Value, text))
    return node
}

// The statement with its children replaced, keeping its own kind, attributes and source
// position.
fun yldWithChildren(like: *AstXmlNode, children: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(like.name, like.kind, like.attributes, Array<AstXmlNode>())
    var i: Int = 0
    while (i < children.size()) {
        var child: AstXmlNode = copy(children[i])
        xmlAddChild(node, child)
        i = i + 1
    }
    return node
}

fun yldNamedType(name: Str): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypeNamed,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// `this.<field>`: the machine's own value, read or written.
fun yldThisMember(field: Str): AstXmlNode {
    var base: AstXmlNode = linName(AstNodeKind.Receiver, "this", 0, 0)
    return yldMember(base, field)
}

fun yldMember(base: AstXmlNode, field: Str): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprMember)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, field))
    var receiver: AstXmlNode = base
    receiver.name = AstNodeKind.Receiver
    xmlAddChild(node, receiver)
    return node
}

fun yldBinary(op: Str, lhs: AstXmlNode, rhs: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprBinary)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    var left: AstXmlNode = lhs
    left.name = AstNodeKind.Lhs
    xmlAddChild(node, left)
    var right: AstXmlNode = rhs
    right.name = AstNodeKind.Rhs
    xmlAddChild(node, right)
    return node
}

fun yldDeref(inner: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprDeref)
    var operand: AstXmlNode = inner
    operand.name = AstNodeKind.Operand
    xmlAddChild(node, operand)
    return node
}

fun yldCall(callee: AstXmlNode, args: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprCall)
    var target: AstXmlNode = callee
    target.name = AstNodeKind.Callee
    xmlAddChild(node, target)
    var i: Int = 0
    while (i < args.size()) {
        var arg: AstXmlNode = copy(args[i])
        arg.name = AstNodeKind.Arg
        xmlAddChild(node, arg)
        i = i + 1
    }
    return node
}

// `Opt<T>.some(value)` / `Opt<T>.none()`: a static call on the RTL's optional, which the
// emitter spells (`Opt<Int>::some(...)`).
fun yldOptionalCall(elementType: AstXmlNode, method: Str, args: *List<AstXmlNode>): AstXmlNode {
    var base: AstXmlNode = yldExpr(AstNodeCategory.ExprGenericName)
    base.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, "Opt"))
    var typeArg: AstXmlNode = elementType
    typeArg.name = AstNodeKind.TypeArg
    xmlAddChild(base, typeArg)
    return yldCall(yldMember(base, method), args)
}

fun yldAssign(target: AstXmlNode, value: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtAssign)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
    var targetNode: AstXmlNode = target
    targetNode.name = AstNodeKind.Target
    xmlAddChild(node, targetNode)
    var valueNode: AstXmlNode = value
    valueNode.name = AstNodeKind.Value
    xmlAddChild(node, valueNode)
    return node
}

fun yldReturn(value: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtReturn)
    var valueNode: AstXmlNode = value
    valueNode.name = AstNodeKind.Value
    xmlAddChild(node, valueNode)
    return node
}

fun yldExprStmt(expr: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtExprStmt)
    var exprNode: AstXmlNode = expr
    exprNode.name = AstNodeKind.Expr
    xmlAddChild(node, exprNode)
    return node
}

// Whether a receiver is a *handle*: a bare `this` is then already the value the caller
// passed, while a value receiver's `this` has to be read back out of the pointer the
// machine holds.
fun yldIsHandle(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    return kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer
}

// Whether a statement is one of the method's own storage declarations (`_sm_expr<n>`, no
// initializer, at the top of the body); a user's local became a field.
fun yldIsLocalDeclaration(stmt: *AstXmlNode): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtVarDecl) {
        return false
    }
    if (!linIsSlotName(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
        return false
    }
    return xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))
}

// The field for an extension function's receiver (`_sm_self`): the receiver is an ordinary
// parameter (`specs/functions.md`), so it crosses a yield like any other value.
fun yldReceiverField(): Str {
    return "_sm_self"
}

fun yldJumpWhen(cond: AstXmlNode, label: Str): AstXmlNode {
    return linCondJump(AstNodeCategory.StmtIfTrue, cond, label, 0, 0)
}

// The labels this pass makes, kept apart from the lowering's `L<n>`.
fun yldLabel(branch: Int): Str {
    return "LY" + branch.toString()
}

fun yldEndLabel(): Str {
    return "LYend"
}

fun yldBranchField(): Str {
    return "branch"
}

// What the machine last yielded; `advance()` stores it and the `for` reads its loop variable
// from it, so the value never travels through a constructed `Opt` (`impl_specs/for.md`).
fun yldCurrentField(): Str {
    return "current"
}

// A body name colliding with the machine's own members (`branch`, `current`, the receiver,
// `advance`) is emitted mangled; this is the single place that decides, so a local named
// `branch` cannot silently alias the protocol.
fun yldFieldName(name: Str): Str {
    if (
        name == yldBranchField() || name == yldCurrentField()
        || name == yldReceiverField() || name == "advance"
    ) {
        return "_sm_f_" + name
    }
    return name
}

data class YldMachinery(
    var decl: *AstXmlNode,
    var elementType: AstXmlNode,

    var valueTypeText: Str,
    var fieldTypes: Dictionary<Str, AstXmlNode>,
    var fieldOrder: List<Str>,
    var yields: Int,
    var error: Str
) {

    fun fail(message: Str): Unit {
        if (this.error.isEmpty()) {
            this.error = message
        }
    }

    fun run(body: *List<AstXmlNode>): Yielded {
        var yielded: Yielded = Yielded(List<YldField>(), List<YldMethod>(), "")
        this.collectFields(body, yielded)
        if (!this.error.isEmpty()) {
            yielded.error = this.error
            return yielded
        }
        // `advance()` steps the machine and leaves what it yielded in `current`, answering
        // whether there was one; the `for` reads `current` itself (`impl_specs/for.md`).
        if (!this.valueTypeText.isEmpty()) {
            yielded.methods.append(this.method(this.valueTypeText, body))
        }
        if (!this.error.isEmpty()) {
            yielded.error = this.error
        }
        return yielded
    }

    // `branch`, the receiver (an extension function's `this` has to cross a yield like
    // anything else), the parameters, and every local the body declares that is not the
    // lowering's storage (those are per-statement and stay locals of the method).
    fun collectFields(body: List<AstXmlNode>, yielded: *Yielded): Unit {
        this.fieldTypes.insert(yldBranchField(), yldNamedType("Int"))
        this.fieldOrder.append(yldBranchField())
        // What `advance` yields: a field of its own, so a `for` reads one load.
        this.fieldTypes.insert(yldCurrentField(), this.elementType)
        this.fieldOrder.append(yldCurrentField())

        val receiver: *AstXmlNode = xmlChildPtr(this.decl, AstNodeKind.Receiver)
        if (!xmlIsEmpty(receiver)) {
            this.fieldTypes.insert(yldReceiverField(), ilReceiverTypeNode(receiver))
            this.fieldOrder.append(yldReceiverField())
        }

        val params: List<AstXmlNode> = xmlChildren(this.decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            val param: *AstXmlNode = *params[i]
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            i = i + 1
            if (name == "this") {
                // A `this` parameter cannot be a field; the receiver-form spelling
                // (`fun T.name`) is the one that can yield.
                this.fail("yield: a `this` parameter cannot be a field; write the receiver before the name (`fun T.name`) instead")
                return
            }
            if (this.fieldTypes.has(yldFieldName(name))) {
                continue
            }
            val typeNode: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
            if (xmlIsEmpty(typeNode)) {
                this.fail(fmtStr("yield: the parameter '|' has no type", name))
                return
            }
            this.fieldTypes.insert(yldFieldName(name), typeNode)
            this.fieldOrder.append(yldFieldName(name))
        }
        this.collectLocals(body)
        if (!this.error.isEmpty()) {
            return
        }
        var f: Int = 0
        while (f < this.fieldOrder.size()) {
            yielded.fields.append(YldField(this.fieldOrder[f], this.fieldTypes.get(this.fieldOrder[f]).value()))
            f = f + 1
        }
    }

    fun collectLocals(body: List<AstXmlNode>): Unit {
        var i: Int = 0
        while (i < body.size()) {
            val stmt: *AstXmlNode = *body[i]
            i = i + 1
            if (!this.error.isEmpty()) {
                return
            }
            if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
                val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                if (!linIsSlotName(name) && !this.fieldTypes.has(yldFieldName(name))) {
                    val typeNode: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
                    if (xmlIsEmpty(typeNode)) {
                        // A local that lives across a yield must be a field, and a field
                        // needs a type (the type pass spells it). A `for` machine type has no
                        // spelling (`..T` is not a value type).
                        if (name.startsWith("_sm_for")) {
                            var message: Str = "yield: a `for` over a machine cannot cross a yield "
                            message.appendStr("(the machine a call creates has no type to make a ")
                            message.appendStr("field of); collect the values into a `List` first")
                            this.fail(message)
                            return
                        }
                        this.fail(fmtStr("yield: the local '|' has no type to make a field of", name))
                        return
                    }
                    this.fieldTypes.insert(yldFieldName(name), typeNode)
                    this.fieldOrder.append(yldFieldName(name))
                }
            }
            var locals: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Body)
            this.collectLocals(locals)
            this.collectLocals(xmlChildren(stmt, AstNodeKind.Then))
            this.collectLocals(xmlChildren(stmt, AstNodeKind.Else))
        }
    }

    // One method of the machine: it fills `current` and answers whether there was a value.
    fun method(name: Str, body: List<AstXmlNode>): YldMethod {
        var params: List<YldParam> = List<YldParam>()
        this.yields = 0
        var rewritten: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < body.size()) {
            this.statements(body[i], rewritten)
            i = i + 1
        }
        var methodBody: List<AstXmlNode> = List<AstXmlNode>()
        // The hoisted storage first: a jump that skips a declaration is what C++ refuses
        // (C2362), so the dispatcher goes after it.
        var first: Int = 0
        while (first < rewritten.size() && yldIsLocalDeclaration(rewritten[first])) {
            methodBody.append(rewritten[first])
            first = first + 1
        }
        methodBody.append(
            yldJumpWhen(
                yldBinary(
                    "==", yldThisMember(yldBranchField()),
                    yldIntLiteral(-1)
                ), yldEndLabel()
            )
        )
        var branch: Int = 1
        while (branch <= this.yields) {
            methodBody.append(
                yldJumpWhen(
                    yldBinary(
                        "==", yldThisMember(yldBranchField()),
                        yldIntLiteral(branch)
                    ), yldLabel(branch)
                )
            )
            branch = branch + 1
        }
        i = first
        while (i < rewritten.size()) {
            methodBody.append(rewritten[i])
            i = i + 1
        }
        methodBody.append(linLabel(yldEndLabel(), 0, 0))
        methodBody.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(-1)))
        methodBody.append(yldReturn(yldBoolLiteral(false)))
        return YldMethod(name, params, methodBody, this.yields)
    }

    fun statements(stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
        if (!this.error.isEmpty()) {
            return
        }
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtYield -> {
                this.yields = this.yields + 1
                val branch: Int = this.yields
                val value: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Value))
                // `current = e; branch = k; return true;`
                out.append(yldAssign(yldThisMember(yldCurrentField()), value))
                out.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(branch)))
                out.append(yldReturn(yldBoolLiteral(true)))
                out.append(linLabel(yldLabel(branch), 0, 0))
                return
            }

            AstNodeCategory.StmtReturn -> {
                // A `return` is `yield break`; a value (which the `..T` signature does not
                // allow) is still evaluated, so nothing silently disappears.
                val returnValue: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                if (!xmlIsEmpty(returnValue)) {
                    out.append(yldExprStmt(this.expr(returnValue)))
                }
                out.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(-1)))
                out.append(yldReturn(yldBoolLiteral(false)))
                return
            }

            AstNodeCategory.StmtVarDecl -> {
                val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                val declared: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
                val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
                if (linIsSlotName(name)) {
                    // The lowering's storage stays a local: per-statement, re-initialised on
                    // every entry.
                    var children: List<AstXmlNode> = List<AstXmlNode>()
                    if (!xmlIsEmpty(declared)) {
                        var declaredChild: AstXmlNode = declared
                        declaredChild.name = AstNodeKind.Type
                        children.append(declaredChild)
                    }
                    if (!xmlIsEmpty(init)) {
                        var initChild: AstXmlNode = this.expr(init)
                        initChild.name = AstNodeKind.Init
                        children.append(initChild)
                    }
                    out.append(yldWithChildren(stmt, children))
                    return
                }
                // Everything else is a field; its initializer runs where it was, on the way
                // to the first yield (a resume past it does not run it again).
                if (!xmlIsEmpty(init)) {
                    out.append(yldAssign(yldThisMember(yldFieldName(name)), this.expr(init)))
                }
                return
            }

            AstNodeCategory.StmtAssign -> {
                var children: List<AstXmlNode> = List<AstXmlNode>()
                var target: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Target))
                target.name = AstNodeKind.Target
                children.append(target)
                var value: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Value))
                value.name = AstNodeKind.Value
                children.append(value)
                out.append(yldWithChildren(stmt, children))
                return
            }

            AstNodeCategory.StmtExprStmt -> {
                var children: List<AstXmlNode> = List<AstXmlNode>()
                var inner: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Expr))
                inner.name = AstNodeKind.Expr
                children.append(inner)
                out.append(yldWithChildren(stmt, children))
                return
            }

            AstNodeCategory.StmtIfTrue, AstNodeCategory.StmtIfFalse -> {
                val cond: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Cond))
                out.append(
                    linCondJump(
                        kind, cond, xmlAttr(stmt, AstNodeAttributeKind.Name),
                        xmlLine(stmt), xmlColumn(stmt)
                    )
                )
                return
            }

            AstNodeCategory.StmtBlock -> {
                var inner: List<AstXmlNode> = List<AstXmlNode>()
                val children: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Body)
                for (*child in children) {
                    this.statements(child, inner)
                }
                out.append(linBlock(inner, xmlLine(stmt), xmlColumn(stmt)))
                return
            }
        }
        // Labels and gotos are the control flow, and `break`/`continue` are gone by now.
        out.append(stmt)
    }

    // A name that is a field is read and written as `this.<name>`, so values live in the
    // instance across calls. `base` says the node is a receiver of a member/index, where the
    // field itself is wanted (the spelling helpers dereference a pointer field:
    // `this._sm_self->size()`); anywhere else the object behind the receiver is meant.
    fun expr(node: *AstXmlNode): AstXmlNode {
        return this.exprAt(node, false)
    }

    fun exprAt(node: AstXmlNode, base: Bool): AstXmlNode {
        if (!this.error.isEmpty()) {
            return node
        }
        if (xmlKind(node) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            if (name == "this") {
                val receiver: *AstXmlNode = xmlChildPtr(this.decl, AstNodeKind.Receiver)
                val selfField: AstXmlNode = yldThisMember(yldReceiverField())
                if (!base && !xmlIsEmpty(receiver) && !yldIsHandle(receiver)) {
                    return yldDeref(selfField)
                }
                return selfField
            }
            if (this.fieldTypes.has(yldFieldName(name))) {
                return yldThisMember(yldFieldName(name))
            }
            return node
        }
        // Every child is rewritten in place, position included: the children keep the roles
        // they were read with.
        var copyNode: AstXmlNode = node
        var rebuilt: AstXmlNode = AstXmlNode(
            copyNode.name, copyNode.kind, copyNode.attributes,
            Array<AstXmlNode>()
        )
        // A member's or an index's receiver is a *place*, not a value: `this` there stays
        // the field.
        val bases: Bool = xmlKind(node) == AstNodeCategory.ExprMember || xmlKind(node) == AstNodeCategory.ExprIndex
        var i: Int = 0
        while (i < copyNode.Children.count()) {
            var child: AstXmlNode = this.exprAt(copyNode.Children[i], bases)
            child.name = copyNode.Children[i].name
            xmlAddChild(rebuilt, child)
            i = i + 1
        }
        return rebuilt
    }
}

// A pointer type node around `inner` (`*T`), for `advance`'s value parameter.
fun ilPointerOf(inner: AstXmlNode): AstXmlNode {
    return ilPointerNode(inner)
}

// Whether a body yields anywhere: the one test the emitter needs to pick the machine path.
fun linHasYield(body: List<AstXmlNode>): Bool {
    var i: Int = 0
    while (i < body.size()) {
        val stmt: *AstXmlNode = *body[i]
        i = i + 1
        if (xmlKind(stmt) == AstNodeCategory.StmtYield) {
            return true
        }
        if (linHasYield(xmlChildren(stmt, AstNodeKind.Body))) {
            return true
        }
        if (linHasYield(xmlChildren(stmt, AstNodeKind.Then))) {
            return true
        }
        if (linHasYield(xmlChildren(stmt, AstNodeKind.Else))) {
            return true
        }
    }
    return false
}

// The rewrite: one function-like body into the machine that yields its values.
fun linLowerYield(
    decl: *AstXmlNode, elementType: AstXmlNode, linearBody: List<AstXmlNode>,
    valueTypeText: Str
): Yielded {
    var machinery: YldMachinery = YldMachinery(
        decl, elementType, valueTypeText,
        Dictionary<Str, AstXmlNode>(), List<Str>(), 0, ""
    )
    return machinery.run(linearBody)
}
