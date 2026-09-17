// Simplify.kt
//
// Peephole simplification of the linear form produced by `linLowerBody`, ported
// from cppsrc/linear/Simplify.cpp (impl_specs/linear-lowering.md). The rules are
// semantics-preserving and deliberately simple; they exist so the emitted code
// stays close to the structured code it came from.
//
//   goto L; L:;                    -> L:;
//   if (c) goto L; L:;             -> L:;
//   ifTrue (c) goto A; goto B; A:; -> ifFalse (c) goto B;
//   goto L; <unreachable> L:;      -> goto L; L:;
//   L:; (nothing jumps to it)      -> (removed)
//   { {op} op }                    -> { op op }   (a block no jump crosses a
//                                                   declaration of; `linFlattenBlocks`)
//
// Runs to a fixed point (dropping a jump can make a label unused, and dropping
// statements can expose another jump-to-next or an unreachable run).
package linear

import common

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

// The statements of a `StmtBlock` (its `Body` child's `Stmt` children). A caller that
// only *reads* them walks the block in place instead (`linStmtJumpsTo` / `linStmtCrosses`):
// this form copies every statement of the block, and the label scan runs once per label
// of the body.
fun linBlockStmts(stmt: *AstXmlNode): List<AstXmlNode> {
    return xmlChildren(xmlChild(stmt, AstNodeKind.Body), AstNodeKind.Stmt)
}

// A one-element list, for `exprReplaceRole`.
fun linOne(node: AstXmlNode): List<AstXmlNode> {
    return listOf<AstXmlNode>(node)
}

// ---- block flattening ------------------------------------------------------
//
// A region of the linear form is a statement sequence; the blocks left in it
// exist only where a declaration needs a C++ scope, which is why the simplifier
// folds every other block into its parent. A block *is* needed when splicing it
// would move one of its declarations across a jump: C++ rejects a jump that skips
// the initialization of a variable in scope at the label ([stmt.dcl]/3, MSVC
// C2362), and the linear form is full of jumps.

// Where a statement of the region lands once the block's body takes the block's
// place in it.
fun linMergedIndex(p: Int, i: Int, len: Int): Int {
    if (p < i) {
        return p
    }
    return p + len - 1
}

