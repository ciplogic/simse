// ParserExpr.kt
//
// The Pratt expression parser: unary, postfix and primary forms, the lambda form, and
// the string-comparison view wrapping. Extension methods on `Parser` (Parser.kt).

package parser
import compiler

import lex
import common


fun Parser.parseExpr(minBindingPower: Int): ExprNode {
    var left: ExprNode = this.parseUnary()
    if (this.failed) {
        return this.emptyExpr()
    }
    while (true) {
        var newlines: Int = 0
        while (this.peek(newlines).kind == TokenKind.EndOfLine) {
            newlines = newlines + 1
        }
        if (newlines > 0) {
            val lookaheadBP: Int = binaryBindingPower(this.peek(newlines).text)
            if (lookaheadBP < 0 || lookaheadBP < minBindingPower) {
                break
            }
            var k: Int = 0
            while (k < newlines) {
                this.advance()
                k = k + 1
            }
        }
        val bp: Int = binaryBindingPower(this.peek(0).text)
        if (bp < 0 || bp < minBindingPower) {
            break
        }
        val op: Str = this.advance().text
        this.skipNewlines()
        val right: ExprNode = this.parseExpr(bp + 1)
        if (this.failed) {
            return this.emptyExpr()
        }
        // One side of a comparison against a string literal is wrapped in `spanOfStr`: the
        // test reads a view of the place instead of copying it into a `Str` temporary, and
        // a `*Str` operand is not read at all. Only a *place* is wrapped - a call's or an
        // operation's result is materialised anyway, so a view of it would only add work.
        var lhsNode: AstXmlNode = left.node
        var rhsNode: AstXmlNode = right.node
        if (this.isStringCompareOp(op)) {
            if (xmlKind(right.node) == AstNodeCategory.ExprStrLit
                && this.whenSubjectIsPlace(left.node)
            ) {
                val viewed: ExprNode = this.freeCallAt("spanOfStr", left, SourcePos(0, left.line, left.column))
                lhsNode = viewed.node
            }
            if (xmlKind(left.node) == AstNodeCategory.ExprStrLit
                && this.whenSubjectIsPlace(right.node)
            ) {
                val viewed: ExprNode = this.freeCallAt("spanOfStr", right, SourcePos(0, right.line, right.column))
                rhsNode = viewed.node
            }
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(left.line, left.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Lhs, lhsNode)
        this.attach(node, AstNodeKind.Rhs, rhsNode)
        left = ExprNode(node, left.line, left.column)
    }
    return left
}

fun Parser.parseUnary(): ExprNode {
    val pos: SourcePos = this.peek(0).pos
    val text: Str = this.peek(0).text
    when (text) {
        "!", "-" -> {
            val op: Str = this.advance().text
            val operand: ExprNode = this.parseUnary()
            if (this.failed) {
                return this.emptyExpr()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Operand, operand.node)
            return ExprNode(node, pos.line, pos.column)
        }

        "&" -> {
            this.advance()
            val operand: ExprNode = this.parseUnary()
            if (this.failed) {
                return this.emptyExpr()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprRef, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Operand, operand.node)
            return ExprNode(node, pos.line, pos.column)
        }

        "*" -> {
            this.advance()
            val operand: ExprNode = this.parseUnary()
            if (this.failed) {
                return this.emptyExpr()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprDeref, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Operand, operand.node)
            return ExprNode(node, pos.line, pos.column)
        }
    }
    return this.parsePostfix()
}

fun Parser.parsePostfix(): ExprNode {
    var expr: ExprNode = this.parsePrimary()
    if (this.failed) {
        return this.emptyExpr()
    }
    // One list for the whole walk, cleared per call: the arguments of the call being parsed.
    var args: List<AstXmlNode> = List<AstXmlNode>()
    while (true) {
        if (this.matchText("(")) {
            args.clear()
            this.skipNewlines()
            if (!this.checkText(")")) {
                val first: ExprNode = this.parseExpr(0)
                if (this.failed) {
                    return this.emptyExpr()
                }
                args.append(first.node)
                this.skipNewlines()
                while (this.matchText(",")) {
                    this.skipNewlines()
                    val next: ExprNode = this.parseExpr(0)
                    if (this.failed) {
                        return this.emptyExpr()
                    }
                    args.append(next.node)
                    this.skipNewlines()
                }
            }
            if (!this.expectText(")")) {
                return this.emptyExpr()
            }
            var kids: List<AstXmlNode> = List<AstXmlNode>()
            kids.append(this.roleOf(expr.node, AstNodeKind.Callee))
            var a: Int = 0
            while (a < args.size()) {
                kids.append(this.roleOf(*args[a], AstNodeKind.Arg))
                a = a + 1
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, attrs, kids.toArray())
            expr = ExprNode(node, expr.line, expr.column)
        } else if (this.matchText("[")) {
            this.skipNewlines()
            val index: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return this.emptyExpr()
            }
            if (!this.expectText("]")) {
                return this.emptyExpr()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIndex, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Receiver, expr.node)
            this.attach(node, AstNodeKind.Index, index.node)
            expr = ExprNode(node, expr.line, expr.column)
        } else if (this.matchText(".")) {
            val name: Str = this.expectName()
            if (this.failed) {
                return this.emptyExpr()
            }
            // A generic member call (`h.getAs<Int8>()`): tentative, the way a leading
            // `name<...>` is in `parsePrimary` - the closer must be followed by `(`, or the
            // `<` was a comparison (`m.branch < 3`) and this is a plain member.
            var typeArgs: List<AstXmlNode> = List<AstXmlNode>()
            if (this.checkText("<")) {
                val savedCursor: Span<Token> = this.cursor
                val savedFailed: Bool = this.failed
                val savedError: Str = this.error
                val savedClosers: Int = this.pendingClosers
                typeArgs = this.parseGenericArgs()
                if (this.failed || !this.checkText("(")) {
                    this.cursor = savedCursor
                    this.failed = savedFailed
                    this.error = savedError
                    this.pendingClosers = savedClosers
                    typeArgs = List<AstXmlNode>()
                }
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Receiver, expr.node)
            if (typeArgs.size() > 0) {
                xmlAddChildren(node, typeArgs)
            }
            expr = ExprNode(node, expr.line, expr.column)
        } else if (this.checkText("!") && this.peek(1).text == "!") {
            // `x!!` (cppsrc/parser/Propagate.kt): the payload of a `Res`, or its failure
            // returned out of the enclosing function. Two bangs, so no scanner change.
            this.advance()
            this.advance()
            var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprPropagate, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Operand, expr.node)
            expr = ExprNode(node, expr.line, expr.column)
        } else {
            break
        }
    }
    return expr
}

