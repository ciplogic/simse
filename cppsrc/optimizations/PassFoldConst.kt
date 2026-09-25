// PassFoldConst.kt
//
//   _sm_expr1 = false;  ifFalse _sm_expr1 L5;   ->   ... ifFalse false L5;
// A slot written exactly once, at the top level, with a literal, stands for it at every top-level
// read after it with no label between; a label or `goto` clears that (a jump may not land between
// write and read). Deliberately not a dataflow analysis.

package optimizations

import common
import linear

// One write: the name written and, when it is a literal, the constant.
data class FoldConstSlot(
    var name: Str,
    var kind: FoldKind,
    var text: Str
)

// The constant a value expression is, in the shape a substitution writes back; empty for anything
// but a literal.
fun foldConstValue(e: *AstXmlNode): Opt<FoldGlobalConst> {
    val kind: FoldKind = foldKindOf(e)
    if (kind == FoldKind.None) {
        return Opt<FoldGlobalConst>.none()
    }
    var text: Str = xmlAttr(e, AstNodeAttributeKind.Text)
    if (kind == FoldKind.Bool) {
        text = xmlAttr(e, AstNodeAttributeKind.Value)
    }
    return Opt<FoldGlobalConst>.some(FoldGlobalConst(kind, text))
}

// The name a statement writes: a declaration's own or an assignment's target; "" otherwise.
fun foldConstWriteName(stmt: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(stmt)
    if (kind == AstNodeCategory.StmtVarDecl) {
        if (xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))) {
            return "" // a hoisted declaration: the storage, not a write
        }
        return xmlAttr(stmt, AstNodeAttributeKind.Name)
    }
    if (kind != AstNodeCategory.StmtAssign) {
        return ""
    }
    val target: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
    if (xmlIsEmpty(target)) {
        return ""
    }
    return xmlAttr(target, AstNodeAttributeKind.Name)
}

// The write a statement is, when its value is a literal. Only a plain `=` qualifies: `x += 1` is
// also a read of `x`, so the value it spells is not what `x` holds (compound-assign).
fun foldConstWrite(stmt: *AstXmlNode): Opt<FoldConstSlot> {
    val name: Str = foldConstWriteName(stmt)
    if (name == "") {
        return Opt<FoldConstSlot>.none()
    }
    var value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
    if (xmlKind(stmt) == AstNodeCategory.StmtAssign) {
        if (xmlAttr(stmt, AstNodeAttributeKind.Op) != "=") {
            return Opt<FoldConstSlot>.none()
        }
        value = xmlChildPtr(stmt, AstNodeKind.Value)
    }
    if (xmlIsEmpty(value)) {
        return Opt<FoldConstSlot>.none()
    }
    val literal: Opt<FoldGlobalConst> = foldConstValue(value)
    if (!literal.hasValue()) {
        return Opt<FoldConstSlot>.none()
    }
    return Opt<FoldConstSlot>.some(
        FoldConstSlot(name, literal.value().kind, literal.value().text)
    )
}

// Every write under `node`, at any depth, so a second write (to any value) disqualifies it.
fun foldConstCountWrites(node: *AstXmlNode, counts: *Dictionary<Str, Int>): Unit {
    val name: Str = foldConstWriteName(node)
    if (name != "") {
        var seen: Int = 0
        val seenPtr: *Int = counts.getPtr(name)
        if (seenPtr != null) {
            seen = * seenPtr
        }
        counts.insert(name, seen + 1)
    }
    for (*child in node.Children) {
        foldConstCountWrites(child, counts)
    }
}

// The table the walk has made available at the statement it stands at; a program-wide `var`
// because a rule takes one node and nothing else.
var linFoldConstAvailable: Dictionary<Str, FoldGlobalConst>

// A read of a slot seen written once, with a literal, above.
fun foldConstReadRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprName) {
        return e
    }
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    val lit: *FoldGlobalConst = linFoldConstAvailable.getPtr(name)
    if (lit == null) {
        return e
    }
    return foldGlobalLiteral(e, *lit)
}

fun linFoldConstBody(stmts: *List<AstXmlNode>): Bool {
    var counts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var unsafe: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < stmts.size()) {
        foldConstCountWrites(*stmts[i], *counts)
        linUseDefMarkEscapes(*stmts[i], *unsafe)
        i = i + 1
    }
    // The names written exactly once, at the top level, with a literal, and not escaping.
    var singles: Dictionary<Str, FoldGlobalConst> = Dictionary<Str, FoldGlobalConst>()
    i = 0
    while (i < stmts.size()) {
        val write: Opt<FoldConstSlot> = foldConstWrite(*stmts[i])
        if (write.hasValue()) {
            val count: *Int = counts.getPtr(write.value().name)
            if ( * count == 1 && !unsafe.has(write.value().name)) {
                singles.insert(write.value().name, FoldGlobalConst(write.value().kind, write.value().text))
            }
        }
        i = i + 1
    }
    if (singles.size() == 0) {
        return false
    }
    var changed: Bool = false
    linFoldConstAvailable = Dictionary<Str, FoldGlobalConst>()
    i = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (linIsLabel(stmt) || linIsGoto(stmt)) {
            // A jump may land here from anywhere, so nothing written before is certain.
            linFoldConstAvailable = Dictionary<Str, FoldGlobalConst>()
        }
        if (linFoldConstAvailable.size() > 0) {
            var state: FoldState = FoldState(false)
            val rewritten: AstXmlNode = foldExprsUnder(*stmts[i], foldConstReadRule, *state)
            if (state.changed) {
                stmts[i] = rewritten
                changed = true
            }
        }
        val write: Opt<FoldConstSlot> = foldConstWrite(*stmts[i])
        if (write.hasValue()) {
            val single: *FoldGlobalConst = singles.getPtr(write.value().name)
            if (single != null) {
                linFoldConstAvailable.insert(write.value().name, *single)
            }
        }
        i = i + 1
    }
    return changed
}

// Self-registration (`Optimize.kt`).
val linFoldConstPass: Bool = registerLinOptPass("foldConst", linFoldConstBody)