// Whether a jump to `jumpName` taken at index `at` would skip the initialization
// of a declaration the splice brings into the parent's scope and land past it -
// the one thing C++ rejects about the splice.
fun linJumpCrosses(
    jumpName: Str, at: Int, decls: *List<Int>, labelNames: *List<Str>,
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

// Every jump inside `stmt` - each of them running after everything before the
// top-level statement it sits in - tested against the spliced declarations. The
// block's statements are walked in place (see `linStmtJumpsTo`).
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

// The same test for item `p` of a level. A block item's body is `bodies[p]` rather
// than the body of `stmts[p]`: the wrapper node is built at the splice decision, so
// `stmts[p]` still carries the tree it was parsed as, not the flattened one - the
// scan has to see what the children left behind.
fun linItemCrosses(
    stmts: *List<AstXmlNode>, bodies: *List<List<AstXmlNode>>, p: Int, at: Int,
    decls: *List<Int>, labelNames: *List<Str>, labelAt: *List<Int>
): Bool {
    if (!linIsBlock(stmts[p])) {
        return linStmtCrosses(stmts[p], at, decls, labelNames, labelAt)
    }
    for (*item in bodies[p]) {
        if (linStmtCrosses(item, at, decls, labelNames, labelAt)) {
            return true
        }
    }
    return false
}

// Whether the block at `i` can be spliced into `stmts`: after the splice the
// block's own declarations are in the parent's scope, so the splice is legal
// exactly when no jump `J` and label `L` satisfy `pos (J) < pos (D) <= pos (L)`
// for a declaration `D` it brings up.
//
// The block's body comes from `bodies[i]`, not from `stmts[i]`: the wrapper node is
// built only when the block *survives* (in `flattenPass`), so a spliced block never
// pays for one.
fun linSpliceIsSafe(stmts: *List<AstXmlNode>, bodies: *List<List<AstXmlNode>>, i: Int): Bool {
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

    // ... and every jump that stays in it.
    p = 0
    while (p < stmts.size()) {
        if (p != i
            && linItemCrosses(stmts, bodies, p, linMergedIndex(p, i, len), decls, labelNames, labelAt)
        ) {
            return false
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

// Whether the statement - or anything inside the block it is - jumps to `name`. Read
// through the block's nodes: `linBlockStmts` would copy every statement of the block,
// and this scan runs once per *label* of the body.
fun linStmtJumpsTo(stmt: *AstXmlNode, name: Str): Bool {
    if ((linIsGoto(stmt) || linIsCondJump(stmt))
        && xmlAttr(stmt, AstNodeAttributeKind.Name) == name
    ) {
        return true
    }
    if (!linIsBlock(stmt)) {
        return false
    }
    for (*child in stmt.Children) {
        if (child.name == AstNodeKind.Body) {
            for (*item in child.Children) {
                if (item.name == AstNodeKind.Stmt && linStmtJumpsTo(item, name)) {
                    return true
                }
            }
        }
    }
    return false
}

// A label belongs to the sequence it sits in, but a jump to it may sit in any
// scope inside that sequence: the expression lowering wraps a jump in the block
// that carries its temporaries, and `break`/`continue` jump out of the body they
// are written in. The scan therefore looks through blocks.
fun linJumpsTo(stmts: *List<AstXmlNode>, name: Str): Bool {
    var i: Int = 0
    while (i < stmts.size()) {
        if (linStmtJumpsTo(*stmts[i], name)) {
            return true
        }
        i = i + 1
    }
    return false
}

// A copy of a conditional jump with a negated condition (IfTrue <-> IfFalse)
// and a new target.
fun linInvertedJump(jump: *AstXmlNode, target: Str): AstXmlNode {
    var kind: AstNodeCategory = AstNodeCategory.StmtIfTrue
    if (xmlKind(jump) == AstNodeCategory.StmtIfTrue) {
        kind = AstNodeCategory.StmtIfFalse
    }
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(jump).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(jump).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Name, target)
    )
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, kind, attrs, Array<AstXmlNode>())
    xmlAddChild(node, xmlChild(jump, AstNodeKind.Cond))
    return node
}

data class LinSimplifier(
    var changed: Bool
) {
    fun prunePass(stmts: *List<AstXmlNode>): List<AstXmlNode> {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < stmts.size()) {
            // The *pointer* form: a statement is a value, so binding it by value here and
            // appending the binding copied every statement twice per pass.
            val stmt: *AstXmlNode = *stmts[i]
            if ((linIsGoto(stmt) || linIsCondJump(stmt)) && i + 1 < stmts.size()
                && linIsLabel(stmts[i + 1])
                && xmlAttr(stmts[i + 1], AstNodeAttributeKind.Name) == xmlAttr(stmt, AstNodeAttributeKind.Name)
            ) {
                // A jump to the statement right after it does nothing.
                this.changed = true
                i = i + 1
            } else if (linIsCondJump(stmt) && i + 2 < stmts.size() && linIsGoto(stmts[i + 1])
                && linIsLabel(stmts[i + 2])
                && xmlAttr(stmts[i + 2], AstNodeAttributeKind.Name) == xmlAttr(stmt, AstNodeAttributeKind.Name)
            ) {
                // `ifTrue (c) goto A; goto B; A:` is `ifFalse (c) goto B;`. The
                // label A stays in the stream and is dropped below if nothing
                // else jumps to it.
                out.append(linInvertedJump(stmt, xmlAttr(stmts[i + 1], AstNodeAttributeKind.Name)))
                this.changed = true
                i = i + 2
            } else {
                out.append(stmt)
                // Nothing before the next label can be reached.
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
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < stmts.size()) {
            val stmt: *AstXmlNode = *stmts[i]
            if (linIsLabel(stmt) && !linJumpsTo(stmts, xmlAttr(stmt, AstNodeAttributeKind.Name))) {
                this.changed = true
            } else {
                out.append(stmt)
            }
            i = i + 1
        }
        return out
    }

    // Folds nested blocks into the parent sequence. Children come first: a spliced
    // child is what makes its parent's declarations cross jumps, so the parent is
    // judged on the body its children leave behind.
    //
    // A block's flattened body is computed up front but its *node* is not: it is built
    // in the one branch where the block survives the splice, so a spliced block never
    // pays for a fresh node. That is also why the decisions are a batch below - each
    // one reads the block bodies of the *other* items in this sequence, so all of them
    // have to be in place before any is used.
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

        var splicing: List<Bool> = List<Bool>(count, false)
        i = 0
        while (i < count) {
            if (linIsBlock(stmts[i]) && linSpliceIsSafe(stmts, bodies, i)) {
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
                // The block stays: it exists because a declaration must not be spliced
                // across a jump. This is the only node this pass builds.
                val bodyNode: AstXmlNode =
                    exprLike(xmlChild(stmts[i], AstNodeKind.Body), bodies[i])
                out.append(exprReplaceRole(stmts[i], AstNodeKind.Body, linOne(bodyNode)))
            } else {
                out.append(copy(stmts[i]))
            }
            i = i + 1
        }
        return out
    }

    fun run(stmts: List<AstXmlNode>): LinLowered {
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

// ---- slot hoisting --------------------------------------------------------
//
// **Every** declaration of a body - the lowering's own temporaries (`_sm_expr<n>`,
// `simse_sw_<n>`) and the program's `val`/`var` alike - moves to the top of the body, and
// each initializer becomes an assignment where the declaration stood:
//
//     { Bool _sm_expr2 = i == 3; if (_sm_expr2) goto L4; }
//       ->
//     Bool _sm_expr2;                          (at the top of the body)
//     ...
//     _sm_expr2 = i == 3;
//     if (_sm_expr2) goto L4;
//
// A declaration at the top of the body is a declaration no jump can bypass, which
// is the one thing the folding needs (C2362), so this is what turns the linear form
// into one flat sequence: after it, no block is left for a declaration's sake. The
// initialization stays where it was, so evaluation order and side effects do not move;
// what moves is where the storage is declared, which makes every slot of the body live
// for the whole body (a bytecode frame's slots, without liveness reuse).
//
// It runs **after the type pass**, because a declaration has to keep the type that
// pass proved - `auto x;` is not a declaration - so a declaration the inference could
// not spell, and a machine's `..T` (no type to write), keep their place.
//

// ---- one scope per body ----------------------------------------------------
//
// The hoisting below moves *every* declaration of a body to the top of it, so a body
// has one scope. That is what makes a name have to be unique *in the body*: the language
// lets two scopes reuse a name (shadowing), and one flat C++ scope cannot, so the second
// declaration of a name is renamed - and the uses that resolve to it move with it, so a
// name never changes what it means (impl_specs/linear-il.md, "the frame is flat"). A
// generated name carries the `_sm_` prefix the language reserves for the compiler
// (`_sm_expr1`, `_sm_for1`), so a rename is never mistaken for a name the program wrote.
//
// `reserved` is what the emitter has already put in the body's own C++ scope: the
// parameters (and `self`), which are declared next to the hoisted storage.

data class SimRenameScope(var renamed: Dictionary<Str, Str>)

// The same attributes with `Name` replaced (added when there is none): how a renamed
// declaration and a rewritten use carry their new name.
fun simNameAttrs(like: *AstXmlNode, name: Str): List<AstNodeAttribute> {
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

// Every name a body binds: the declarations of its statements, at any depth. A use of one
// of those inside a lambda is the lambda's own wherever it stands, so the enclosing scopes
// must not rewrite it, and the lambda's own pass is what names it.
fun simBoundNames(body: *AstXmlNode, bound: List<Str>): List<Str> {
    var names: List<Str> = bound
    val stmts: List<AstXmlNode> = xmlChildren(body, AstNodeKind.Stmt)
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            names.append(xmlAttr(stmt, AstNodeAttributeKind.Name))
        }
        names = simBoundNames(xmlChild(stmt, AstNodeKind.Body), names)
        names = simBoundNames(xmlChild(stmt, AstNodeKind.Then), names)
        names = simBoundNames(xmlChild(stmt, AstNodeKind.Else), names)
    }
    return names
}

data class SimRenamer(
    var scopes: List<SimRenameScope>,

    var used: Dictionary<Str, Bool>
) {
    // The innermost scope that renames this name, if any: "" when none does.
    fun renamedTo(name: Str): Str {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            // The scope is borrowed: a `SimRenameScope` holds a dictionary, so binding it
            // by value copied that dictionary at every name the walk asks about.
            val scope: *SimRenameScope = *this.scopes[i]
            if (scope.renamed.has(name)) {
                return scope.renamed.get(name).value()
            }
            i = i - 1
        }
        return ""
    }

    // The name a shadowed declaration gets: `_sm_` and the original name, then the counter
    // *after an underscore* - so a rename can never collide with a name the compiler
    // generates itself (`_sm_expr1`, `_sm_base1`, `simse_sw_1`), which is the one thing a
    // reserved prefix alone does not buy: `base2` renamed to `_sm_base2` would be the
    // lowering's own place slot.
    fun shadowName(name: Str): Str {
        var n: Int = 2
        var candidate: Str = "_sm_" + name + "_" + n.toString()
        while (this.used.has(candidate)) {
            n = n + 1
            candidate = "_sm_" + name + "_" + n.toString()
        }
        return candidate
    }

    // One statement list: name its own declarations first - a use may stand before the
    // declaration it means (`hoisting.kt`), and the scope answers for the whole list
    // either way - then rewrite the list with that scope pushed.
    fun inList(stmts: *List<AstXmlNode>): List<AstXmlNode> {
        var scope: SimRenameScope = SimRenameScope(Dictionary<Str, Str>())
        var emitted: List<Str> = List<Str>()
        for (*stmt in stmts) {
            var name: Str = ""
            if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
                val original: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
                name = original
                if (this.used.has(original)) {
                    name = this.shadowName(original)
                    scope.renamed.insert(original, name)
                }
                this.used.insert(name, true)
            }
            emitted.append(name)
        }
        this.scopes.append(scope)
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < stmts.size()) {
            var stmt: AstXmlNode = this.rewrite(*stmts[i], true)
            if (emitted[i] != "") {
                stmt.attributes = simNameAttrs(stmt, emitted[i])
            }
            out.append(stmt)
            i = i + 1
        }
        this.scopes.removeAt(this.scopes.size() - 1)
        return out
    }

    // A list inside a lambda body: the declarations there are the lambda's own, so this
    // pass rewrites the uses and names nothing.
    fun listOf(stmts: *List<AstXmlNode>, nameNested: Bool): List<AstXmlNode> {
        if (nameNested) {
            return this.inList(stmts)
        }
        var out: List<AstXmlNode> = List<AstXmlNode>()
        for (*stmt in stmts) {
            out.append(this.rewrite(stmt, false))
        }
        return out
    }

    // One node: its expressions rewritten, and the statement lists inside it named (or, in
    // a lambda body, rewritten for uses only). The node is *borrowed*: the walk reads it and
    // builds the rewritten one, and a by-value parameter copied every node of the body (and
    // of every expression under it) once per pass.
    fun rewrite(node: *AstXmlNode, nameNested: Bool): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(node)
        val masked: Bool = kind == AstNodeCategory.ExprLambda
        // Inside a lambda body this body names *nothing*: the lambda is a body of its own,
        // so its declarations are its own pass's to name (the same rule the C++ ring's
        // `rewriteUses(..., false)` states). Without this, two lambdas in one body that
        // each declare the same local would see the *second* one renamed - the enclosing
        // `used` had already seen the first - and the two rings would emit different
        // names for the same program.
        var nested: Bool = nameNested
        if (masked) {
            nested = false
        }
        if (masked) {
            // A lambda is a body of its own, so its own declarations are not this body's
            // to name - but a name it does not bind is captured from *this* body, and that
            // is the name the scopes decide.
            var inner: SimRenameScope = SimRenameScope(Dictionary<Str, Str>())
            val params: List<Str> = xmlLambdaParams(node)
            var p: Int = 0
            while (p < params.size()) {
                inner.renamed.insert(params[p], params[p])
                p = p + 1
            }
            val names: List<Str> = simBoundNames(xmlChild(node, AstNodeKind.Body), List<Str>())
            p = 0
            while (p < names.size()) {
                inner.renamed.insert(names[p], names[p])
                p = p + 1
            }
            this.scopes.append(inner)
        }
        var kids: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < node.Children.count()) {
            val child: *AstXmlNode = *node.Children[i]
            if (child.name == AstNodeKind.Body || child.name == AstNodeKind.Then
                || child.name == AstNodeKind.Else
            ) {
                val stmts: List<AstXmlNode> = xmlChildren(child, AstNodeKind.Stmt)
                if (stmts.size() > 0) {
                    kids.append(
                        exprReplaceRole(
                            child, AstNodeKind.Stmt,
                            this.listOf(*stmts, nested)
                        )
                    )
                } else {
                    kids.append(this.rewrite(child, nested))
                }
            } else {
                kids.append(this.rewrite(child, nested))
            }
            i = i + 1
        }
        var out: AstXmlNode = exprLike(node, kids)
        if (kind == AstNodeCategory.ExprName) {
            val mapped: Str = this.renamedTo(xmlAttr(node, AstNodeAttributeKind.Name))
            if (mapped != "") {
                out.attributes = simNameAttrs(node, mapped)
            }
        }
        if (masked) {
            this.scopes.removeAt(this.scopes.size() - 1)
        }
        return out
    }
}

