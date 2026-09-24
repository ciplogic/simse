// Linear.kt
//
// Structured control flow lowered to labels and gotos (impl_specs/linear-lowering.md); the
// emitter sees only these forms. A body is wrapped in a block where a jump would otherwise
// bypass a declaration in scope at its target; labels restart at L1 per body, so C++'s
// per-function scope cannot collide across bodies.
package linear

import common

fun linStmt(kind: AstNodeCategory, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
    )
    return AstXmlNode(AstNodeKind.Stmt, kind, attrs, Array<AstXmlNode>())
}

// The same node under a new structural role; roles live in the element name
// (impl_specs/ast-xmlnode.md), so a reused expression must be re-rooted.
fun linRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    var renamed: AstXmlNode = child
    renamed.name = role
    return renamed
}

fun linLabel(name: *Str, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtLabel, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

fun linGoto(name: *Str, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtGoto, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// The condition is re-rooted under `Cond`: a lowering-*built* jump carries `Expr`, and the
// emitter finds its condition by role.
fun linCondJump(kind: AstNodeCategory, cond: *AstXmlNode, name: *Str, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(kind, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    xmlAddChild(node, linRole(cond, AstNodeKind.Cond))
    return node
}

fun linBlock(body: *List<AstXmlNode>, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtBlock, line, column)
    xmlAddChild(node, AstXmlNode(AstNodeKind.Body, AstNodeCategory.None, List<AstNodeAttribute>(), body.toArray()))
    return node
}

// An untyped `VarDecl`: the emitter spells it `auto`.
fun linSubjectDecl(name: *Str, init: *AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtVarDecl, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, "false"))
    xmlAddChild(node, linRole(init, AstNodeKind.Init))
    return node
}

fun linName(role: AstNodeKind, name: *Str, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, column.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Name, name)
    )
    return AstXmlNode(role, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>())
}

// One stage's output: the body, and whether that stage changed anything. Every stage has to
// report the work it did *not* do: the stages loop until a whole round changes nothing.
data class LinLowered(
    var body: List<AstXmlNode>,

    var changed: Bool
)

