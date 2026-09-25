// Propagate.kt
//
// `x!!` is the success payload of a `Res<T>`, or the failure of the enclosing function,
// propagated out of it. A rewrite, not a semantic feature - the `for` precedent
// (impl_specs/for.md): `Parser.kt`'s parsePostfix records the operator and this pass expands
// it *before sema sees the tree*, so no later stage has an operator to know about.
//
//     fun test(): Res<Int> {                      fun test(): Res<Int> {
//         val data: Res<Str> = readFile(name)         val _sm_prop1 = readFile(name)
//         val text: Str = data!!                      if (!_sm_prop1.isOk()) {
//                                                         return Res<Int>.err(_sm_prop1.error)
//                                                     }
//                                                     val text: Str = _sm_prop1.value
//
// `Res`'s error arm is always a `Str` (specs/core-types.md), so a differing return type has
// nothing to remap on the failure side: the message is carried across as it is, and no
// `errorAs` wrapper is needed. When the operand's declared type *is* the enclosing return
// type, the failure path returns the operand itself (`return _sm_prop1`) - a move of the
// union instead of a rebuilt one - which is why the pass reads local and parameter type
// nodes first.
//
// The operand is evaluated once, into a temp; the payload read and the failure test both read
// it. Positions the expansion cannot express (a `!!` inside a larger expression, or inside a
// lambda body, whose return type the pass does not know) are diagnosed rather than left to
// the C++ compiler.

package parser

import common

// One function body's expansion state: the temp counter and the declarations the identity
// test consults. A name declared twice in one function is ambiguous (a shadow could name a
// different `Res`), so it never takes the identity path.
data class PropState(
    var next: Int,

    var locals: Dictionary<Str, AstXmlNode>,
    var ambiguous: List<Str>,
    var returnInner: AstXmlNode,
    var error: Str,
    var errorLine: Int,
    var errorColumn: Int
)

fun propAttrs(line: Int, column: Int): List<AstNodeAttribute> {
    return listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
    )
}

fun propExpr(kind: AstNodeCategory, line: Int, column: Int): AstXmlNode {
    return AstXmlNode(AstNodeKind.Expr, kind, propAttrs(line, column), Array<AstXmlNode>())
}

// A name in `role`: the temp read as a value, or as the base of a member.
fun propName(role: AstNodeKind, name: Str, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = propAttrs(line, column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return AstXmlNode(role, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>())
}

fun propMember(base: AstXmlNode, member: Str, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = propAttrs(line, column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, member))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, attrs, Array<AstXmlNode>())
    var receiver: AstXmlNode = base
    receiver.name = AstNodeKind.Receiver
    xmlAddChild(node, receiver)
    return node
}

fun propCall0(callee: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = propExpr(AstNodeCategory.ExprCall, line, column)
    var target: AstXmlNode = callee
    target.name = AstNodeKind.Callee
    xmlAddChild(node, target)
    return node
}

fun propCall1(callee: AstXmlNode, arg: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = propExpr(AstNodeCategory.ExprCall, line, column)
    var target: AstXmlNode = callee
    target.name = AstNodeKind.Callee
    xmlAddChild(node, target)
    var value: AstXmlNode = arg
    value.name = AstNodeKind.Arg
    xmlAddChild(node, value)
    return node
}

// `Res<inner>` as the head of a static call: what `Res<T2>.err(...)` is written against.
fun propGenericName(name: Str, arg: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = propAttrs(line, column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprGenericName, attrs, Array<AstXmlNode>())
    var typeArg: AstXmlNode = arg
    typeArg.name = AstNodeKind.TypeArg
    xmlAddChild(node, typeArg)
    return node
}

// `!<temp>.isOk()`: the failure test. `Res` has no `isError`, and its state is the union's
// tag rather than the message (an empty message is a failure, specs/core-types.md), so
// `isOk` is the test.
fun propNotOk(temp: Str, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = propAttrs(line, column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, "!"))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, attrs, Array<AstXmlNode>())
    var receiver: AstXmlNode = propName(AstNodeKind.Receiver, temp, line, column)
    var inner: AstXmlNode = propCall0(propMember(receiver, "isOk", line, column), line, column)
    inner.name = AstNodeKind.Operand
    xmlAddChild(node, inner)
    return node
}

