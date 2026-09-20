// PassLabels.kt
//
// The labels pass: a run of contiguous labels is one label.
//
//   L14:;
//   L12:;
//   L9:;
//   literal = _sm_for2.value();
//
// Three names for one position - nothing runs between them, so entering the sequence at
// `L14` and entering it at `L12` are the same place. The lowering mints a label per
// branch it builds (`lowerIf`, `lowerWhile`, the `&&`/`||` short-circuits), and a branch
// the peephole folds back leaves its label behind, so a body that has been through a
// round or two ends up with exactly this shape. What the extra names cost is *jumps*:
// every one of them is a target some `goto` still spells, so no later pass may drop the
// label, and the emitted C++ carries a branch to a line it could have fallen into.
//
// The pass keeps the first name of a run and points every jump that named one of the
// others at it. Nothing else moves: no statement is reordered, and the position the
// jumps land on is the position they always landed on.
//
// What it is *not* is the unused-label pass - `LinSimplifier.labelPass` already drops a
// label nothing jumps to, and it runs in the same round as this one. The two are
// complementary: this pass turns `L12` and `L9` into names nothing else uses, and that
// pass then removes their lines.
//
// **Safety is the point of the linear form.** A merged label is only ever the *next*
// statement of the sequence it is in, so this pass never reasons about a jump crossing a
// declaration, a scope, or a block: a run is broken by any statement that is not a label
// (a block included - the brace between two labels is not a position a jump can land on,
// which is why an inner block's run is collected as a run of its own).

package optimizations

import common
import linear

// Every merge the body has, as `merged name -> the name it stands for`, collected from
// every sequence of the body (an inner block is a sequence of its own).
fun linCollectLabelMerges(stmts: *List<AstXmlNode>, renames: *Dictionary<Str, Str>): Unit {
    var previous: Str = ""
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        var name: Str = ""
        if (linIsLabel(stmt)) {
            name = xmlAttr(stmt, AstNodeAttributeKind.Name)
        }
        if (name != "") {
            if (previous != "") {
                // The whole run collapses onto its first name: `L14`, `L12`, `L9` is one
                // position, and `previous` stays `L14` for `L9` as well.
                renames.insert(name, previous)
            } else {
                previous = name
            }
        } else {
            previous = ""
            if (linIsBlock(stmt)) {
                var inner: List<AstXmlNode> = linBlockStmts(stmt)
                linCollectLabelMerges(*inner, renames)
            }
        }
        i = i + 1
    }
}

// The jump with one new target. The children come along (`Cond` is a conditional jump's
// condition) and only the `Name` attribute is replaced - the rewrite the shadowing pass
// gives a renamed use.
fun linRetargetJump(stmt: *AstXmlNode, target: Str): AstXmlNode {
    var node: AstXmlNode = exprLike(stmt, stmt.Children.toList())
    node.attributes = simNameAttrs(stmt, target)
    return node
}

// The sequence with every merged label dropped and every jump through `renames` pointed
// at the name it stands for. A block whose own sequence changed is rebuilt; one that did
// not is passed on as it is, so a body with nothing to fold is not rebuilt at all.
fun linFoldLabelsIn(stmts: *List<AstXmlNode>, renames: *Dictionary<Str, Str>): Bool {
    var changed: Bool = false
    var out: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        i = i + 1
        if (linIsLabel(stmt) && renames.has(xmlAttr(stmt, AstNodeAttributeKind.Name))) {
            changed = true
            continue
        }
        if (linIsGoto(stmt) || linIsCondJump(stmt)) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            if (renames.has(name)) {
                val target: Str = renames.get(name).value()
                out.append(linRetargetJump(stmt, target))
                changed = true
                continue
            }
            out.append(stmt)
            continue
        }
        if (linIsBlock(stmt)) {
            var inner: List<AstXmlNode> = linBlockStmts(stmt)
            if (linFoldLabelsIn(*inner, renames)) {
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
    // In place, because the pass reads and writes the body it was handed: the pipeline
    // keeps the `List` it passed and only asks whether it moved.
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// One body's contiguous labels, folded. Two walks: the merges are what the second one
// rewrites *to*, so every sequence has to be read before any jump is retargeted.
fun linFoldLabels(stmts: *List<AstXmlNode>): Bool {
    var renames: Dictionary<Str, Str> = Dictionary<Str, Str>()
    linCollectLabelMerges(stmts, *renames)
    if (renames.size() == 0) {
        return false
    }
    return linFoldLabelsIn(stmts, *renames)
}

// Self-registration (`Optimize.kt`): the table starts empty and this is what fills it.
val linFoldLabelsPass: Bool = registerLinOptPass("foldLabels", linFoldLabels)
