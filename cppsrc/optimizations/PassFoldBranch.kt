// PassFoldBranch.kt
//
//   ifTrue true goto L;    ->   goto L;         ifTrue false goto L;   ->   (removed)
// A constant condition folds the branch: taken is a `goto`, never-taken is dropped (inverted for
// `ifFalse`). The condition must be a *literal* - a slot holding one is `PassFoldConst`'s job -
// and the label is left to `LinSimplifier.prunePass`.

package optimizations

import common
import linear

// The statement a constant branch becomes: a `goto` when always taken, nothing when never.
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
    // A never-taken branch answers an empty node, which the walk drops.
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
    // In place: the pipeline keeps the list it passed (`PassLabels`).
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// Self-registration (`Optimize.kt`).
val linFoldBranchPass: Bool = registerLinOptPass("foldBranch", linFoldBranchIn)
