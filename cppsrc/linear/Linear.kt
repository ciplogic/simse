// Linear.kt
//
// Post-sema lowering of structured control flow into labels and gotos, ported
// from cppsrc/linear/Linear.cpp (impl_specs/linear-lowering.md). The emitter
// consumes only the linear forms, so it no longer knows If/While/Switch/
// Break/Continue:
//
//   label L;            -> Stmt.Label
//   goto L;             -> Stmt.Goto
//   if (c) goto L;      -> Stmt.IfTrue
//   if (!(c)) goto L;   -> Stmt.IfFalse
//   { ... }             -> Stmt.Block
//
// Bodies are wrapped in blocks because a C++ jump may not bypass a declaration
// that is still in scope at the target; the language already scopes each
// branch/loop body separately, so the wrapper preserves semantics.
//
// Label numbering restarts at L1 for every body (function/method or lambda):
// labels are function scoped in C++, so per-body numbering cannot collide, and
// re-emitting a body (generic instantiation) always produces the same names.
// The hoisted `switch` subject is named `simse_sw_<n>` from the same counter.
package linear

import common

// ---- node construction -----------------------------------------------------

fun linStmt(kind: AstNodeCategory, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
    )
    return AstXmlNode(AstNodeKind.Stmt, kind, attrs, Array<AstXmlNode>())
}

// The same node under a new structural role; the AST carries roles in the
// element name (impl_specs/ast-xmlnode.md), so a reused expression has to be
// re-rooted for its new position.
fun linRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    var renamed: AstXmlNode = child
    renamed.name = role
    return renamed
}

fun linLabel(name: Str, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtLabel, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

fun linGoto(name: Str, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtGoto, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// `kind` is StmtIfTrue or StmtIfFalse. The condition is re-rooted under `Cond`: a
// condition that came from the source already carries that role, but one a lowering
// *builds* (the yield machine's `if (branch == n) goto LYn;`) carries `Expr`, and the
// emitter finds its condition by role.
fun linCondJump(kind: AstNodeCategory, cond: *AstXmlNode, name: Str, line: Int, column: Int): AstXmlNode {
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

// The hoisted `switch` subject: an untyped VarDecl, so the emitter emits `auto`.
fun linSubjectDecl(name: Str, init: *AstXmlNode, line: Int, column: Int): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtVarDecl, line, column)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, "false"))
    xmlAddChild(node, linRole(init, AstNodeKind.Init))
    return node
}

fun linName(role: AstNodeKind, name: Str, line: Int, column: Int): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, column.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Name, name)
    )
    return AstXmlNode(role, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>())
}

// ---- the lowering ----------------------------------------------------------

// What one stage of the linear form produced: the body, and whether the stage
// changed anything. The stages run in a loop - each can leave work for the
// others - and stop when a whole round changes nothing, so every stage has to
// report the work it did and, just as important, the work it did not do.
data class LinLowered(
    var body: List<AstXmlNode>,

    var changed: Bool
)

