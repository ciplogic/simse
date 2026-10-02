// ParserWhen.kt
//
// The `when` lowering: the label tests, the dispatch shape the parser picks, and
// `parseWhen`. Extension methods on `Parser` (Parser.kt).

package parser
import compiler

import lex
import common


// Whether a `when` subject may be read *again* per test instead of being copied into a
// template: a place - a name, or a member/index/deref chain of places - has no call and no
// side effect, and nothing in the test chain writes it (every test runs before any arm body),
// so the chain can be evaluated against the subject itself. That is what saves the copy the
// template would make of a `Str` subject - a heap copy, for a text longer than the inline
// buffer - which is a cost on *every* `when` execution and dwarfs the tests themselves.
fun Parser.whenSubjectIsPlace(node: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprName) {
        return true
    }
    if (kind == AstNodeCategory.ExprDeref) {
        val operand: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Operand)
        return !xmlIsEmpty(operand) && this.whenSubjectIsPlace(operand)
    }
    if (kind == AstNodeCategory.ExprMember) {
        val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
        return !xmlIsEmpty(receiver) && this.whenSubjectIsPlace(receiver)
    }
    if (kind == AstNodeCategory.ExprIndex) {
        val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
        val index: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Index)
        return !xmlIsEmpty(receiver) && this.whenSubjectIsPlace(receiver)
                && !xmlIsEmpty(index) && this.whenSubjectIsPlace(index)
    }
    return false
}

// `<receiver>.<method>()`, for a receiver that is an expression rather than a name.
fun Parser.receiverCallAt(receiver: *ExprNode, method: *Str, pos: SourcePos): ExprNode {
    var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, method))
    var member: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    this.attach(member, AstNodeKind.Receiver, receiver.node)
    var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
    this.attach(call, AstNodeKind.Callee, member)
    return ExprNode(call, pos.line, pos.column)
}

// `<callee>(<arg>)`, for a free call the desugaring builds rather than parses.
fun Parser.freeCallAt(callee: *Str, arg: *ExprNode, pos: SourcePos): ExprNode {
    val calleeExpr: ExprNode = this.nameExprAt(callee, pos)
    var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
    this.attach(call, AstNodeKind.Callee, calleeExpr.node)
    this.attach(call, AstNodeKind.Arg, arg.node)
    return ExprNode(call, pos.line, pos.column)
}

// `<receiver>[<index>]`, for a receiver that is an expression rather than a name.
fun Parser.receiverIndexAt(receiver: *ExprNode, index: *ExprNode, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIndex, attrs, Array<AstXmlNode>())
    this.attach(node, AstNodeKind.Receiver, receiver.node)
    this.attach(node, AstNodeKind.Index, index.node)
    return ExprNode(node, pos.line, pos.column)
}

// One condition for an arm's labels, so their body is emitted once. With `dispatch` (the
// `when`-lowering optimization) each label's `==` is *guarded*: by the subject's length
// first, and - for a one-byte literal, or for a longer one when `--when-first-char` asks -
// by its first byte. Both guards are necessary conditions, so the predicate is unchanged
// and the whole string compare runs only for a label that can still match, which is what
// turns a `when` over N string labels from N `memcmp` calls into a few integer compares.
fun Parser.whenCondition(
    subject: *
    ExprNode,
    lengthName: *
    Str,
    labels: *
    List<AstXmlNode>,
    pos: SourcePos,
    dispatch: Bool
): ExprNode {
    var cond: ExprNode = this.whenLabelCondition(subject, lengthName, labels[0], pos, dispatch)
    var i: Int = 1
    while (i < labels.size()) {
        val equals: ExprNode = this.whenLabelCondition(subject, lengthName, labels[i], pos, dispatch)
        cond = this.binaryExprAt("||", cond, equals, pos)
        i = i + 1
    }
    return cond
}

