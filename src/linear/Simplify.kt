// Simplify.kt
//
// Peephole simplification of the linear form (impl_specs/linear-lowering.md): drops a jump
// to the next label, drops an unreachable run and a label nothing jumps to. Runs to a fixed
// point, since a dropped jump or statement can expose another fold.
//
// The condition of a branch is *not* inverted: `ifTrue (c) goto A; goto B; A:` stays as the
// lowering built it (the condition the reader wrote, then the branch to the arm and the jump
// past it), rather than being folded into one `ifFalse (c) goto B;`. The fold saved a jump
// but reordered the emitted text, so it is gone; a `goto` to the next label is still dropped.
package linear
import compiler

import common
import optimizations

fun linIsLabel(stmt: *AstXmlNode): Bool {
    return xmlKind(stmt) == AstNodeCategory.StmtLabel
}

fun linIsGoto(stmt: *AstXmlNode): Bool {
    return xmlKind(stmt) == AstNodeCategory.StmtGoto
}

fun linIsCondJump(stmt: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(stmt)
    return kind == AstNodeCategory.StmtIfTrue || kind == AstNodeCategory.StmtIfFalse
}

fun linIsTerminator(stmt: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(stmt)
    return kind == AstNodeCategory.StmtGoto || kind == AstNodeCategory.StmtReturn
}

fun linIsBlock(stmt: *AstXmlNode): Bool {
    return xmlKind(stmt) == AstNodeCategory.StmtBlock
}

// The statements of a `StmtBlock`. A caller that only *reads* them walks the block in
// place (`linCollectJumpTargets`/`linStmtCrosses`): this form copies each statement.
fun linBlockStmts(stmt: *AstXmlNode): List<AstXmlNode> {
    return xmlChildren(xmlChildPtr(stmt, AstNodeKind.Body), AstNodeKind.Stmt)
}

fun linOne(node: *AstXmlNode): List<AstXmlNode> {
    return listOf<AstXmlNode>(node)
}

// A region of the linear form is a statement sequence; the blocks left in it exist only
// where a declaration needs a C++ scope, because C++ rejects a jump that skips the
// initialization of a variable in scope at the label (C2362).

// Where a statement of the region lands once the block's body takes the block's
// place in it.
fun linMergedIndex(p: Int, i: Int, len: Int): Int {
    if (p < i) {
        return p
    }
    return p + len - 1
}

// Whether a jump to `jumpName` taken at `at` would skip a spliced declaration and land past
// it - the one thing C++ rejects about the splice.
fun linJumpCrosses(
    jumpName: *Str, at: Int, decls: *List<Int>, labelNames: *List<Str>,
    labelAt: *List<Int>
): Bool {
    var l: Int = 0
    while (l < labelNames.size()) {
        if (labelNames[l] == jumpName) {
            var d: Int = 0
            while (d < decls.size()) {
                if (at < decls[d] && decls[d] <= labelAt[l]) {
                    return true
                }
                d = d + 1
            }
        }
        l = l + 1
    }
    return false
}

// Every jump inside `stmt`, tested against the spliced declarations; the block's statements
// are walked in place.
fun linStmtCrosses(
    stmt: *AstXmlNode, at: Int, decls: *List<Int>, labelNames: *List<Str>,
    labelAt: *List<Int>
): Bool {
    if (linIsGoto(stmt) || linIsCondJump(stmt)) {
        return linJumpCrosses(xmlAttr(stmt, AstNodeAttributeKind.Name), at, decls, labelNames, labelAt)
    }
    if (!linIsBlock(stmt)) {
        return false
    }
    for (*child in stmt.Children) {
        if (child.name == AstNodeKind.Body) {
            for (*item in child.Children) {
                if (item.name == AstNodeKind.Stmt
                    && linStmtCrosses(item, at, decls, labelNames, labelAt)
                ) {
                    return true
                }
            }
        }
    }
    return false
}

// Every jump name in `stmt`'s subtree, in pre-order; the walk mirrors `linStmtCrosses` (a
// jump is its own name, a block recurses into its statements).
fun linStmtJumpNames(stmt: *AstXmlNode, out: *List<Str>): Unit {
    if (linIsGoto(stmt) || linIsCondJump(stmt)) {
        out.append(xmlAttr(stmt, AstNodeAttributeKind.Name))
    }
    if (!linIsBlock(stmt)) {
        return
    }
    for (*child in stmt.Children) {
        if (child.name == AstNodeKind.Body) {
            for (*item in child.Children) {
                if (item.name == AstNodeKind.Stmt) {
                    linStmtJumpNames(item, out)
                }
            }
        }
    }
}

