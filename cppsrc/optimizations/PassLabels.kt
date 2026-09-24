// PassLabels.kt
//
//   L14:;  L12:;  L9:;   ->   L14:;                (a run of contiguous labels is one name)
// A run collapses onto its first name, and every jump to any other name in it is retargeted;
// nothing is reordered and no jump lands anywhere new. `LinSimplifier.labelPass` then drops the
// names nothing names any more.

package optimizations

import common
import linear

// Every merge the body has, `merged name -> the name it stands for`, blocks included.
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
                // The run collapses onto its first name: `previous` stays `L14` for `L9` too.
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

// The jump with one new target: the children come along, only `Name` is replaced.
fun linRetargetJump(stmt: *AstXmlNode, target: Str): AstXmlNode {
    var node: AstXmlNode = exprLike(stmt, stmt.Children.toList())
    node.attributes = simNameAttrs(stmt, target)
    return node
}

// The sequence with every merged label dropped and every jump pointed at the name it stands
// for; an unchanged block is passed on as is, so a body with nothing to fold is not rebuilt.
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
    // In place: the pipeline keeps the `List` it passed and only asks whether it moved.
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// One body's contiguous labels, folded. Two walks: every sequence is read before a jump moves.
fun linFoldLabels(stmts: *List<AstXmlNode>): Bool {
    var renames: Dictionary<Str, Str> = Dictionary<Str, Str>()
    linCollectLabelMerges(stmts, *renames)
    if (renames.size() == 0) {
        return false
    }
    return linFoldLabelsIn(stmts, *renames)
}

// Self-registration (`Optimize.kt`).
val linFoldLabelsPass: Bool = registerLinOptPass("foldLabels", linFoldLabels)
