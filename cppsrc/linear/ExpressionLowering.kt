// ExpressionLowering.kt
//
// Lowering of nested expressions into temporaries (impl_specs/linear-lowering.md,
// "Expression lowering"), ported from cppsrc/linear/ExpressionLowering.cpp. The
// linear pass gives the emitter one statement vocabulary (`label`/`goto`/`ifTrue`/
// `ifFalse`/blocks); this pass gives it one *expression* vocabulary: every
// expression is either a simple operand or an operation over simple operands, and
// anything deeper is bound to a `_sm_expr<n>` local:
//
//   var a = (b + c) * d;        ->  var _sm_expr1 = b + c;
//                                  var a = _sm_expr1 * d;
//   x = a[i + 2].toString();    ->  var _sm_expr1 = i + 2;
//                                  x = a[_sm_expr1].toString();
//
// It runs on a lowered body, after `linSimplifyBody` and before emission, so the
// peephole pass never sees the temporaries. The counter restarts per body, like the
// labels.
//
// Two boundaries are deliberate:
//
//   - an **lvalue path** (a name, or a member/index/deref chain rooted at one) is
//     left as it is: it is already a simple operand, and binding it to a value
//     temporary would copy what is behind it, so a mutating call on the copy would
//     be lost. Its indices and arguments are flattened;
//   - **`&&` and `||`** (and a future `?:`) are left untouched: their operands are
//     evaluated conditionally, so hoisting anything out of them would change the
//     program. They belong to the control-flow lowering (`ifTrue`/`ifFalse` plus
//     labels), not to this pass.

package linear

// A literal, a name, a qualified name (`Res<T>`), or a lambda. A lambda's own body
// is a separate body: the same passes lower it when it is emitted. The schema's
// *absent* sentinel (an empty node, `kind` `None`) counts as simple: it is where a
// child that does not exist sits - a bare `return;` has no value - and treating it
// as an expression is how a missing child would end up bound to a temporary.
fun exprIsSimple(e: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(e)
    if (kind == AstNodeCategory.None) {
        return true
    }
    if (kind == AstNodeCategory.ExprIntLit || kind == AstNodeCategory.ExprFloatLit
        || kind == AstNodeCategory.ExprStrLit || kind == AstNodeCategory.ExprCharLit
        || kind == AstNodeCategory.ExprBoolLit || kind == AstNodeCategory.ExprNullLit
        || kind == AstNodeCategory.ExprName || kind == AstNodeCategory.ExprGenericName
        || kind == AstNodeCategory.ExprLambda
    ) {
        return true
    }
    return false
}

// An lvalue path: never bound to a value temporary. `ExprDeref` counts even when its
// pointer is a temporary: it still names a place the emitter passes on as a
// reference.
fun exprIsPlace(e: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(e)
    if (kind == AstNodeCategory.ExprName || kind == AstNodeCategory.ExprGenericName
        || kind == AstNodeCategory.ExprDeref
    ) {
        return true
    }
    if (kind == AstNodeCategory.ExprMember || kind == AstNodeCategory.ExprIndex) {
        return exprIsPlace(*xmlChild(e, AstNodeKind.Receiver))
    }
    return false
}

// Whether binding this expression to a temporary is *safe*. Everything is, except a
// borrow whose operand is not an lvalue: `*f()` names a temporary, and
// `simse_addressOf`'s contract is that the pointer lasts for the call it is passed
// to (cppsrc/rtl/types.hpp) - hoisting it into a variable would outlive it. Such a
// borrow stays inline.
fun exprIsBindable(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprDeref) {
        return true
    }
    return exprIsPlace(*xmlChild(e, AstNodeKind.Operand))
}

// `&&`, `||` (and a future `?:`): operands that may not even be evaluated.
fun exprIsShortCircuit(e: *AstXmlNode): Bool {
    if (xmlKind(e) != AstNodeCategory.ExprBinary) {
        return false
    }
    val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
    return op == "&&" || op == "||"
}

