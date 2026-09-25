// Simplify.kt
//
// Peephole simplification of the linear form (impl_specs/linear-lowering.md): drops a jump
// to the next label, folds `ifTrue (c) goto A; goto B; A:` into `ifFalse (c) goto B;`, and
// drops an unreachable run and a label nothing jumps to. Runs to a fixed point, since a
// dropped jump or statement can expose another fold.
package linear

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

// The same test for item `p`. A block's body is `bodies[p]`, not `stmts[p]`: the wrapper is
// built only when the block survives, so `stmts[p]` still carries the parsed tree.
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

// Whether the block at `i` can be spliced into `stmts`: after the splice its declarations
// are in the parent's scope, so it is legal exactly when no jump `J` and label `L` satisfy
// `pos (J) < pos (D) <= pos (L)` for a declaration `D` it brings up.
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

// A copy of a conditional jump with a negated condition (IfTrue <-> IfFalse) and a new
// target.
fun linInvertedJump(jump: *AstXmlNode, target: *Str): AstXmlNode {
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
    xmlAddChild(node, xmlChildPtr(jump, AstNodeKind.Cond))
    return node
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
            } else if (linIsCondJump(stmt) && i + 2 < stmts.size() && linIsGoto(stmts[i + 1])
                && linIsLabel(stmts[i + 2])
                && xmlAttr(stmts[i + 2], AstNodeAttributeKind.Name) == xmlAttr(stmt, AstNodeAttributeKind.Name)
            ) {
                out.append(linInvertedJump(stmt, xmlAttr(stmts[i + 1], AstNodeAttributeKind.Name)))
                this.changed = true
                i = i + 2
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

// Every declaration of a body - the lowering's temporaries and the program's `val`/`var`
// alike - moves to the top of the body, and each initializer becomes an assignment where
// the declaration stood. A declaration at the top is one no jump can bypass, which is what
// the folding needs (C2362): after it, no block is left for a declaration's sake. The
// initialization stays where it was, so evaluation order and side effects do not move.
//
// Runs after the type pass: a declaration has to keep the type that pass proved (`auto x;`
// is not a declaration), so one the inference could not spell keeps its place.

// Hoisting gives a body one C++ scope, so a name must be unique *in the body*: the second
// declaration of a name is renamed and the uses that resolve to it move with it, so a name
// never changes what it means (impl_specs/linear-il.md). A generated name carries the
// reserved `_sm_` prefix (`_sm_expr1`, `_sm_for1`), so a rename is never mistaken for a
// source name.
//
// `reserved` is what the emitter has already declared in that scope: the parameters and
// `self`.

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

// Every name a body binds, at any depth. A use inside a lambda belongs to the lambda, so the
// enclosing scopes must not rewrite it, and the lambda's own pass names it.
fun simBoundNames(body: *AstXmlNode, bound: List<Str>): List<Str> {
    var names: List<Str> = bound
    val stmts: List<AstXmlNode> = xmlChildren(body, AstNodeKind.Stmt)
    for (*stmt in stmts) {
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            names.append(xmlAttr(stmt, AstNodeAttributeKind.Name))
        }
        names = simBoundNames(xmlChildPtr(stmt, AstNodeKind.Body), names)
        names = simBoundNames(xmlChildPtr(stmt, AstNodeKind.Then), names)
        names = simBoundNames(xmlChildPtr(stmt, AstNodeKind.Else), names)
    }
    return names
}

data class SimRenamer(
    var scopes: List<SimRenameScope>,

    var used: Dictionary<Str, Bool>
) {
    // The innermost scope that renames this name, if any: "" when none does.
    fun renamedTo(name: *Str): Str {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            val scope: *SimRenameScope = *this.scopes[i]
            val renamed: *Str = scope.renamed.getPtr(name)
            if (renamed != null) {
                return *renamed
            }
            i = i - 1
        }
        return ""
    }

    // The name a shadowed declaration gets: `_sm_` plus the original name, then the counter
    // *after an underscore*, so a rename cannot collide with a name the compiler generates
    // itself (`_sm_expr1`).
    fun shadowName(name: Str): Str {
        var n: Int = 2
        var candidate: Str = fmtStr("_sm_|_|", name, n.toString())
        while (this.used.has(candidate)) {
            n = n + 1
            candidate = fmtStr("_sm_|_|", name, n.toString())
        }
        return candidate
    }

    // One statement list: name its own declarations first - a use may stand before the
    // declaration it means, and the scope answers for the whole list either way - then
    // rewrite the list with that scope pushed.
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

    // Its expressions rewritten and the statement lists inside it named (or, in a lambda
    // body, uses only).
    fun rewrite(node: *AstXmlNode, nameNested: Bool): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(node)
        val masked: Bool = kind == AstNodeCategory.ExprLambda
        // Inside a lambda body this body names *nothing*: the lambda is a body of its own, so
        // its declarations are its own pass's to name. Without this, two lambdas in one body
        // that each declare the same local would see the *second* one renamed (the enclosing
        // `used` had already seen the first).
        var nested: Bool = nameNested
        if (masked) {
            nested = false
        }
        if (masked) {
            // A lambda is a body of its own, so its own declarations are not this body's to
            // name - but a name it does not bind is captured from *this* body, and that is
            // the name the scopes decide.
            var inner: SimRenameScope = SimRenameScope(Dictionary<Str, Str>())
            val params: List<Str> = xmlLambdaParams(node)
            var p: Int = 0
            while (p < params.size()) {
                inner.renamed.insert(params[p], params[p])
                p = p + 1
            }
            val names: List<Str> = simBoundNames(xmlChildPtr(node, AstNodeKind.Body), List<Str>())
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
fun linRenameShadowed(body: *List<AstXmlNode>, reserved: *List<Str>): List<AstXmlNode> {
    var used: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < reserved.size()) {
        used.insert(reserved[i], true)
        i = i + 1
    }
    var renamer: SimRenamer = SimRenamer(List<SimRenameScope>(), used)
    return renamer.inList(*body)
}

