// ReuseExprs.kt
//
// Two instructions that compute the same expression are one computation, so the second becomes a
// read of the first. The property is *invariance*: the expression is pure and memory-independent
// and its operands still hold what they held at the first occurrence. That is one rule with no
// per-kind exceptions - the opcodes it covers are the ones whose result is a function of their
// operand *values* alone and which read no memory:
//
//   BinaryOp        a + b, a < b, ...
//   UnaryOp         -a, !a, ~a
//   FieldAddr       &base.field
//   IndexAddr       &base[i]
//   GetStaticAddr   &Static.member
//
// (impl_specs/expr-reuse.md, "Purity, effects and value numbering": the "pure, memory-independent"
// row.) A memory-reading op (`x.f`, `x[i]`, a call) is *not* here: a write between its two
// occurrences may change it, and the kill analysis that would decide that is not built yet - so
// `_sm_expr2 = _sm_base7->size()` is still recomputed across the append in the evidence. An op
// that yields a fresh identity (`Pack`, `toArray`, the fused `Concat`) is not here either:
// sharing one block between two uses would make a write through one show through the other.
//
// **One basic block at a time.** The kept instruction stands in the same straight-line run as the
// one it replaces, so it surely ran first; the tables are dropped at every label and branch. That
// is what makes the rule sound where a whole-body scan is not: `&x.f` in the `then` arm and in the
// `else` arm share a key and their operands, yet neither ran before the other, so they must stay
// two. It is also why the pass cannot hoist a common expression to the entry block on its own -
// that is the dominance/entry-hoisting step impl_specs/expr-reuse.md leaves for later.
//
// The operands are compared by *value*: a slot contributes its number and the instruction that last
// wrote it, so an operand reassigned between the two occurrences changes the key and the pair stays
// two expressions. The destination is not part of the key - it is what the instruction produces -
// and the slot the first instruction wrote must still hold its value (nothing rewrote it since).
// Both the kept slot and the merged-away one must also be written exactly once in the body: the
// local merge reuses a slot whose live ranges do not overlap, and redirecting every read of a
// slot that is later reused would read the later value.
//
// The pass runs before `ilReusePure` (both are `ilReuseUnit`'s): merging the addresses is what makes
// a later read name the *same* slot a mutation's argument names, so the purity rule sees the alias
// (impl_specs/expr-reuse.md, "A write kills by the same numbers").

package linear

import common

// The pure, memory-independent opcodes (impl_specs/expr-reuse.md).
fun ilReuseExprOp(kind: IlOpKind): Bool {
    return kind == IlOpKind.BinaryOp || kind == IlOpKind.UnaryOp
            || kind == IlOpKind.FieldAddr || kind == IlOpKind.IndexAddr
            || kind == IlOpKind.GetStaticAddr
}

// Whether the body has an op this pass could reuse at all: the cheap gate in front of the scans.
fun ilReuseExprHas(il: *IlBody): Bool {
    var i: Int = 0
    while (i < il.ops.size()) {
        if (ilReuseExprOp(( * il.ops[i]).kind)) {
            return true
        }
        i = i + 1
    }
    return false
}

// One expression's key: the opcode, then every operand but the destination. A slot is its number
// and the instruction that last wrote it, so a reassignment between two occurrences changes the
// key; anything else (a text, a type, a method, a literal) is a constant and part of the key as
// itself. `lastWrite` is the frame's "last writer of each slot" list, by slot.
fun ilReuseExprKey(op: *IlOp, lastWrite: *List<Int>): Str {
    // The opcode as a local first: `op.kind.toInt()` addresses a member of a pointer.
    val kind: IlOpKind = op.kind
    var key: Str = kind.toInt().toString()
    var j: Int = 1
    while (j < op.operands.size()) {
        val operand: Int = op.operands[j]
        val operandKind: IlOperandKind = ilReuseOperandKind(op, j)
        val isSlot: Bool = (operandKind == IlOperandKind.Var || operandKind == IlOperandKind.Value)
                && operand >= 0 && operand < lastWrite.size()
        if (isSlot) {
            key = key + "|#" + operand.toString() + "@" + lastWrite[operand].toString()
        } else {
            key = key + "|=" + operand.toString()
        }
        j = j + 1
    }
    return key
}