// One label's test: `<subject> == <label>`, guarded when the lowering is on. A label whose
// first byte has no printable spelling keeps the plain comparison, and a missing guard only
// costs the `memcmp` it would have saved - so this is always safe, label by label.
fun Parser.whenLabelCondition(
    subject: *
    ExprNode,
    lengthName: *
    Str,
    label: *
    AstXmlNode,
    pos: SourcePos,
    dispatch: Bool
): ExprNode {
    val equals: ExprNode = this.binaryExprAt(
        "==", subject, ExprNode(*label, pos.line, pos.column), pos
    )
    if (!dispatch) {
        return equals
    }
    val text: Str = xmlAttr(label, AstNodeAttributeKind.Text)
    val length: Int = litByteLength(text)
    var test: ExprNode = this.binaryExprAt(
        "==", this.nameExprAt(lengthName, pos), this.intLiteralAt(length, pos), pos
    )
    if (length == 0) {
        // The empty text is the only one of length zero, so the length test is the whole test.
        return test
    }
    val ch: Str = litCharSpelling(text)
    if (ch == "") {
        return this.binaryExprAt("&&", test, equals, pos)
    }
    if (length == 1 || whenFirstChar()) {
        test = this.binaryExprAt(
            "&&", test,
            this.binaryExprAt(
                "==",
                this.receiverIndexAt(subject, this.intLiteralAt(0, pos), pos),
                this.charLiteralAt(ch, pos),
                pos
            ),
            pos
        )
        if (length == 1) {
            // The one byte *is* the text, so the string compare has nothing left to decide.
            return test
        }
    }
    return this.binaryExprAt("&&", test, equals, pos)
}

// Whether every one of an arm's labels is a string literal: what the guarded tests need,
// since a length is a compile-time property only for a literal.
fun Parser.whenLabelsAreLiterals(labels: *List<AstXmlNode>): Bool {
    var i: Int = 0
    while (i < labels.size()) {
        if (xmlKind(*labels[i]) != AstNodeCategory.ExprStrLit) {
            return false
        }
        i = i + 1
    }
    return true
}

// A character literal whose source spelling is `spelling` (like `'x'`), which
// `litCharSpelling` built from a byte of a string literal.
fun Parser.charLiteralAt(spelling: *Str, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, spelling))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCharLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