// Whether a declaration is one the hoisting can move: it must be writable bare, which needs
// its whole type - `auto x;` is not a declaration, and the inference leaves some slots
// partly unknown (`*?`). A machine's `..T` is spellable once the call that created it named
// the class (`semMachineType`); an anonymous one keeps its block.
fun linIsSpellableType(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeNamed, AstNodeCategory.TypeGeneric -> {
            return xmlAttr(typeNode, AstNodeAttributeKind.Name) != ""
        }

        AstNodeCategory.TypeIntLit -> {
            return true
        }

        AstNodeCategory.TypeYield -> {
            if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "") {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            var i: Int = 0
            while (i < args.size()) {
                if (!linIsSpellableType(args[i])) {
                    return false
                }
                i = i + 1
            }
            return true
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            return linIsSpellableType(xmlChildPtr(typeNode, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            if (!linIsSpellableType(xmlChildPtr(typeNode, AstNodeKind.ReturnType))) {
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
    return linIsSpellableType(xmlChildPtr(stmt, AstNodeKind.Type))
}

// One list rewritten: every declaration becomes an assignment (when it had an initializer)
// and is collected in `decls` for the top of the body. Blocks keep their place; a lambda is
// a body of its own, so the walk does not enter one. A declaration already at the top is not
// collected - it is where the hoisting puts one.
fun linHoistInList(stmts: *List<AstXmlNode>, decls: *List<AstXmlNode>, atTop: Bool): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (linIsHoistable(stmt)) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
            if (!xmlIsEmpty(init)) {
                var assignment: AstXmlNode = linStmt(AstNodeCategory.StmtAssign, xmlLine(stmt), xmlColumn(stmt))
                assignment.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
                xmlAddChild(assignment, linName(AstNodeKind.Target, name, xmlLine(stmt), xmlColumn(stmt)))
                xmlAddChild(assignment, linRole(init, AstNodeKind.Value))
                out.append(assignment)

                val none: List<AstXmlNode> = List<AstXmlNode>()
                decls.append(exprReplaceRole(stmt, AstNodeKind.Init, none))
            } else if (!atTop) {
                // Nothing is left where it stood: a default construction is an
                // initialization too, so a jump may not skip it.
                val none: List<AstXmlNode> = List<AstXmlNode>()
                decls.append(exprReplaceRole(stmt, AstNodeKind.Init, none))
            } else {
                out.append(stmt)
            }
        } else if (linIsBlock(stmt)) {
            val inner: List<AstXmlNode> = linHoistInList(linBlockStmts(stmt), decls, false)
            val bodyNode: AstXmlNode = exprLike(xmlChildPtr(stmt, AstNodeKind.Body), inner)
            out.append(exprReplaceRole(stmt, AstNodeKind.Body, linOne(bodyNode)))
        } else {
            out.append(stmt)
        }
        i = i + 1
    }
    return out
}

fun linHoistSlots(body: *List<AstXmlNode>): LinLowered {
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

// The second half of the pipeline for one body, run once `semInferTypes` has spelled the
// declarations: shadowing resolved, declarations hoisted to the top (which lets the folding
// fold the blocks they forced), then the peephole again. The loop is `linLowerForEmission`'s
// shape with hoisting in place of the rewriting stages. `reserved` is what the emitter has
// already declared in the body's scope (its parameters, `self`).
fun linFinishForEmission(body: *List<AstXmlNode>, reserved: *List<Str>): List<AstXmlNode> {
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
        // The linear form's own passes (cppsrc/optimizations) rewrite the body in place and
        // answer whether it moved.
        if (linOptimizeBody(*current)) {
            canChange = true
        }
    }
    return current
}

fun linSimplifyBody(body: *List<AstXmlNode>): LinLowered {
    var simplifier: LinSimplifier = LinSimplifier(false)
    return simplifier.run(body)
}

// Folds nested blocks into their parent sequence.
fun linFlattenBlocks(body: *List<AstXmlNode>): LinLowered {
    var flattener: LinSimplifier = LinSimplifier(false)
    val flattened: List<AstXmlNode> = flattener.flattenPass(body)
    return LinLowered(flattened, flattener.changed)
}