// The body with one name per declaration: the first declaration of a name keeps it, and
// every later one - in any scope of the body - is renamed with its uses.
fun linRenameShadowed(body: List<AstXmlNode>, reserved: List<Str>): List<AstXmlNode> {
    var used: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < reserved.size()) {
        used.insert(reserved[i], true)
        i = i + 1
    }
    var renamer: SimRenamer = SimRenamer(List<SimRenameScope>(), used)
    return renamer.inList(*body)
}

// Whether a declaration is one the hoisting can move: a declaration has to be writable
// bare, and that needs its *whole* type - `auto x;` is not a declaration, a machine's
// `..T` has no spelling at all, and the inference leaves some slots partly unknown (`*?`:
// a pointer to nothing it could name).
fun linIsSpellableType(typeNode: AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeNamed, AstNodeCategory.TypeGeneric -> {
            return xmlAttr(typeNode, AstNodeAttributeKind.Name) != ""
        }

        AstNodeCategory.TypeIntLit -> {
            return true
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            return linIsSpellableType(xmlChild(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            if (!linIsSpellableType(xmlChild(typeNode, AstNodeKind.ReturnType))) {
                return false
            }
            val params: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            var i: Int = 0
            while (i < params.size()) {
                if (!linIsSpellableType(params[i])) {
                    return false
                }
                i = i + 1
            }
            return true
        }
    }
    return false
}

fun linIsHoistable(stmt: *AstXmlNode): Bool {
    if (xmlKind(stmt) != AstNodeCategory.StmtVarDecl) {
        return false
    }
    return linIsSpellableType(xmlChild(stmt, AstNodeKind.Type))
}

// One statement list rewritten: every declaration becomes an assignment (when it had an
// initializer) and its declaration is collected for the top of the body - so the caller
// learns whether anything moved from `decls` alone. Blocks keep their place (the folding
// is what deals with them); a lambda is a body of its own, so the walk does not enter one.
// A declaration *already* at the top of the list is not collected: it is where the
// hoisting puts one, so collecting it again would be work it did not do.
fun linHoistInList(stmts: *List<AstXmlNode>, decls: *List<AstXmlNode>, atTop: Bool): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (linIsHoistable(stmt)) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            val init: AstXmlNode = xmlChild(stmt, AstNodeKind.Init)
            if (!xmlIsEmpty(init)) {
                var assignment: AstXmlNode = linStmt(AstNodeCategory.StmtAssign, xmlLine(stmt), xmlColumn(stmt))
                assignment.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
                xmlAddChild(assignment, linName(AstNodeKind.Target, name, xmlLine(stmt), xmlColumn(stmt)))
                xmlAddChild(assignment, linRole(init, AstNodeKind.Value))
                out.append(assignment)

                val none: List<AstXmlNode> = List<AstXmlNode>()
                decls.append(exprReplaceRole(stmt, AstNodeKind.Init, none))
            } else if (!atTop) {
                // A declaration with nothing to initialize: it moves to the top for the
                // same reason as the rest (a jump may not skip it - the default
                // construction of a `Str` is an initialization too), and nothing is left
                // where it stood.
                val none: List<AstXmlNode> = List<AstXmlNode>()
                decls.append(exprReplaceRole(stmt, AstNodeKind.Init, none))
            } else {
                out.append(stmt)
            }
        } else if (linIsBlock(stmt)) {
            val inner: List<AstXmlNode> = linHoistInList(linBlockStmts(stmt), decls, false)
            val bodyNode: AstXmlNode = exprLike(xmlChild(stmt, AstNodeKind.Body), inner)
            out.append(exprReplaceRole(stmt, AstNodeKind.Body, linOne(bodyNode)))
        } else {
            out.append(stmt)
        }
        i = i + 1
    }
    return out
}

