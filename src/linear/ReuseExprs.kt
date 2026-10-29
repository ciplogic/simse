// ReuseExprs.kt
//
// Two instructions computing the same expression are one computation, so the second becomes a read
// of the first. The property is *invariance*: the expression is pure and memory-independent and its
// operands still hold what they held at the first occurrence. Covered opcodes: BinaryOp, UnaryOp,
// FieldAddr, IndexAddr, GetStaticAddr (impl_specs/expr-reuse.md, "Purity, effects and value
// numbering"). Memory-reading ops (`x.f`, `x[i]`, a call) are excluded - a write between two
// occurrences may change them and the kill analysis is not built yet - and so are ops yielding a
// fresh identity (`Pack`, `toArray`, the fused `Concat`), since sharing one block would make a write
// through one show through the other.
//
// **One linear region at a time, not one basic block**: a region is a run of instructions every
// path into which ran the instructions above it, which is more than a basic block. A conditional
// branch keeps the tables (it reads a condition and modifies nothing); a label keeps every entry
// recorded at or before the *earliest* op that jumps to it, because every path to the label ran
// those (`ilReuseLabelFacts`, the region rule); and a back edge - a loop - empties the tables,
// because the region runs again over slots the body has written since. That is what merges the
// attribute-scan loop's three `&e->attributes` per iteration into the one at its top, and a
// dominated chain's re-derivations (`&op->operands` in test after test) into the first one's. The
// boundary is real: a `when` arm's body is entered after an earlier arm's test failed, and the
// join that enters it skips the earlier arm - the filter drops that arm's entries there, so an
// undominated arm keeps its own computation rather than read a slot the surviving path never
// wrote.
//
// Operands are compared by *value*: a slot contributes its number and last writer, so a reassignment
// changes the key. Both slots must be written exactly once in the body, since the local merge reuses
// a slot whose live ranges do not overlap.
//
// **An element address dies at a call**: `&xs[i]` names the buffer's storage, and a callee may
// move it (an append reallocs), so an element address never merges with an equal twin computed
// before the walk's most recent call - the address classes before and after the call are separate.
// A callee the purity table proves writes nothing (`ilReusePureCall`) moves no buffer, so it does
// not split the classes - the same trust `ReusePure.kt` rests its folds on. Field addresses are
// untouched by the call rule entirely: a call cannot move a struct, and a field of a
// killed element address cannot match its pre-call twin (the base slot's key already moved), so
// the `self->out` collapse below survives. Scalar arithmetic and static addresses cannot move at
// all.
//
// Runs before `ilReusePure` (both are `ilReuseUnit`'s): merging the addresses makes a later read name
// the same slot a mutation's argument names, so the purity rule sees the alias.

package linear

import common

// The pure, memory-independent opcodes (impl_specs/expr-reuse.md).
fun ilReuseExprOp(kind: IlOpKind): Bool {
    return kind == IlOpKind.BinaryOp || kind == IlOpKind.UnaryOp
            || kind == IlOpKind.FieldAddr || kind == IlOpKind.IndexAddr
            || kind == IlOpKind.GetStaticAddr
}

// The call opcodes: a callee may mutate a container, and a mutating call moves its buffer
// (an append reallocs), so an address computed before one does not name the same storage
// after it - `append` borrows the list, it is not pure.
fun ilReuseCallOp(kind: IlOpKind): Bool {
    return kind == IlOpKind.Call || kind == IlOpKind.CallVoid
            || kind == IlOpKind.CallIndirect || kind == IlOpKind.CallIndirectVoid
            || kind == IlOpKind.CallCtor
}

