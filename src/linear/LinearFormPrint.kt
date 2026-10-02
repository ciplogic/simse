// LinearFormPrint.kt
//
// Printing and reading the IL: `printIlBody`/`printIlUnit`, the per-op comments, and the
// node helpers the extractor builds with.

package linear
import compiler

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
            val ilVarNameText: Str = ilVarName(body, slot)
            val ilVarTypeNameText: Str = ilVarTypeName(body, slot)
            return `var @ilVarNameText: @ilVarTypeNameText`
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
                return `if (@condition) goto @target`
            }
            return `if (!@condition) goto @target`
        }

        IlOpKind.SetVar -> {
            val ilVarNameText2: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText3: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilVarNameText2 = @ilVarNameText3`
        }

        IlOpKind.SetVar_Null -> {
            return ilVarName(body, ilOperandAt(operands, 0)) + " = null"
        }

        IlOpKind.BinaryOp -> {
            val ilVarNameText4: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText5: Str = ilVarName(body, ilOperandAt(operands, 2))
            val ilPoolTextText: Str = ilPoolText(body, ilOperandAt(operands, 1))
            val ilVarNameText6: Str = ilVarName(body, ilOperandAt(operands, 3))
            return `@ilVarNameText4 = @ilVarNameText5 @ilPoolTextText @ilVarNameText6`
        }

        IlOpKind.UnaryOp -> {
            val ilVarNameText7: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilPoolTextText2: Str = ilPoolText(body, ilOperandAt(operands, 1))
            val ilVarNameText8: Str = ilVarName(body, ilOperandAt(operands, 2))
            return `@ilVarNameText7 = @ilPoolTextText2@ilVarNameText8`
        }

        IlOpKind.Cast -> {
            val ilVarNameText9: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText10: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilVarNameText9 = cast @ilVarNameText10`
        }

        IlOpKind.Box -> {
            val ilVarNameText11: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText12: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilVarNameText11 = &@ilVarNameText12`
        }

        IlOpKind.Deref -> {
            val ilVarNameText13: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText14: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilVarNameText13 = *@ilVarNameText14`
        }

        IlOpKind.CopyValue -> {
            val ilVarNameText15: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText16: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilVarNameText15 = copy(@ilVarNameText16)`
        }

        IlOpKind.Store -> {
            val ilVarNameText17: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText18: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `*@ilVarNameText17 = @ilVarNameText18`
        }

        IlOpKind.GetField -> {
            val ilVarNameText19: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText20: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilPoolTextText3: Str = ilPoolText(body, ilOperandAt(operands, 2))
            return `@ilVarNameText19 = @ilVarNameText20.@ilPoolTextText3`
        }

        IlOpKind.SetField -> {
            val ilVarNameText21: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilPoolTextText4: Str = ilPoolText(body, ilOperandAt(operands, 0))
            val ilVarNameText22: Str = ilVarName(body, ilOperandAt(operands, 2))
            return `@ilVarNameText21.@ilPoolTextText4 = @ilVarNameText22`
        }

        IlOpKind.GetIndex -> {
            val ilVarNameText23: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText24: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilVarNameText25: Str = ilVarName(body, ilOperandAt(operands, 2))
            return `@ilVarNameText23 = @ilVarNameText24[@ilVarNameText25]`
        }

        IlOpKind.SetIndex -> {
            val ilVarNameText26: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilVarNameText27: Str = ilVarName(body, ilOperandAt(operands, 2))
            val ilVarNameText28: Str = ilVarName(body, ilOperandAt(operands, 3))
            return `@ilVarNameText26[@ilVarNameText27] = @ilVarNameText28`
        }

        IlOpKind.FieldAddr -> {
            val ilVarNameText29: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText30: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilPoolTextText5: Str = ilPoolText(body, ilOperandAt(operands, 2))
            return `@ilVarNameText29 = &@ilVarNameText30.@ilPoolTextText5`
        }

        IlOpKind.IndexAddr -> {
            val ilVarNameText31: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilVarNameText32: Str = ilVarName(body, ilOperandAt(operands, 1))
            val ilVarNameText33: Str = ilVarName(body, ilOperandAt(operands, 2))
            return `@ilVarNameText31 = &@ilVarNameText32[@ilVarNameText33]`
        }

        IlOpKind.GetStatic -> {
            val ilVarNameText34: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilPoolTextText6: Str = ilPoolText(body, ilOperandAt(operands, 1))
            return `@ilVarNameText34 = @ilPoolTextText6`
        }

        IlOpKind.GetStaticAddr -> {
            val ilVarNameText35: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilPoolTextText7: Str = ilPoolText(body, ilOperandAt(operands, 1))
            return `@ilVarNameText35 = &@ilPoolTextText7`
        }

        IlOpKind.SetStatic -> {
            val ilPoolTextText8: Str = ilPoolText(body, ilOperandAt(operands, 0))
            val ilVarNameText36: Str = ilVarName(body, ilOperandAt(operands, 1))
            return `@ilPoolTextText8 = @ilVarNameText36`
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
                val ilIntTextText: Str = ilIntText(methodOp)
                val ilArgListText: Str = ilArgList(body, operands, first)
                return `@dst?m@ilIntTextText(@ilArgListText)`
            }
            val method: IlMethod = body.methods[methodOp]
            if (method.kind == IlMethodKind.Method && first < operands.size()) {
                val ilVarNameText37: Str = ilVarName(body, operands[first])
                val methodNameText: Str = method.name
                val ilArgListText2: Str = ilArgList(body, operands, first + 1)
                return `@dst@ilVarNameText37.@methodNameText(@ilArgListText2)`
            }
            val methodText: Str = method.name
            val argsText: Str = ilArgList(body, operands, first)
            return `@dst@methodText(@argsText)`
        }

        IlOpKind.CallIndirect, IlOpKind.CallIndirectVoid -> {
            val hasDst2: Bool = kind == IlOpKind.CallIndirect
            var dst2: Str
            var calleeAt: Int = 0
            if (hasDst2) {
                dst2 = ilVarName(body, ilOperandAt(operands, 0)) + " = "
                calleeAt = 1
            }
            val calleeText: Str = ilVarName(body, ilOperandAt(operands, calleeAt))
            val argsText2: Str = ilArgList(body, operands, calleeAt + 1)
            return `@dst2@calleeText(@argsText2)`
        }

        IlOpKind.Pack -> {
            val ilVarNameText38: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilArgListText3: Str = ilArgList(body, operands, 1)
            return `@ilVarNameText38 = [@ilArgListText3]`
        }

        IlOpKind.Concat -> {
            val ilVarNameText39: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilArgListText4: Str = ilArgList(body, operands, 1)
            return `@ilVarNameText39 = concat(@ilArgListText4)`
        }

        IlOpKind.CallCtor -> {
            val ilVarNameText40: Str = ilVarName(body, ilOperandAt(operands, 0))
            val ilTypeNameText: Str = ilTypeName(body, ilOperandAt(operands, 1))
            val ilArgListText5: Str = ilArgList(body, operands, 2)
            return `@ilVarNameText40 = new @ilTypeNameText(@ilArgListText5)`
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
            val ilPoolTextText9: Str = ilPoolText(body, ilOperandAt(operands, 1))
            return `<unsupported: @ilPoolTextText9>`
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
        val ilIntTextText2: Str = ilIntText(i)
        val typesText: Str = body.types[i]
        types.append(`@ilIntTextText2 @typesText`)
        i = i + 1
    }
    ilAppendTable(out, "types:   ", types)

    var vars: List<Str> = List<Str>()
    i = 0
    while (i < body.vars.size()) {
        val slot: IlVar = body.vars[i]
        val indexText: Str = ilIntText(i)
        val slotNameText: Str = slot.name
        val typeIndexText: Str = ilIntText(slot.typeIndex)
        val kindText: Str = ilVarKindText(slot.kind)
        vars.append(`@indexText @slotNameText:@typeIndexText:@kindText`)
        i = i + 1
    }
    ilAppendTable(out, "vars:    ", vars)

    var pool: List<Str> = List<Str>()
    i = 0
    while (i < body.pool.size()) {
        val ilIntTextText3: Str = ilIntText(i)
        val ilPoolAsTextText: Str = ilPoolAsText(body.pool[i])
        pool.append(`@ilIntTextText3 @ilPoolAsTextText`)
        i = i + 1
    }
    ilAppendTable(out, "pool:    ", pool)

    var methods: List<Str> = List<Str>()
    i = 0
    while (i < body.methods.size()) {
        val method: IlMethod = body.methods[i]
        val ilIntTextText4: Str = ilIntText(i)
        val methodNameText2: Str = method.name
        val ilMethodKindTextText: Str = ilMethodKindText(method.kind)
        val ilIntTextText5: Str = ilIntText(method.argCount)
        var text: Str = `@ilIntTextText4 @methodNameText2:@ilMethodKindTextText:@ilIntTextText5`
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
        val ilIntTextText6: Str = ilIntText(i)
        val labelsText: Str = body.labels[i]
        labels.append(`@ilIntTextText6 @labelsText`)
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

        val ilPadRightText: Str = ilPadRight(ilIntText(i), 4)
        val ilPadRightText2: Str = ilPadRight(ilOpKindText(op.kind), 16)
        val joinStrsText: Str = joinStrs(rendered, ", ")
        var text: Str = `@ilPadRightText,  @ilPadRightText2@joinStrsText`
        val comment: Str = ilOpComment(body, op)
        if (!comment.isEmpty()) {
            val ilPadRightText3: Str = ilPadRight(text, 74)
            text = `@ilPadRightText3# @comment`
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
        +joinStrs(closure.captures, ", ") + ")  " + closure.signature + "\n"
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
