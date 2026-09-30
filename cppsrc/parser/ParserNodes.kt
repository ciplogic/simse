// ParserNodes.kt
//
// The small node and expression builders shared by the statement and expression parsers,
// including the ones the `for` desugaring uses. Extension methods on `Parser` (Parser.kt).

package parser

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

// The `<target>.<wrap>()` wrap a `for` puts around what it iterates (`iter`/`iterPtr`).
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

// What the machine last yielded (`current`): the `for` template's loop variable.
fun Parser.memberExprAt(target: *Str, field: *Str, pos: SourcePos): ExprNode {
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, field))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    val receiver: ExprNode = this.nameExprAt(target, pos)
    this.attach(member, AstNodeKind.Receiver, receiver.node)
    return ExprNode(member, pos.line, pos.column)
}

// `<target>.<method>()`: the machine's `advance`.
fun Parser.methodCallAt(target: *Str, method: *Str, pos: SourcePos): ExprNode {
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, method))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    val receiver: ExprNode = this.nameExprAt(target, pos)
    this.attach(member, AstNodeKind.Receiver, receiver.node)
    var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
    this.attach(call, AstNodeKind.Callee, member)
    return ExprNode(call, pos.line, pos.column)
}