// The temp the operand is evaluated into: untyped, so the type pass spells it (or leaves it
// an `auto`).
fun propTempDecl(name: Str, init: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = propAttrs(line, column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, "false"))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtVarDecl, attrs, Array<AstXmlNode>())
    var value: AstXmlNode = init
    value.name = AstNodeKind.Init
    xmlAddChild(node, value)
    return node
}

fun propReturn(value: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Stmt, AstNodeCategory.StmtReturn,
        propAttrs(line, column), Array<AstXmlNode>()
    )
    var inner: AstXmlNode = value
    inner.name = AstNodeKind.Value
    xmlAddChild(node, inner)
    return node
}

// `if (<cond>) { <ret> }`: the expansion's whole control flow, at the level of the statement
// it replaces.
fun propIfReturn(cond: AstXmlNode, ret: AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Stmt, AstNodeCategory.StmtIf,
        propAttrs(line, column), Array<AstXmlNode>()
    )
    var test: AstXmlNode = cond
    test.name = AstNodeKind.Cond
    xmlAddChild(node, test)
    var body: List<AstXmlNode> = List<AstXmlNode>()
    body.append(ret)
    xmlAddChild(
        node,
        AstXmlNode(AstNodeKind.Then, AstNodeCategory.None, List<AstNodeAttribute>(), body.toArray())
    )
    return node
}

// The statement with one child role replaced (the old child dropped): what puts the payload
// read where the operand was.
fun propReplaceChild(like: *AstXmlNode, role: AstNodeKind, replacement: AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(like.name, like.kind, like.attributes, Array<AstXmlNode>())
    for (*child in like.Children) {
        if (child.name == role) {
            continue
        }
        var kept: AstXmlNode = child
        xmlAddChild(node, kept)
    }
    var added: AstXmlNode = replacement
    added.name = role
    xmlAddChild(node, added)
    return node
}

// The first `!!` at this body's *own* level, or an empty node: a diagnostic needs its position.
// A lambda body is a place of its own - its `!!` propagates into the lambda's result, not this
// body's - so the walk stops there (propRewriteLambdas owns those).
fun propFind(node: AstXmlNode): AstXmlNode {
    if (xmlKind(node) == AstNodeCategory.ExprPropagate) {
        return node
    }
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return xmlEmptyNode()
    }
    for (*child in node.Children) {
        val found: AstXmlNode = propFind(child)
        if (!xmlIsEmpty(found)) {
            return found
        }
    }
    return xmlEmptyNode()
}

// Structural equality of two type nodes, over the shapes a `Res` payload can have. A name
// that is a `typealias` is not expanded, so an alias never takes the identity path - the
// conservative direction.
fun propTypeEqual(a: AstXmlNode, b: AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(a)
    if (kind != xmlKind(b)) {
        return false
    }
    if (kind == AstNodeCategory.TypeNamed) {
        return xmlAttr(a, AstNodeAttributeKind.Name) == xmlAttr(b, AstNodeAttributeKind.Name)
    }
    if (kind == AstNodeCategory.TypeGeneric) {
        if (xmlAttr(a, AstNodeAttributeKind.Name) != xmlAttr(b, AstNodeAttributeKind.Name)) {
            return false
        }
        val argsA: List<AstXmlNode> = xmlChildren(a, AstNodeKind.TypeArg)
        val argsB: List<AstXmlNode> = xmlChildren(b, AstNodeKind.TypeArg)
        if (argsA.size() != argsB.size()) {
            return false
        }
        var i: Int = 0
        while (i < argsA.size()) {
            if (!propTypeEqual(argsA[i], argsB[i])) {
                return false
            }
            i = i + 1
        }
        return true
    }
    if (
        kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference
        || kind == AstNodeCategory.TypeYield
    ) {
        return propTypeEqual(xmlChild(a, AstNodeKind.Inner), xmlChild(b, AstNodeKind.Inner))
    }
    return false
}

