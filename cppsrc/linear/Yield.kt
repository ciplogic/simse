// Yield.kt
//
// `yield`: a body that yields is a state machine, produced by lowering
// (impl_specs/yield.md). The mirror of cppsrc/linear/Yield.cpp.
//
// `yield` is **not a semantic feature**. The parser produces one statement kind for it,
// and everything else is a *rewrite* - which is why nothing in sema, in the type pass or
// in the emitter knows what a yield is:
//
//   fun everyOther(n: Int): ..Int {      struct everyOther_yieldable {
//       var i: Int = 0                       Int branch{};
//       while (i < n) {                      Int n{};
//           if (i % 2 == 0) {                Int i{};
//               yield i                      Opt<Int> next() {
//           }                                    if (this->branch == -1) goto LYend;
//           i = i + 1                            if (this->branch == 1) goto LY1;
//       }                                        this->i = 0;            // branch 0
//   }                                            ...
//                                            LY1:;                        // resume here
//                                            ...
//                                            LYend:;
//                                            this->branch = -1;
//                                            return Opt<Int>::none();
//                                        }
//                                    };
//
// The rewrite runs on the *linear* body (after the lowering, so the control flow is
// already labels and gotos and the yielded value is one operand) and after the type pass
// (so a local has the type a field needs), in `linLowerYield`:
//
//   1. fields = `branch`, the parameters, and every local the body declares - except the
//      lowering's own storage (`linIsSlotName`), which is per-statement and stays a local
//      of the method;
//   2. the dispatcher is a chain of conditional jumps, `if (branch == -1) goto LYend;`
//      then `if (branch == n) goto LYn;` for every yield (branch 0 falls through, so it is
//      the start) - there is no `switch` anywhere, since it would only be lowered to
//      these jumps anyway;
//   3. `yield e` becomes `branch = n; return Opt<T>.some(e); LYn:;` - the label *is* the
//      resumption point - or, in `advance`, `*value = e; return true;`;
//   4. a `return` (or the end of the body) finishes the machine:
//      `branch = -1; return Opt<T>.none();` - `yield break`;
//   5. a reference to a field - read or written - is `this.<name>`, so a name that lives
//      across a yield lives in the instance.
//
// The generated statements carry no source position (the C++ ring's builders leave it
// zeroed), so the emitter writes no `// file:line` comment for them and the two rings'
// output stays identical.

package linear

import common

// One field of the machine: the values that live across a yield.
data class YldField(var name: Str; var typeNode: AstXmlNode)

// One parameter of a machine method (`advance`'s `value: *T`).
data class YldParam(var name: Str; var typeNode: AstXmlNode)

// One way of advancing the machine: `next()` (the optional form) or `advance(*T)` (the
// same machine without the copy).
data class YldMethod(var name: Str; var params: List<YldParam>; var body: List<AstXmlNode>; var yieldCount: Int)

data class Yielded(var fields: List<YldField>; var methods: List<YldMethod>; var error: Str)

// ---- node builders ---------------------------------------------------------

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
// position: a rewritten statement stays the statement it was (the C++ ring's `copyWith`).
fun yldWithChildren(like: *AstXmlNode, children: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(like.name, like.kind, like.attributes, Array<AstXmlNode>())
    var i: Int = 0
    while (i < children.size()) {
        var child: AstXmlNode = copy(children[i])
        xmlAddChild(*node, child)
        i = i + 1
    }
    return node
}

fun yldNamedType(name: Str): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed,
                                      List<AstNodeAttribute>(), Array<AstXmlNode>())
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
    var receiver: AstXmlNode = copy(base)
    receiver.name = AstNodeKind.Receiver
    xmlAddChild(*node, receiver)
    return node
}

fun yldBinary(op: Str, lhs: AstXmlNode, rhs: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprBinary)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    var left: AstXmlNode = copy(lhs)
    left.name = AstNodeKind.Lhs
    xmlAddChild(*node, left)
    var right: AstXmlNode = copy(rhs)
    right.name = AstNodeKind.Rhs
    xmlAddChild(*node, right)
    return node
}

fun yldDeref(inner: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprDeref)
    var operand: AstXmlNode = copy(inner)
    operand.name = AstNodeKind.Operand
    xmlAddChild(*node, operand)
    return node
}

