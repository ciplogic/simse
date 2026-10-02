// ParserStmt.kt
//
// Statements and control flow: blocks, `var`/`if`/`while`, `return`, `yield`, and the
// `return (...)` construction convention. Extension methods on `Parser` (Parser.kt).

package parser
import compiler

import lex
import common


fun Parser.parseBlock(): List<AstXmlNode> {
    var body: List<AstXmlNode> = List<AstXmlNode>()
    // A newline before a `{` where a block is the only thing that can follow is not a
    // statement boundary (Kotlin's rule, and why a Kotlin formatter can be pointed here).
    this.skipNewlines()
    if (!this.expectText("{")) {
        return body
    }
    this.skipSeparators()
    while (!this.checkText("}") && !this.atEnd() && !this.failed) {
        if (!this.parseStmtInto(body)) {
            return body
        }
        this.skipSeparators()
    }
    this.expectText("}")
    return body
}

// One source statement appended to `out`, but a statement may expand to several: `for`
// is a declaration plus its loop, and the declaration must sit outside the loop.
fun Parser.parseStmtInto(out: *List<AstXmlNode>): Bool {
    val text: Str = this.peek(0).text
    when (text) {
        "for" -> {
            return this.parseFor(out)
        }

        "when" -> {
            return this.parseWhen(out)
        }
    }
    val stmt: AstXmlNode = this.parseStmt()
    if (this.failed) {
        return false
    }
    out.append(stmt)
    return true
}

fun Parser.parseStmt(): AstXmlNode {
    val text: Str = this.peek(0).text
    when (text) {
        "val", "var" -> {
            return this.parseVarDecl()
        }

        "if" -> {
            return this.parseIf()
        }

        "while" -> {
            return this.parseWhile()
        }

        "return" -> {
            return this.parseReturn()
        }

        "yield" -> {
            return this.parseYield()
        }

        "break" -> {
            val pos: SourcePos = this.peek(0).pos
            this.advance()
            return AstXmlNode(
                AstNodeKind.Stmt,
                AstNodeCategory.StmtBreak,
                this.posAttrs(pos.line, pos.column),
                Array<AstXmlNode>()
            )
        }

        "continue" -> {
            val pos: SourcePos = this.peek(0).pos
            this.advance()
            return AstXmlNode(
                AstNodeKind.Stmt,
                AstNodeCategory.StmtContinue,
                this.posAttrs(pos.line, pos.column),
                Array<AstXmlNode>()
            )
        }

        "++", "--" -> {
            // A step's value is the assignment's, so a prefix one has nothing to hand back.
            this.fail(fmtStr("`|` stands on its own as a statement (`i|`)", text, text))
            return this.emptyNode()
        }
    }

    val pos: SourcePos = this.peek(0).pos
    val expr: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return this.emptyNode()
    }
    if (isStepOp(this.peek(0).text)) {
        // `i++`/`i--` are the compound assignment with a `1` (specs/memory-model.md);
        // anywhere else a step is the diagnostic above.
        val step: Str = this.advance().text
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, stepAssignOp(step)))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Target, expr.node)
        this.attach(node, AstNodeKind.Value, this.intLiteralAt(1, pos).node)
        return node
    }
    if (isAssignOp(this.peek(0).text)) {
        val op: Str = this.advance().text
        this.skipNewlines()
        val value: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Target, expr.node)
        this.attach(node, AstNodeKind.Value, value.node)
        return node
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Expr, expr.node)
    return node
}

fun Parser.parseVarDecl(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    val isVar: Bool = this.matchText("var")
    if (!isVar) {
        if (!this.expectText("val")) {
            return this.emptyNode()
        }
    }
    val name: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    var typeNode: AstXmlNode = this.emptyNode()
    if (this.matchText(":")) {
        typeNode = this.parseType(AstNodeKind.Type)
        if (this.failed) {
            return this.emptyNode()
        }
    }
    var init: ExprNode = this.emptyExpr()
    if (this.matchText("=")) {
        this.skipNewlines()
        init = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, boolText(isVar)))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtVarDecl, attrs, Array<AstXmlNode>())
    if (typeNode.name != AstNodeKind.None) {
        xmlAddChild(node, typeNode)
    }
    if (init.node.name != AstNodeKind.None) {
        this.attach(node, AstNodeKind.Init, init.node)
    }
    return node
}

