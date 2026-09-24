// PassJumps.kt
//
//   goto L8;  ...  L8:;  goto L1;   ->   goto L1;  ...  L8:;  goto L1;
//
// Landing on `L8` is landing on `L1`, so every jump naming `L8` names `L1`. The label stays: a
// fallthrough still has to take the jump, and only `LinSimplifier.labelPass` may drop it.

package optimizations

import common
import linear

// The end of the chain a label starts: the last target reachable by jumping label to label.
fun linResolveJumpTarget(start: Str, next: *Dictionary<Str, Str>): Str {
    var current: Str = start
    var guard: Int = 0
    while (guard < 8) {
        guard = guard + 1
        if (!next.has(current)) {
            return current
        }
        val step: Str = next.get(current).value()
        if (step == current) {
            return current
        }
        current = step
    }
    return current
}

// Every label whose next statement is an unconditional `goto`, as `label -> its chain end`.
fun linCollectJumpThreads(stmts: *List<AstXmlNode>, next: *Dictionary<Str, Str>): Unit {
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        i = i + 1
        if (linIsLabel(stmt)) {
            if (i < stmts.size() && linIsGoto(*stmts[i])) {
                next.insert(
                    xmlAttr(stmt, AstNodeAttributeKind.Name),
                    xmlAttr(*stmts[i], AstNodeAttributeKind.Name)
                )
            }
            continue
        }
        if (linIsBlock(stmt)) {
            var inner: List<AstXmlNode> = linBlockStmts(stmt)
            linCollectJumpThreads(*inner, next)
        }
    }
}

// The sequence with every jump through `threads` retargeted; only jumps that change are rebuilt.
fun linThreadJumpsIn(stmts: *List<AstXmlNode>, threads: *Dictionary<Str, Str>): Bool {
    var changed: Bool = false
    var out: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        i = i + 1
        if (linIsGoto(stmt) || linIsCondJump(stmt)) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            if (threads.has(name)) {
                val target: Str = threads.get(name).value()
                if (target != name) {
                    out.append(linRetargetJump(stmt, target))
                    changed = true
                    continue
                }
            }
            out.append(stmt)
            continue
        }
        if (linIsBlock(stmt)) {
            var inner: List<AstXmlNode> = linBlockStmts(stmt)
            if (linThreadJumpsIn(*inner, threads)) {
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
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// One body's jump chains, threaded. Two walks: the whole body is read before any jump moves.
fun linThreadJumps(stmts: *List<AstXmlNode>): Bool {
    var next: Dictionary<Str, Str> = Dictionary<Str, Str>()
    linCollectJumpThreads(stmts, *next)
    if (next.size() == 0) {
        return false
    }
    var threads: Dictionary<Str, Str> = Dictionary<Str, Str>()
    val names: List<Str> = next.keys()
    var i: Int = 0
    while (i < names.size()) {
        threads.insert(names[i], linResolveJumpTarget(names[i], *next))
        i = i + 1
    }
    return linThreadJumpsIn(stmts, *threads)
}

// Self-registration (`Optimize.kt`).
val linThreadJumpsPass: Bool = registerLinOptPass("threadJumps", linThreadJumps)
