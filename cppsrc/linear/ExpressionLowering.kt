// ExpressionLowering.kt
//
// Binds every expression deeper than one operation to a `_sm_expr<n>` temporary, one
// expression vocabulary for the emitter (impl_specs/linear-lowering.md, "Expression
// lowering"); runs after `linSimplifyBody`, counter per body. An lvalue path stays unbound;
// `&&`/`||`, whose operands are conditional, belong to the control-flow lowering.

package linear

// A literal, a name, a qualified name (`Res<T>`), or a lambda (lowered as its own body).
// The empty `None` node counts too: it is where a child that does not exist sits, and a
// missing child must not be bound to a temporary.
fun exprIsSimple(e: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.None -> {
            return true
        }

        AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit, AstNodeCategory.ExprStrLit,
        AstNodeCategory.ExprCharLit, AstNodeCategory.ExprBoolLit, AstNodeCategory.ExprNullLit,
        AstNodeCategory.ExprName, AstNodeCategory.ExprGenericName, AstNodeCategory.ExprLambda -> {
            return true
        }
    }
    return false
}

// An lvalue path, never bound to a value temporary; `ExprDeref` counts even when its
// pointer is a temporary.
fun exprIsPlace(e: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprName, AstNodeCategory.ExprGenericName, AstNodeCategory.ExprDeref -> {
            return true
        }

        AstNodeCategory.ExprMember, AstNodeCategory.ExprIndex -> {
            return exprIsPlace(xmlChildPtr(e, AstNodeKind.Receiver))
        }
    }
    return false
}

// Whether binding this expression to a temporary is *safe*: a borrow whose operand is not
// an lvalue (`*f()`) must stay inline, since `simse_addressOf`'s pointer only lasts for the
// call it is passed to (cppsrc/rtl/types.hpp).
fun exprIsBindable(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprDeref) {
        return true
    }
    return exprIsPlace(xmlChildPtr(e, AstNodeKind.Operand))
}

// `&&`, `||` (and a future `?:`): operands that may not even be evaluated.
fun exprIsShortCircuit(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprBinary) {
        return false
    }
    val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
    return op == "&&" || op == "||"
}

// A fresh node with the same role, kind and attributes - the role is what a parent looks a
// child up by (`xmlChild`/`xmlChildren`).
fun exprLike(like: *AstXmlNode, kids: *List<AstXmlNode>): AstXmlNode {
    return AstXmlNode(like.name, like.kind, copy(like.attributes), kids.toArray())
}

// The same node with every `role` child replaced, in order, by `replacements`; `like` is
// never modified.
fun exprReplaceRole(like: *AstXmlNode, role: AstNodeKind, replacements: *List<AstXmlNode>): AstXmlNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    val existing: List<AstXmlNode> = like.Children.toList()
    var seen: Int = 0
    for (*child in existing) {
        if (child.name == role) {
            if (seen < replacements.size()) {
                kids.append(replacements[seen])
            }
            seen = seen + 1
        } else {
            kids.append(child)
        }
    }
    while (seen < replacements.size()) {
        kids.append(replacements[seen])
        seen = seen + 1
    }
    return exprLike(like, kids)
}

// Where an expression sits. `Root` is the statement's own expression (a declaration's
// initializer, an assignment's value), `Value` is a position whose *value* is read, and
// `Path` is a position that must stay an alias (a call receiver, an assignment target, the
// operand of `&`/`*`).
enum class ExprSlot {
    Root,
    Value,
    Path
}