// A string token's text: the ordinary quoted literal as written, or the quoted literal a
// backtick string's raw content spells (litRawString) - so everything downstream, the
// when guards and StrView resolution included, sees one kind of text.
fun Parser.stringTokenText(text: *Str): Str {
    if (text.size() > 0 && text[0] == '`') {
        return litRawString(text)
    }
    return * text
}

fun Parser.parsePrimary(): ExprNode {
    val pos: SourcePos = this.peek(0).pos
    if (this.checkKind(TokenKind.Number)) {
        val text: Str = this.advance().text
        var kind: AstNodeCategory = AstNodeCategory.ExprIntLit
        if (text.find(".") != -1) {
            kind = AstNodeCategory.ExprFloatLit
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
        return ExprNode(AstXmlNode(AstNodeKind.Expr, kind, attrs, Array<AstXmlNode>()), pos.line, pos.column)
    }
    if (this.checkKind(TokenKind.String)) {
        val raw: Str = this.advance().text
        val text: Str = this.stringTokenText(raw)
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprStrLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkKind(TokenKind.Character)) {
        val text: Str = this.advance().text
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCharLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkText("true") || this.checkText("false")) {
        val text: Str = this.advance().text
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        if (text == "true") {
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, "true"))
        } else {
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, "false"))
        }
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBoolLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkText("null")) {
        this.advance()
        val attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprNullLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkText("this")) {
        this.advance()
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "this"))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkKind(TokenKind.Identifier)) {
        val name: Str = this.peek(0).text
        if (name == "copy" && this.peek(1).text == "(") {
            this.advance()
            this.advance()
            val inner: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return this.emptyExpr()
            }
            if (!this.expectText(")")) {
                return this.emptyExpr()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCopy, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Operand, inner.node)
            return ExprNode(node, pos.line, pos.column)
        }
        if (this.peek(1).text == "<") {
            val savedCursor: Span<Token> = this.cursor
            val savedFailed: Bool = this.failed
            val savedError: Str = this.error
            this.advance()
            val typeArgs: List<AstXmlNode> = this.parseGenericArgs()
            if (!this.failed && (this.checkText(".") || this.checkText("("))) {
                var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
                var gnode: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprGenericName, attrs, Array<AstXmlNode>())
                xmlAddChildren(gnode, typeArgs)
                return ExprNode(gnode, pos.line, pos.column)
            }
            this.cursor = savedCursor
            this.failed = savedFailed
            this.error = savedError
        }
        this.advance()
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }
    if (this.checkText("(")) {
        val savedCursor: Span<Token> = this.cursor
        val savedFailed: Bool = this.failed
        val savedError: Str = this.error
        val lambda: ExprNode = this.tryParseLambda()
        if (!this.failed) {
            return lambda
        }
        this.cursor = savedCursor
        this.failed = savedFailed
        this.error = savedError
        this.advance()
        this.skipNewlines()
        val inner: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyExpr()
        }
        if (!this.expectText(")")) {
            return this.emptyExpr()
        }
        return inner
    }
    this.fail("expected expression")
    return this.emptyExpr()
}