// The `T` of a `Res<T>` return type, looking through one `Async<...>` wrapper: an async
// function returns `Async<Res<T>>`, and `!!` inside it still propagates a `Res<T>` - the
// `return` carrying the failure is the machine's own completion.
fun propResInner(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = *typeNode
    var guard: Int = 0
    while (guard < 4) {
        guard = guard + 1
        if (xmlKind(current) != AstNodeCategory.TypeGeneric) {
            return xmlEmptyNode()
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val arg: AstXmlNode = xmlChild(current, AstNodeKind.TypeArg)
        if (xmlIsEmpty(arg)) {
            return xmlEmptyNode()
        }
        if (name == "Res") {
            return arg
        }
        if (name != "Async") {
            return xmlEmptyNode()
        }
        current = arg
    }
    return xmlEmptyNode()
}

// Every typed local of a function body, by name.
fun propCollectLocals(state: *PropState, stmts: *List<AstXmlNode>): Unit {
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            val declared: AstXmlNode = xmlChild(stmt, AstNodeKind.Type)
            if (!xmlIsEmpty(declared)) {
                if (state.locals.has(name)) {
                    state.ambiguous.append(name)
                }
                state.locals.insert(name, declared)
            }
        }
        propCollectLocals(state, xmlChildren(stmt, AstNodeKind.Body))
        propCollectLocals(state, xmlChildren(stmt, AstNodeKind.Then))
        propCollectLocals(state, xmlChildren(stmt, AstNodeKind.Else))
    }
}

fun propCollectParams(state: *PropState, decl: *AstXmlNode): Unit {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in params) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        val declared: AstXmlNode = xmlChild(param, AstNodeKind.Type)
        if (xmlIsEmpty(declared) || name == "this") {
            continue
        }
        if (state.locals.has(name)) {
            state.ambiguous.append(name)
        }
        state.locals.insert(name, declared)
    }
}

// The failure path: the operand itself when its declared type is what the function returns (a
// move of the union), otherwise a rebuilt `Res<returnInner>` carrying the same message.
fun propFailure(state: *PropState, temp: Str, operand: *AstXmlNode, line: Int, column: Int): AstXmlNode {
    if (xmlKind(operand) == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(operand, AstNodeAttributeKind.Name)
        if (!state.ambiguous.contains(name)) {
            val declared: *AstXmlNode = state.locals.getPtr(name)
            if (declared != null && propTypeEqual(propResInner(declared), state.returnInner)) {
                return propReturn(propName(AstNodeKind.Expr, temp, line, column), line, column)
            }
        }
    }
    val head: AstXmlNode = propGenericName("Res", state.returnInner, line, column)
    val read: AstXmlNode = propMember(propName(AstNodeKind.Receiver, temp, line, column), "error", line, column)
    return propReturn(propCall1(propMember(head, "err", line, column), read, line, column), line, column)
}

// One statement expanded in place: the temp, the failure test, and the payload read - where
// the statement carried the `!!`. Answers false when the statement is not a position the
// expansion can express.
//
// `return x!!` is deliberately not a position: `!!` yields the *payload*, so returning it
// would return a `T` where the function answered a `Res` - the failure path is the only part
// of `!!` that a `return` has a use for, and `return x` (without the operator) is how that is
// spelled.
fun propExpand(state: *PropState, stmt: *AstXmlNode, out: *List<AstXmlNode>): Bool {
    val kind: AstNodeCategory = xmlKind(stmt)
    var valueRole: AstNodeKind = AstNodeKind.None
    if (kind == AstNodeCategory.StmtVarDecl) {
        valueRole = AstNodeKind.Init
    } else if (kind == AstNodeCategory.StmtAssign) {
        valueRole = AstNodeKind.Value
    } else if (kind == AstNodeCategory.StmtExprStmt) {
        valueRole = AstNodeKind.Expr
    } else {
        return false
    }
    val holder: *AstXmlNode = xmlChildPtr(stmt, valueRole)
    if (xmlKind(holder) != AstNodeCategory.ExprPropagate) {
        return false
    }
    val operand: *AstXmlNode = xmlChildPtr(holder, AstNodeKind.Operand)
    if (xmlIsEmpty(operand)) {
        return false
    }
    val line: Int = xmlLine(holder)
    val column: Int = xmlColumn(holder)
    val temp: Str = fmtStr("_sm_prop|", state.next.toString())
    state.next = state.next + 1

    // The operand first: one evaluation, and both later uses read the temp.
    out.append(propTempDecl(temp, operand, line, column))
    val test: AstXmlNode = propNotOk(temp, line, column)
    val failure: AstXmlNode = propFailure(state, temp, operand, line, column)
    out.append(propIfReturn(test, failure, line, column))

    val payload: AstXmlNode = propMember(propName(AstNodeKind.Receiver, temp, line, column), "value", line, column)
    if (kind == AstNodeCategory.StmtExprStmt) {
        var node: AstXmlNode = AstXmlNode(
            AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt,
            stmt.attributes, Array<AstXmlNode>()
        )
        var inner: AstXmlNode = payload
        inner.name = AstNodeKind.Expr
        xmlAddChild(node, inner)
        out.append(node)
        return true
    }
    out.append(propReplaceChild(stmt, valueRole, payload))
    return true
}