data class LinLowerer(
    var next: Int,
    var changed: Bool
) {
    fun nextId(): Int {
        val id: Int = this.next
        this.next = this.next + 1
        return id
    }

    fun freshLabel(): Str {
        return "L" + this.nextId().toString()
    }

    // One body in, one linear body out; the input statements are not modified (only copied
    // or re-rooted).
    fun lowerBody(stmts: *List<AstXmlNode>): LinLowered {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        this.lowerStmts(stmts, "", "", out)
        return LinLowered(out, this.changed)
    }

    // breakTo/continueTo are empty when no enclosing construct accepts them.
    fun lowerStmts(stmts: *List<AstXmlNode>, breakTo: *Str, continueTo: *Str, out: *List<AstXmlNode>): Unit {
        for (*stmt in stmts) {
            this.lowerStmt(stmt, breakTo, continueTo, out)
        }
    }

    fun lowerStmt(stmt: *AstXmlNode, breakTo: *Str, continueTo: *Str, out: *List<AstXmlNode>): Unit {
        val kind: AstNodeCategory = xmlKind(stmt)
        when (kind) {
            AstNodeCategory.StmtIf -> {
                this.changed = true
                this.lowerIf(stmt, breakTo, continueTo, out)
                return
            }

            AstNodeCategory.StmtWhile -> {
                this.changed = true
                this.lowerWhile(stmt, breakTo, continueTo, out)
                return
            }

            AstNodeCategory.StmtBreak -> {
                if (breakTo != "") {
                    this.changed = true
                    out.append(linGoto(breakTo, xmlLine(stmt), xmlColumn(stmt)))
                    return
                }
                out.append(stmt)
                return
            }

            AstNodeCategory.StmtContinue -> {
                if (continueTo != "") {
                    this.changed = true
                    out.append(linGoto(continueTo, xmlLine(stmt), xmlColumn(stmt)))
                    return
                }
                out.append(stmt)
                return
            }
        }
        out.append(stmt)
    }

    // A region needs its own C++ scope only when it declares a variable at its own level; a
    // jump may not bypass an initialization still in scope at the target.
    fun appendBody(body: *List<AstXmlNode>, line: Int, column: Int, out: *List<AstXmlNode>): Unit {
        if (this.declares(body)) {
            out.append(linBlock(body, line, column))
            return
        }
        for (*stmt in body) {
            out.append(*stmt)
        }
    }

    fun declares(body: *List<AstXmlNode>): Bool {
        for (*stmt in body) {
            if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
                return true
            }
        }
        return false
    }

    // Whether an expression contains `&&` or `||` anywhere, including inside a call's
    // arguments, where nothing here can decompose it.
    fun containsShortCircuit(e: *AstXmlNode): Bool {
        if (xmlKind(e) == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "&&" || op == "||") {
                return true
            }
        }
        val kids: List<AstXmlNode> = e.Children.toList()
        for (*kid in kids) {
            if (this.containsShortCircuit(kid)) {
                return true
            }
        }
        return false
    }

    // A condition that is nothing but `&&`/`||`/`!` over leaves with no short-circuit
    // inside them: what this pass can decompose into jumps.
    fun isDecomposable(e: *AstXmlNode): Bool {
        if (xmlKind(e) == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "&&" || op == "||") {
                return this.isDecomposable(xmlChildPtr(e, AstNodeKind.Lhs))
                        && this.isDecomposable(xmlChildPtr(e, AstNodeKind.Rhs))
            }
        }
        if (xmlKind(e) == AstNodeCategory.ExprUnary && xmlAttr(e, AstNodeAttributeKind.Op) == "!") {
            return this.isDecomposable(xmlChildPtr(e, AstNodeKind.Operand))
        }
        return !this.containsShortCircuit(e)
    }

    // A boolean condition as conditional jumps, one per leaf, `&&`/`||` short-circuiting
    // as the `if`/`while` they came from (impl_specs/linear-lowering.md, "Short-circuit
    // operators"). A leaf is tested with whichever of `IfTrue`/`IfFalse` matches its value,
    // so no negation is spelled out here.
    fun lowerCondition(
        cond: *
        AstXmlNode,
        trueTarget: *Str,
        falseTarget: *Str,
        line: Int,
        column: Int,
        out: *
        List<AstXmlNode>
    ): Unit {
        if (xmlKind(cond) == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(cond, AstNodeAttributeKind.Op)
            if (op == "&&" || op == "||") {
                val mid: Str = this.freshLabel()
                if (op == "&&") {
                    // Both operands must hold: the first that does not jumps past the rest.
                    this.lowerCondition(xmlChildPtr(cond, AstNodeKind.Lhs), mid, falseTarget, line, column, out)
                    out.append(linLabel(mid, line, column))
                    this.lowerCondition(xmlChildPtr(cond, AstNodeKind.Rhs), trueTarget, falseTarget, line, column, out)
                } else {
                    // The first operand that holds jumps to the target.
                    this.lowerCondition(xmlChildPtr(cond, AstNodeKind.Lhs), trueTarget, mid, line, column, out)
                    out.append(linLabel(mid, line, column))
                    this.lowerCondition(xmlChildPtr(cond, AstNodeKind.Rhs), trueTarget, falseTarget, line, column, out)
                }
                return
            }
        }
        if (xmlKind(cond) == AstNodeCategory.ExprUnary && xmlAttr(cond, AstNodeAttributeKind.Op) == "!") {
            // `!x` is `x` with its outcomes swapped; `IfTrue`/`IfFalse` covers both
            // polarities.
            this.lowerCondition(xmlChildPtr(cond, AstNodeKind.Operand), falseTarget, trueTarget, line, column, out)
            return
        }
        val leaf: AstXmlNode = linRole(cond, AstNodeKind.Cond)
        out.append(linCondJump(AstNodeCategory.StmtIfTrue, leaf, trueTarget, line, column))
        out.append(linGoto(falseTarget, line, column))
    }

    fun lowerIf(stmt: *AstXmlNode, breakTo: *Str, continueTo: *Str, out: *List<AstXmlNode>): Unit {
        val thenLabel: Str = this.freshLabel()
        val elseLabel: Str = this.freshLabel()
        val line: Int = xmlLine(stmt)
        val column: Int = xmlColumn(stmt)
        val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
        if (this.containsShortCircuit(cond) && this.isDecomposable(cond)) {
            this.lowerCondition(cond, thenLabel, elseLabel, line, column, out)
        } else {
            out.append(linCondJump(AstNodeCategory.StmtIfTrue, cond, thenLabel, line, column))
            out.append(linGoto(elseLabel, line, column))
        }
        out.append(linLabel(thenLabel, line, column))
        var thenOut: List<AstXmlNode> = List<AstXmlNode>()
        this.lowerStmts(
            xmlChildren(xmlChildPtr(stmt, AstNodeKind.Then), AstNodeKind.Stmt),
            breakTo,
            continueTo,
            thenOut
        )
        this.appendBody(thenOut, line, column, out)
        if (xmlHasChild(stmt, AstNodeKind.Else)) {
            val endLabel: Str = this.freshLabel()
            out.append(linGoto(endLabel, line, column))
            out.append(linLabel(elseLabel, line, column))
            var elseOut: List<AstXmlNode> = List<AstXmlNode>()
            this.lowerStmts(
                xmlChildren(xmlChildPtr(stmt, AstNodeKind.Else), AstNodeKind.Stmt),
                breakTo,
                continueTo,
                elseOut
            )
            this.appendBody(elseOut, line, column, out)
            out.append(linLabel(endLabel, line, column))
        } else {
            out.append(linLabel(elseLabel, line, column))
        }
    }

    fun lowerWhile(stmt: *AstXmlNode, breakTo: Str, continueTo: Str, out: *List<AstXmlNode>): Unit {
        val condLabel: Str = this.freshLabel()
        val endLabel: Str = this.freshLabel()
        val line: Int = xmlLine(stmt)
        val column: Int = xmlColumn(stmt)
        var bodyOut: List<AstXmlNode> = List<AstXmlNode>()
        this.lowerStmts(
            xmlChildren(xmlChildPtr(stmt, AstNodeKind.Body), AstNodeKind.Stmt),
            endLabel,
            condLabel,
            bodyOut
        )
        out.append(linLabel(condLabel, line, column))
        val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
        if (this.containsShortCircuit(cond) && this.isDecomposable(cond)) {
            // Where a holding operand lands; `linSimplifyBody` folds it away when one jump
            // remains.
            val bodyLabel: Str = this.freshLabel()
            this.lowerCondition(cond, bodyLabel, endLabel, line, column, out)
            out.append(linLabel(bodyLabel, line, column))
        } else {
            out.append(linCondJump(AstNodeCategory.StmtIfFalse, cond, endLabel, line, column))
        }
        this.appendBody(bodyOut, line, column, out)
        out.append(linGoto(condLabel, line, column))
        out.append(linLabel(endLabel, line, column))
    }
}