fun Parser.parseIf(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    if (!this.expectText("(")) {
        return this.emptyNode()
    }
    val cond: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return this.emptyNode()
    }
    if (!this.expectText(")")) {
        return this.emptyNode()
    }
    val thenBody: List<AstXmlNode> = this.parseBlock()
    if (this.failed) {
        return this.emptyNode()
    }
    var hasElse: Bool = false
    var elseBody: List<AstXmlNode> = List<AstXmlNode>()
    if (this.matchText("else")) {
        hasElse = true
        if (this.checkText("if")) {
            val nested: AstXmlNode = this.parseIf()
            if (this.failed) {
                return this.emptyNode()
            }
            elseBody.append(nested)
        } else {
            elseBody = this.parseBlock()
            if (this.failed) {
                return this.emptyNode()
            }
        }
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtIf, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Cond, cond.node)
    xmlAddChild(node, this.container(AstNodeKind.Then, thenBody))
    if (hasElse) {
        xmlAddChild(node, this.container(AstNodeKind.Else, elseBody))
    }
    return node
}

fun Parser.parseWhile(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    if (!this.expectText("(")) {
        return this.emptyNode()
    }
    val cond: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return this.emptyNode()
    }
    if (!this.expectText(")")) {
        return this.emptyNode()
    }
    val body: List<AstXmlNode> = this.parseBlock()
    if (this.failed) {
        return this.emptyNode()
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtWhile, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Cond, cond.node)
    xmlAddChild(node, this.container(AstNodeKind.Body, body))
    return node
}

fun Parser.parseReturn(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    var value: ExprNode = this.emptyExpr()
    if (!this.atStmtEnd()) {
        if (this.checkText("(") && this.ctorReturnable()) {
            val block: AstXmlNode = this.tryParseCtorReturn(pos)
            if (!xmlIsEmpty(block)) {
                return block
            }
        }
        value = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, attrs, Array<AstXmlNode>())
    if (value.node.name != AstNodeKind.None) {
        this.attach(node, AstNodeKind.Value, value.node)
    }
    return node
}

// Whether the enclosing return type can be constructed by `initByValue`: a plain named
// or generic type (`Opt<Int>`, `Point`), never a pointer or reference.
fun Parser.ctorReturnable(): Bool {
    if (xmlIsEmpty(this.returnTypeCtx)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(this.returnTypeCtx)
    return kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric
}

// `return (a, b)`: `initByValue` constructs the return type. It is an *extension* on the
// instance (it sets the receiver and returns nothing), so this lowers to a default-built
// `T`, the extension called on it, and the `T` returned. Tries the group and restores the
// cursor when it is not the whole statement, so a plain `return (a) || (b)` still parses.
fun Parser.tryParseCtorReturn(pos: SourcePos): AstXmlNode {
    val savedCursor: Span<Token> = this.cursor
    val savedFailed: Bool = this.failed
    val savedError: Str = this.error
    val savedClosers: Int = this.pendingClosers
    this.advance()
    this.skipNewlines()
    var args: List<ExprNode> = List<ExprNode>()
    if (!this.checkText(")")) {
        while (true) {
            val arg: ExprNode = this.parseExpr(0)
            if (this.failed) {
                break
            }
            args.append(arg)
            this.skipNewlines()
            if (!this.matchText(",")) {
                break
            }
            this.skipNewlines()
        }
    }
    var ok: Bool = !this.failed && this.checkText(")")
    if (ok) {
        this.advance()
        ok = this.atStmtEnd()
    }
    if (!ok) {
        this.cursor = savedCursor
        this.failed = savedFailed
        this.error = savedError
        this.pendingClosers = savedClosers
        return this.emptyNode()
    }
    this.ctorCounter = this.ctorCounter + 1
    val ctorCounterText: Str = this.ctorCounter.toString()
    val temp: Str = `_sm_ctor@ctorCounterText`
    val tempExpr: ExprNode = this.nameExprAt(temp, pos)
    val retType: AstXmlNode = this.roleOf(this.returnTypeCtx, AstNodeKind.Type)
    val decl: AstXmlNode = this.varDeclNode(temp, true, retType, this.emptyExpr(), pos)
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "initByValue"))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    this.attach(member, AstNodeKind.Receiver, tempExpr.node)
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    kids.append(this.roleOf(member, AstNodeKind.Callee))
    var i: Int = 0
    while (i < args.size()) {
        kids.append(this.roleOf(args[i].node, AstNodeKind.Arg))
        i = i + 1
    }
    var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, kids.toArray())
    var stmtAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var stmt: AstXmlNode =
        AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, stmtAttrs, Array<AstXmlNode>())
    this.attach(stmt, AstNodeKind.Expr, call)
    var retAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var ret: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, retAttrs, Array<AstXmlNode>())
    this.attach(ret, AstNodeKind.Value, tempExpr.node)
    var body: List<AstXmlNode> = List<AstXmlNode>()
    body.append(decl)
    body.append(stmt)
    body.append(ret)
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var block: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtBlock, attrs, Array<AstXmlNode>())
    xmlAddChild(block, this.container(AstNodeKind.Body, body))
    return block
}