// One list of expanded children in `target`, under `role`.
fun propAppendList(target: *AstXmlNode, role: AstNodeKind, kind: AstNodeCategory, items: *List<AstXmlNode>): Unit {
    if (items.size() == 0) {
        return
    }
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*item in items) {
        var moved: AstXmlNode = item
        kids.append(moved)
    }
    xmlAddChild(target, AstXmlNode(role, kind, List<AstNodeAttribute>(), kids.toArray()))
}

// The structured statement with its regions replaced by their expanded selves; every other
// child (a condition, a `for`'s parts) is kept as it was.
fun propRebuild(
    stmt: *AstXmlNode, thenOut: *List<AstXmlNode>,
    elseOut: *List<AstXmlNode>, bodyOut: *List<AstXmlNode>
): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(stmt.name, stmt.kind, stmt.attributes, Array<AstXmlNode>())
    for (*child in stmt.Children) {
        if (child.name == AstNodeKind.Then) {
            propAppendList(node, AstNodeKind.Then, child.kind, thenOut)
        } else if (child.name == AstNodeKind.Else) {
            propAppendList(node, AstNodeKind.Else, child.kind, elseOut)
        } else if (child.name == AstNodeKind.Body) {
            propAppendList(node, AstNodeKind.Body, child.kind, bodyOut)
        } else {
            var kept: AstXmlNode = child
            xmlAddChild(node, kept)
        }
    }
    return node
}

fun propStmts(state: *PropState, stmts: *List<AstXmlNode>, out: *List<AstXmlNode>): Unit {
    for (*stmt in stmts) {
        if (!state.error.isEmpty()) {
            return
        }
        propStmt(state, stmt, out)
    }
}

fun propStmt(state: *PropState, stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
    val kind: AstNodeCategory = xmlKind(stmt)
    if (kind == AstNodeCategory.StmtBlock) {
        var inner: List<AstXmlNode> = List<AstXmlNode>()
        propStmts(state, xmlChildren(stmt, AstNodeKind.Body), inner)
        out.append(propRebuild(stmt, List<AstXmlNode>(), List<AstXmlNode>(), inner))
        return
    }
    if (kind == AstNodeCategory.StmtIf || kind == AstNodeCategory.StmtWhile) {
        val inCond: AstXmlNode = propFind(xmlChild(stmt, AstNodeKind.Cond))
        if (!xmlIsEmpty(inCond)) {
            state.error = "`!!` is not allowed inside a condition"
            state.errorLine = xmlLine(inCond)
            state.errorColumn = xmlColumn(inCond)
            return
        }
        var thenOut: List<AstXmlNode> = List<AstXmlNode>()
        propStmts(state, xmlChildren(stmt, AstNodeKind.Then), thenOut)
        var elseOut: List<AstXmlNode> = List<AstXmlNode>()
        propStmts(state, xmlChildren(stmt, AstNodeKind.Else), elseOut)
        var bodyOut: List<AstXmlNode> = List<AstXmlNode>()
        propStmts(state, xmlChildren(stmt, AstNodeKind.Body), bodyOut)
        out.append(propRebuild(stmt, thenOut, elseOut, bodyOut))
        return
    }
    if (propExpand(state, stmt, out)) {
        return
    }
    val found: AstXmlNode = propFind(stmt)
    if (!xmlIsEmpty(found)) {
        state.error = "`!!` must be the whole right-hand side of a `val`/`var`, an assignment, "
        state.error.appendStr("or a statement of its own")
        state.errorLine = xmlLine(found)
        state.errorColumn = xmlColumn(found)
    }
    out.append(stmt)
}

