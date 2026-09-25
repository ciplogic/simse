// DeadStores.kt
//
//   a = 1;  b = a + 2;      ->  (both gone: nothing in the body reads a or b)
//   a = f(x);               ->  f(x);   (the call is what the statement was for)
//
// A store to a local the body never reads is a store to nothing: the value cannot be observed.
// The name must be one this body declares - a global is read by another body. What is left of a
// dead statement is what its value did: a call keeps a statement without the left side, a
// construction cannot (`Point(1, 2);` is not an instruction, so that statement stays), and a
// value with no call in it goes whole. Dropping a store leaves the names it read unread, which
// makes *their* stores dead too, so the pass runs again until a round removes nothing.

package optimizations

import common
import linear

// Whether a call is a construction - `Point(1, 2)`, `List<Int>()`. The extractor reads such a
// callee as a type and emits `CallCtor`, which always writes a destination.
fun linDeadStoreIsConstruction(value: *AstXmlNode): Bool {
    return xmlKind(value) == AstNodeCategory.ExprCall
            && xmlKind(xmlChildPtr(value, AstNodeKind.Callee)) == AstNodeCategory.ExprGenericName
}

// Whether a value holds a call that has to happen: a function or a method call, or the argument
// of a construction. Building a closure calls nothing - its body runs when the closure is called.
fun linDeadStoreHasCall(node: *AstXmlNode): Bool {
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return false
    }
    if (xmlKind(node) == AstNodeCategory.ExprCall && !linDeadStoreIsConstruction(node)) {
        return true
    }
    for (*child in node.Children) {
        if (linDeadStoreHasCall(child)) {
            return true
        }
    }
    return false
}

// What a dead store's statement keeps: 0 nothing, 1 the call alone, 2 the statement as it stands.
fun linDeadStoreKeep(value: *AstXmlNode): Int {
    if (!linDeadStoreHasCall(value)) {
        return 0
    }
    if (linDeadStoreIsConstruction(value)) {
        return 2
    }
    if (xmlKind(value) == AstNodeCategory.ExprCall) {
        return 1
    }
    return 2
}

// Whether a statement is a store to a local this body never reads.
fun linDeadStoreIsDead(
    stmt: *AstXmlNode, declared: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Int>
): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtAssign
        || xmlAttr(stmt, AstNodeAttributeKind.Op) != "="
    ) {
        return false
    }
    val target: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
    if (xmlKind(target) != AstNodeCategory.ExprName) {
        return false
    }
    val name: Str = xmlAttr(target, AstNodeAttributeKind.Name)
    return declared.has(name) && linUseDefAt(reads, name, 0) == 0
}

// The call of a dead store, kept as a statement of its own.
fun linDeadStoreCallOf(stmt: *AstXmlNode, value: *AstXmlNode): AstXmlNode {
    var node: AstXmlNode = linStmt(AstNodeCategory.StmtExprStmt, xmlLine(stmt), xmlColumn(stmt))
    var call: AstXmlNode = linRole(value, AstNodeKind.Expr)
    xmlAddChild(node, call)
    return node
}

// One body's dead stores removed, to a fixed point: a round removes every store nothing reads at
// that point, which is what exposes the stores its value was read by.
fun linDeadStoresOptimization(stmts: *List<AstXmlNode>): Bool {
    var changed: Bool = false
    var guard: Int = 0
    while (guard < 32) {
        guard = guard + 1
        val useDefs: LinUseDefs = linUseDefsOf(stmts)
        var declared: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var reads: Dictionary<Str, Int> = Dictionary<Str, Int>()
        linUseDefDeclared(stmts, *declared)
        var i: Int = 0
        while (i < stmts.size()) {
            val stmt: *AstXmlNode = *stmts[i]
            linUseDefCount(useDefs.usesAt(i), *reads)
            // A name a lambda reads is read: the lambda may run long after the store.
            var captured: List<Str> = List<Str>()
            linUseDefCaptured(stmt, *captured)
            linUseDefCount(captured, *reads)
            i = i + 1
        }

        var out: List<AstXmlNode> = List<AstXmlNode>()
        var removed: Bool = false
        i = 0
        while (i < stmts.size()) {
            val stmt: *AstXmlNode = *stmts[i]
            val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
            var keep: Int = 2
            if (linDeadStoreIsDead(stmt, *declared, *reads)) {
                keep = linDeadStoreKeep(value)
            }
            if (keep == 0) {
                removed = true
            } else if (keep == 1) {
                out.append(linDeadStoreCallOf(stmt, value))
                removed = true
            } else {
                out.append(stmt)
            }
            i = i + 1
        }
        if (!removed) {
            return changed
        }
        stmts.clear()
        for (*stmt in out) {
            stmts.append(stmt)
        }
        changed = true
    }
    return changed
}

// Self-registration (`Optimize.kt`).
val linDeadStoresPass: Bool = registerLinOptPass("deadStores", linDeadStoresOptimization)