// The value the state machine hands out (impl_specs/yield.md); a `return`-like statement
// the state-machine pass replaces.
fun Parser.parseYield(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    val value: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return this.emptyNode()
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtYield, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Value, value.node)
    return node
}

// `for` (specs/functions.md): two forms, each binding an element or a *pointer* to it,
// all iterating a state machine (`..T`). Lowered here to the `while` it means, so no
// stage downstream sees a `for` and `break`/`continue` inside are the `while`'s own.
//
//   for (v in m) { body }       var _sm_for1 = m
//                               while (_sm_for1.advance()) {
//                                   val v = _sm_for1.current
//                                   body
//                               }
//
//   for ((v, i) in m) { ... }   the same, plus `var _sm_index1: Int = -1` before the
//                               loop, the pre-increment as the body's first statement,
//                               and `val i = _sm_index1` after `v`.
//
// The index pre-increments as the body's *first* statement, not in the condition:
// `continue` jumps to the condition, so a bump at the end of the body would miss that
// iteration, and `-1` makes the first one 0. The names come from the per-file
// `nextTemplateId` counter, so nested loops never collide and two runs agree.
fun Parser.parseFor(out: *List<AstXmlNode>): Bool {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    if (!this.expectText("(")) {
        return false
    }
    var valueName: Str = ""
    var indexName: Str = ""
    var withIndex: Bool = false
    var valueIsPointer: Bool = false
    if (this.matchText("(")) {
        withIndex = true
        valueIsPointer = this.matchText("*")
        valueName = this.expectName()
        if (this.failed) {
            return false
        }
        if (!this.expectText(",")) {
            return false
        }
        indexName = this.expectName()
        if (this.failed) {
            return false
        }
        if (!this.expectText(")")) {
            return false
        }
    } else {
        valueIsPointer = this.matchText("*")
        valueName = this.expectName()
        if (this.failed) {
            return false
        }
    }
    if (!this.matchText("in")) {
        return this.fail("expected 'in'")
    }
    this.skipNewlines()
    val machine: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return false
    }
    if (!this.expectText(")")) {
        return false
    }
    val body: List<AstXmlNode> = this.parseBlock()
    if (this.failed) {
        return false
    }

    val id: Int = this.nextTemplateId
    this.nextTemplateId = this.nextTemplateId + 1
    val machineName: Str = "_sm_for" + id.toString()
    val counterName: Str = "_sm_index" + id.toString()

    // A *member* `iter()` call, not `iter(x)`: that is what binds the function's type
    // parameter from the receiver, leaving the loop variable typed.
    var wrap: Str = "iter"
    if (valueIsPointer) {
        wrap = "iterPtr"
    }
    val iterated: ExprNode = this.iterCall(machine, pos, wrap)
    out.append(this.varDeclNode(machineName, true, this.emptyNode(), iterated, pos))
    if (withIndex) {
        val counterInit: ExprNode = this.intLiteralAt(-1, pos)
        out.append(this.varDeclNode(counterName, true, this.namedTypeNode("Int", pos), counterInit, pos))
    }

    var loop: List<AstXmlNode> = List<AstXmlNode>()
    if (withIndex) {
        val target: ExprNode = this.nameExprAt(counterName, pos)
        val one: ExprNode = this.intLiteralAt(1, pos)
        val value: ExprNode = this.binaryExprAt("+", this.nameExprAt(counterName, pos), one, pos)
        loop.append(this.assignNode(target, value, pos))
    }
    val stepValue: ExprNode = this.memberExprAt(machineName, "current", pos)
    loop.append(this.varDeclNode(valueName, false, this.emptyNode(), stepValue, pos))
    if (withIndex) {
        val counterRef: ExprNode = this.nameExprAt(counterName, pos)
        loop.append(this.varDeclNode(indexName, false, this.emptyNode(), counterRef, pos))
    }
    var bi: Int = 0
    while (bi < body.size()) {
        loop.append(body[bi])
        bi = bi + 1
    }
    out.append(this.whileNode(this.methodCallAt(machineName, "advance", pos), loop, pos))
    return true
}