data class LinLowerer(
    var next: Int,

// Whether this pass actually lowered anything.
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

    // One body in, one linear body out. Pure: the input statements are not
    // modified (only copied or re-rooted).
    fun lowerBody(stmts: List<AstXmlNode>): LinLowered {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        this.lowerStmts(stmts, "", "", out)
        return LinLowered(out, this.changed)
    }

    // breakTo/continueTo are empty when no enclosing construct accepts them.
    //
    // A statement is a value (`List<AstXmlNode>` holds them by value), so the pointer
    // form is what keeps the pass from copying every statement it walks: `*stmt` is the
    // element's place, and the passes it is handed to read through it.
    fun lowerStmts(stmts: *List<AstXmlNode>, breakTo: Str, continueTo: Str, out: *List<AstXmlNode>): Unit {
        for (*stmt in stmts) {
            this.lowerStmt(stmt, breakTo, continueTo, out)
        }
    }

    fun lowerStmt(stmt: *AstXmlNode, breakTo: Str, continueTo: Str, out: *List<AstXmlNode>): Unit {
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

    // A region needs its own C++ scope only when it declares a variable at its
    // own level: a jump may not bypass an initialization that is still in scope
    // at the target. Everything else is spliced flat into the enclosing
    // sequence, which keeps the emitted code compact.
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

    // Whether an expression contains `&&` or `||` anywhere - including inside a call's
    // arguments, where the *value* form applies and nothing here can decompose it.
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

    // A condition that is nothing but boolean operators (`&&`, `||`, `!`) and leaves
    // with no short-circuit inside them: the conditions this pass can decompose into
    // jumps.
    fun isDecomposable(e: *AstXmlNode): Bool {
        if (xmlKind(e) == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "&&" || op == "||") {
                return this.isDecomposable(xmlChild(e, AstNodeKind.Lhs))
                        && this.isDecomposable(xmlChild(e, AstNodeKind.Rhs))
            }
        }
        if (xmlKind(e) == AstNodeCategory.ExprUnary && xmlAttr(e, AstNodeAttributeKind.Op) == "!") {
            return this.isDecomposable(xmlChild(e, AstNodeKind.Operand))
        }
        return !this.containsShortCircuit(e)
    }

    // Lowers a boolean condition into conditional jumps, one per leaf, with `&&`/`||`
    // evaluating short-circuit exactly as the `if`/`while` they came from
    // (impl_specs/linear-lowering.md, "Short-circuit operators"). A leaf is tested with
    // whichever of `IfTrue`/`IfFalse` matches its value, so no negation is spelled out
    // here; `simplify`, which runs next, folds each leaf's jump pair into one
    // conditional jump and drops the labels the chain no longer needs.
    fun lowerCondition(
        cond: *
        AstXmlNode,
        trueTarget: Str,
        falseTarget: Str,
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
                    // Both operands must hold: the first one that does not jumps
                    // straight past the rest.
                    this.lowerCondition(xmlChild(cond, AstNodeKind.Lhs), mid, falseTarget, line, column, out)
                    out.append(linLabel(mid, line, column))
                    this.lowerCondition(xmlChild(cond, AstNodeKind.Rhs), trueTarget, falseTarget, line, column, out)
                } else {
                    // The first operand that holds jumps straight to the target.
                    this.lowerCondition(xmlChild(cond, AstNodeKind.Lhs), trueTarget, mid, line, column, out)
                    out.append(linLabel(mid, line, column))
                    this.lowerCondition(xmlChild(cond, AstNodeKind.Rhs), trueTarget, falseTarget, line, column, out)
                }
                return
            }
        }
        if (xmlKind(cond) == AstNodeCategory.ExprUnary && xmlAttr(cond, AstNodeAttributeKind.Op) == "!") {
            // `!x` is `x` with its outcomes swapped: the test itself is never negated
            // here, `IfTrue`/`IfFalse` covers both polarities.
            this.lowerCondition(xmlChild(cond, AstNodeKind.Operand), falseTarget, trueTarget, line, column, out)
            return
        }
        val leaf: AstXmlNode = linRole(cond, AstNodeKind.Cond)
        out.append(linCondJump(AstNodeCategory.StmtIfTrue, leaf, trueTarget, line, column))
        out.append(linGoto(falseTarget, line, column))
    }

    fun lowerIf(stmt: *AstXmlNode, breakTo: Str, continueTo: Str, out: *List<AstXmlNode>): Unit {
        val thenLabel: Str = this.freshLabel()
        val elseLabel: Str = this.freshLabel()
        val line: Int = xmlLine(stmt)
        val column: Int = xmlColumn(stmt)
        val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
        if (this.containsShortCircuit(cond) && this.isDecomposable(cond)) {
            this.lowerCondition(cond, thenLabel, elseLabel, line, column, out)
        } else {
            out.append(linCondJump(AstNodeCategory.StmtIfTrue, cond, thenLabel, line, column))
            out.append(linGoto(elseLabel, line, column))
        }
        out.append(linLabel(thenLabel, line, column))
        var thenOut: List<AstXmlNode> = List<AstXmlNode>()
        this.lowerStmts(
            xmlChildren(xmlChild(stmt, AstNodeKind.Then), AstNodeKind.Stmt),
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
                xmlChildren(xmlChild(stmt, AstNodeKind.Else), AstNodeKind.Stmt),
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
            xmlChildren(xmlChild(stmt, AstNodeKind.Body), AstNodeKind.Stmt),
            endLabel,
            condLabel,
            bodyOut
        )
        out.append(linLabel(condLabel, line, column))
        val cond: AstXmlNode = xmlChild(stmt, AstNodeKind.Cond)
        if (this.containsShortCircuit(cond) && this.isDecomposable(cond)) {
            // The body label is where a holding operand lands; the fold in `simplify`
            // removes it again when only one jump remains.
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
fun linLowerBody(stmts: List<AstXmlNode>): LinLowered {
    var lowerer: LinLowerer = LinLowerer(1, false)
    return lowerer.lowerBody(stmts)
}

// The names the lowering generates for its own storage: the expression lowering's
// temporaries (`_sm_expr<n>`). A name the program wrote can collide with one of
// these - an accepted, documented risk - but nothing the lowering generates came
// from the source, which is what the slot hoisting asks about (Linear.h).
fun linIsSlotName(name: Str): Bool {
    return name.startsWith("_sm_expr")
}

// The whole linear form of one function-like body, ready to emit: the stages
// (`linLowerBody`, `linSimplifyBody`, `linLowerExprs`) run in a loop until none of
// them has work left, then the block folding (`linFlattenBlocks`) runs, and while
// that changed something the loop starts over. Folding is what lets the next round
// see a flatter body - and a jump a block used to hide is a jump the peephole can
// fold.
fun linLowerForEmission(body: List<AstXmlNode>): List<AstXmlNode> {
    var current: List<AstXmlNode> = body
    var canChange: Bool = true
    var guard: Int = 0
    // A round only removes statements (it never adds any), so the loop below always
    // terminates; the guard is there to bound a bug, not the work.
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