// The declaration a call's name reaches, by name - the approximation the coloring pass uses too
// (cppsrc/sema/Async.kt). A name two declarations share resolves to the first, which costs the
// identity path at worst and never correctness: the remap form is always right.
fun propFindFunction(declarations: *List<AstXmlNode>, name: Str): AstXmlNode {
    for (*decl in declarations) {
        if (xmlAttr(decl, AstNodeAttributeKind.Name) == name) {
            return decl
        }
    }
    return xmlEmptyNode()
}

// A lambda argument's target: the result type of the parameter it is passed to. A lambda has no
// declared return type, so *this* - not the enclosing function's - is what a `!!` inside it
// propagates into.
fun propLambdaTarget(call: *AstXmlNode, argIndex: Int, declarations: *List<AstXmlNode>): AstXmlNode {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (name == "") {
        return xmlEmptyNode()
    }
    val decl: AstXmlNode = propFindFunction(declarations, name)
    if (xmlIsEmpty(decl)) {
        return xmlEmptyNode()
    }
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (argIndex >= params.size()) {
        return xmlEmptyNode()
    }
    val paramType: AstXmlNode = xmlChild(params[argIndex], AstNodeKind.Type)
    if (xmlKind(paramType) != AstNodeCategory.TypeFunction) {
        return xmlEmptyNode()
    }
    return xmlChild(paramType, AstNodeKind.ReturnType)
}

// One lambda body rewritten in place, with the result type its parameter names as the target.
// `propFind` stops at a nested lambda, so a lambda inside a lambda is the recursion's business.
fun propRewriteLambdaBody(lambda: *AstXmlNode, target: AstXmlNode, fileName: *Str): Str {
    val body: *AstXmlNode = xmlChildPtr(lambda, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return ""
    }
    val found: AstXmlNode = propFind(body)
    if (xmlIsEmpty(found)) {
        return ""
    }
    var state: PropState = PropState(
        1, dictionaryOf<Str, AstXmlNode>(), List<Str>(), target, "", 0, 0
    )
    // A lambda's parameters are read from the `Params` attribute - names, no types - so only the
    // body's own declared locals can serve the identity path; the rest take the remap path.
    propCollectLocals(state, xmlChildren(body, AstNodeKind.Stmt))
    var out: List<AstXmlNode> = List<AstXmlNode>()
    propStmts(state, xmlChildren(body, AstNodeKind.Stmt), out)
    if (!state.error.isEmpty()) {
        return fmtStr(
            "|: |:|: |", fileName, state.errorLine.toString(),
            state.errorColumn.toString(), state.error
        )
    }
    body.Children = out.toArray()
    return ""
}

// Every lambda argument of one call, in argument order.
fun propCallLambdas(call: *AstXmlNode, fileName: *Str, declarations: *List<AstXmlNode>): Str {
    var index: Int = 0
    for (*arg in call.Children) {
        if (arg.name != AstNodeKind.Arg) {
            continue
        }
        val position: Int = index
        index = index + 1
        if (xmlKind(arg) != AstNodeCategory.ExprLambda) {
            continue
        }
        val target: AstXmlNode = propLambdaTarget(call, position, declarations)
        val inner: AstXmlNode = propResInner(target)
        if (xmlIsEmpty(inner)) {
            // A lambda the pass cannot place: only a `!!` inside it needs reporting, so a lambda
            // that does not propagate is left alone and the C++ compiler keeps the last word.
            val body: AstXmlNode = xmlChild(arg, AstNodeKind.Body)
            val found: AstXmlNode = propFind(body)
            if (xmlIsEmpty(found)) {
                continue
            }
            return fmtStr(
                "|: |:|: |", fileName, xmlLine(found).toString(), xmlColumn(found).toString(),
                "`!!` inside a lambda propagates into the lambda's own result, and this parameter's type has none"
            )
        }
        val error: Str = propRewriteLambdaBody(arg, inner, fileName)
        if (error != "") {
            return error
        }
    }
    return ""
}