// Whether a call op's callee is one the purity table proves writes nothing: such a call
// cannot move a container's buffer, so an element address computed before it still names
// its storage after it. The indirect forms and constructions name no callee to trust - a
// refusal, never a "safe". The trust is the same one `ReusePure.kt` rests on: a `data`
// mark is the author's word the body writes nothing.
fun ilReusePureCall(il: *IlBody, pure: *Dictionary<Str, Bool>, op: *IlOp): Bool {
    var methodIndex: Int = -1
    if (op.kind == IlOpKind.Call && op.operands.size() > 1) {
        methodIndex = op.operands[1]
    } else if (op.kind == IlOpKind.CallVoid && op.operands.size() > 0) {
        methodIndex = op.operands[0]
    }
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        return false
    }
    return pure.has(il.methods[methodIndex].name)
}

// The container-element addresses: `&xs[i]` names the buffer's storage, and a callee may
// move that buffer (an append reallocs), so it never merges across one. A field address
// keeps its validity across calls - its base is the slot chain the key already tracks, so
// a field on a killed element address cannot match its pre-call twin anyway.
fun ilReuseAddrOp(kind: IlOpKind): Bool {
    return kind == IlOpKind.IndexAddr
}

// Whether the body has an op this pass could reuse at all: the cheap gate in front of the scans.
fun ilReuseExprHas(il: *IlBody): Bool {
    for (op in il.ops) {
        if (ilReuseExprOp(( * op).kind)) {
            return true
        }
    }
    return false
}

// The label facts the region rule reads: the earliest op that jumps to each label, and whether any
// jump to it is a back edge. A conditional branch's target is a jump like a `goto`'s - the
// fall-through path is the only other way in, and the label rule below weighs it through the
// entries' positions. A label no op jumps to is not a fact at all.
fun ilReuseLabelFacts(
    il: *IlBody, first: *Dictionary<Int, Int>, loops: *Dictionary<Int, Bool>
): Unit {
    var labelPos: Dictionary<Int, Int> = Dictionary<Int, Int>()
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        if (op.kind == IlOpKind.Label && op.operands.size() > 0) {
            labelPos.insert(op.operands[0], i)
        }
        i++
    }
    i = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        val kind: IlOpKind = op.kind
        var labelOperand: Int = -1
        if (kind == IlOpKind.Goto) {
            labelOperand = 0
        } else if (kind == IlOpKind.IfTrue || kind == IlOpKind.IfFalse) {
            labelOperand = 1
        }
        if (labelOperand >= 0 && op.operands.size() > labelOperand) {
            val target: Int = op.operands[labelOperand]
            val at: *Int = labelPos.getPtr(target)
            if (at != null) {
                if (*at < i) {
                    // The label sits above its jump: a loop's back edge.
                    loops.insert(target, true)
                }
                val seen: *Int = first.getPtr(target)
                if (seen == null || *seen > i) {
                    first.insert(target, i)
                }
            }
        }
        i++
    }
}

// The entries a join takes past it: one recorded after the earliest op that jumps to the label did
// not run on the path that jump took, so it cannot stand below the label. The entries at or before
// it ran on every path - the fall-through's and every jump's.
fun ilReuseRegionDrop(firstDst: *Dictionary<Str, Int>, firstAt: *Dictionary<Str, Int>, after: Int): Unit {
    var stale: List<Str> = List<Str>()
    for (key in firstAt.keys()) {
        if (*firstAt.getPtr(key) > after) {
            stale.append(key)
        }
    }
    for (key in stale) {
        firstAt.remove(key)
        firstDst.remove(key)
    }
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
        j++
    }
    return key
}