fun yldCall(callee: AstXmlNode, args: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode = yldExpr(AstNodeCategory.ExprCall)
    var target: AstXmlNode = copy(callee)
    target.name = AstNodeKind.Callee
    xmlAddChild(*node, target)
    var i: Int = 0
    while (i < args.size()) {
        var arg: AstXmlNode = copy(args[i])
        arg.name = AstNodeKind.Arg
        xmlAddChild(*node, arg)
        i = i + 1
    }
    return node
}

// `Opt<T>.some(value)` / `Opt<T>.none()`: a static call on the RTL's optional, which the
// emitter already spells (`Opt<Int>::some(...)`).
fun yldOptionalCall(elementType: AstXmlNode, method: Str, args: *List<AstXmlNode>): AstXmlNode {
    var base: AstXmlNode = yldExpr(AstNodeCategory.ExprGenericName)
    base.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, "Opt"))
    var typeArg: AstXmlNode = copy(elementType)
    typeArg.name = AstNodeKind.TypeArg
    xmlAddChild(*base, typeArg)
    return yldCall(yldMember(base, method), args)
}

fun yldAssign(target: AstXmlNode, value: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtAssign)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
    var targetNode: AstXmlNode = copy(target)
    targetNode.name = AstNodeKind.Target
    xmlAddChild(*node, targetNode)
    var valueNode: AstXmlNode = copy(value)
    valueNode.name = AstNodeKind.Value
    xmlAddChild(*node, valueNode)
    return node
}

fun yldReturn(value: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtReturn)
    var valueNode: AstXmlNode = copy(value)
    valueNode.name = AstNodeKind.Value
    xmlAddChild(*node, valueNode)
    return node
}

fun yldExprStmt(expr: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = yldStmt(AstNodeCategory.StmtExprStmt)
    var exprNode: AstXmlNode = copy(expr)
    exprNode.name = AstNodeKind.Expr
    xmlAddChild(*node, exprNode)
    return node
}

// Whether a receiver is a *handle*: a bare `this` is then already the value the caller
// passed, while a value receiver's `this` has to be read back out of the pointer the
// machine holds.
fun yldIsHandle(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    return kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer
}

// Whether a statement is one of the method's own declarations: the lowering's storage
// (`_sm_expr<n>`), declared without an initializer at the top of the body (`linIsSlotName`
// is the one place that is stated). A user's local became a field, so this is exactly the
// set that stays with the method.
fun yldIsLocalDeclaration(stmt: *AstXmlNode): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtVarDecl) {
        return false
    }
    if (!linIsSlotName(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
        return false
    }
    return xmlIsEmpty(*xmlChild(stmt, AstNodeKind.Init))
}

// The machine's field for the receiver of an extension function (`_sm_self`). The
// receiver is an ordinary parameter (`specs/functions.md`), so it lives in the instance
// like every other value that crosses a yield; the emitter initialises the field from its
// own `self` parameter.
fun yldReceiverField(): Str {
    return "_sm_self"
}