// Lowers one function-like body; the counter restarts per body.
fun linLowerBody(stmts: *List<AstXmlNode>): LinLowered {
    var lowerer: LinLowerer = LinLowerer(1, false)
    return lowerer.lowerBody(stmts)
}

// The lowering's own storage names (`_sm_expr<n>`): nothing the lowering generates came
// from the source, which is what the slot hoisting asks about.
fun linIsSlotName(name: Str): Bool {
    return name.startsWith("_sm_expr")
}

// The whole linear form of one function-like body, ready to emit: the stages run in a loop
// until none has work left, then the block folding runs and the loop starts over. Folding
// is what lets the next round see a flatter body.
fun linLowerForEmission(body: *List<AstXmlNode>): List<AstXmlNode> {
    var current: List<AstXmlNode> = body
    var canChange: Bool = true
    var guard: Int = 0
    // A round only removes statements, so the loop terminates; the guard bounds a bug, not
    // the work.
    while (canChange && guard < 256) {
        guard = guard + 1
        var canExtract: Bool = true
        while (canExtract) {
            canExtract = false
            val lowered: LinLowered = linLowerBody(current)
            current = lowered.body
            canExtract = canExtract || lowered.changed
            val simplified: LinLowered = linSimplifyBody(current)
            current = simplified.body
            canExtract = canExtract || simplified.changed
            val extracted: LinLowered = linLowerExprs(current)
            current = extracted.body
            canExtract = canExtract || extracted.changed
        }
        val flattened: LinLowered = linFlattenBlocks(current)
        current = flattened.body
        canChange = flattened.changed
    }
    return current
}
