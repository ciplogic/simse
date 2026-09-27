// ReusePure.kt
//
// A *pure* call of an unchanged variable answers the same value every time, so one call is
// enough: a body that views the same string over and over -
//
//     spanOfStr(name) == "Int" || spanOfStr(name) == "Int8" || ...
//
// the shape `semIsBuiltinType` has - keeps one view and tests every label against it, instead
// of building a view per test. The reuse is at the IL (impl_specs/linear-il.md): after the
// expression lowering every operand is a slot, so "the same call" is two instructions naming
// the same callee and the same argument slot.
//
// Each restriction is a *refusal* rather than a guess:
//
//   - only the callees `ilReuseCall` names are reused (today `spanOfStr` alone: the view of a
//     string's bytes, the call the `when` lowering and the `Str`/literal comparison build,
//     `Parser.kt`). The mechanism is the same for any such accessor - `name.size()` is the next
//     one - but a result is only reused when its value is known to be a function of the
//     argument alone;
//   - the argument is a frame slot this body never writes (`defCount`) and never hands to a
//     call that could write it (`escapes`): a raw pointer or a counted reference a callee
//     receives may be written through, which would change what a *later* view sees (a view is
//     valid only while its source is unchanged, specs/built-in-types.md). A by-value parameter
//     a callee cannot write through is safe (`ilReuseArgSafe`);
//   - the first call of a key must stand in the body's first block - under the hoisted
//     declarations, before any label or branch. It is the call that stays, so it has to
//     dominate every use, and the first block is the one position that does; and
//   - the slot the first call writes must have no writer outside this key's own calls. The
//     local merge re-uses a slot whose live ranges do not overlap, so a slot carrying this
//     view may carry another value somewhere else in the body; dropping a call whose result
//     that other value clobbered would read the wrong view. When the writers are one group of
//     calls, the value the first one wrote is still in the slot at every use (`_sm_expr1` in
//     `semIsBuiltinType` is exactly that: nineteen calls, one slot); and
//   - nothing is reordered. The kept call stays where it was, and the merged-away ones become
//     reads of it, so no evaluation moves and no side effect changes.
//
// A slot merged into a *different* slot keeps neither its call nor its `Declaration` (nothing
// assigns it any more, the same cleanup `MergeConcat` does). A group that shares one slot - the
// common case - drops the calls and keeps the declaration.

package linear

import common

// The callees whose call may be reused - read the argument, answer the same value for the same
// argument, write nothing. `spanOfStr(v)` is the view of a string's bytes.
fun ilReuseCall(name: *Str): Bool {
    return name == "spanOfStr"
}

// The slot an op writes, or -1 when it writes no value.
fun ilReuseDst(op: *IlOp): Int {
    if (!ilWritesDestination(op.kind) || op.operands.size() == 0) {
        return -1
    }
    return op.operands[0]
}

// A call-like instruction: one that names a callee (or a function slot) and reads arguments.
fun ilReuseIsCallLike(kind: IlOpKind): Bool {
    return kind == IlOpKind.Call || kind == IlOpKind.CallVoid || kind == IlOpKind.CallIndirect
            || kind == IlOpKind.CallIndirectVoid || kind == IlOpKind.CallCtor
}

// The index of the callee's method entry for a call-like op, or -1 when the op names none (the
// indirect forms - an unknown callee is a refusal, never a "safe").
fun ilReuseMethodIndex(op: *IlOp): Int {
    if (op.kind == IlOpKind.Call && op.operands.size() > 1) {
        return op.operands[1]
    }
    if (op.kind == IlOpKind.CallVoid && op.operands.size() > 0) {
        return op.operands[0]
    }
    return -1
}

// The first operand position that is an argument (the receiver counts as one, as the ABI does).
fun ilReuseArgBase(kind: IlOpKind): Int {
    if (kind == IlOpKind.CallVoid || kind == IlOpKind.CallIndirectVoid) {
        return 1
    }
    return 2
}

// Whether reading a slot in an argument of this call is safe for the slot: the callee is one
// this pass reuses (a pure read), or the parameter is *by value* - a value the callee cannot
// write through. A raw pointer or a counted reference is not.
fun ilReuseArgSafe(il: *IlBody, methodIndex: Int, argIndex: Int): Bool {
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        return false
    }
    val method: IlMethod = il.methods[methodIndex]
    if (ilReuseCall(method.name)) {
        return true
    }
    if (argIndex < 0 || argIndex >= method.argTypes.size()) {
        return false
    }
    val text: Str = ilTypeName(il, method.argTypes[argIndex])
    if (text.size() == 0) {
        return false
    }
    val first: Char = text[0]
    return first != '*' && first != '&'
}

