// DeadLocals.kt
//
//   Int _sm_expr3;   ...   (no statement names _sm_expr3 any more)  ->  dropped
//
// A declaration nothing names is storage nothing uses - and for a `Str`, a `List` or a class, a
// constructor and a destructor with it. This is the other half of `DeadStores.kt`: the store
// goes first, the storage it wrote is left over, and the next round finds it. A bare declaration
// is neither a read nor a write of its own name, which is what leaves the storage behind.

package optimizations

import common
import linear

// The declaration of a name nothing names: not read, not written, not captured by a lambda.
fun linDeadLocalsOptimization(stmts: *List<AstXmlNode>): Bool {
    val useDefs: LinUseDefs = linUseDefsOf(stmts)
    var named: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var declared: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    linUseDefDeclared(stmts, *declared)
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        linUseDefCount(useDefs.usesAt(i), *named)
        linUseDefCount(useDefs.defsAt(i), *named)
        var captured: List<Str> = List<Str>()
        linUseDefCaptured(stmt, *captured)
        linUseDefCount(captured, *named)
        i = i + 1
    }

    var drop: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    val names: List<Str> = declared.keys()
    i = 0
    while (i < names.size()) {
        if (linUseDefAt(*named, names[i], 0) == 0) {
            drop.insert(names[i], true)
        }
        i = i + 1
    }
    if (drop.size() == 0) {
        return false
    }

    var out: List<AstXmlNode> = List<AstXmlNode>()
    var removed: Bool = false
    i = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl
            && xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))
            && drop.has(xmlAttr(stmt, AstNodeAttributeKind.Name))
        ) {
            removed = true
        } else {
            out.append(stmt)
        }
        i = i + 1
    }
    if (!removed) {
        return false
    }
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// Self-registration (`Optimize.kt`).
val linDeadLocalsPass: Bool = registerLinOptPass("deadLocals", linDeadLocalsOptimization)