// Every lambda argument anywhere below `node`, each rewritten with its own target.
fun propRewriteLambdas(node: *AstXmlNode, fileName: *Str, declarations: *List<AstXmlNode>): Str {
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val error: Str = propCallLambdas(node, fileName, declarations)
        if (error != "") {
            return error
        }
    }
    for (*child in node.Children) {
        val error: Str = propRewriteLambdas(child, fileName, declarations)
        if (error != "") {
            return error
        }
    }
    return ""
}

// One function-like body rewritten in place: the module's function, or a data class's method.
// Lambdas go first: their `!!` belongs to *their* result, so a body whose only `!!` is inside a
// lambda need not answer a `Res` itself.
fun propRewriteBody(decl: *AstXmlNode, fileName: *Str, declarations: *List<AstXmlNode>): Str {
    val body: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return ""
    }
    val lambdaError: Str = propRewriteLambdas(body, fileName, declarations)
    if (lambdaError != "") {
        return lambdaError
    }
    val found: AstXmlNode = propFind(body)
    if (xmlIsEmpty(found)) {
        return ""
    }
    val returnType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (xmlIsEmpty(returnType)) {
        return fmtStr(
            "|: |:|: |", fileName, xmlLine(found).toString(), xmlColumn(found).toString(),
            "`!!` propagates a failure, and this function returns nothing to propagate into"
        )
    }
    val inner: AstXmlNode = propResInner(returnType)
    if (xmlIsEmpty(inner)) {
        return fmtStr(
            "|: |:|: |", fileName, xmlLine(found).toString(), xmlColumn(found).toString(),
            "`!!` propagates a failure, so the enclosing function must return `Res<T>`"
        )
    }
    var state: PropState = PropState(
        1, dictionaryOf<Str, AstXmlNode>(), List<Str>(), inner, "", 0, 0
    )
    propCollectParams(state, decl)
    propCollectLocals(state, xmlChildren(body, AstNodeKind.Stmt))
    var out: List<AstXmlNode> = List<AstXmlNode>()
    propStmts(state, xmlChildren(body, AstNodeKind.Stmt), out)
    if (!state.error.isEmpty()) {
        return fmtStr(
            "|: |:|: |", fileName, state.errorLine.toString(),
            state.errorColumn.toString(), state.error
        )
    }
    body.Children = out.toArray()
    return ""
}

fun propRewriteDecl(decl: *AstXmlNode, fileName: *Str, declarations: *List<AstXmlNode>): Str {
    if (decl.name == AstNodeKind.Function) {
        return propRewriteBody(decl, fileName, declarations)
    }
    if (decl.name == AstNodeKind.DataClass) {
        val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
        for (*method in methods) {
            val error: Str = propRewriteBody(method, fileName, declarations)
            if (error != "") {
                return error
            }
        }
    }
    return ""
}

// Every declaration of one parsed module, each function's and each lambda's `!!` expanded. Answers
// "" when the module is clean, or one positioned diagnostic.
fun propRewriteModule(module: *AstXmlNode, fileName: *Str, declarations: *List<AstXmlNode>): Str {
    val decls: List<AstXmlNode> = xmlDecls(module)
    for (*decl in decls) {
        val error: Str = propRewriteDecl(decl, fileName, declarations)
        if (error != "") {
            return error
        }
    }
    return ""
}

// Every function declaration a module contributes, so the pass can resolve a call's parameter
// types by name. The driver collects the prelude's too - a lambda's target is often a prelude
// declaration (`asyncRunTransform`) - and runs the pass once, when all of them are in hand.
fun propCollectDecls(module: *AstXmlNode, out: *List<AstXmlNode>): Unit {
    val top: List<AstXmlNode> = xmlDecls(module)
    for (*decl in top) {
        if (decl.name == AstNodeKind.Function) {
            out.append(decl)
        }
        if (decl.name == AstNodeKind.DataClass) {
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            for (*method in methods) {
                out.append(method)
            }
        }
    }
}
