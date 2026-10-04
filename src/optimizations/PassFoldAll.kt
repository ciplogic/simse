// PassFoldAll.kt
//
// The literal folds as one pass. The four rules live with their subject (`FoldGlobals.kt`,
// `PassFoldArith.kt`, `PassFoldCompare.kt`, `PassFoldToString.kt`); what is here is the single
// traversal that offers all of them (`foldAllRules`, `FoldExprs.kt`) and its registration.
//
// It is one pass and not four because the walk is the cost: each rule is a bottom-up rewrite of
// the same nodes, so a walk per rule visited every node in every body four times a round, and the
// fixpoint runs the round until nothing moves.

package optimizations
import compiler

import common

// Built once: the walk reads it per body and the list never changes.
var foldAllRuleList: List<FoldRule> = foldAllRules()

fun linFoldAllBody(stmts: *List<AstXmlNode>, useDefs: &LinUseDefs): Bool {
    return foldExprsInList(stmts, *foldAllRuleList)
}

// Self-registration (`Optimize.kt`). The fold walk reads no use-def facts, so the box passes
// through untouched.
val linFoldAllPass: Bool = registerLinOptPass("foldAll", linFoldAllBody)