// One reusable call: the callee's name, the argument slot it reads, and the slot it writes.
// `arg < 0` means the op is not one (`dst` is -1 with it).
data class IlReuseHit(
    var callee: Str,

    var arg: Int,
    var dst: Int
)

fun ilReuseHit(il: *IlBody, op: *IlOp): IlReuseHit {
    if (op.kind != IlOpKind.Call || op.operands.size() != 3) {
        return IlReuseHit("", -1, -1)
    }
    val methodIndex: Int = op.operands[1]
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        return IlReuseHit("", -1, -1)
    }
    val method: IlMethod = il.methods[methodIndex]
    if (!ilReuseCall(method.name)) {
        return IlReuseHit("", -1, -1)
    }
    val arg: Int = op.operands[2]
    if (arg < 0 || arg >= il.vars.size()) {
        return IlReuseHit("", -1, -1)
    }
    return IlReuseHit(method.name, arg, op.operands[0])
}

// The index of the body's first control-flow op. The run before it is the first block: the
// straight-line code under the hoisted declarations, reached before anything can branch.
fun ilReuseFirstBlockEnd(il: *IlBody): Int {
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        val kind: IlOpKind = op.kind
        if (kind == IlOpKind.Label || kind == IlOpKind.Goto || kind == IlOpKind.IfTrue
            || kind == IlOpKind.IfFalse || kind == IlOpKind.Return || kind == IlOpKind.ReturnVoid
        ) {
            return i
        }
        i = i + 1
    }
    return il.ops.size()
}

// A key for one `(callee, argument)`: the two calls it identifies are the same call.
fun ilReuseKey(callee: *Str, arg: Int): Str {
    return fmtStr("|:|", callee, arg.toString())
}

// The signature tokens per opcode, built once: `ilOperandKind` re-splits the signature string
// on every call, which a pass asking about every operand of every instruction cannot afford.
var ilReuseTokenAt: Dictionary<Int, List<Str>> = Dictionary<Int, List<Str>>()

fun ilReuseOperandKind(op: *IlOp, index: Int): IlOperandKind {
    // The opcode as a local first: `op.kind.toInt()` compares/addresses a member of a pointer
    // (`ilOpKindText` reads `kind.toInt()` the same way).
    val kind: IlOpKind = op.kind
    val key: Int = kind.toInt()
    var tokens: *List<Str> = ilReuseTokenAt.getPtr(key)
    if (tokens == null) {
        val signature: Opt<IlSignature> = ilSignature(kind)
        var built: List<Str> = List<Str>()
        if (signature.hasValue()) {
            built = ilOperandTokens(signature.value())
        }
        ilReuseTokenAt.insert(key, built)
        tokens = ilReuseTokenAt.getPtr(key)
    }
    return ilOperandKindAt(tokens, index)
}

// Whether the body calls a reusable callee at all: the cheap gate in front of the pass - most
// bodies have no such call, and the scans below are not free.
fun ilReuseHasCall(il: *IlBody): Bool {
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        i = i + 1
        if (op.kind != IlOpKind.Call || op.operands.size() < 2) {
            continue
        }
        val methodIndex: Int = op.operands[1]
        if (methodIndex >= 0 && methodIndex < il.methods.size()
            && ilReuseCall(il.methods[methodIndex].name)
        ) {
            return true
        }
    }
    return false
}

