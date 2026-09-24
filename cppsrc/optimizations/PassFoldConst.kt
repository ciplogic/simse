// PassFoldConst.kt
//
// A slot that is written once, with a constant, is that constant at the reads that follow
// it - which is what lets the other folds see past a temporary.
//
//   _sm_expr1 = false;      ->   _sm_expr1 = false;
//   ifFalse _sm_expr1 L5;         ifFalse false L5;     (and then `PassFoldBranch`)
//
// Without this step most folds are invisible: the expression lowering binds a *binding*
// (a value read twice, an aggregate) to a temporary, so a comparison in an `if` reaches the
// branch as a slot and not as the literal the comparison folded to (`if (x > 100)` emits
// `_sm_expr1 = 7 > 100; ifFalse _sm_expr1 L5`, and the branch fold has nothing to read).
//
// ## Why it is allowed to
//
// One rule, and it is a *dominance* rule rather than a guess:
//
//   - the name is written **exactly once** in the whole body - at any depth, and a write of
//     anything (not only a literal) counts, or a later `x = y` would be invisible and a
//     read would take the constant that a previous statement wrote;
//   - that write is a literal, and it sits at the **top level** of the body;
//   - the read is at the top level of the body **after** it, with **no label between them**:
//     a jump may not land between the write and the read, so control reaching the read has
//     passed through the write. A label therefore *clears* everything a walk has made
//     available (`linFoldConstAvailable`), and a `goto` clears it too (control leaves).
//
// The write has to be at the top level for the read's index to mean anything, which is fine
// in practice: the hoisting puts every declaration of a body at the top, and the temporaries
// this step is for are written where they are used, in the sequence the branch is in.
//
// What it deliberately is not: a dataflow analysis. It does not look inside a block, it
// does not track two writes that agree, and it does not try to prove that a label has no
// incoming jump from before the write.

package optimizations

import common
import linear

// One write: the name a statement writes, and the constant it writes when that is a literal.
data class FoldConstSlot(
    var name: Str,
    var kind: FoldKind,
    var text: Str
)

// The constant a value expression is, in the shape a substitution writes back. Empty for
// anything that is not a literal: a call, a name, an operation this round did not fold.
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

// The name a statement writes: the declaration's own, or an assignment's target. "" for a
// statement that writes nothing (a label, a call, a comparison in an `if`).
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

// The write a statement is, when its value is a literal. Only a plain `=` writes the
// literal: `x += 1` is a *read* of `x` as well as a write of it, so the value it spells is
// not what `x` holds afterwards - which is how `compound-assign` caught this the first run.
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

// Every name under `node`: what a place operator takes the address of, without asking
// which name it is under.
fun foldConstMarkNames(node: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        unsafe.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    for (*child in node.Children) {
        foldConstMarkNames(child, unsafe)
    }
}

// The names this body must not treat as constants whatever they hold: one handed to a call
// (a `*T` parameter takes its address, and which parameters those are is not this pass's
// to know), one under a `&`/`*` (the storage, which a write can reach through the pointer
// without naming it - `stress/compound-assign`'s `stepByThree(*value)`), and the receiver
// of a *method call*, which the emitter passes as `T* self` however `this` is spelled: a
// value receiver is how the RTL mutates in place, so `text.appendStr(name)` writes `text`
// without an assignment anywhere - which is the bug `stress/smgen-kt` caught.
fun foldConstMarkUnsafe(node: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    if (node.name == AstNodeKind.Arg && xmlKind(node) == AstNodeCategory.ExprName) {
        unsafe.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprDeref || kind == AstNodeCategory.ExprRef) {
        foldConstMarkNames(node, unsafe)
    }
    if (kind == AstNodeCategory.ExprCall) {
        foldConstMarkReceiver(xmlChildPtr(node, AstNodeKind.Callee), unsafe)
    }
    for (*child in node.Children) {
        foldConstMarkUnsafe(child, unsafe)
    }
}

// The name a method call is made on, when it is a plain name: `text.appendStr(x)` writes
// `text`, because the emitter hands the receiver to the callee as a pointer (`T* self`,
// `guide4ai.md` "A value receiver is `T* self`").
fun foldConstMarkReceiver(callee: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    if (xmlIsEmpty(callee) || xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.ExprName) {
        unsafe.insert(xmlAttr(recv, AstNodeAttributeKind.Name), true)
    }
}

// Every write under `node`, at any depth: a name written twice is not a constant, and the
// count is over *all* writes so a later assignment to another value disqualifies the name.
fun foldConstCountWrites(node: *AstXmlNode, counts: *Dictionary<Str, Int>): Unit {
    val name: Str = foldConstWriteName(node)
    if (name != "") {
        var seen: Int = 0
        if (counts.has(name)) {
            seen = counts.get(name).value()
        }
        counts.insert(name, seen + 1)
    }
    for (*child in node.Children) {
        foldConstCountWrites(child, counts)
    }
}

// What the substitution reads: the table the walk has made available at the statement it is
// standing at. A program-wide `var` because a rule takes one node and nothing else
// (`Optimize.kt`); the pass below is its only writer.
var linFoldConstAvailable: Dictionary<Str, FoldGlobalConst>

// A read of a slot this walk has seen written once, with a literal, above it.
fun foldConstReadRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprName) {
        return e
    }
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    if (!linFoldConstAvailable.has(name)) {
        return e
    }
    return foldGlobalLiteral(e, linFoldConstAvailable.get(name).value())
}

fun linFoldConstBody(stmts: *List<AstXmlNode>): Bool {
    var counts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var unsafe: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < stmts.size()) {
        foldConstCountWrites(*stmts[i], *counts)
        foldConstMarkUnsafe(*stmts[i], *unsafe)
        i = i + 1
    }
    // The names the body writes exactly once, at the top level, with a literal - and never
    // escapes through a pointer or a call.
    var singles: Dictionary<Str, FoldGlobalConst> = Dictionary<Str, FoldGlobalConst>()
    i = 0
    while (i < stmts.size()) {
        val write: Opt<FoldConstSlot> = foldConstWrite(*stmts[i])
        if (write.hasValue() && counts.get(write.value().name).value() == 1
            && !unsafe.has(write.value().name)
        ) {
            singles.insert(write.value().name, FoldGlobalConst(write.value().kind, write.value().text))
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
        if (write.hasValue() && singles.has(write.value().name)) {
            linFoldConstAvailable.insert(
                write.value().name, singles.get(write.value().name).value()
            )
        }
        i = i + 1
    }
    return changed
}

// Self-registration (`Optimize.kt`).
val linFoldConstPass: Bool = registerLinOptPass("foldConst", linFoldConstBody)
