// ParserNodes.kt
//
// The small node and expression builders shared by the statement and expression parsers,
// including the ones the `for` desugaring uses. Extension methods on `Parser` (Parser.kt).

package parser
import compiler

import lex
import common


// Builders for the `for` desugaring below. Every node they make carries the `for`
// token's position, so a diagnostic points at the line the user wrote.

fun Parser.varDeclNode(name: *Str, isVar: Bool, typeNode: *AstXmlNode, init: *ExprNode, pos: SourcePos): AstXmlNode {
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

fun Parser.namedTypeNode(name: *Str, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, attrs, Array<AstXmlNode>())
}

fun Parser.assignNode(target: *ExprNode, value: *ExprNode, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Target, target.node)
    this.attach(node, AstNodeKind.Value, value.node)
    return node
}

fun Parser.breakNode(pos: SourcePos): AstXmlNode {
    return AstXmlNode(
        AstNodeKind.Stmt,
        AstNodeCategory.StmtBreak,
        this.posAttrs(pos.line, pos.column),
        Array<AstXmlNode>()
    )
}

fun Parser.ifNode(cond: *ExprNode, thenBody: *List<AstXmlNode>, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtIf, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Cond, cond.node)
    xmlAddChild(node, this.container(AstNodeKind.Then, thenBody))
    return node
}

fun Parser.whileNode(cond: *ExprNode, body: *List<AstXmlNode>, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtWhile, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Cond, cond.node)
    xmlAddChild(node, this.container(AstNodeKind.Body, body))
    return node
}

fun Parser.nameExprAt(text: *Str, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

fun Parser.intLiteralAt(value: Int, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, value.toString()))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIntLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

fun Parser.boolLiteralAt(value: Bool, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, boolText(value)))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBoolLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

fun Parser.unaryExprAt(op: *Str, operand: *ExprNode, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Operand, operand.node)
    return ExprNode(node, pos.line, pos.column)
}

fun Parser.binaryExprAt(op: *Str, lhs: *ExprNode, rhs: *ExprNode, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Lhs, lhs.node)
    this.attach(node, AstNodeKind.Rhs, rhs.node)
    return ExprNode(node, pos.line, pos.column)
}

// The `<target>.<wrap>()` wrap a `for` puts around what it iterates (`iterValues`/`iter`).
fun Parser.iterCall(target: *ExprNode, pos: SourcePos, wrap: *Str): ExprNode {
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, wrap))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    this.attach(member, AstNodeKind.Receiver, target.node)
    var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
    this.attach(call, AstNodeKind.Callee, member)
    return ExprNode(call, pos.line, pos.column)
}

// `&operand`: the address-of form an out slot takes - `machine.tryAdvance(&slot)`, the
// `for` template's step, where the callee is the one that writes the operand.
fun Parser.refExprAt(operand: *ExprNode, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprRef, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Operand, operand.node)
    return ExprNode(node, pos.line, pos.column)
}

// The machine whose step fills a `for` template's out slot: `_sm_item3` is the slot of
// `_sm_for3` (ParserStmt.parseFor). Empty for any other name. The slot is declared with no
// type and no initializer of its own, so whoever has to say what it holds - its type, or
// which storage it reaches - asks this for the machine.
fun forItemMachine(name: *Str): Str {
    if (!name.startsWith("_sm_item")) {
        return ""
    }
    val id: Str = name.substr(8, name.size() - 8)
    if (id.size() == 0) {
        return ""
    }
    var i: Int = 0
    while (i < id.size()) {
        val ch: Int = Int(id[i])
        if (ch < 48 || ch > 57) {
            return ""
        }
        i++
    }
    return "_sm_for" + id
}

// `<receiver>.<method>(<arg>)`, for a receiver that is an expression rather than a name
// (`x in c` is `c.contains(x)`, `a..b` is `a.rangeTo(b)`).
fun Parser.receiverCall1At(receiver: *ExprNode, method: *Str, arg: *ExprNode, pos: SourcePos): ExprNode {
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, method))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    this.attach(member, AstNodeKind.Receiver, receiver.node)
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    kids.append(this.roleOf(member, AstNodeKind.Callee))
    kids.append(this.roleOf(arg.node, AstNodeKind.Arg))
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, attrs, kids.toArray())
    return ExprNode(call, pos.line, pos.column)
}