// A fresh node with the same role, kind and attributes, and the given children. The
// role is kept because a parent looks a child up by it (`xmlChild`/`xmlChildren`).
fun exprLike(like: *AstXmlNode, kids: *List<AstXmlNode>): AstXmlNode {
    return AstXmlNode(like.name, like.kind, copy(like.attributes), kids.toArray())
}

// The same node with every child whose role is `role` replaced, in order, by
// `replacements`; the other children keep their places. `like` is never modified.
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
            kids.append(copy(child))
        }
    }
    while (seen < replacements.size()) {
        kids.append(replacements[seen])
        seen = seen + 1
    }
    return exprLike(like, *kids)
}

// Where an expression sits. `Root` is the statement's own expression (a declaration's
// initializer, an assignment's value), `Value` is a position whose *value* is read,
// and `Path` is a position that must stay an alias (a call receiver, an assignment
// target, the operand of `&`/`*`).
enum ExprSlot {
    Root,
    Value,
    Path
}

data class ExprFlattener(
    // Per body, like the label counter in the linear pass.
    var next: Int,
    // Whether an expression was actually bound to a temporary. Everything else
    // this pass does keeps the shape it read, so a body that is already lowered
    // comes back with this false (`linLowerForEmission`).
    var changed: Bool
) {
    fun freshTemp(): Str {
        val id: Int = this.next
        this.next = this.next + 1
        return "_sm_expr" + id.toString()
    }

    fun roleName(role: AstNodeKind, name: Str, line: Int, column: Int): AstXmlNode {
        return linName(role, name, line, column)
    }

    // Binds a value expression to a fresh temporary and returns its name.
    fun bind(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val name: Str = this.freshTemp()
        temps.append(linSubjectDecl(name, e, xmlLine(e), xmlColumn(e)))
        this.changed = true
        return this.roleName(e.name, name, xmlLine(e), xmlColumn(e))
    }

    fun flatList(exprs: *List<AstXmlNode>, temps: *List<AstXmlNode>): List<AstXmlNode> {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < exprs.size()) {
            out.append(this.flat(*exprs[i], ExprSlot.Value, temps))
            i = i + 1
        }
        return out
    }

    // A callee keeps its shape: `f`, `Res<T>.ok`, and a member call's receiver path
    // (`a[i].f(...)` stays a call on `a[i]`).
    fun flatCallee(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        if (kind == AstNodeCategory.ExprMember) {
            return this.flat(e, ExprSlot.Path, temps)
        }
        if (kind == AstNodeCategory.ExprName || kind == AstNodeCategory.ExprGenericName) {
            return copy(e)
        }
        return this.flat(e, ExprSlot.Value, temps)
    }

    // A place stays a place; anything that produces a value is flattened as a value
    // (and bound if it is deeper than one operation).
    fun pathOrValue(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        if (exprIsPlace(e)) {
            return this.flat(e, ExprSlot.Path, temps)
        }
        return this.flat(e, ExprSlot.Value, temps)
    }

    // The same node with its operands flattened. The original is never touched.
    fun rebuild(e: *AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        if (kind == AstNodeCategory.ExprMember) {
            var receiver: List<AstXmlNode> = List<AstXmlNode>()
            receiver.append(this.pathOrValue(*xmlChild(e, AstNodeKind.Receiver), temps))
            return exprReplaceRole(e, AstNodeKind.Receiver, *receiver)
        }
        if (kind == AstNodeCategory.ExprIndex) {
            var receiver: List<AstXmlNode> = List<AstXmlNode>()
            receiver.append(this.pathOrValue(*xmlChild(e, AstNodeKind.Receiver), temps))
            var index: List<AstXmlNode> = List<AstXmlNode>()
            index.append(this.flat(*xmlChild(e, AstNodeKind.Index), ExprSlot.Value, temps))
            val withReceiver: AstXmlNode = exprReplaceRole(e, AstNodeKind.Receiver, *receiver)
            return exprReplaceRole(*withReceiver, AstNodeKind.Index, *index)
        }
        if (kind == AstNodeCategory.ExprCall) {
            var callee: List<AstXmlNode> = List<AstXmlNode>()
            callee.append(this.flatCallee(*xmlChild(e, AstNodeKind.Callee), temps))
            val argList: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)
            val args: List<AstXmlNode> = this.flatList(*argList, temps)
            val withCallee: AstXmlNode = exprReplaceRole(e, AstNodeKind.Callee, *callee)
            return exprReplaceRole(*withCallee, AstNodeKind.Arg, *args)
        }
        if (kind == AstNodeCategory.ExprBinary) {
            var lhs: List<AstXmlNode> = List<AstXmlNode>()
            lhs.append(this.flat(*xmlChild(e, AstNodeKind.Lhs), ExprSlot.Value, temps))
            var rhs: List<AstXmlNode> = List<AstXmlNode>()
            rhs.append(this.flat(*xmlChild(e, AstNodeKind.Rhs), ExprSlot.Value, temps))
            val withLhs: AstXmlNode = exprReplaceRole(e, AstNodeKind.Lhs, *lhs)
            return exprReplaceRole(*withLhs, AstNodeKind.Rhs, *rhs)
        }
        if (kind == AstNodeCategory.ExprUnary || kind == AstNodeCategory.ExprCopy) {
            var operand: List<AstXmlNode> = List<AstXmlNode>()
            operand.append(this.flat(*xmlChild(e, AstNodeKind.Operand), ExprSlot.Value, temps))
            return exprReplaceRole(e, AstNodeKind.Operand, *operand)
        }
        if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
            // `&x` boxes and `*x` borrows: both keep a place in place, or the address
            // of a temporary would be taken.
            var operand: List<AstXmlNode> = List<AstXmlNode>()
            operand.append(this.pathOrValue(*xmlChild(e, AstNodeKind.Operand), temps))
            return exprReplaceRole(e, AstNodeKind.Operand, *operand)
        }
        return copy(e)
    }

    fun flat(e: *AstXmlNode, slot: ExprSlot, temps: *List<AstXmlNode>): AstXmlNode {
        if (exprIsSimple(e) || exprIsShortCircuit(e)) {
            return copy(e)
        }
        val built: AstXmlNode = this.rebuild(e, temps)
        if (slot != ExprSlot.Value) {
            return built
        }
        // A *value* position is one operation deep: `self.x` is a member access like
        // any other, so it becomes its own temporary and `self.x + self.y` is three
        // temporaries. Only the positions that must stay aliases - a call receiver,
        // an assignment target, the operand of `&`/`*` - keep a path unbound.
        if (exprIsSimple(*built) || !exprIsBindable(*built)) {
            return built
        }
        return this.bind(*built, temps)
    }

    // The statement plus the temporaries its expressions needed, scoped so a jump can
    // never cross one of them.
    fun withTemps(stmt: AstXmlNode, temps: *List<AstXmlNode>): AstXmlNode {
        if (temps.size() == 0) {
            return stmt
        }
        temps.append(stmt)
        return linBlock(temps, xmlLine(*stmt), xmlColumn(*stmt))
    }

    fun walkStmts(stmts: List<AstXmlNode>, out: *List<AstXmlNode>): Unit {
        var i: Int = 0
        while (i < stmts.size()) {
            this.walkStmt(*stmts[i], out)
            i = i + 1
        }
    }

    fun walkStmt(stmt: *AstXmlNode, out: *List<AstXmlNode>): Unit {
        val kind: AstNodeCategory = xmlKind(stmt)
        var temps: List<AstXmlNode> = List<AstXmlNode>()
        if (kind == AstNodeCategory.StmtBlock) {
            var inner: List<AstXmlNode> = List<AstXmlNode>()
            this.walkStmts(xmlChildren(*xmlChild(stmt, AstNodeKind.Body), AstNodeKind.Stmt), *inner)
            out.append(linBlock(*inner, xmlLine(stmt), xmlColumn(stmt)))
            return
        }
        if (kind == AstNodeCategory.StmtVarDecl) {
            // The temporaries stay in this scope: the declared name is visible for the
            // rest of the region. A region that declares anything is already a C++
            // block (`linLowerBody`), which is what keeps a jump from crossing them.
            var init: List<AstXmlNode> = List<AstXmlNode>()
            init.append(this.flat(*xmlChild(stmt, AstNodeKind.Init), ExprSlot.Root, *temps))
            var i: Int = 0
            while (i < temps.size()) {
                out.append(temps[i])
                i = i + 1
            }
            out.append(exprReplaceRole(stmt, AstNodeKind.Init, *init))
            return
        }
        if (kind == AstNodeCategory.StmtIfTrue || kind == AstNodeCategory.StmtIfFalse) {
            // The condition is a *value* position like any operand: a comparison or a
            // call that is not a single name becomes its own temporary, so a jump is
            // the only thing the statement does.
            var cond: List<AstXmlNode> = List<AstXmlNode>()
            cond.append(this.flat(*xmlChild(stmt, AstNodeKind.Cond), ExprSlot.Value, *temps))
            out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Cond, *cond), *temps))
            return
        }
        if (kind == AstNodeCategory.StmtAssign) {
            var target: List<AstXmlNode> = List<AstXmlNode>()
            target.append(this.flat(*xmlChild(stmt, AstNodeKind.Target), ExprSlot.Path, *temps))
            var value: List<AstXmlNode> = List<AstXmlNode>()
            value.append(this.flat(*xmlChild(stmt, AstNodeKind.Value), ExprSlot.Root, *temps))
            val withTarget: AstXmlNode = exprReplaceRole(stmt, AstNodeKind.Target, *target)
            out.append(this.withTemps(exprReplaceRole(*withTarget, AstNodeKind.Value, *value), *temps))
            return
        }
        if (kind == AstNodeCategory.StmtReturn) {
            // Same for the returned value: `return i < 2;` is a temporary and then a
            // `return` of one name.
            var value: List<AstXmlNode> = List<AstXmlNode>()
            value.append(this.flat(*xmlChild(stmt, AstNodeKind.Value), ExprSlot.Value, *temps))
            out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Value, *value), *temps))
            return
        }
        if (kind == AstNodeCategory.StmtExprStmt) {
            var expr: List<AstXmlNode> = List<AstXmlNode>()
            expr.append(this.flat(*xmlChild(stmt, AstNodeKind.Expr), ExprSlot.Root, *temps))
            out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Expr, *expr), *temps))
            return
        }
        if (kind == AstNodeCategory.StmtYield) {
            // `yield e` hands a value out, so its expression is a value position like a
            // `return`'s: one operand, or a temporary. (The state-machine pass replaces the
            // statement afterwards.) Without this, the *first* thing the machine inlines is
            // a whole expression, and the two rings stop emitting the same C++.
            var value: List<AstXmlNode> = List<AstXmlNode>()
            value.append(this.flat(*xmlChild(stmt, AstNodeKind.Value), ExprSlot.Value, *temps))
            out.append(this.withTemps(exprReplaceRole(stmt, AstNodeKind.Value, *value), *temps))
            return
        }
        // Label and Goto hold no expressions; If/While/Switch/Break/Continue cannot
        // appear here (`linLowerBody` removed them).
        out.append(copy(stmt))
    }
}

// Lowers the expressions of one linear body; the counter restarts per body.
fun linLowerExprs(body: List<AstXmlNode>): LinLowered {
    var flattener: ExprFlattener = ExprFlattener(1, false)
    var out: List<AstXmlNode> = List<AstXmlNode>()
    flattener.walkStmts(body, *out)
    return LinLowered(out, flattener.changed)
}