// The body with every repeated pure memory-independent expression merged into its first occurrence
// in the same basic block. Answers whether the body changed. Iterated to a fixpoint: merging
// `&self->out` is what makes `&(that)->pool` match its twin.
fun ilReuseExprs(il: *IlBody): Bool {
    if (il.vars.size() == 0 || il.ops.size() == 0 || !ilReuseExprHas(il)) {
        return false
    }
    var changed: Bool = false
    var progress: Bool = true
    while (progress) {
        progress = false
        val slots: Int = il.vars.size()
        var lastWrite: List<Int> = List<Int>(slots, -1)
        // How many instructions write each slot. A kept or merged-away slot with more than one
        // writer may carry another value elsewhere in the body (the local merge reuses a slot
        // whose live ranges do not overlap), so only a singly-written slot may be merged: that
        // is what makes redirecting every read of the merged-away slot to the kept one sound.
        var defCount: List<Int> = List<Int>(slots, 0)
        var d: Int = 0
        while (d < il.ops.size()) {
            val op0: *IlOp = *il.ops[d]
            val written0: Int = ilReuseDst(op0)
            if (written0 >= 0 && written0 < slots) {
                defCount[written0] = defCount[written0] + 1
            }
            d = d + 1
        }
        // The tables of the current basic block - dropped at a label or a branch, since two
        // instructions can share a value only when the first surely ran before the second.
        var firstDst: Dictionary<Str, Int> = Dictionary<Str, Int>()
        var firstAt: Dictionary<Str, Int> = Dictionary<Str, Int>()
        var drop: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
        var replace: Dictionary<Int, Int> = Dictionary<Int, Int>()
        var i: Int = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            val kind: IlOpKind = op.kind
            if (kind == IlOpKind.Label || kind == IlOpKind.Goto || kind == IlOpKind.IfTrue
                || kind == IlOpKind.IfFalse || kind == IlOpKind.Return || kind == IlOpKind.ReturnVoid
            ) {
                firstDst = Dictionary<Str, Int>()
                firstAt = Dictionary<Str, Int>()
            } else if (ilReuseExprOp(kind) && op.operands.size() >= 2) {
                val dst: Int = op.operands[0]
                if (dst >= 0 && dst < slots) {
                    val key: Str = ilReuseExprKey(op, lastWrite)
                    val keep: *Int = firstDst.getPtr(key)
                    if (keep == null) {
                        firstDst.insert(key, dst)
                        firstAt.insert(key, i)
                    } else if (defCount[dst] == 1 && defCount[ * keep] == 1
                    && lastWrite[ * keep] == *firstAt.getPtr(key)) {
                        // The same expression, both slots written nowhere else, and the slot the
                        // first one wrote still holds its value: this one is a read of that one.
                        drop.insert(i, true)
                        if (dst != * keep) {
                            replace.insert(dst, *keep)
                        }
                        progress = true
                    }
                }
            }
            val written: Int = ilReuseDst(op)
            if (written >= 0 && written < slots) {
                lastWrite[written] = i
            }
            i = i + 1
        }
        if (!progress) {
            break
        }
        // The merged-away op goes, and so does the declaration of a slot nothing writes any more;
        // every read of a merged slot becomes a read of the one it merged into (the same cleanup
        // `ilReusePure` does).
        var ops: List<IlOp> = List<IlOp>()
        var lines: List<Int> = List<Int>()
        i = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            if (drop.has(i)) {
                i = i + 1
                continue
            }
            if ((op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit)
                && op.operands.size() > 0 && replace.has(op.operands[0])
            ) {
                i = i + 1
                continue
            }
            var operands: List<Int> = List<Int>()
            var j: Int = 0
            while (j < op.operands.size()) {
                var value: Int = op.operands[j]
                val operandKind: IlOperandKind = ilReuseOperandKind(op, j)
                if (operandKind == IlOperandKind.Var || operandKind == IlOperandKind.Value) {
                    val target: *Int = replace.getPtr(value)
                    if (target != null) {
                        value = * target
                    }
                }
                operands.append(value)
                j = j + 1
            }
            ops.append(IlOp(op.kind, operands))
            lines.append(il.lines[i])
            i = i + 1
        }
        il.ops = ops
        il.lines = lines
        changed = true
    }
    return changed
}