// Every jump name item `p` contributes - a block's flattened body is `bodies[p]`, not
// `stmts[p]`, because the wrapper node is built only where the block survives the splice.
// Collected once per sequence, so the safety test never re-walks an item's subtree.
fun linItemJumpNames(
    stmts: *List<AstXmlNode>, bodies: *List<List<AstXmlNode>>, p: Int
): List<Str> {
    var out: List<Str> = List<Str>()
    if (linIsBlock(stmts[p])) {
        for (*item in bodies[p]) {
            linStmtJumpNames(item, out)
        }
    } else {
        linStmtJumpNames(*stmts[p], out)
    }
    return out
}

// Whether the block at `i` can be spliced into `stmts`: after the splice its declarations
// are in the parent's scope, so it is legal exactly when no jump `J` and label `L` satisfy
// `pos (J) < pos (D) <= pos (L)` for a declaration `D` it brings up. `itemJumps[p]` holds
// item `p`'s jump names (`linItemJumpNames`), read once per sequence.
fun linSpliceIsSafe(
    stmts: *List<AstXmlNode>, bodies: *List<List<AstXmlNode>>, i: Int,
    itemJumps: *List<List<Str>>
): Bool {
    val body: *List<AstXmlNode> = *bodies[i]
    val len: Int = body.size()
    if (len == 0) {
        return true
    }

    var decls: List<Int> = List<Int>()
    var k: Int = 0
    while (k < len) {
        if (xmlKind(body[k]) == AstNodeCategory.StmtVarDecl) {
            decls.append(i + k)
        }
        k = k + 1
    }
    if (decls.size() == 0) {
        return true
    }

    // Every label the sequence has at this level after the splice.
    var labelNames: List<Str> = List<Str>()
    var labelAt: List<Int> = List<Int>()
    var p: Int = 0
    while (p < stmts.size()) {
        if (p != i && linIsLabel(stmts[p])) {
            labelNames.append(xmlAttr(stmts[p], AstNodeAttributeKind.Name))
            labelAt.append(linMergedIndex(p, i, len))
        }
        p = p + 1
    }
    k = 0
    while (k < len) {
        if (linIsLabel(body[k])) {
            labelNames.append(xmlAttr(body[k], AstNodeAttributeKind.Name))
            labelAt.append(i + k)
        }
        k = k + 1
    }

    // ... and every jump that stays in it. Only an item *before* the block can cross a
    // declaration the splice brings up: an item after it lands at `p + len - 1`, past every
    // `i + k` a declaration occupies, so its jumps can never satisfy `at < decl`.
    p = 0
    while (p < i) {
        val names: *List<Str> = *itemJumps[p]
        var j: Int = 0
        while (j < names.size()) {
            if (linJumpCrosses(*names[j], p, decls, labelNames, labelAt)) {
                return false
            }
            j = j + 1
        }
        p = p + 1
    }
    k = 0
    while (k < len) {
        if (linStmtCrosses(body[k], i + k, decls, labelNames, labelAt)) {
            return false
        }
        k = k + 1
    }
    return true
}

// Every name a jump in `stmts` targets, looking through blocks: a jump may sit in any scope
// inside the sequence. Collected once per sequence - asking per label is quadratic
// (`linJumpTargets`).
fun linJumpTargets(stmts: *List<AstXmlNode>): Dictionary<Str, Bool> {
    var targets: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < stmts.size()) {
        linCollectJumpTargets(*stmts[i], targets)
        i = i + 1
    }
    return targets
}

fun linCollectJumpTargets(stmt: *AstXmlNode, targets: *Dictionary<Str, Bool>): Unit {
    if (linIsGoto(stmt) || linIsCondJump(stmt)) {
        targets.insert(xmlAttr(stmt, AstNodeAttributeKind.Name), true)
    }
    if (!linIsBlock(stmt)) {
        return
    }
    for (*child in stmt.Children) {
        if (child.name == AstNodeKind.Body) {
            for (*item in child.Children) {
                if (item.name == AstNodeKind.Stmt) {
                    linCollectJumpTargets(item, targets)
                }
            }
        }
    }
}