fun linHoistSlots(body: List<AstXmlNode>): LinLowered {
    var decls: List<AstXmlNode> = List<AstXmlNode>()
    val rewritten: List<AstXmlNode> = linHoistInList(body, decls, true)
    if (decls.size() == 0) {
        return LinLowered(rewritten, false)
    }
    var hoisted: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < decls.size()) {
        hoisted.append(decls[i])
        i = i + 1
    }
    i = 0
    while (i < rewritten.size()) {
        hoisted.append(rewritten[i])
        i = i + 1
    }
    return LinLowered(hoisted, true)
}

// The second half of the pipeline for one function-like body, run once `semInferTypes` has
// spelled the declarations: the shadowing is resolved (`linRenameShadowed`), the
// declarations move to the top of the body (`linHoistSlots`), which is what lets the
// folding fold the blocks the declarations forced, and the peephole gets another look at
// the flatter body (a jump a block hid is a jump it can fold, and a folded jump can free a
// label). The loop is the shape `linLowerForEmission` runs, with the hoisting in the place
// of the rewriting stages - there is nothing left to rewrite. `reserved` is the names the
// emitter has already declared in the body's own C++ scope (its parameters, `self`).
fun linFinishForEmission(body: List<AstXmlNode>, reserved: List<Str>): List<AstXmlNode> {
    var current: List<AstXmlNode> = linRenameShadowed(body, reserved)
    var canChange: Bool = true
    var guard: Int = 0
    while (canChange && guard < 256) {
        guard = guard + 1
        canChange = false
        val hoisted: LinLowered = linHoistSlots(current)
        current = hoisted.body
        canChange = canChange || hoisted.changed
        val simplified: LinLowered = linSimplifyBody(current)
        current = simplified.body
        canChange = canChange || simplified.changed
        val folded: LinLowered = linFlattenBlocks(current)
        current = folded.body
        canChange = canChange || folded.changed
    }
    return current
}

fun linSimplifyBody(body: List<AstXmlNode>): LinLowered {
    var simplifier: LinSimplifier = LinSimplifier(false)
    return simplifier.run(body)
}

// Folds nested blocks into their parent sequence.
fun linFlattenBlocks(body: *List<AstXmlNode>): LinLowered {
    var flattener: LinSimplifier = LinSimplifier(false)
    val flattened: List<AstXmlNode> = flattener.flattenPass(body)
    return LinLowered(flattened, flattener.changed)
}