data class ExprFlattener(
    // Per body, like the label counter in the linear pass.
    var next: Int,
    // Set when an expression was bound to a temporary; a body already lowered comes back
    // false (`linLowerForEmission`).
    var changed: Bool,
    // Set when the statement being walked had something rebuilt under it; an untouched
    // statement is passed on as it is, so the pass does not rebuild every child
    // (`exprReplaceRole`).
    var touched: Bool
) {
    fun freshTemp(): Str {
        val id: Int = this.next
        this.next = this.next + 1
        return "_sm_expr" + id.toString()
    }

    fun roleName(role: AstNodeKind, name: *Str, line: Int, column: Int): AstXmlNode {
        return linName(role, name, line, column)
    }

    fun bind(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val name: Str = this.freshTemp()
        temps.append(linSubjectDecl(name, e, xmlLine(e), xmlColumn(e)))
        this.changed = true
        return this.roleName(e.name, name, xmlLine(e), xmlColumn(e))
    }

    fun flatList(exprs: *List<AstXmlNode>, temps: *List<AstXmlNode>): List<AstXmlNode> {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        for (*expr in exprs) {
            out.append(this.pathOrValue(expr, temps))
        }
        return out
    }

    // A callee keeps its shape: `f`, `Res<T>.ok`, and a member call's receiver path
    // (`a[i].f(...)` stays a call on `a[i]`).
    fun flatCallee(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprMember -> {
                return this.flat(e, ExprSlot.Path, temps)
            }

            AstNodeCategory.ExprName, AstNodeCategory.ExprGenericName -> {
                return e
            }
        }
        return this.flat(e, ExprSlot.Value, temps)
    }

    fun pathOrValue(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        if (exprIsPlace(e)) {
            return this.flat(e, ExprSlot.Path, temps)
        }
        return this.flat(e, ExprSlot.Value, temps)
    }

    // The same node with its operands flattened. The original is never touched.
    fun rebuild(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        this.touched = true
        when (kind) {
            AstNodeCategory.ExprMember -> {
                var receiver: List<AstXmlNode> = List<AstXmlNode>()
                receiver.append(this.pathOrValue(xmlChildPtr(e, AstNodeKind.Receiver), temps))
                return exprReplaceRole(e, AstNodeKind.Receiver, receiver)
            }

            AstNodeCategory.ExprIndex -> {
                var receiver: List<AstXmlNode> = List<AstXmlNode>()
                receiver.append(this.pathOrValue(xmlChildPtr(e, AstNodeKind.Receiver), temps))
                var index: List<AstXmlNode> = List<AstXmlNode>()
                index.append(this.flat(xmlChildPtr(e, AstNodeKind.Index), ExprSlot.Value, temps))
                val withReceiver: AstXmlNode = exprReplaceRole(e, AstNodeKind.Receiver, receiver)
                return exprReplaceRole(withReceiver, AstNodeKind.Index, index)
            }

            AstNodeCategory.ExprCall -> {
                var callee: List<AstXmlNode> = List<AstXmlNode>()
                callee.append(this.flatCallee(xmlChildPtr(e, AstNodeKind.Callee), temps))
                val argList: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)
                val args: List<AstXmlNode> = this.flatList(argList, temps)
                val withCallee: AstXmlNode = exprReplaceRole(e, AstNodeKind.Callee, callee)
                return exprReplaceRole(withCallee, AstNodeKind.Arg, args)
            }

            AstNodeCategory.ExprBinary -> {
                var lhs: List<AstXmlNode> = List<AstXmlNode>()
                lhs.append(this.flat(xmlChildPtr(e, AstNodeKind.Lhs), ExprSlot.Value, temps))
                var rhs: List<AstXmlNode> = List<AstXmlNode>()
                rhs.append(this.flat(xmlChildPtr(e, AstNodeKind.Rhs), ExprSlot.Value, temps))
                val withLhs: AstXmlNode = exprReplaceRole(e, AstNodeKind.Lhs, lhs)
                return exprReplaceRole(withLhs, AstNodeKind.Rhs, rhs)
            }

            AstNodeCategory.ExprUnary, AstNodeCategory.ExprCopy -> {
                var operand: List<AstXmlNode> = List<AstXmlNode>()
                operand.append(this.flat(xmlChildPtr(e, AstNodeKind.Operand), ExprSlot.Value, temps))
                return exprReplaceRole(e, AstNodeKind.Operand, operand)
            }

            AstNodeCategory.ExprRef, AstNodeCategory.ExprDeref -> {
                // `&x` boxes and `*x` borrows: the operand stays a place, or the address
                // of a temporary would be taken.
                var operand: List<AstXmlNode> = List<AstXmlNode>()
                operand.append(this.pathOrValue(xmlChildPtr(e, AstNodeKind.Operand), temps))
                return exprReplaceRole(e, AstNodeKind.Operand, operand)
            }
        }
        return e
    }

    fun flat(e: *AstXmlNode, slot: ExprSlot, temps: *List<AstXmlNode>): AstXmlNode {
        if (exprIsSimple(e) || exprIsShortCircuit(e)) {
            return e
        }
        val built: AstXmlNode = this.rebuild(e, temps)
        if (slot != ExprSlot.Value) {
            return built
        }
        // A *value* position is one operation deep: `self.x` becomes its own temporary,
        // so only the alias positions above keep a path unbound.
        if (exprIsSimple(built) || !exprIsBindable(built)) {
            return built
        }
        return this.bind(built, temps)
    }

    // The statement plus the temporaries its expressions needed, scoped so a jump can never
    // cross one of them.
    fun withTemps(stmt: AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        if (temps.size() == 0) {
            return stmt
        }
        temps.append(stmt)
        return linBlock(temps, xmlLine(stmt), xmlColumn(stmt))
    }

    // Whether any statement had a rebuild under it: a child's walk clears `touched` as it
    // goes, so the answer is the disjunction.
    fun walkStmts(stmts: *List<AstXmlNode>, out: *List<AstXmlNode>): Bool {
        var any: Bool = false
        for (*stmt in stmts) {
            this.walkStmt(stmt, out)
            if (this.touched) {
                any = true
            }
        }
        return any
    }

    fun walkStmt(stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
        val kind: AstNodeCategory = xmlKind(stmt)
        var temps: List<AstXmlNode> = List<AstXmlNode>()
        this.touched = false
        when (kind) {
            AstNodeCategory.StmtBlock -> {
                var inner: List<AstXmlNode> = List<AstXmlNode>()
                val anyInner: Bool =
                    this.walkStmts(xmlChildren(xmlChildPtr(stmt, AstNodeKind.Body), AstNodeKind.Stmt), inner)
                this.touched = anyInner
                if (!anyInner) {
                    out.append(stmt)
                    return
                }
                out.append(linBlock(inner, xmlLine(stmt), xmlColumn(stmt)))
                return
            }

            AstNodeCategory.StmtVarDecl -> {
                // The temporaries stay in this scope (the declared name is visible for the
                // rest of the region, which is already a C++ block, so no jump crosses them).
                var init: List<AstXmlNode> = List<AstXmlNode>()
                init.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Init), ExprSlot.Root, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                var i: Int = 0
                while (i < temps.size()) {
                    out.append(temps[i])
                    i = i + 1
                }
                out.append(exprReplaceRole(stmt, AstNodeKind.Init, init))
                return
            }

            AstNodeCategory.StmtIfTrue, AstNodeCategory.StmtIfFalse -> {
                // The condition is a value position: a non-name becomes its own temporary,
                // so the jump is all the statement does.
                var cond: List<AstXmlNode> = List<AstXmlNode>()
                cond.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Cond), ExprSlot.Value, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Cond, cond), temps))
                return
            }

            AstNodeCategory.StmtAssign -> {
                var target: List<AstXmlNode> = List<AstXmlNode>()
                target.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Target), ExprSlot.Path, temps))
                var value: List<AstXmlNode> = List<AstXmlNode>()
                value.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Value), ExprSlot.Root, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                val withTarget: AstXmlNode = exprReplaceRole(stmt, AstNodeKind.Target, target)
                out.append(this.withTemps(exprReplaceRole(withTarget, AstNodeKind.Value, value), temps))
                return
            }

            AstNodeCategory.StmtReturn -> {
                var value: List<AstXmlNode> = List<AstXmlNode>()
                value.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Value), ExprSlot.Value, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Value, value), temps))
                return
            }

            AstNodeCategory.StmtExprStmt -> {
                var expr: List<AstXmlNode> = List<AstXmlNode>()
                expr.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Expr), ExprSlot.Root, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Expr, expr), temps))
                return
            }

            AstNodeCategory.StmtYield -> {
                // `yield e` hands a value out, so its expression is a value position like a
                // `return`'s: one operand, or a temporary. (The state-machine pass replaces
                // the statement afterwards.)
                var value: List<AstXmlNode> = List<AstXmlNode>()
                value.append(this.flat(xmlChildPtr(stmt, AstNodeKind.Value), ExprSlot.Value, temps))
                if (!this.touched) {
                    out.append(stmt)
                    return
                }
                out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Value, value), temps))
                return
            }
        }
        // Label/Goto hold no expressions; If/While/Break/Continue are gone by now
        // (`linLowerBody`).
        out.append(stmt)
    }
}

// Lowers the expressions of one linear body; the counter restarts per body.
fun linLowerExprs(body: *List<AstXmlNode>): LinLowered {
    var flattener: ExprFlattener = ExprFlattener(1, false, false)
    var out: List<AstXmlNode> = List<AstXmlNode>()
    flattener.walkStmts(body, out)
    return LinLowered(out, flattener.changed)
}