// `if (cond) goto label;`
fun yldJumpWhen(cond: AstXmlNode, label: Str): AstXmlNode {
    return linCondJump(AstNodeCategory.StmtIfTrue, *cond, label, 0, 0)
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

// ---- the machine -----------------------------------------------------------

data class YldMachinery(
    var decl: *AstXmlNode;
    var elementType: AstXmlNode;
    var valueTypeText: Str;
    var fieldTypes: Dictionary<Str, AstXmlNode>;
    var fieldOrder: List<Str>;
    var yields: Int;
    var error: Str;
    var byReference: Bool
) {

    fun fail(message: Str): Unit {
        if (this.error.isEmpty()) {
            this.error = message
        }
    }

    fun run(body: List<AstXmlNode>): Yielded {
        var yielded: Yielded = Yielded(List<YldField>(), List<YldMethod>(), "")
        this.collectFields(body, *yielded)
        if (!this.error.isEmpty()) {
            yielded.error = this.error
            return yielded
        }
        // `next()`: the optional form. `advance()`: the same machine without the copy - it
        // writes through the caller's pointer and says whether there was a value.
        yielded.methods.append(this.method("next", false, body))
        if (!this.valueTypeText.isEmpty()) {
            yielded.methods.append(this.method("advance", true, body))
        }
        if (!this.error.isEmpty()) {
            yielded.error = this.error
        }
        return yielded
    }

    // The fields: `branch`, the receiver (an extension function's `this` has to cross a
    // yield like anything else), the parameters, and every local the body declares that is
    // not the lowering's own storage (those are per-statement and are re-initialised on
    // every entry, so they stay locals of the method).
    fun collectFields(body: List<AstXmlNode>, yielded: *Yielded): Unit {
        this.fieldTypes.insert(yldBranchField(), yldNamedType("Int"))
        this.fieldOrder.append(yldBranchField())

        val receiver: AstXmlNode = xmlChild(this.decl, AstNodeKind.Receiver)
        if (!xmlIsEmpty(*receiver)) {
            this.fieldTypes.insert(yldReceiverField(), ilReceiverTypeNode(*receiver))
            this.fieldOrder.append(yldReceiverField())
        }

        val params: List<AstXmlNode> = xmlChildren(this.decl, AstNodeKind.Param)
        var i: Int = 0
        while (i < params.size()) {
            val param: *AstXmlNode = *params[i]
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            i = i + 1
            if (name == "this") {
                // A receiver written as a parameter *is* the `this` of the body, and `this`
                // cannot name a C++ member: the receiver-form spelling (`fun T.name`) is
                // the one that can yield for now.
                this.fail("yield: a `this` parameter cannot be a field; write the receiver before the name (`fun T.name`) instead")
                return
            }
            if (this.fieldTypes.has(name)) {
                continue
            }
            val typeNode: AstXmlNode = xmlChild(param, AstNodeKind.Type)
            if (xmlIsEmpty(*typeNode)) {
                this.fail("yield: the parameter '" + name + "' has no type")
                return
            }
            this.fieldTypes.insert(name, typeNode)
            this.fieldOrder.append(name)
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
                if (!linIsSlotName(name) && !this.fieldTypes.has(name)) {
                    val typeNode: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
                    if (xmlIsEmpty(*typeNode)) {
                        // A local that lives across a yield must be a field, and a field
                        // needs a type - the type pass spells it, so an untyped one here is
                        // a gap in the body, not in this pass. One gap has a name of its own:
                        // the machine a `for` iterates is created by a call, and a machine
                        // type is the class that call's own function got (`..T` is not a
                        // value type).
                        if (name.startsWith("_sm_for") || name.startsWith("_sm_step")) {
                            var message: Str = "yield: a `for` over a machine cannot cross a yield "
                            message.appendStr("(the machine a call creates has no type to make a ")
                            message.appendStr("field of); collect the values into a `List` first")
                            this.fail(message)
                            return
                        }
                        this.fail("yield: the local '" + name + "' has no type to make a field of")
                        return
                    }
                    this.fieldTypes.insert(name, typeNode)
                    this.fieldOrder.append(name)
                }
            }
            var locals: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Body)
            this.collectLocals(locals)
            this.collectLocals(xmlChildren(stmt, AstNodeKind.Then))
            this.collectLocals(xmlChildren(stmt, AstNodeKind.Else))
            val cases: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Case)
            var c: Int = 0
            while (c < cases.size()) {
                var arm: List<AstXmlNode> = List<AstXmlNode>()
                var k: Int = 0
                val children: Array<AstXmlNode> = cases[c].Children
                while (k < children.count()) {
                    if (children[k].name != AstNodeKind.Label) {
                        arm.append(children[k])
                    }
                    k = k + 1
                }
                var armList: List<AstXmlNode> = arm
                this.collectLocals(armList)
                c = c + 1
            }
        }
    }

    // One method of the machine. The body is the same statements either way: only what a
    // yield *does* with the value differs.
    fun method(name: Str, byReference: Bool, body: List<AstXmlNode>): YldMethod {
        var params: List<YldParam> = List<YldParam>()
        this.byReference = byReference
        if (byReference) {
            params.append(YldParam("value", ilPointerOf(this.elementType)))
        }
        this.yields = 0
        var rewritten: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < body.size()) {
            this.statements(*body[i], *rewritten)
            i = i + 1
        }
        var methodBody: List<AstXmlNode> = List<AstXmlNode>()
        // The hoisted storage first - the declarations the lowering moved to the top of the
        // body, which stay locals of the method (they are per-statement, so they never have
        // to survive a call). They have to precede the dispatcher: a jump that skips a
        // declaration is what C++ refuses (C2362), and for a generic function a declaration
        // is `T`, i.e. non-trivial for `Str` and friends. So the dispatcher goes *after*
        // them.
        var first: Int = 0
        while (first < rewritten.size() && yldIsLocalDeclaration(*rewritten[first])) {
            methodBody.append(rewritten[first])
            first = first + 1
        }
        methodBody.append(yldJumpWhen(yldBinary("==", yldThisMember(yldBranchField()),
                                                yldIntLiteral(-1)), yldEndLabel()))
        var branch: Int = 1
        while (branch <= this.yields) {
            methodBody.append(yldJumpWhen(yldBinary("==", yldThisMember(yldBranchField()),
                                                    yldIntLiteral(branch)), yldLabel(branch)))
            branch = branch + 1
        }
        i = first
        while (i < rewritten.size()) {
            methodBody.append(rewritten[i])
            i = i + 1
        }
        methodBody.append(linLabel(yldEndLabel(), 0, 0))
        methodBody.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(-1)))
        methodBody.append(yldReturn(this.finishValue()))
        return YldMethod(name, params, methodBody, this.yields)
    }

    // What a finished machine answers: an empty optional, or `false` when the value is
    // handed back through the caller's pointer.
    fun finishValue(): AstXmlNode {
        if (this.byReference) {
            return yldBoolLiteral(false)
        }
        return yldOptionalCall(this.elementType, "none", *List<AstXmlNode>())
    }

    fun statements(stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
        if (!this.error.isEmpty()) {
            return
        }
        val kind: AstNodeCategory = xmlKind(stmt)
        if (kind == AstNodeCategory.StmtYield) {
            this.yields = this.yields + 1
            val branch: Int = this.yields
            val value: AstXmlNode = this.expr(xmlChild(stmt, AstNodeKind.Value))
            if (this.byReference) {
                // `*value = e; return true;`
                out.append(yldAssign(yldDeref(linName(AstNodeKind.None, "value", 0, 0)), value))
                out.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(branch)))
                out.append(yldReturn(yldBoolLiteral(true)))
            } else {
                var args: List<AstXmlNode> = List<AstXmlNode>()
                args.append(value)
                out.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(branch)))
                out.append(yldReturn(yldOptionalCall(this.elementType, "some", *args)))
            }
            out.append(linLabel(yldLabel(branch), 0, 0))
            return
        }
        if (kind == AstNodeCategory.StmtReturn) {
            // A `return` in a yielding body is `yield break`: the machine is finished. A
            // value (which the `..T` signature does not allow) is still evaluated, so
            // nothing silently disappears.
            val returnValue: AstXmlNode = xmlChild(stmt, AstNodeKind.Value)
            if (!xmlIsEmpty(*returnValue)) {
                out.append(yldExprStmt(this.expr(returnValue)))
            }
            out.append(yldAssign(yldThisMember(yldBranchField()), yldIntLiteral(-1)))
            out.append(yldReturn(this.finishValue()))
            return
        }
        if (kind == AstNodeCategory.StmtVarDecl) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            val declared: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
            val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
            if (linIsSlotName(name)) {
                // The lowering's own storage stays a local of the method: it is
                // per-statement, so it is re-initialised on every entry and never has to
                // survive a yield.
                var children: List<AstXmlNode> = List<AstXmlNode>()
                if (!xmlIsEmpty(*declared)) {
                    var declaredChild: AstXmlNode = copy(declared)
                    declaredChild.name = AstNodeKind.Type
                    children.append(declaredChild)
                }
                if (!xmlIsEmpty(*init)) {
                    var initChild: AstXmlNode = this.expr(init)
                    initChild.name = AstNodeKind.Init
                    children.append(initChild)
                }
                out.append(yldWithChildren(stmt, *children))
                return
            }
            // Everything else is a field now; its initializer runs where it was, which is
            // on the way to the first yield (a machine that resumes past it does not run it
            // again).
            if (!xmlIsEmpty(*init)) {
                out.append(yldAssign(yldThisMember(name), this.expr(init)))
            }
            return
        }
        if (kind == AstNodeCategory.StmtAssign) {
            var children: List<AstXmlNode> = List<AstXmlNode>()
            var target: AstXmlNode = this.expr(xmlChild(stmt, AstNodeKind.Target))
            target.name = AstNodeKind.Target
            children.append(target)
            var value: AstXmlNode = this.expr(xmlChild(stmt, AstNodeKind.Value))
            value.name = AstNodeKind.Value
            children.append(value)
            out.append(yldWithChildren(stmt, *children))
            return
        }
        if (kind == AstNodeCategory.StmtExprStmt) {
            var children: List<AstXmlNode> = List<AstXmlNode>()
            var inner: AstXmlNode = this.expr(xmlChild(stmt, AstNodeKind.Expr))
            inner.name = AstNodeKind.Expr
            children.append(inner)
            out.append(yldWithChildren(stmt, *children))
            return
        }
        if (kind == AstNodeCategory.StmtIfTrue || kind == AstNodeCategory.StmtIfFalse) {
            val cond: AstXmlNode = this.expr(xmlChild(stmt, AstNodeKind.Cond))
            out.append(linCondJump(kind, *cond, xmlAttr(stmt, AstNodeAttributeKind.Name),
                                   xmlLine(stmt), xmlColumn(stmt)))
            return
        }
        if (kind == AstNodeCategory.StmtBlock) {
            var inner: List<AstXmlNode> = List<AstXmlNode>()
            val children: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Body)
            var i: Int = 0
            while (i < children.size()) {
                this.statements(*children[i], *inner)
                i = i + 1
            }
            out.append(linBlock(*inner, xmlLine(stmt), xmlColumn(stmt)))
            return
        }
        // Labels and gotos are the control flow, and `break`/`continue` are gone by now.
        out.append(copy(stmt))
    }

    // A name that is a field is read and written as a field of the machine, so the values
    // live in the instance across calls. Everything else (the lowering's temporaries,
    // statics, calls) is left as it was.
    //
    // `base` says the node is the receiver of a member or an index, where the *field* is
    // what is wanted: the spelling helpers dereference a pointer field where they have to
    // (`this._sm_self->size()`, `(*this._sm_self)[i]`). Anywhere else the language means
    // the object behind the receiver, so a value receiver's `this` is read back out of it.
    fun expr(node: AstXmlNode): AstXmlNode {
        return this.exprAt(node, false)
    }

    fun exprAt(node: AstXmlNode, base: Bool): AstXmlNode {
        if (!this.error.isEmpty()) {
            return node
        }
        if (xmlKind(*node) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(*node, AstNodeAttributeKind.Name)
            if (name == "this") {
                val receiver: AstXmlNode = xmlChild(this.decl, AstNodeKind.Receiver)
                val selfField: AstXmlNode = yldThisMember(yldReceiverField())
                if (!base && !xmlIsEmpty(*receiver) && !yldIsHandle(*receiver)) {
                    return yldDeref(selfField)
                }
                return selfField
            }
            if (this.fieldTypes.has(name)) {
                return yldThisMember(name)
            }
            return node
        }
        // Every child is rewritten in place, position included: the children keep the
        // roles they were read with, so the node's shape does not change.
        var copyNode: AstXmlNode = copy(node)
        var rebuilt: AstXmlNode = AstXmlNode(copyNode.name, copyNode.kind, copyNode.attributes,
                                             Array<AstXmlNode>())
        // A member's or an index's receiver is a *place*, not a value: `this` there stays
        // the field (see above).
        val bases: Bool = xmlKind(*node) == AstNodeCategory.ExprMember || xmlKind(*node) == AstNodeCategory.ExprIndex
        var i: Int = 0
        while (i < copyNode.Children.count()) {
            var child: AstXmlNode = this.exprAt(copyNode.Children[i], bases)
            child.name = copyNode.Children[i].name
            xmlAddChild(*rebuilt, child)
            i = i + 1
        }
        return rebuilt
    }
}

// A pointer type node around `inner` (`*T`), for `advance`'s value parameter.
fun ilPointerOf(inner: AstXmlNode): AstXmlNode {
    return ilPointerNode(*inner)
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
        val cases: List<AstXmlNode> = xmlChildren(stmt, AstNodeKind.Case)
        var c: Int = 0
        while (c < cases.size()) {
            var arm: List<AstXmlNode> = List<AstXmlNode>()
            var k: Int = 0
            val children: Array<AstXmlNode> = cases[c].Children
            while (k < children.count()) {
                if (children[k].name != AstNodeKind.Label) {
                    arm.append(children[k])
                }
                k = k + 1
            }
            if (linHasYield(arm)) {
                return true
            }
            c = c + 1
        }
    }
    return false
}

// The rewrite: one function-like body into the machine that yields its values.
fun linLowerYield(decl: *AstXmlNode, elementType: AstXmlNode, linearBody: List<AstXmlNode>,
                  valueTypeText: Str): Yielded {
    var machinery: YldMachinery = YldMachinery(decl, elementType, valueTypeText,
                                               Dictionary<Str, AstXmlNode>(), List<Str>(), 0, "", false)
    return machinery.run(linearBody)
}
