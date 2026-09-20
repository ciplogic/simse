// PassJumps.kt
//
// The jump pass: a jump to a label that does nothing but jump again is that second jump.
//
//         goto L8;                     goto L1;
//         ...                          ...
//     L8:;                         L8:;
//         goto L1;                     goto L1;
//
// Landing on `L8` *is* landing on `L1`: nothing runs in between, so a jump to `L8` can
// name `L1` directly. It holds for both forms of jump - an unconditional `goto` and a
// conditional one, because the condition decides *whether* to jump, never where - so the
// pass retargets every jump of the body whose name is such a label.
//
// The label itself stays where it is. A *fallthrough* into it still has to take the jump
// (`L8` is in the middle of the sequence all the same), and only the unused-label pass
// may remove the line - which it will, once no jump names `L8` any more
// (`LinSimplifier.labelPass`, in the same round).
//
// A chain is resolved to its end in one step - `L8: goto L1; L1: goto L2;` points a jump
// to `L8` at `L2` - and the walk that resolves it stops the moment it revisits a name, so
// two labels jumping to each other (a cycle nothing can enter) change nothing at all
// rather than oscillating between two shapes.

package optimizations

import common
import linear

// The end of the chain a label starts, when its own next statement is an unconditional
// jump: the last target reachable by jumping from one label straight into another.
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

// Every label of the body whose next statement is an unconditional jump, as
// `label -> the end of the chain it starts`. A block is a sequence of its own, so its
// labels are read the same way.
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

// The sequence with every jump through `threads` pointed at the name it stands for. Only
// jumps that actually change are rebuilt, so a body with nothing to thread is not
// rebuilt at all (and `false` is the honest answer).
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

// One body's jump chains, threaded. Two walks: the chains are what the second one
// retargets *to*, so the whole body has to be read before any jump moves.
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

// Self-registration (`Optimize.kt`): the table starts empty and this is what fills it.
val linThreadJumpsPass: Bool = registerLinOptPass("threadJumps", linThreadJumps)
