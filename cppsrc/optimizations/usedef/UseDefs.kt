// UseDefs.kt
//
// The use-def facts of the linear form, per statement: the names it reads, the names it writes,
// and whether it ends a block (a label or a jump). A pass that pairs one name's storage with
// another's, or drops a name nothing reads, reads a body through this instead of walking the
// tree itself - `MergeLocals.kt`, `DeadStores.kt` and `DeadLocals.kt` do.

package optimizations

import common
import linear

// ---- counting names ---------------------------------------------------------

// The number recorded for `name`, `missing` when there is none.
fun linUseDefAt(counts: *Dictionary<Str, Int>, name: Str, missing: Int): Int {
    val found: *Int = counts.getPtr(name)
    if (found == null) {
        return missing
    }
    return * found
}

// Every name counted once per occurrence.
fun linUseDefCount(names: List<Str>, counts: *Dictionary<Str, Int>): Unit {
    var i: Int = 0
    while (i < names.size()) {
        counts.insert(names[i], linUseDefAt(counts, names[i], 0) + 1)
        i = i + 1
    }
}

// ---- the names whose storage escapes naming ---------------------------------

// Every name under `node`, a lambda body included.
fun linUseDefNames(node: *AstXmlNode, names: *List<Str>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        names.append(xmlAttr(node, AstNodeAttributeKind.Name))
    }
    for (*child in node.Children) {
        linUseDefNames(child, names)
    }
}

// The name a method call is made on, when plain: the emitter hands the receiver as `T* self`
// (`guide4ai.md`), so `text.appendStr(x)` writes `text`.
fun linUseDefReceiver(callee: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    if (xmlIsEmpty(callee) || xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.ExprName) {
        unsafe.insert(xmlAttr(recv, AstNodeAttributeKind.Name), true)
    }
}

// The names this body must not treat as a value whichever they hold: one handed to a call (a
// `*T` parameter keeps its address), one under a `&`/`*` (the storage, reachable without naming
// it), and a method call's receiver (`T* self`).
fun linUseDefMarkEscapes(node: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    if (node.name == AstNodeKind.Arg && xmlKind(node) == AstNodeCategory.ExprName) {
        unsafe.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
        var place: List<Str> = List<Str>()
        linUseDefNames(node, *place)
        var i: Int = 0
        while (i < place.size()) {
            unsafe.insert(place[i], true)
            i = i + 1
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        linUseDefReceiver(xmlChildPtr(node, AstNodeKind.Callee), unsafe)
    }
    for (*child in node.Children) {
        linUseDefMarkEscapes(child, unsafe)
    }
}

// Every name a lambda under `node` reads or binds. A lambda runs when it is called, so such a
// name lives past the statement that holds it.
fun linUseDefCaptured(node: *AstXmlNode, names: *List<Str>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        linUseDefNames(node, names)
        return
    }
    for (*child in node.Children) {
        linUseDefCaptured(child, names)
    }
}

// The names a lambda body captures, for a body that must not share their storage.
fun linUseDefMarkCaptures(node: *AstXmlNode, unsafe: *Dictionary<Str, Bool>): Unit {
    var captured: List<Str> = List<Str>()
    linUseDefCaptured(node, *captured)
    var i: Int = 0
    while (i < captured.size()) {
        unsafe.insert(captured[i], true)
        i = i + 1
    }
}

// ---- the facts, per statement -----------------------------------------------

// One statement: the names it reads, the names it writes, and whether it ends a block.
data class LinUseDef(
    var uses: List<Str>,
    var defs: List<Str>,
    var boundary: Bool
)

