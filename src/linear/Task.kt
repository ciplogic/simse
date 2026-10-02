// Task.kt
//
// The `suspend` lowering (impl_specs/async.md, "The machine is push"): a suspending body becomes a
// *task* - a state machine of fields the loop drives, in place of the stack a synchronous call would
// use. It mirrors `Yield.kt` (the field collection, the `branch` dispatcher, the resume labels, the
// label/goto form) and differs at the two points where the surface differs:
//
//   * a *suspension* (`val x = f(args)` / `x = f(args)` / `f(args)` / `return f(args)`, with `f`
//     suspend): create the child task, suspend on it, and read its `result` at the resume label;
//   * `return v`: store the result and complete the task.
//
// There is no fast path: every suspension is a heap task the loop runs, and the caller always steps
// aside (a `ValueTask`-style shortcut is a future optimization). The protocol uses the RTL's free
// functions (`tasksBranch`, `tasksSuspendAt`, `tasksFinish`, `tasksReleaseHandle`) plus a per-callee
// pair the emitter generates (`<f>_smNew`, `<f>_smResult`), so nothing here names a task type.

package linear
import compiler

import common

// One field of the task class. `name` is the *source* name the body used (a parameter, a local, or
// the lowering's own handle name); `member` is the C++ member it is emitted as, derived from the
// field's id (`_sm_f<id>`). Naming own fields by id is what removes the reserved-name list: a
// source name can never collide with the runtime's own `Task` members.
data class TskField(
    var name: Str,

    var member: Str,
    var typeNode: AstXmlNode
)

data class TskTask(
    var fields: List<TskField>,

    var body: List<AstXmlNode>,
// The frame the expression pass proved for the body (`semInferTypes`), filled by the emitter
// before the body is written - a machine's methods carry theirs the same way.
    var inferred: Dictionary<Str, AstXmlNode>,
    var error: Str
) {
    // The member a field is emitted as (see `TskField`: by id, never the source name), by the
    // field's source name (the factory fills parameters, the accessor and `main` read `result`).
    fun memberOf(name: Str): Str {
        for (*field in this.fields) {
            if (field.name == name) {
                return field.member
            }
        }
        return ""
    }
}

// The C++ member of the task's `id`-th own field.
fun tskFieldMember(id: Int): Str {
    return "_sm_f" + id.toString()
}

fun tskHandleField(at: Int): Str {
    return "_sm_task" + at.toString()
}

fun tskLabel(branch: Int): Str {
    return "LT" + branch.toString()
}

fun tskEndLabel(): Str {
    return "LTend"
}

// A declaration the lowering passes through for one of its own temporaries (`_sm_expr<n>`): the
// one statement kind that has to sit in the body's outermost scope (`method`).
fun tskIsSlotDecl(stmt: *AstXmlNode): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtVarDecl) {
        return false
    }
    return linIsSlotName(xmlAttr(stmt, AstNodeAttributeKind.Name))
}

// A `return;` - the completion a task arms with `tasksFinish()`.
fun tskVoidReturn(): AstXmlNode {
    return yldStmt(AstNodeCategory.StmtReturn)
}

// The statements inside a body or an arm: a block's (or an `if`-arm's) children are the
// *container's* `Stmt` children, one level below the node itself (`Parser.container`, `linBlock`).
// Reading the container instead would hand the walker a node with no category at all, which is
// what the IL turns into an `Unsupported`.
fun tskContainerStmts(node: *AstXmlNode, role: AstNodeKind): List<AstXmlNode> {
    return xmlChildren(xmlChildPtr(node, role), AstNodeKind.Stmt)
}

fun tskCallNamed(name: Str, args: *List<AstXmlNode>): AstXmlNode {
    return yldCall(linName(AstNodeKind.Callee, name, 0, 0), args)
}

// The `branch` the loop re-entered this task at: a *field* of the runtime's `Task`, read exactly
// as a machine's `advance()` reads its own. A call would be hoisted into a temporary by the
// expression lowering, which is what made a resume inside a loop miscompile.
fun tskBranchCall(): AstXmlNode {
    return yldThisMember("branch")
}

