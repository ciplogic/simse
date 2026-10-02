// UseDefs.kt
//
// The use-def facts of the linear form, per statement: the names it reads, the names it writes,
// whether it ends a block (a label or a jump), and - once per body, not once per statement - the
// names whose storage escapes and the names a lambda captures. One subtree walk per statement
// fills the reads and both sets, where the escapes and the captures used to be three more walks;
// the two sets live on the body because a caller only asks *whether* a name escapes or is
// captured, and a per-statement list of each would be paid for at every statement whether or not
// it is empty.

package optimizations
import compiler

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

// Every name `names` marks, as a presence (the count is incidental), for the body-level sets.
fun linUseDefMarkEach(names: List<Str>, marks: *Dictionary<Str, Int>): Unit {
    var i: Int = 0
    while (i < names.size()) {
        marks.insert(names[i], 1)
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

// The same, marked rather than listed: a lambda's captures are a set, not an ordered run.
fun linUseDefMarkNames(node: *AstXmlNode, marks: *Dictionary<Str, Int>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName) {
        marks.insert(xmlAttr(node, AstNodeAttributeKind.Name), 1)
    }
    for (*child in node.Children) {
        linUseDefMarkNames(child, marks)
    }
}

// The name a method call is made on, when plain: the emitter hands the receiver as `T* self`
// (`agents.md`), so `text.appendStr(x)` writes `text`.
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
// it), and a method call's receiver (`T* self`). `linUseDefWalk` records the same per statement;
// this walks on its own for `PassFoldConst`, which counts its writes as it goes and so does not
// read a body through `linUseDefsOf`.
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

// ---- the facts, per statement -----------------------------------------------

// One statement: the names it reads, the names it writes, and whether it ends a block.
data class LinUseDef(
    var uses: List<Str>,
    var defs: List<Str>,
    var boundary: Bool
)

// A body's statements with their facts and the block each statement falls in. A block is the
// run of statements between two boundaries, so it holds no jump and no label: a name written
// and read inside one block is live for that block only. `escapes` and `captures` are the body's
// own: which names it hands out, and which a lambda reads or binds.
data class LinUseDefs(
    var stmts: *List<AstXmlNode>,
    var facts: List<LinUseDef>,
    var blocks: List<Int>,
    var escapes: Dictionary<Str, Bool>,
    var captures: Dictionary<Str, Int>
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

    // Whether a lambda anywhere in the body reads or binds `name`. The lambda runs later, so a
    // store it reads is not dead, and its storage may not be shared with another local's.
    fun captured(name: Str): Bool {
        return this.captures.has(name)
    }

    // Whether the body hands `name`'s storage out - a call argument, a name under `&`/`*`, a
    // method call's receiver - or a lambda captures it.
    fun escaped(name: Str): Bool {
        return this.escapes.has(name) || this.captures.has(name)
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

// One statement's reads, escapes and captures in a single subtree walk. A lambda is where the
// three part ways: it runs when it is called, so its body is not a read here and not an escape
// either - every name it touches is a capture, and the walk stops at the lambda because the
// captures already are all of them.
fun linUseDefWalk(
    node: *AstXmlNode, uses: *List<Str>, escapes: *Dictionary<Str, Bool>,
    captures: *Dictionary<Str, Int>
): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprLambda) {
        linUseDefMarkNames(node, captures)
        return
    }
    if (kind == AstNodeCategory.ExprName) {
        uses.append(xmlAttr(node, AstNodeAttributeKind.Name))
    }
    if (node.name == AstNodeKind.Arg && kind == AstNodeCategory.ExprName) {
        escapes.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
        var place: List<Str> = List<Str>()
        linUseDefNames(node, *place)
        var i: Int = 0
        while (i < place.size()) {
            escapes.insert(place[i], true)
            i = i + 1
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        linUseDefReceiver(xmlChildPtr(node, AstNodeKind.Callee), escapes)
    }
    for (*child in node.Children) {
        linUseDefWalk(child, uses, escapes, captures)
    }
}

// The names a statement reads. The plain target of an assignment is written, not read; an
// element or a field the target names is read.
fun linUseDefReads(
    stmt: *AstXmlNode, uses: *List<Str>, escapes: *Dictionary<Str, Bool>,
    captures: *Dictionary<Str, Int>
): Unit {
    if (xmlKind(stmt) == AstNodeCategory.StmtAssign
        && xmlAttr(stmt, AstNodeAttributeKind.Op) == "="
        && xmlKind(xmlChildPtr(stmt, AstNodeKind.Target)) == AstNodeCategory.ExprName
    ) {
        for (*child in stmt.Children) {
            if (child.name != AstNodeKind.Target) {
                linUseDefWalk(child, uses, escapes, captures)
            }
        }
        return
    }
    for (*child in stmt.Children) {
        linUseDefWalk(child, uses, escapes, captures)
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

// One body read once: every statement's names - reads, escapes and captures in one walk - and
// the block it falls in.
fun linUseDefsOf(stmts: *List<AstXmlNode>): LinUseDefs {
    var facts: List<LinUseDef> = List<LinUseDef>()
    var blocks: List<Int> = List<Int>()
    var escapes: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var captures: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var block: Int = 0
    for (*stmt in stmts) {
        val boundary: Bool = linUseDefIsBoundary(stmt)
        if (boundary) {
            block = block + 1
        }
        var uses: List<Str> = List<Str>()
        var defs: List<Str> = List<Str>()
        linUseDefReads(stmt, *uses, *escapes, *captures)
        linUseDefWrites(stmt, *defs)
        var fact: LinUseDef = LinUseDef(uses, defs, boundary)
        facts.append(fact)
        blocks.append(block)
    }
    return LinUseDefs(stmts, facts, blocks, escapes, captures)
}

// The names the body declares at its own level. A declaration is the storage, not a use of the
// name or a definition of it, so it neither keeps a name alive nor stands as one: the passes
// that drop storage want the names nothing else names.
fun linUseDefDeclared(stmts: *List<AstXmlNode>, declared: *Dictionary<Str, Bool>): Unit {
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            declared.insert(xmlAttr(stmt, AstNodeAttributeKind.Name), true)
        }
    }
}
