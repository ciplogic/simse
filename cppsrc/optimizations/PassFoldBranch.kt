// PassFoldBranch.kt
//
// A branch with a constant condition is not a branch.
//
//   ifTrue true goto L;    ->   goto L;         (the jump was always taken)
//   ifTrue false goto L;   ->   (removed)       (fall through: the jump never was)
//   ifFalse ...                 the two, with the test inverted
//
//   _sm_expr5 = true;
//   ifFalse _sm_expr5 L2;  ->   (removed)       after `PassFoldCompare` and a constant
//
// What is left is a `StmtGoto` the peephole already knows how to fold into its target
// (`LinSimplifier.prunePass`: a jump to the statement that follows it disappears, a jump
// whose label has nothing else reaching it frees the label), which is why this pass only
// rewrites the jump and does not try to remove the label itself.
//
// The condition is read as a *literal*: `if (true)` is one, and so is a comparison or a
// call this round has already folded, because the passes run to a fixpoint and this one
// runs after them. A condition that is a *slot* is not read here - knowing that a slot
// holds a constant is constant propagation, which is a pass of its own (`PassFoldConst`),
// not something a branch fold should assume.
//
// A statement-level pass: the block walk is `PassLabels`'s, so a nested sequence is
// rewritten by the same rule and a body with no constant branch is not rebuilt at all.

package optimizations

import common
import linear

// The statement a constant branch becomes, or none when it becomes nothing. `taken` is
// whether the jump is taken; a branch that always jumps is a `goto`, and one that never
// does is dropped.
fun linFoldBranchStmt(stmt: *AstXmlNode): Opt<AstXmlNode> {
    val kind: AstNodeCategory = xmlKind(stmt)
    if (kind != AstNodeCategory.StmtIfTrue && kind != AstNodeCategory.StmtIfFalse) {
        return Opt<AstXmlNode>.none()
    }
    val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
    if (xmlIsEmpty(cond) || foldKindOf(cond) != FoldKind.Bool) {
        return Opt<AstXmlNode>.none()
    }
    val holds: Bool = xmlAttr(cond, AstNodeAttributeKind.Value) == "true"
    // `ifTrue c` jumps when `c` holds, `ifFalse c` when it does not.
    var taken: Bool = holds
    if (kind == AstNodeCategory.StmtIfFalse) {
        taken = !holds
    }
    if (taken) {
        return Opt<AstXmlNode>.some(
            linGoto(xmlAttr(stmt, AstNodeAttributeKind.Name), xmlLine(stmt), xmlColumn(stmt))
        )
    }
    // A branch that is never taken is not a statement at all: the answer is an empty node,
    // which the walk reads as "drop it".
    return Opt<AstXmlNode>.some(linStmt(AstNodeCategory.None, xmlLine(stmt), xmlColumn(stmt)))
}

fun linFoldBranchIn(stmts: *List<AstXmlNode>): Bool {
    var changed: Bool = false
    var out: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        i = i + 1
        val folded: Opt<AstXmlNode> = linFoldBranchStmt(stmt)
        if (folded.hasValue()) {
            changed = true
            val replacement: AstXmlNode = folded.value()
            if (xmlKind(replacement) != AstNodeCategory.None) {
                out.append(replacement)
            }
            continue
        }
        if (linIsBlock(stmt)) {
            var inner: List<AstXmlNode> = linBlockStmts(stmt)
            if (linFoldBranchIn(*inner)) {
                val bodyNode: AstXmlNode = exprLike(xmlChildPtr(stmt, AstNodeKind.Body), inner)
                out.append(exprReplaceRole(stmt, AstNodeKind.Body, linOne(bodyNode)))
                changed = true
                continue
            }
        }
        out.append(stmt)
    }
    if (!changed) {
        return false
    }
    // In place: the pipeline keeps the list it passed (`PassLabels` says why).
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// Self-registration (`Optimize.kt`).
val linFoldBranchPass: Bool = registerLinOptPass("foldBranch", linFoldBranchIn)