// The body's instruction list, with every repeated pure call of an unchanged slot merged into
// the first one. Answers whether the body changed.
fun ilReusePure(il: *IlBody): Bool {
    val count: Int = il.vars.size()
    if (count == 0 || il.ops.size() == 0 || !ilReuseHasCall(il)) {
        return false
    }

    // One walk over the body: what each slot is - how often it is written, and whether a call
    // could write through it - and every reusable call in instruction order. The calls are
    // filtered after, since a later instruction can write an argument an earlier call read.
    val firstBlockEnd: Int = ilReuseFirstBlockEnd(il)
    var defCount: List<Int> = List<Int>(count, 0)
    var escapes: List<Bool> = List<Bool>(count, false)
    var allOps: List<Int> = List<Int>()
    var allKeys: List<Str> = List<Str>()
    var allDsts: List<Int> = List<Int>()
    var allArgs: List<Int> = List<Int>()
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        val dst: Int = ilReuseDst(op)
        if (dst >= 0 && dst < count) {
            defCount[dst] = defCount[dst] + 1
        }
        if (ilReuseIsCallLike(op.kind)) {
            val methodIndex: Int = ilReuseMethodIndex(op)
            val base: Int = ilReuseArgBase(op.kind)
            var j: Int = base
            while (j < op.operands.size()) {
                val slot: Int = op.operands[j]
                if (slot >= 0 && slot < count && !ilReuseArgSafe(il, methodIndex, j - base)) {
                    escapes[slot] = true
                }
                j = j + 1
            }
        }
        val hit: IlReuseHit = ilReuseHit(il, op)
        if (hit.arg >= 0 && hit.dst >= 0 && !xmlIsEmpty(ilVarType(il, hit.dst))) {
            allOps.append(i)
            allKeys.append(ilReuseKey(hit.callee, hit.arg))
            allDsts.append(hit.dst)
            allArgs.append(hit.arg)
        }
        i = i + 1
    }

    // The calls whose argument this body never writes and no call may write through.
    var hitOps: List<Int> = List<Int>()
    var hitKeys: List<Str> = List<Str>()
    var hitDsts: List<Int> = List<Int>()
    var h: Int = 0
    while (h < allOps.size()) {
        val arg: Int = allArgs[h]
        if (defCount[arg] == 0 && !escapes[arg]) {
            hitOps.append(allOps[h])
            hitKeys.append(allKeys[h])
            hitDsts.append(allDsts[h])
        }
        h = h + 1
    }
    // Per key: the first call (the one that stays) and how many of the key's calls write the
    // slot it writes.
    var firstOps: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var firstDsts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var sameDsts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    h = 0
    while (h < hitOps.size()) {
        val key: Str = hitKeys[h]
        val dst: Int = hitDsts[h]
        val first: *Int = firstOps.getPtr(key)
        if (first == null) {
            firstOps.insert(key, hitOps[h])
            firstDsts.insert(key, dst)
            sameDsts.insert(key, 1)
        } else if (dst == *firstDsts.getPtr(key)) {
            sameDsts.insert(key, *sameDsts.getPtr(key) + 1)
        }
        h = h + 1
    }

    // A later call of a key is merged when the first one is in the first block and the slot it
    // writes has this key's calls as its only writers - so the value the first one wrote is
    // still in the slot at every use.
    var replaced: Dictionary<Int, Int> = Dictionary<Int, Int>()
    var dropped: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
    h = 0
    while (h < hitOps.size()) {
        val key: Str = hitKeys[h]
        val first: Int = *firstOps.getPtr(key)
        val firstDst: Int = *firstDsts.getPtr(key)
        val dst: Int = hitDsts[h]
        val opIndex: Int = hitOps[h]
        if (opIndex != first && first < firstBlockEnd && !replaced.has(firstDst)
            && defCount[firstDst] == *sameDsts.getPtr(key)
        ) {
            dropped.insert(opIndex, true)
            if (dst != firstDst) {
                replaced.insert(dst, firstDst)
            }
        }
        h = h + 1
    }
    if (dropped.size() == 0) {
        return false
    }

    // The reads of a slot merged into another become reads of that one; a merged-away call and
    // the declaration of a slot nothing writes any more both go.
    var ops: List<IlOp> = List<IlOp>()
    var lines: List<Int> = List<Int>()
    i = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        if (dropped.has(i)) {
            i = i + 1
            continue
        }
        if ((op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit)
            && op.operands.size() > 0 && replaced.has(op.operands[0])
        ) {
            i = i + 1
            continue
        }
        var operands: List<Int> = List<Int>()
        var j: Int = 0
        while (j < op.operands.size()) {
            var value: Int = op.operands[j]
            val kind: IlOperandKind = ilReuseOperandKind(op, j)
            if (kind == IlOperandKind.Var || kind == IlOperandKind.Value) {
                val target: *Int = replaced.getPtr(value)
                if (target != null) {
                    value = *target
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
    return true
}

// Every body of a unit - the function and the lambdas it constructs - since the slots of one
// are not the slots of another.
fun ilReuseUnit(unit: *IlUnit): Bool {
    var changed: Bool = ilReusePure(*unit.body)
    for (*lambda in unit.lambdas) {
        if (ilReusePure(lambda)) {
            changed = true
        }
    }
    return changed
}