// A body's statements with their facts and the block each statement falls in. A block is the
// run of statements between two boundaries, so it holds no jump and no label: a name written
// and read inside one block is live for that block only.
data class LinUseDefs(
    var stmts: *List<AstXmlNode>,
    var facts: List<LinUseDef>,
    var blocks: List<Int>
) {
    fun usesAt(i: Int): List<Str> {
        if (i < 0 || i >= this.facts.size()) {
            return List<Str>()
        }
        val fact: *LinUseDef = *this.facts[i]
        return fact.uses
    }

    fun defsAt(i: Int): List<Str> {
        if (i < 0 || i >= this.facts.size()) {
            return List<Str>()
        }
        val fact: *LinUseDef = *this.facts[i]
        return fact.defs
    }

    fun blockAt(i: Int): Int {
        if (i < 0 || i >= this.blocks.size()) {
            return -1
        }
        return this.blocks[i]
    }

    // `renamed` applied to the body in place: a use moves with the name it means, and a
    // lambda's own declarations stay its own (`SimRenamer` masks them, so a capture moves and
    // a local does not).
    fun rename(renamed: *Dictionary<Str, Str>): Unit {
        var renamer: SimRenamer = SimRenamer(
            listOf<SimRenameScope>(SimRenameScope(*renamed)), Dictionary<Str, Bool>()
        )
        var i: Int = 0
        while (i < this.stmts.size()) {
            this.stmts[i] = renamer.rewrite(*this.stmts[i], true)
            i = i + 1
        }
    }
}

// A statement that ends the run: anything that moves control, and a block a declaration kept.
fun linUseDefIsBoundary(stmt: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(stmt)
    return kind == AstNodeCategory.StmtLabel || kind == AstNodeCategory.StmtGoto
            || kind == AstNodeCategory.StmtIfTrue || kind == AstNodeCategory.StmtIfFalse
            || kind == AstNodeCategory.StmtBlock
}

// Every name a statement reads; a lambda body is a body of its own, so its statements are not
// this one's.
fun linUseDefStatementNames(node: *AstXmlNode, names: *List<Str>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return
    }
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        names.append(xmlAttr(node, AstNodeAttributeKind.Name))
    }
    for (*child in node.Children) {
        linUseDefStatementNames(child, names)
    }
}

// The names a statement reads. The plain target of an assignment is written, not read; an
// element or a field the target names is read.
fun linUseDefUses(stmt: *AstXmlNode, names: *List<Str>): Unit {
    if (xmlKind(stmt) == AstNodeCategory.StmtAssign
        && xmlAttr(stmt, AstNodeAttributeKind.Op) == "="
        && xmlKind(xmlChildPtr(stmt, AstNodeKind.Target)) == AstNodeCategory.ExprName
    ) {
        for (*child in stmt.Children) {
            if (child.name != AstNodeKind.Target) {
                linUseDefStatementNames(child, names)
            }
        }
        return
    }
    for (*child in stmt.Children) {
        linUseDefStatementNames(child, names)
    }
}

// The names a statement writes: a declaration that initializes (a bare one is the storage, not
// a write) and an assignment's plain target.
fun linUseDefWrites(stmt: *AstXmlNode, names: *List<Str>): Unit {
    val kind: AstNodeCategory = xmlKind(stmt)
    if (kind == AstNodeCategory.StmtVarDecl) {
        if (!xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))) {
            names.append(xmlAttr(stmt, AstNodeAttributeKind.Name))
        }
        return
    }
    if (kind != AstNodeCategory.StmtAssign) {
        return
    }
    val target: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
    if (xmlKind(target) == AstNodeCategory.ExprName) {
        names.append(xmlAttr(target, AstNodeAttributeKind.Name))
    }
}

// One body read once: every statement's names, and the block it falls in.
fun linUseDefsOf(stmts: *List<AstXmlNode>): LinUseDefs {
    var facts: List<LinUseDef> = List<LinUseDef>()
    var blocks: List<Int> = List<Int>()
    var block: Int = 0
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        val boundary: Bool = linUseDefIsBoundary(stmt)
        if (boundary) {
            block = block + 1
        }
        var uses: List<Str> = List<Str>()
        var defs: List<Str> = List<Str>()
        linUseDefUses(stmt, *uses)
        linUseDefWrites(stmt, *defs)
        var fact: LinUseDef = LinUseDef(uses, defs, boundary)
        facts.append(fact)
        blocks.append(block)
        i = i + 1
    }
    return LinUseDefs(stmts, facts, blocks)
}

// The names the body declares at its own level. A declaration is the storage, not a use of the
// name or a definition of it, so it neither keeps a name alive nor stands as one: the passes
// that drop storage want the names nothing else names.
fun linUseDefDeclared(stmts: *List<AstXmlNode>, declared: *Dictionary<Str, Bool>): Unit {
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            declared.insert(xmlAttr(stmt, AstNodeAttributeKind.Name), true)
        }
        i = i + 1
    }
}
