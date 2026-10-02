// SimplifyNames.kt
//
// The linear form's name analysis: which names a body binds or uses, and the merges and
// renames the peephole (Simplify.kt) needs.

package linear
import compiler

import common
import optimizations


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
        var candidate: Str = `_sm_@(name)_@n`
        while (this.used.has(candidate)) {
            n = n + 1
            candidate = `_sm_@(name)_@n`
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
        // Inside a lambda body this body names *nothing*: the lambda is a body of its own, so its
        // declarations are its own pass's to name (otherwise two lambdas in one body that each
        // declare the same local would see the *second* one renamed, the enclosing `used` having
        // already seen the first).
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
    // A constructed declaration (`var x = T(a)`) stays with its initializer: the backend routes
    // it through `T.initByValue` rather than hoisting the initializer into an assignment.
    if (xmlAttr(stmt, AstNodeAttributeKind.InitByValue) == "true") {
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
    for (*stmt in stmts) {
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