// The `T` a suspend call's callee answers, or an empty node for `Unit`; "" when the name is not
// an async callee at all. The table is the coloring pass's, keyed by name.
fun tskCalleeReturn(asyncNames: *Dictionary<Str, AstXmlNode>, name: Str): AstXmlNode {
    if (!asyncNames.has(name)) {
        return xmlEmptyNode()
    }
    return *asyncNames.getPtr(name)
}

fun tskIsAsyncName(asyncNames: *Dictionary<Str, AstXmlNode>, name: Str): Bool {
    return asyncNames.has(name)
}

// The name a call's callee spells, or "" when it is not a plain name (a member/indirect call is
// not a suspension this lowering can express).
fun tskCallName(call: *AstXmlNode): Str {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprName) {
        return ""
    }
    return xmlAttr(callee, AstNodeAttributeKind.Name)
}

// Whether an expression is a suspension: a call whose callee is an async name.
fun tskIsSuspension(node: *AstXmlNode, asyncNames: *Dictionary<Str, AstXmlNode>): Bool {
    if (xmlKind(node) != AstNodeCategory.ExprCall) {
        return false
    }
    val name: Str = tskCallName(node)
    if (name == "") {
        return false
    }
    return tskIsAsyncName(asyncNames, name)
}

data class TskMachinery(
    var decl: *AstXmlNode,
    var returnType: AstXmlNode,
    var hasValue: Bool,
    var asyncNames: *Dictionary<Str, AstXmlNode>,
    var fieldTypes: Dictionary<Str, AstXmlNode>,
    var fieldIds: Dictionary<Str, Int>,
    var fieldOrder: List<Str>,
    var nextField: Int,
    var suspensions: Int,
    var error: Str
) {

    fun fail(message: Str): Unit {
        if (this.error.isEmpty()) {
            this.error = message
        }
    }

    fun addField(name: Str, typeNode: AstXmlNode): Unit {
        if (this.fieldTypes.has(name)) {
            return
        }
        this.fieldTypes.insert(name, typeNode)
        this.fieldIds.insert(name, this.nextField)
        this.nextField = this.nextField + 1
        this.fieldOrder.append(name)
    }

    // The C++ member a field is emitted as: the id, never the source name (see `TskField`).
    fun member(name: Str): Str {
        if (!this.fieldIds.has(name)) {
            return ""
        }
        return tskFieldMember(*this.fieldIds.getPtr(name))
    }

    fun run(body: *List<AstXmlNode>): TskTask {
        val task: TskTask = TskTask(List<TskField>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>(), "")
        this.collectFields(body)
        if (!this.error.isEmpty()) {
            task.error = this.error
            return task
        }
        task.body = this.method(body)
        if (!this.error.isEmpty()) {
            task.error = this.error
            return task
        }
        var f: Int = 0
        while (f < this.fieldOrder.size()) {
            if (!this.fieldTypes.has(this.fieldOrder[f])) {
                val fieldOrderText: Str = this.fieldOrder[f]
                task.error = `internal: task field '@fieldOrderText' has no type`
                return task
            }
            val fieldType: *AstXmlNode = this.fieldTypes.getPtr(this.fieldOrder[f])
            task.fields.append(TskField(this.fieldOrder[f], this.member(this.fieldOrder[f]), *fieldType))
            f = f + 1
        }
        return task
    }

    // The class's own fields: the parameters and every local (all of them, since a suspension can
    // happen anywhere), plus `result` when the callee answers a value. The child handles are added
    // as the suspensions are met.
    fun collectFields(body: List<AstXmlNode>): Unit {
        if (this.hasValue) {
            this.addField("result", this.returnType)
        }
        val receiver: *AstXmlNode = xmlChildPtr(this.decl, AstNodeKind.Receiver)
        if (!xmlIsEmpty(receiver)) {
            this.fail("suspend: an extension function's receiver is not supported yet")
            return
        }
        val params: List<AstXmlNode> = xmlChildren(this.decl, AstNodeKind.Param)
        for (*param in params) {
            val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            if (name == "this") {
                this.fail("suspend: a `this` parameter is not supported yet")
                return
            }
            val typeNode: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
            if (xmlIsEmpty(typeNode)) {
                this.fail(`suspend: the parameter '@name' has no type`)
                return
            }
            this.addField(name, *typeNode)
        }
        this.collectLocals(body, 0)
    }

    // A guard on the walk's own depth. The statement tree is a tree, so this can only fire if
    // something re-parented a node into its own descendants - which used to be a silent stack
    // overflow in the compiler. Failing the compile loudly is the assert `impl_specs/async.md`
    // asks for: unsafe input should be an error we can read, not a crash.
    fun collectLocals(body: List<AstXmlNode>, depth: Int): Unit {
        if (depth > 128) {
            this.fail("suspend: the body nests deeper than the lowering allows (a cycle in the AST?)")
            return
        }
        for (*stmt in body) {
            if (!this.error.isEmpty()) {
                return
            }
            if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
                val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                // The lowering's own temporaries (`_sm_expr<n>`) stay *locals*: the extractor
                // reuses those names (`MergeLocals`), so turning one into a field would merge two
                // unrelated values. A suspension binds a field of its own and copies into the
                // local at the resume label, which keeps the reuse safe.
                if (!linIsSlotName(name)) {
                    val typeNode: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
                    if (xmlIsEmpty(typeNode)) {
                        if (name.startsWith("_sm_for")) {
                            this.fail("suspend: a `for` over a machine cannot cross a suspension; collect the values into a `List` first")
                            return
                        }
                        this.fail(`suspend: the local '@name' has no type to make a field of`)
                        return
                    }
                    this.addField(name, *typeNode)
                }
                // A slot the extractor made stays a local here; one a suspension *binds* becomes a
                // field when that statement is lowered.
            }
            this.collectLocals(tskContainerStmts(stmt, AstNodeKind.Body), depth + 1)
            this.collectLocals(tskContainerStmts(stmt, AstNodeKind.Then), depth + 1)
            this.collectLocals(tskContainerStmts(stmt, AstNodeKind.Else), depth + 1)
        }
    }

    // The `run()` body: the dispatcher, the body itself, and the end label's completion. Every
    // re-entry lands on the resume label the dispatcher jumps to.
    fun method(body: List<AstXmlNode>): List<AstXmlNode> {
        var rewritten: List<AstXmlNode> = List<AstXmlNode>()
        for (*stmt in body) {
            this.statements(stmt, *rewritten)
        }
        // The lowering's own temporaries (`_sm_expr<n>`) stay locals, and their declarations are what
        // the body carries. A jump to a resume label crosses the region they would sit in, so the
        // emitter - which scopes a declaration a jump crosses in a block of its own - would put them
        // inside a block the resume label, and every statement after it, escapes; the C++ then reads
        // a slot out of scope. Hoisting them above the dispatcher keeps them in the body's outermost
        // scope, which the whole lowered body - the dispatcher's targets included - can reach.
        var slots: List<AstXmlNode> = List<AstXmlNode>()
        var rest: List<AstXmlNode> = List<AstXmlNode>()
        for (*stmt in rewritten) {
            if (tskIsSlotDecl(stmt)) {
                slots.append(*stmt)
            } else {
                rest.append(*stmt)
            }
        }
        var out: List<AstXmlNode> = List<AstXmlNode>()
        for (*decl in slots) {
            out.append(*decl)
        }
        out.append(
            yldJumpWhen(yldBinary("==", tskBranchCall(), yldIntLiteral(-1)), tskEndLabel())
        )
        var branch: Int = 1
        while (branch <= this.suspensions) {
            out.append(
                yldJumpWhen(yldBinary("==", tskBranchCall(), yldIntLiteral(branch)), tskLabel(branch))
            )
            branch = branch + 1
        }
        for (*stmt in rest) {
            out.append(*stmt)
        }
        out.append(linLabel(tskEndLabel(), 0, 0))
        out.append(yldExprStmt(tskCallNamed("tasksFinish", List<AstXmlNode>())))
        out.append(tskVoidReturn())
        return out
    }

    // The completion a `return` (or the end of a unit body) arms: `tasksFinish()` then out.
    fun completion(out: *List<AstXmlNode>): Unit {
        out.append(yldExprStmt(tskCallNamed("tasksFinish", List<AstXmlNode>())))
        out.append(tskVoidReturn())
    }

    // `h = <f>_smNew(args); tasksSuspendAt(h, k); return; LTk:; <target> = <f>_smResult(h);
    // tasksReleaseHandle(h); h = null;`
    //
    // The target is a *field* of the task: the value is written after the resume label, so
    // whatever holds it has to live across the suspension - a field does, a local would not (the
    // resume label sits outside the block the extractor wrapped the call in). `bindName` is the
    // target's own name when it is a plain local or slot, and `bindPlace` an already-rewritten
    // place otherwise (`this.result` for a `return`).
    fun suspension(
        call: *AstXmlNode, bindName: Str, bindPlace: AstXmlNode, hasBind: Bool, out: *List<AstXmlNode>
    ): Unit {
        val calleeName: Str = tskCallName(call)
        if (calleeName == "") {
            this.fail("suspend: a suspension's callee must be a plain function name")
            return
        }
        this.suspensions = this.suspensions + 1
        val at: Int = this.suspensions
        val handleName: Str = tskHandleField(at)
        // The child handle is a `RawPtr` (`void*`): the task type is not a name this lowering
        // knows, and `*Unit` is exactly the opaque pointer the RTL protocol takes.
        this.addField(handleName, ilPointerNode(yldNamedType("Unit")))
        val handle: Str = this.member(handleName)
        val calleeReturn: AstXmlNode = tskCalleeReturn(this.asyncNames, calleeName)
        val hasResult: Bool = !xmlIsEmpty(calleeReturn)
        var target: AstXmlNode = bindPlace
        if (hasBind && bindName != "") {
            // The callee's answer is what the name holds, which is the type the field takes.
            this.addField(bindName, calleeReturn)
            target = yldThisMember(this.member(bindName))
        }
        // `h = <f>_smNew(args)`: the child task, its parameters filled, parked on the loop by the
        // `tasksSuspendAt` that follows.
        var args: List<AstXmlNode> = List<AstXmlNode>()
        for (*arg in xmlChildren(call, AstNodeKind.Arg)) {
            args.append(this.expr(arg))
        }
        val factoryCall: AstXmlNode = yldCall(linName(AstNodeKind.Callee, calleeName + "Task", 0, 0), args)
        out.append(yldAssign(yldThisMember(handle), factoryCall))
        out.append(
            yldExprStmt(
                tskCallNamed(
                    "tasksSuspendAt",
                    listOf<AstXmlNode>(yldThisMember(handle), yldIntLiteral(at))
                )
            )
        )
        out.append(tskVoidReturn())
        out.append(linLabel(tskLabel(at), 0, 0))
        if (hasResult && hasBind) {
            out.append(
                yldAssign(
                    target,
                    yldCall(
                        linName(AstNodeKind.Callee, calleeName + "_smResult", 0, 0),
                        listOf<AstXmlNode>(yldThisMember(handle))
                    )
                )
            )
        }
        out.append(
            yldExprStmt(
                tskCallNamed(
                    "tasksReleaseHandle",
                    listOf<AstXmlNode>(yldThisMember(handle))
                )
            )
        )
        out.append(
            yldAssign(yldThisMember(handle), yldExpr(AstNodeCategory.ExprNullLit))
        )
    }

    fun statements(stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
        if (!this.error.isEmpty()) {
            return
        }
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtVarDecl -> {
                val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
                if (!xmlIsEmpty(init) && tskIsSuspension(init, this.asyncNames)) {
                    this.suspension(init, name, xmlEmptyNode(), true, out)
                    return
                }
                // A user field: its declaration emits nothing, and its initializer (when there is
                // one) runs where it was written.
                if (this.fieldTypes.has(name)) {
                    if (!xmlIsEmpty(init)) {
                        out.append(yldAssign(yldThisMember(this.member(name)), this.expr(init)))
                    }
                    return
                }
                // A slot: the lowering's own temporary, which stays a local.
                if (linIsSlotName(name)) {
                    var children: List<AstXmlNode> = List<AstXmlNode>()
                    val declared: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
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
                return
            }

            AstNodeCategory.StmtAssign -> {
                val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                if (tskIsSuspension(value, this.asyncNames)) {
                    val targetNode: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
                    if (xmlKind(targetNode) == AstNodeCategory.ExprName) {
                        this.suspension(
                            value, xmlAttr(targetNode, AstNodeAttributeKind.Name),
                            xmlEmptyNode(), true, out
                        )
                    } else {
                        this.suspension(value, "", this.expr(targetNode), true, out)
                    }
                    return
                }
                var children: List<AstXmlNode> = List<AstXmlNode>()
                var target: AstXmlNode = this.expr(xmlChildPtr(stmt, AstNodeKind.Target))
                target.name = AstNodeKind.Target
                children.append(target)
                var valueChild: AstXmlNode = this.expr(value)
                valueChild.name = AstNodeKind.Value
                children.append(valueChild)
                out.append(yldWithChildren(stmt, children))
                return
            }

            AstNodeCategory.StmtExprStmt -> {
                val inner: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Expr)
                if (tskIsSuspension(inner, this.asyncNames)) {
                    this.suspension(inner, "", xmlEmptyNode(), false, out)
                    return
                }
                var children: List<AstXmlNode> = List<AstXmlNode>()
                var exprChild: AstXmlNode = this.expr(inner)
                exprChild.name = AstNodeKind.Expr
                children.append(exprChild)
                out.append(yldWithChildren(stmt, children))
                return
            }

            AstNodeCategory.StmtReturn -> {
                val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
                if (xmlIsEmpty(value)) {
                    this.completion(out)
                    return
                }
                if (tskIsSuspension(value, this.asyncNames)) {
                    if (!this.hasValue) {
                        this.fail("suspend: a `return` of a suspension in a function that answers nothing")
                        return
                    }
                    this.suspension(value, "", yldThisMember(this.member("result")), true, out)
                    this.completion(out)
                    return
                }
                if (this.hasValue) {
                    out.append(yldAssign(yldThisMember(this.member("result")), this.expr(value)))
                } else {
                    // A value the signature does not carry is still evaluated, so nothing
                    // silently disappears.
                    out.append(yldExprStmt(this.expr(value)))
                }
                this.completion(out)
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
                val children: List<AstXmlNode> = tskContainerStmts(stmt, AstNodeKind.Body)
                for (*child in children) {
                    this.statements(child, inner)
                }
                out.append(linBlock(inner, xmlLine(stmt), xmlColumn(stmt)))
                return
            }
        }
        out.append(stmt)
    }

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
                return yldThisMember("")
            }
            if (this.fieldTypes.has(name)) {
                return yldThisMember(this.member(name))
            }
            return node
        }
        var copyNode: AstXmlNode = node
        var rebuilt: AstXmlNode = AstXmlNode(
            copyNode.name, copyNode.kind, copyNode.attributes,
            Array<AstXmlNode>()
        )
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

// The rewrite: one suspending body into the task that runs it.
fun linLowerAsync(
    decl: *AstXmlNode, returnType: AstXmlNode, hasValue: Bool, asyncNames: *Dictionary<Str, AstXmlNode>,
    linearBody: List<AstXmlNode>
): TskTask {
    var machinery: TskMachinery = TskMachinery(
        decl, returnType, hasValue, asyncNames,
        Dictionary<Str, AstXmlNode>(), Dictionary<Str, Int>(), List<Str>(), 0, 0, ""
    )
    return machinery.run(*linearBody)
}