data class LinSimplifier(
    var changed: Bool
) {
    fun prunePass(stmts: *List<AstXmlNode>): List<AstXmlNode> {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < stmts.size()) {
            val stmt: *AstXmlNode = *stmts[i]
            if ((linIsGoto(stmt) || linIsCondJump(stmt)) && i + 1 < stmts.size()
                && linIsLabel(stmts[i + 1])
                && xmlAttr(stmts[i + 1], AstNodeAttributeKind.Name) == xmlAttr(stmt, AstNodeAttributeKind.Name)
            ) {
                this.changed = true
                i = i + 1
            } else {
                out.append(stmt)
                if (linIsTerminator(stmt)) {
                    while (i + 1 < stmts.size() && !linIsLabel(stmts[i + 1])) {
                        this.changed = true
                        i = i + 1
                    }
                }
                i = i + 1
            }
        }
        return out
    }

    fun labelPass(stmts: *List<AstXmlNode>): List<AstXmlNode> {
        // A label nothing jumps to is dropped; the targets are collected once for the
        // sequence (`linJumpTargets`).
        val targets: Dictionary<Str, Bool> = linJumpTargets(stmts)
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < stmts.size()) {
            val stmt: *AstXmlNode = *stmts[i]
            if (linIsLabel(stmt) && !targets.has(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
                this.changed = true
            } else {
                out.append(stmt)
            }
            i = i + 1
        }
        return out
    }

    // Folds nested blocks into the parent sequence. Children come first: a spliced child is
    // what makes its parent's declarations cross jumps, so the parent is judged on the body
    // its children leave behind. A block's flattened body is computed up front but its node
    // is built only where the block survives, so the splice decisions are read as a batch.
    fun flattenPass(stmts: *List<AstXmlNode>): List<AstXmlNode> {
        val count: Int = stmts.size()
        var bodies: List<List<AstXmlNode>> = List<List<AstXmlNode>>(count)
        var bodyList: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < count) {
            if (linIsBlock(stmts[i])) {
                bodyList = linBlockStmts(stmts[i])
                bodies[i] = this.flattenPass(bodyList)
            }
            i = i + 1
        }

        // The jump names each item contributes, collected once: the safety test below would
        // otherwise re-walk every item's subtree for every candidate block.
        var itemJumps: List<List<Str>> = List<List<Str>>(count)
        i = 0
        while (i < count) {
            itemJumps[i] = linItemJumpNames(stmts, bodies, i)
            i = i + 1
        }

        var splicing: List<Bool> = List<Bool>(count, false)
        i = 0
        while (i < count) {
            if (linIsBlock(stmts[i]) && linSpliceIsSafe(stmts, bodies, i, itemJumps)) {
                splicing[i] = true
                this.changed = true
            }
            i = i + 1
        }

        var out: List<AstXmlNode> = List<AstXmlNode>()
        i = 0
        while (i < count) {
            if (splicing[i]) {
                var k: Int = 0
                while (k < bodies[i].size()) {
                    out.append(bodies[i][k])
                    k = k + 1
                }
            } else if (linIsBlock(stmts[i])) {
                // The block stays: a declaration must not be spliced across a jump; this is
                // the only node this pass builds.
                val bodyNode: AstXmlNode =
                    exprLike(xmlChildPtr(stmts[i], AstNodeKind.Body), bodies[i])
                out.append(exprReplaceRole(stmts[i], AstNodeKind.Body, linOne(bodyNode)))
            } else {
                out.append(copy(stmts[i]))
            }
            i = i + 1
        }
        return out
    }

    fun run(stmts: *List<AstXmlNode>): LinLowered {
        var current: List<AstXmlNode> = stmts
        var any: Bool = false
        this.changed = true
        var guard: Int = 0
        while (this.changed && guard < 16) {
            guard = guard + 1
            this.changed = false
            current = this.prunePass(current)
            current = this.labelPass(current)
            any = any || this.changed
        }
        return LinLowered(current, any)
    }
}

// Every declaration of a body - the lowering's temporaries and the program's `val`/`var` alike -
// moves to the top, and each initializer becomes an assignment where the declaration stood. A
// declaration at the top is one no jump can bypass, which is what the folding needs (C2362): after
// it, no block is left for a declaration's sake. The initialization stays where it was, so evaluation
// order and side effects do not move. Runs after the type pass, since a declaration has to keep the
// type that pass proved (`auto x;` is not a declaration).

// Hoisting gives a body one C++ scope, so a name must be unique *in the body*: the second
// declaration of a name is renamed with the uses that resolve to it, so a name never changes what it
// means (impl_specs/linear-il.md). A rename carries the reserved `_sm_` prefix, so it is never
// mistaken for a source name. `reserved` is what the emitter already declared in that scope.

data class SimRenameScope(var renamed: Dictionary<Str, Str>)

// The same attributes with `Name` replaced (added when absent).
fun simNameAttrs(like: *AstXmlNode, name: *Str): List<AstNodeAttribute> {
    var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    var found: Bool = false
    for (*attr in like.attributes) {
        if (attr.name == AstNodeAttributeKind.Name) {
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            found = true
        } else {
            attrs.append(attr)
        }
    }
    if (!found) {
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    }
    return attrs
}