// The body with every repeated pure memory-independent expression merged into its first occurrence
// in the same linear region. Answers whether the body changed. Iterated to a fixpoint: merging
// `&self->out` is what makes `&(that)->pool` match its twin.
fun ilReuseExprs(il: *IlBody, pure: *Dictionary<Str, Bool>): Bool {
    if (il.vars.size() == 0 || il.ops.size() == 0 || !ilReuseExprHas(il)) {
        return false
    }
    var changed: Bool = false
    var progress: Bool = true
    while (progress) {
        progress = false
        val slots: Int = il.vars.size()
        var lastWrite: List<Int> = List<Int>(slots, -1)
        // How many instructions write each slot: only a singly-written slot may be merged (a
        // writer elsewhere could carry another value, since the local merge reuses overlapping-free
        // slots).
        var defCount: List<Int> = List<Int>(slots, 0)
        for (*op0 in il.ops) {
            val written0: Int = ilReuseDst(op0)
            if (written0 >= 0 && written0 < slots) {
                defCount[written0]++
            }
        }
        // The tables of the current linear region, and the label facts the region rule reads
        // (`ilReuseLabelFacts`). A branch keeps them; a label filters them; a back edge drops
        // them (the header's reason).
        var labelFirst: Dictionary<Int, Int> = Dictionary<Int, Int>()
        var labelLoops: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
        ilReuseLabelFacts(il, *labelFirst, *labelLoops)
        var firstDst: Dictionary<Str, Int> = Dictionary<Str, Int>()
        var firstAt: Dictionary<Str, Int> = Dictionary<Str, Int>()
        var drop: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
        var replace: Dictionary<Int, Int> = Dictionary<Int, Int>()
        var i: Int = 0
        // Where the walk's most recent call sits (-1 before one): an address older than it
        // names storage the callee may have moved, so it is not a reusable twin.
        var lastCallAt: Int = -1
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            val kind: IlOpKind = op.kind
            if (kind == IlOpKind.Label) {
                // The region rule: a back edge empties the tables (the region runs again over
                // slots the body has written), and any other label keeps exactly the entries
                // recorded at or before its earliest jump.
                if (op.operands.size() > 0 && labelLoops.has(op.operands[0])) {
                    firstDst = Dictionary<Str, Int>()
                    firstAt = Dictionary<Str, Int>()
                } else if (op.operands.size() > 0) {
                    val earliest: *Int = labelFirst.getPtr(op.operands[0])
                    if (earliest != null) {
                        ilReuseRegionDrop(*firstDst, *firstAt, *earliest)
                    }
                }
            } else if (ilReuseCallOp(kind)) {
                // A pure callee writes nothing, so it moves no buffer: an element address
                // computed before it still names its storage after it.
                if (!ilReusePureCall(il, *pure, op)) {
                    lastCallAt = i
                }
            } else if (ilReuseExprOp(kind) && op.operands.size() >= 2) {
                val dst: Int = op.operands[0]
                if (dst >= 0 && dst < slots) {
                    val key: Str = ilReuseExprKey(op, lastWrite)
                    val keep: *Int = firstDst.getPtr(key)
                    if (keep == null) {
                        firstDst.insert(key, dst)
                        firstAt.insert(key, i)
                    } else if (ilReuseAddrOp(kind) && *firstAt.getPtr(key) < lastCallAt) {
                        // A call sat between the kept address and this one: the callee may
                        // have moved the container's buffer (an append reallocs), so the
                        // kept address names storage that may be gone. The classes before
                        // and after the call stay separate - this occurrence becomes the
                        // kept one.
                        firstDst.insert(key, dst)
                        firstAt.insert(key, i)
                    } else if (defCount[dst] == 1 && defCount[ * keep] == 1
                    && lastWrite[ * keep] == *firstAt.getPtr(key)) {
                        // Both slots written nowhere else, and the first one's slot still holds
                        // its value: this one is a read of that one.
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
            i++
        }
        if (!progress) {
            break
        }
        // Drop the merged-away op and the declaration of a slot nothing writes any more; redirect
        // every read of a merged slot to the one it merged into.
        var ops: List<IlOp> = List<IlOp>()
        var lines: List<Int> = List<Int>()
        i = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            if (drop.has(i)) {
                i++
                continue
            }
            if ((op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit)
                && op.operands.size() > 0 && replace.has(op.operands[0])
            ) {
                i++
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
                j++
            }
            ops.append(IlOp(op.kind, operands))
            lines.append(il.lines[i])
            i++
        }
        il.ops = ops
        il.lines = lines
        changed = true
    }
    return changed
}
