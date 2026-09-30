// LinearFormPrint.kt
//
// Printing and reading the IL: `printIlBody`/`printIlUnit`, the per-op comments, and the
// node helpers the extractor builds with.

package linear

import common
import sema


// What the instruction means, spelled the way the language would write it. Every operand
// read is bounds-checked, so a wrong-arity instruction shows `?` instead of crashing.
fun ilOpComment(body: *IlBody, op: *IlOp): Str {
    val kind: IlOpKind = op.kind
    val operands: *List<Int> = *op.operands

    when (kind) {
        IlOpKind.Declare -> {
            val slot: Int = ilOperandAt(operands, 0)
            return fmtStr(
                "var |: |", ilVarName(body, slot), ilVarTypeName(body, slot)
            )
        }

        IlOpKind.Label -> {
            return ilLabelName(body, ilOperandAt(operands, 0)) + ":"
        }

        IlOpKind.Goto -> {
            return "goto " + ilLabelName(body, ilOperandAt(operands, 0))
        }

        IlOpKind.IfTrue, IlOpKind.IfFalse -> {
            val condition: Str = ilVarName(body, ilOperandAt(operands, 0))
            val target: Str = ilLabelName(body, ilOperandAt(operands, 1))
            if (kind == IlOpKind.IfTrue) {
                return fmtStr("if (|) goto |", condition, target)
            }
            return fmtStr("if (!|) goto |", condition, target)
        }

        IlOpKind.SetVar -> {
            return fmtStr(
                "| = |", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.SetVar_Null -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = null"
        }

        IlOpKind.BinaryOp -> {
            return fmtStr(
                "| = | | |", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 2)),
                ilPoolText(body, ilOperandAt(operands, 1)),
                ilVarName(body, ilOperandAt(operands, 3))
            )
        }

        IlOpKind.UnaryOp -> {
            return fmtStr(
                "| = ||", ilVarName(body, ilOperandAt(operands, 0)),
                ilPoolText(body, ilOperandAt(operands, 1)),
                ilVarName(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.Cast -> {
            return fmtStr(
                "| = cast |", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.Box -> {
            return fmtStr(
                "| = &|", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.Deref -> {
            return fmtStr(
                "| = *|", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.CopyValue -> {
            return fmtStr(
                "| = copy(|)", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.Store -> {
            return fmtStr(
                "*| = |", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.GetField -> {
            return fmtStr(
                "| = |.|", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1)),
                ilPoolText(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.SetField -> {
            return fmtStr(
                "|.| = |", ilVarName(body, ilOperandAt(operands, 1)),
                ilPoolText(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.GetIndex -> {
            return fmtStr(
                "| = |[|]", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1)),
                ilVarName(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.SetIndex -> {
            return fmtStr(
                "|[|] = |", ilVarName(body, ilOperandAt(operands, 1)),
                ilVarName(body, ilOperandAt(operands, 2)),
                ilVarName(body, ilOperandAt(operands, 3))
            )
        }

        IlOpKind.FieldAddr -> {
            return fmtStr(
                "| = &|.|", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1)),
                ilPoolText(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.IndexAddr -> {
            return fmtStr(
                "| = &|[|]", ilVarName(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1)),
                ilVarName(body, ilOperandAt(operands, 2))
            )
        }

        IlOpKind.GetStatic -> {
            return fmtStr(
                "| = |", ilVarName(body, ilOperandAt(operands, 0)),
                ilPoolText(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.GetStaticAddr -> {
            return fmtStr(
                "| = &|", ilVarName(body, ilOperandAt(operands, 0)),
                ilPoolText(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.SetStatic -> {
            return fmtStr(
                "| = |", ilPoolText(body, ilOperandAt(operands, 0)),
                ilVarName(body, ilOperandAt(operands, 1))
            )
        }

        IlOpKind.Call, IlOpKind.CallVoid -> {
            val hasDst: Bool = kind == IlOpKind.Call
            var dst: Str
            if (hasDst) {
                dst = ilVarName(body, ilOperandAt(operands, 0)) + " = "
            }
            var methodOp: Int = ilOperandAt(operands, 0)
            var first: Int = 1
            if (hasDst) {
                methodOp = ilOperandAt(operands, 1)
                first = 2
            }
            if (methodOp < 0 || methodOp >= body.methods.size()) {
                return fmtStr(
                    "|?m|(|)", dst, ilIntText(methodOp),
                    ilArgList(body, operands, first)
                )
            }
            val method: IlMethod = body.methods[methodOp]
            if (method.kind == IlMethodKind.Method && first < operands.size()) {
                return fmtStr(
                    "||.|(|)", dst, ilVarName(body, operands[first]), method.name,
                    ilArgList(body, operands, first + 1)
                )
            }
            return fmtStr(
                "|(|)", dst, method.name, ilArgList(body, operands, first)
            )
        }

        IlOpKind.CallIndirect, IlOpKind.CallIndirectVoid -> {
            val hasDst2: Bool = kind == IlOpKind.CallIndirect
            var dst2: Str
            var calleeAt: Int = 0
            if (hasDst2) {
                dst2 = ilVarName(body, ilOperandAt(operands, 0)) + " = "
                calleeAt = 1
            }
            return fmtStr(
                "|(|)", dst2, ilVarName(body, ilOperandAt(operands, calleeAt)),
                ilArgList(body, operands, calleeAt + 1)
            )
        }

        IlOpKind.Pack -> {
            return fmtStr(
                "| = [|]", ilVarName(body, ilOperandAt(operands, 0)),
                ilArgList(body, operands, 1)
            )
        }

        IlOpKind.Concat -> {
            return fmtStr(
                "| = concat(|)", ilVarName(body, ilOperandAt(operands, 0)),
                ilArgList(body, operands, 1)
            )
        }

        IlOpKind.CallCtor -> {
            return fmtStr(
                "| = new |(|)", ilVarName(body, ilOperandAt(operands, 0)),
                ilTypeName(body, ilOperandAt(operands, 1)),
                ilArgList(body, operands, 2)
            )
        }

        IlOpKind.Return -> {
            return "return " + ilVarName(body, ilOperandAt(operands, 0))
        }

        IlOpKind.ReturnVoid -> {
            return "return"
        }

        IlOpKind.Lambda -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = <lambda>"
        }

        IlOpKind.Unsupported -> {
            return fmtStr(
                "<unsupported: |>", ilPoolText(body, ilOperandAt(operands, 1))
            )
        }
    }
    return Str()
}

// The dump: the tables, then one line per instruction, operands resolved.
fun printIlBody(body: *IlBody): Str {
    var out: Str
    out = out + "# " + body.file + ":" + ilIntText(body.line) + "  " + body.symbol + " "
    +body.signature + "\n"

    var types: List<Str> = List<Str>()
    var i: Int = 0
    while (i < body.types.size()) {
        types.append(fmtStr("| |", ilIntText(i), body.types[i]))
        i = i + 1
    }
    ilAppendTable(out, "types:   ", types)

    var vars: List<Str> = List<Str>()
    i = 0
    while (i < body.vars.size()) {
        val slot: IlVar = body.vars[i]
        vars.append(
            fmtStr(
                "| |:|:|", ilIntText(i), slot.name, ilIntText(slot.typeIndex),
                ilVarKindText(slot.kind)
            )
        )
        i = i + 1
    }
    ilAppendTable(out, "vars:    ", vars)

    var pool: List<Str> = List<Str>()
    i = 0
    while (i < body.pool.size()) {
        pool.append(fmtStr("| |", ilIntText(i), ilPoolAsText(body.pool[i])))
        i = i + 1
    }
    ilAppendTable(out, "pool:    ", pool)

    var methods: List<Str> = List<Str>()
    i = 0
    while (i < body.methods.size()) {
        val method: IlMethod = body.methods[i]
        var text: Str = fmtStr(
            "| |:|:|", ilIntText(i), method.name, ilMethodKindText(method.kind),
            ilIntText(method.argCount)
        )
        if (method.staticBase >= 0) {
            text = text + ":static=" + ilTypeName(body, method.staticBase)
        }
        if (method.returnType >= 0) {
            text = text + ":ret=" + ilTypeName(body, method.returnType)
        }
        methods.append(text)
        i = i + 1
    }
    ilAppendTable(out, "methods: ", methods)

    var labels: List<Str> = List<Str>()
    i = 0
    while (i < body.labels.size()) {
        labels.append(fmtStr("| |", ilIntText(i), body.labels[i]))
        i = i + 1
    }
    ilAppendTable(out, "labels:  ", labels)

    i = 0
    while (i < body.ops.size()) {
        val op: IlOp = body.ops[i]
        val signature: Opt<IlSignature> = ilSignature(op.kind)
        var tokens: List<Str> = List<Str>()
        if (signature.hasValue()) {
            val found: IlSignature = signature.value()
            tokens = ilOperandTokens(found)
        }

        var rendered: List<Str> = List<Str>()
        var j: Int = 0
        while (j < op.operands.size()) {
            rendered.append(ilRenderOperand(body, ilOperandKindAt(tokens, j), op.operands[j]))
            j = j + 1
        }

        var text: Str = fmtStr(
            "|,  ||", ilPadRight(ilIntText(i), 4), ilPadRight(ilOpKindText(op.kind), 16),
            ilJoinList(rendered, ", ")
        )
        val comment: Str = ilOpComment(body, op)
        if (!comment.isEmpty()) {
            text = fmtStr("|# |", ilPadRight(text, 74), comment)
        }
        var sourceLine: Int = 0
        if (i < body.lines.size()) {
            sourceLine = body.lines[i]
        }
        if (sourceLine > 0) {
            text = text + "  (line " + ilIntText(sourceLine) + ")"
        }
        out = out + text + "\n"
        i = i + 1
    }
    return out
}

// The dump of a whole unit: the body, then a section per lambda.
fun printIlUnit(unit: *IlUnit): Str {
    var out: Str = printIlBody(unit.body)
    for (closure in unit.closures) {
        out = out + "\n## closure " + closure.symbol + "  captures ("
        +ilJoinList(closure.captures, ", ") + ")  " + closure.signature + "\n"
        if (closure.bodyIndex >= 0 && closure.bodyIndex < unit.lambdas.size()) {
            out = out + printIlBody(unit.lambdas[closure.bodyIndex])
        }
    }
    return out
}

// The closure of a lambda: the names its body reads that are not its parameters and not
// names it declares - the class's fields. Order is first-read, so the tables reproduce.
fun ilCollectExprNames(node: *AstXmlNode, order: *List<Str>, seen: *Dictionary<Str, Bool>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (name != "this" && !seen.has(name)) {
            seen.insert(name, true)
            order.append(name)
        }
        return
    }
    // A nested lambda is its own closure: its free names resolve against its own frame, so
    // walking into it here would collect the wrong set.
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return
    }
    for (*child in node.Children) {
        ilCollectExprNames(child, order, seen)
    }
}

// The names a statement sequence reads: the statement's own expressions, then its
// containers'. A name it declares counts as declared wherever the declaration stands.
fun ilCollectStmtNames(
    stmts: *List<AstXmlNode>, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            val declaredName: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            if (!declaredName.isEmpty()) {
                declared.insert(declaredName, true)
            }
        }
        ilCollectStmtExprs(stmt, AstNodeKind.Cond, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Target, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Value, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Init, order, seen)
        ilCollectStmtExprs(stmt, AstNodeKind.Expr, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Body, declared, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Then, declared, order, seen)
        ilCollectStmtNamesIn(stmt, AstNodeKind.Else, declared, order, seen)
    }
}

fun ilCollectStmtExprs(
    stmt: *AstXmlNode, role: AstNodeKind, order: *List<Str>,
    seen: *Dictionary<Str, Bool>
): Unit {
    val child: *AstXmlNode = xmlChildPtr(stmt, role)
    if (!xmlIsEmpty(child)) {
        ilCollectExprNames(child, order, seen)
    }
}

// The statements of one container child (`Body`, `Then`, `Else`).
fun ilCollectStmtNamesIn(
    stmt: *AstXmlNode, role: AstNodeKind, declared: *Dictionary<Str, Bool>,
    order: *List<Str>, seen: *Dictionary<Str, Bool>
): Unit {
    val container: *AstXmlNode = xmlChildPtr(stmt, role)
    if (xmlIsEmpty(container)) {
        return
    }
    var i: Int = 0
    val children: Array<AstXmlNode> = container.Children
    var body: List<AstXmlNode> = List<AstXmlNode>()
    while (i < children.count()) {
        body.append(children[i])
        i = i + 1
    }
    ilCollectStmtNames(body, declared, order, seen)
}

fun ilOps1(a: Int): List<Int> {
    return listOf<Int>(a)
}

fun ilOps2(a: Int, b: Int): List<Int> {
    return listOf<Int>(a, b)
}

fun ilOps3(a: Int, b: Int, c: Int): List<Int> {
    return listOf<Int>(a, b, c)
}

fun ilOps4(a: Int, b: Int, c: Int, d: Int): List<Int> {
    return listOf<Int>(a, b, c, d)
}

// A pointer type node around `inner`, for a synthesized slot's `*T`. `inner` is copied.
fun ilPointerNode(inner: *AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypePointer,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var renamed: AstXmlNode = inner
    // A synthesized `*T` carries the `Inner` role like a parsed one.
    renamed.name = AstNodeKind.Inner
    xmlAddChild(node, renamed)
    return node
}

// A `*x` node (the operand under the `Operand` role), made when a `*T` parameter's
// argument needs its address - so it is spelled by the same code an explicit `*` reaches.
fun ilDerefNode(operand: *AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprDeref,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var renamed: AstXmlNode = operand
    renamed.name = AstNodeKind.Operand
    xmlAddChild(node, renamed)
    return node
}

// Whether an assignment's operator is a compound form (`+=`, `<<=`, ...). The step forms
// (`i++`, `i--`) are the parser's `+= 1`/`-= 1`.
fun isCompoundAssignOp(op: Str): Bool {
    return op == "+=" || op == "-=" || op == "*=" || op == "/=" || op == "%="
            || op == "&=" || op == "|=" || op == "^="
            || op == "<<=" || op == ">>="
}

// The binary operation a compound operator names. The fallback row is `%=`'s, the one
// operator the `when` does not list.
fun compoundBinaryOp(op: Str): Str {
    when (op) {
        "+=" -> {
            return "+"
        }

        "-=" -> {
            return "-"
        }

        "*=" -> {
            return "*"
        }

        "/=" -> {
            return "/"
        }

        "&=" -> {
            return "&"
        }

        "|=" -> {
            return "|"
        }

        "^=" -> {
            return "^"
        }

        "<<=" -> {
            return "<<"
        }

        ">>=" -> {
            return ">>"
        }
    }
    return "%"
}

fun ilNamedTypeNode(name: Str): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypeNamed,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// A receiver slot's type: already a pointer or a handle when the source said so, else `T*`.
fun ilReceiverTypeNode(typeNode: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return typeNode
    }
    return ilPointerNode(typeNode)
}

// Whether a receiver declared as `typeNode` is one the emitter passes as the *handle* rather than
// as `T* self`: a counted reference or the `PList` alias. Those are the shapes a raw pointer
// cannot stand in for, and an unreadable receiver type is treated as one of them.
fun ilIsReceiverRef(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return true
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference) {
        return true
    }
    if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList") {
        return true
    }
    return false
}

fun ilIsHandleType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList") {
        return true
    }
    return false
}