fun Parser.tryParseLambda(): ExprNode {
    if (!this.matchText("(")) {
        this.fail("expected '('")
        return this.emptyExpr()
    }
    val pos: SourcePos = this.peek(0).pos
    var names: List<Str> = List<Str>()
    var paramTypes: List<AstXmlNode> = List<AstXmlNode>()
    this.skipNewlines()
    if (!this.checkText(")")) {
        while (true) {
            if (!this.checkKind(TokenKind.Identifier)) {
                this.fail("expected lambda parameter")
                return this.emptyExpr()
            }
            names.append(this.advance().text)
            if (this.matchText(":")) {
                val paramType: AstXmlNode = this.parseType(AstNodeKind.ParamType)
                if (this.failed) {
                    return this.emptyExpr()
                }
                paramTypes.append(paramType)
            }
            this.skipNewlines()
            if (!this.matchText(",")) {
                break
            }
            this.skipNewlines()
        }
    }
    if (!this.checkText(")")) {
        this.fail("expected ')'")
        return this.emptyExpr()
    }
    if (this.peek(1).text != "->") {
        this.fail("expected '->'")
        return this.emptyExpr()
    }
    this.advance()
    this.advance()

    var body: List<AstXmlNode> = List<AstXmlNode>()
    if (this.checkText("{")) {
        // A lambda has no declared return type, so its returns construct nothing.
        val savedReturnType: AstXmlNode = this.returnTypeCtx
        this.returnTypeCtx = this.emptyNode()
        body = this.parseBlock()
        this.returnTypeCtx = savedReturnType
        if (this.failed) {
            return this.emptyExpr()
        }
    } else {
        val value: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyExpr()
        }
        var sattrs: List<AstNodeAttribute> = this.posAttrs(value.line, value.column)
        var snode: AstXmlNode =
            AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, sattrs, Array<AstXmlNode>())
        this.attach(snode, AstNodeKind.Expr, value.node)
        body.append(snode)
    }

    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Params, joinStrs(names, ",")))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprLambda, attrs, Array<AstXmlNode>())
    xmlAddChildren(node, paramTypes)
    xmlAddChild(node, this.container(AstNodeKind.Body, body))
    return ExprNode(node, pos.line, pos.column)
}