// `when` (specs/functions.md): desugared here to the `if`/`else` chain it means, so
// nothing downstream knows what a `when` is. The subject is bound once in a template, so it
// is evaluated once; arms do not fall through, so a `break`/`continue` in one is the
// enclosing loop's. A **place** subject skips the template entirely (`whenSubjectIsPlace`):
// reading it again per test is free and cannot change.
//
// When *every* arm's labels are string literals - and `--when-dispatch` is on, by default -
// the chain also binds a **view** of the subject (`_sm_when1_v`, `spanOfStr`,
// cppsrc/rtl/StrView.kt) and its length (`_sm_when1_n`), and each label's test compares the
// view, guarded by the length (`whenLabelCondition`). The view keeps the subject from being
// copied per label, while a subject that already is a `StrView` views itself (the same
// `spanOfStr`, whose `StrView` overload is the identity, cppsrc/rtl/_res.md), so the desugar
// never has to know which of the two it got. The arms, their order and the `else` are
// untouched, so the rewrite cannot change which arm matches.
fun Parser.parseWhen(out: *List<AstXmlNode>): Bool {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    if (!this.expectText("(")) {
        return false
    }
    val subject: ExprNode = this.parseExpr(0)
    if (this.failed) {
        return false
    }
    if (!this.expectText(")")) {
        return false
    }
    this.skipNewlines()
    if (!this.expectText("{")) {
        return false
    }
    this.skipSeparators()

    // Bound before the arms are parsed, so a nested `for`/`when` in an arm takes the next id.
    val whenId: Int = this.nextTemplateId
    val subjectName: Str = "_sm_when" + whenId.toString()
    val viewName: Str = subjectName + "_v"
    val lengthName: Str = subjectName + "_n"
    this.nextTemplateId = whenId + 1

    // The subject's own value: itself when it is a place, the template otherwise.
    val place: Bool = this.whenSubjectIsPlace(subject.node) && !whenCopySubject()
    var base: ExprNode = subject
    if (!place) {
        base = this.nameExprAt(subjectName, pos)
    }
    var subjectExpr: ExprNode = base

    // One `if` per arm in source order, the `else` arm's statements as the tail. Labels and
    // bodies are collected first, because whether the tests can be guarded - every label a
    // string literal - is only known once every arm has been read.
    var armLabels: List<List<AstXmlNode>> = List<List<AstXmlNode>>()
    var armBodies: List<List<AstXmlNode>> = List<List<AstXmlNode>>()
    var armPositions: List<SourcePos> = List<SourcePos>()
    var tail: List<AstXmlNode> = List<AstXmlNode>()
    var literals: Bool = true
    var seenElse: Bool = false
    while (!this.checkText("}") && !this.atEnd() && !this.failed) {
        val armPos: SourcePos = this.peek(0).pos
        if (this.matchText("else")) {
            if (seenElse) {
                this.fail("'when' can have only one 'else' arm")
                return false
            }
            seenElse = true
            this.skipNewlines()
            if (!this.expectText("->")) {
                return false
            }
            tail = this.parseBlock()
            if (this.failed) {
                return false
            }
            this.skipSeparators()
            continue
        }
        if (seenElse) {
            this.fail("'else' must be the last arm of a 'when'")
            return false
        }
        // `is`/`in` are Kotlin's pattern labels; `when` matches a value with `==` only.
        if (this.checkText("is") || this.checkText("in")) {
            this.fail("'when' matches a value or 'else', not a pattern")
            return false
        }
        var labels: List<AstXmlNode> = List<AstXmlNode>()
        val first: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return false
        }
        labels.append(first.node)
        while (this.matchText(",")) {
            this.skipNewlines()
            val next: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return false
            }
            labels.append(next.node)
        }
        this.skipNewlines()
        if (!this.expectText("->")) {
            return false
        }
        val body: List<AstXmlNode> = this.parseBlock()
        if (this.failed) {
            return false
        }
        if (!this.whenLabelsAreLiterals(*labels)) {
            literals = false
        }
        armLabels.append(labels)
        armBodies.append(body)
        armPositions.append(armPos)
        this.skipSeparators()
    }
    if (!this.expectText("}")) {
        return false
    }
    val dispatch: Bool = whenDispatch() && literals && armLabels.size() > 0
    if (dispatch) {
        // The tests compare a *view* over the subject, not the subject (see the header).
        subjectExpr = this.nameExprAt(viewName, pos)
    }

    var arms: List<AstXmlNode> = List<AstXmlNode>()
    for ((*label, a) in armLabels) {
        val cond: ExprNode = this.whenCondition(
            subjectExpr, lengthName, label, armPositions[a], dispatch
        )
        arms.append(this.ifNode(cond, *armBodies[a], armPositions[a]))
    }

    // The chain is right-nested, and the tail goes on first: linking copies an arm into
    // its predecessor's else body (nodes are values), so an arm must be complete before
    // it is.
    if (seenElse && arms.size() > 0 && tail.size() > 0) {
        xmlAddChild(arms[arms.size() - 1], this.container(AstNodeKind.Else, tail))
    }
    var i: Int = arms.size() - 1
    while (i > 0) {
        var next: List<AstXmlNode> = List<AstXmlNode>()
        next.append(arms[i])
        xmlAddChild(arms[i - 1], this.container(AstNodeKind.Else, next))
        i = i - 1
    }
    if (!place) {
        out.append(this.varDeclNode(subjectName, true, this.emptyNode(), subject, pos))
    }
    if (dispatch) {
        // The view, taken once from the subject (`spanOfStr`, cppsrc/rtl/StrView.kt).
        out.append(
            this.varDeclNode(viewName, true, this.emptyNode(), this.freeCallAt("spanOfStr", base, pos), pos)
        )
        // Once, so a guarded test does not call `size()` per label.
        out.append(
            this.varDeclNode(
                lengthName, true, this.emptyNode(), this.receiverCallAt(subjectExpr, "size", pos), pos
            )
        )
    }
    if (arms.size() > 0) {
        out.append(arms[0])
    } else {
        // `else` was the only arm, so its statements are the whole construct.
        var e: Int = 0
        while (e < tail.size()) {
            out.append(tail[e])
            e = e + 1
        }
    }
    return true
}
