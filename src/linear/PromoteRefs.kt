// PromoteRefs.kt
//
// A counted reference (`&T`) is a heap box plus a refcount (`specs/ref-counted-layout.md`). When a
// local handle never escapes and is bound once, the box can be built on the *stack* and the handle
// made a raw pointer to it: the refcount traffic disappears and the value is destroyed with the
// scope - what `impl_specs/escape-analysis.md` calls refcount promotion.
//
// The rewrite is a handful of edits on the IL and no new opcode, because the emitter spells every use
// of the handle the same either way: `c->value` auto-derefs both a counted reference and a raw
// pointer, `c.add(4)` is the `T* self` a value receiver takes, `*c` is the pointer itself, and the
// construction is a *value* one because `ilBoxedCtorText` boxes only a `&T` destination.
//
// Two definitions of the handle are promoted, differing in where the value lives:
//
//   - `CallCtor c, T, args` - the box built in place: a value slot is appended for the payload, the
//     construction is retargeted to it, and `Deref c, slot` writes the handle;
//   - `Box c, src` - `&src` boxing a copy. When `src` is a value slot used *nowhere else* - built by
//     its own `CallCtor`, or by the single `initByValue` call a `union class` construction lowers
//     to - the copy is unnecessary: `c = &src` points at the very storage `src` already has.
//
// In both cases `c`'s type becomes `*T` (a `*T` entry appended with `ilPointerNode`), in
// `vars[c].typeIndex` *and* in `inferredTypes[c.name]` - the emitter seeds `localTypes`, and
// `receiverArg`/`ExprIndex` read a receiver's kind, from the latter.
//
// What it refuses, it refuses by *not promoting*, never by guessing: an argument, a store, a capture,
// a `return`, a second definition, a receiver declared as a counted reference, a `Box` whose source
// is read elsewhere, and - for the in-place `CallCtor` only - a payload whose type declares `unInit`
// (a fresh stack value would run its destructor twice) all stay a box. The call positions the *escape
// analysis* (`parser/EscapeParams.kt`, `--showEscape`) proves retain the value are refusals too: a
// receiver whose method hands `this` out, and the `*T` address a retaining parameter receives
// (`argEscape`). `print`/`println` are mapped as borrows there, so `print(x)` on a `&Str` both
// promotes and reads through. An unmodeled use therefore costs an allocation, never correctness. The
// exact set, and the decisions still open (auto-borrow semantics, the "complex" threshold), are in
// `impl_specs/escape-analysis.md`.

package linear
import compiler

import common
import sema
import parser

// Whether operand `j` of `op` is a base the emitter spells the same way for a `&T` and a `*T`:
// `x.f`, `x.f = v`, `&x.f`, `x[i]`, `x[i] = v`, `&x[i]` - the base operand of each.
fun ilPromoteIsBase(op: *IlOp, j: Int): Bool {
    val kind: IlOpKind = op.kind
    if (kind == IlOpKind.GetField || kind == IlOpKind.GetIndex
        || kind == IlOpKind.FieldAddr || kind == IlOpKind.IndexAddr
    ) {
        return j == 1
    }
    if (kind == IlOpKind.SetField || kind == IlOpKind.SetIndex) {
        return j == 0
    }
    return false
}

// Whether operand `j` of `op` is a position a counted reference and a promoted raw pointer spell
// the same way: a base, a `*c` an address was taken of, a copy of the pointee, or a call position
// that only reads - the `print`/`println` builtins' argument (mapped as borrows by
// `parser/EscapeParams.kt`), or a method's receiver when the analysis has not proved the callee
// retains it.
fun ilPromoteUseSafe(il: *IlBody, op: *IlOp, j: Int, argEscape: *List<Bool>): Bool {
    if (ilPromoteIsBase(op, j)) {
        return true
    }
    val kind: IlOpKind = op.kind
    // `*c` on a counted reference is `.get()` - exactly the pointer a promoted handle already is.
    // The op is rewritten to a plain assignment `d = c` (`ilPromoteApply`), so it is safe when `d`
    // is the raw pointer it must be - and when the address never reaches a parameter the escape
    // analysis proved may retain it (`argEscape`, the call below).
    if (kind == IlOpKind.Deref) {
        if (j != 1 || op.operands.size() < 2) {
            return false
        }
        val dstType: AstXmlNode = ilVarType(il, op.operands[0])
        if (xmlIsEmpty(dstType) || xmlKind(dstType) != AstNodeCategory.TypePointer) {
            return false
        }
        val dst: Int = op.operands[0]
        return !(dst >= 0 && dst < argEscape.size() && ( * argEscape)[dst])
    }
    // A copy of the pointee out of the handle (`readThrough`): a by-value parameter, a binary
    // operand, a `copy(...)`. The handle itself is only read - nothing stores it.
    if (kind == IlOpKind.CopyValue) {
        return j == 1
    }
    // `d = c` where `d` is a *value*: the destination's type drives the read-through
    // (`cgNeedsReadThrough`), so the pointee is copied and the handle is not stored. A `d`
    // that is itself a handle is the handle copy that is the escape, and stays unsafe.
    if (kind == IlOpKind.SetVar) {
        if (j != 1 || op.operands.size() < 2) {
            return false
        }
        val dstType: AstXmlNode = ilVarType(il, op.operands[0])
        return !xmlIsEmpty(dstType) && !ilIsHandleType(dstType)
    }
    if (kind == IlOpKind.Call || kind == IlOpKind.CallVoid) {
        val base: Int = ilReuseArgBase(kind)
        if (j < base) {
            return false
        }
        val at: Int = ilReuseMethodIndex(op)
        if (at < 0 || at >= il.methods.size()) {
            return false
        }
        val method: IlMethod = il.methods[at]
        // `print`/`println` are the emitter's own builtins (`CgCall.kt`): the value is written
        // where it stands (`simse_print` takes it by `const T&`), and the analysis maps them as
        // borrows (`epBuiltinBorrow`) - exactly the allowance a marked native gets.
        if (method.kind == IlMethodKind.Function && epBuiltinBorrow(method.name)) {
            return true
        }
        if (j != base) {
            return false
        }
        // The receiver position: `T* self` (what a value receiver takes) is what a promoted
        // handle is, unless the analysis proved the callee hands the receiver out. A
        // counted-reference receiver's parameter *is* the handle, so a raw pointer would not
        // convert - `IlMethod.recvIsValue` tells the two apart (`LinearForm.kt`).
        if (method.kind == IlMethodKind.Method) {
            if (epKindAt(method.name, 0) == EpKind.Escapes) {
                return false
            }
            return method.recvIsValue
        }
    }
    return false
}

// Whether operand `j` of `op` is the handle side of a null test: a `BinaryOp` whose *other*
// operand is the placeholder `SetVar_Null` wrote (the `?`-typed temporary the lowering makes
// of `null`). A promoted raw pointer tests against `nullptr` exactly as the handle does.
fun ilPromoteNullTest(op: *IlOp, j: Int, nulls: *List<Bool>): Bool {
    if (op.kind != IlOpKind.BinaryOp || j < 2 || op.operands.size() < 4) {
        return false
    }
    var other: Int = op.operands[2]
    if (j == 2) {
        other = op.operands[3]
    }
    return other >= 0 && other < nulls.size() && ( * nulls)[other]
}

// The slot a construction call *writes*, or -1: the receiver of an `initByValue` call, the
// `union class` construction convention the lowering routes a `&U(v)` through. The receiver is
// storage the call sets, so the `Box` rule below counts it as a definition of the slot - the
// opcode's own destination (`ilWritesDestination`) is not involved.
fun ilPromoteInitReceiver(op: *IlOp, il: *IlBody): Int {
    if (op.kind != IlOpKind.Call && op.kind != IlOpKind.CallVoid) {
        return -1
    }
    val at: Int = ilReuseMethodIndex(op)
    if (at < 0 || at >= il.methods.size()) {
        return -1
    }
    if (il.methods[at].name != "initByValue") {
        return -1
    }
    val method: IlMethod = il.methods[at]
    if (method.kind != IlMethodKind.Method || !method.recvIsValue) {
        return -1
    }
    val base: Int = ilReuseArgBase(op.kind)
    if (op.operands.size() <= base) {
        return -1
    }
    return op.operands[base]
}

// A slot name nothing in the body already uses.
fun ilPromoteStackName(il: *IlBody, c: Int): Str {
    val base: Str = "_sm_stk" + c.toString()
    var name: Str = base
    var n: Int = 0
    while (ilPromoteNameTaken(il, name)) {
        n = n + 1
        name = base + "_" + n.toString()
    }
    return name
}

fun ilPromoteNameTaken(il: *IlBody, name: Str): Bool {
    var i: Int = 0
    while (i < il.vars.size()) {
        if (il.vars[i].name == name) {
            return true
        }
        i = i + 1
    }
    return false
}

// A payload a stack value cannot carry: one whose type declares `unInit`. Such a type has a real
// destructor (`src/codegen/Codegen.kt`, `emitDataClass`), and a stack value is built from a
// temporary - the temporary's destructor runs, then the stack value's runs again at scope end,
// where the box ran it once. A payload that is not a plain named type the facts know is refused
// too: unknown is not "safe". A generic data class (`Gen<Int>`) is carried by its declaration,
// and a `union class` is refused - its only construction is the generated `initByValue` arms,
// so a value `CallCtor` could only spell an aggregate that puts the argument in the tag.
fun ilPromotePayloadRefused(inner: *AstXmlNode, facts: *SemFacts): Bool {
    if (xmlIsEmpty(inner)) {
        return true
    }
    val kind: AstNodeCategory = xmlKind(inner)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return true
    }
    val name: Str = xmlAttr(inner, AstNodeAttributeKind.Name)
    val decl: *AstXmlNode = facts.types.getPtr(name)
    if (decl == null) {
        return true
    }
    if (xmlAttr(*decl, AstNodeAttributeKind.IsUnionClass) == "true") {
        return true
    }
    for (*member in xmlChildren(decl, AstNodeKind.Function)) {
        if (xmlAttr(member, AstNodeAttributeKind.Name) == "unInit") {
            return true
        }
    }
    return false
}

// Whether a slot's own type is a value a `Box` may point at: a live value, never a handle.
fun ilPromoteIsValueSlot(il: *IlBody, slot: Int): Bool {
    val node: AstXmlNode = ilVarType(il, slot)
    if (xmlIsEmpty(node)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(node)
    return kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer
}

// The one slot this round would promote, or -1. A candidate is a `&T` slot written exactly once -
// by a `CallCtor` in the body's first block, or by a `Box` of a value slot nothing else reads - with
// no use outside the safe set.
fun ilPromoteFind(
    il: *IlBody, bad: *List<Bool>, defCount: *List<Int>, defOp: *List<Int>,
    useCount: *List<Int>, initCount: *List<Int>, initOp: *List<Int>, facts: *SemFacts
): Int {
    val slots: Int = il.vars.size()
    val firstBlockEnd: Int = ilReuseFirstBlockEnd(il)
    var c: Int = 0
    while (c < slots) {
        if (!( * bad) [c] && ( * defCount)[c] == 1) {
            val refNode: AstXmlNode = ilVarType(il, c)
            if (!xmlIsEmpty(refNode) && xmlKind(refNode) == AstNodeCategory.TypeReference) {
                val at: Int = ( * defOp)[c]
                val def: *IlOp = *il.ops[at]
                if (def.kind == IlOpKind.CallCtor && def.operands.size() >= 2
                    && def.operands[0] == c && at < firstBlockEnd
                ) {
                    // The box is built in place, so the stack value is a fresh temporary: a payload
                    // with a destructor would run it twice - refuse it.
                    if (!ilPromotePayloadRefused(xmlChildPtr(refNode, AstNodeKind.Inner), facts)) {
                        return c
                    }
                }
                // `&src` boxing a copy: pointing at `src` itself drops the copy, so long as that
                // changes nothing observable - `src` is built before the box (once, by its own
                // definition or by a single construction call), and the box is the only use of
                // it that is not the construction itself. A read anywhere is the same value
                // through the box; a write or a second construction after the box would not be.
                if (def.kind == IlOpKind.Box && def.operands.size() >= 2 && def.operands[0] == c) {
                    val src: Int = def.operands[1]
                    if (src >= 0 && src < slots && src != c && ilPromoteIsValueSlot(il, src)) {
                        val inits: Int = (*initCount)[src]
                        val otherUses: Int = (*useCount)[src] - inits
                        var built: Bool = (*defCount)[src] == 1 && (*defOp)[src] < at && inits == 0
                        if (!built && (*defCount)[src] == 0 && inits == 1
                            && (*initOp)[src] < at
                        ) {
                            built = true
                        }
                        if (otherUses == 1 && built) {
                            return c
                        }
                    }
                }
            }
        }
        c = c + 1
    }
    return -1
}

// The body rebuilt with slot `c` promoted; `at` is its single definition.
fun ilPromoteApply(il: *IlBody, c: Int, at: Int): Unit {
    val def: *IlOp = *il.ops[at]
    val box: Bool = def.kind == IlOpKind.Box
    // The payload type: the `&T`'s *inner* node, so a value declaration and a value construction
    // both spell the plain `Cell`. The box's own `Type` operand is the generic spelling `Cell<>`,
    // which only a `makeRef` can name (a value declaration spells it `ns1_Cell<>`, not a type).
    val refNode: AstXmlNode = ilVarType(il, c)
    val inner: *AstXmlNode = xmlChildPtr(refNode, AstNodeKind.Inner)
    var valueNode: AstXmlNode = inner
    valueNode.name = AstNodeKind.Type
    val valueType: Int = il.types.size()
    il.types.append(ilTypeText(valueNode))
    il.typeNodes.append(valueNode)
    val ptrNode: AstXmlNode = ilPointerNode(valueNode)
    val ptrType: Int = il.types.size()
    il.types.append(ilTypeText(ptrNode))
    il.typeNodes.append(ptrNode)
    il.vars[c].typeIndex = ptrType
    il.inferredTypes.insert(il.vars[c].name, ptrNode)

    // The `Box` case points at `src`'s own storage; the `CallCtor` case builds one.
    var stack: Int = -1
    if (box) {
        stack = def.operands[1]
    } else {
        stack = il.vars.size()
        il.vars.append(IlVar(ilPromoteStackName(il, c), valueType, IlVarKind.Local))
    }

    var ops: List<IlOp> = List<IlOp>()
    var lines: List<Int> = List<Int>()
    var declared: Bool = false
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        // The stack value is declared with the body's other declarations (`c`'s own), before any
        // branch - the construction is in the first block, so nothing jumps across it.
        if (!declared && op.kind == IlOpKind.Declare && op.operands.size() > 0 && op.operands[0] == c) {
            if (!box) {
                ops.append(IlOp(IlOpKind.Declare, ilOps1(stack)))
                lines.append(il.lines[i])
            }
            declared = true
        }
        if (i == at) {
            if (box) {
                // `c = &src`, the storage `src` already has.
                ops.append(IlOp(IlOpKind.Deref, ilOps2(c, stack)))
                lines.append(il.lines[i])
            } else {
                // The construction now builds the stack value, and the handle points at it.
                var built: List<Int> = List<Int>()
                built.append(stack)
                built.append(valueType)
                var j: Int = 2
                while (j < op.operands.size()) {
                    built.append(op.operands[j])
                    j = j + 1
                }
                ops.append(IlOp(IlOpKind.CallCtor, built))
                lines.append(il.lines[i])
                ops.append(IlOp(IlOpKind.Deref, ilOps2(c, stack)))
                lines.append(il.lines[i])
            }
            i = i + 1
            continue
        }
        // `d = *c` was `.get()`; `c` is the pointer itself now, so it is `d = c`.
        if (op.kind == IlOpKind.Deref && op.operands.size() >= 2 && op.operands[1] == c) {
            ops.append(IlOp(IlOpKind.SetVar, ilOps2(op.operands[0], c)))
            lines.append(il.lines[i])
            i = i + 1
            continue
        }
        ops.append(IlOp(op.kind, op.operands))
        lines.append(il.lines[i])
        i = i + 1
    }
    if (!declared && !box) {
        ops.insert(0, IlOp(IlOpKind.Declare, ilOps1(stack)))
        lines.insert(0, 0)
    }
    il.ops = ops
    il.lines = lines
}

// The body with every promotable counted-reference local promoted to a stack value and a raw
// pointer. Answers whether the body changed; repeated to a fixpoint, since promoting one slot
// rewrites the instruction list (the indices are rebuilt each round).
fun ilPromoteRefs(il: *IlBody, facts: *SemFacts): Bool {
    val slots: Int = il.vars.size()
    if (slots == 0 || il.ops.size() == 0) {
        return false
    }
    var changed: Bool = false
    var more: Bool = true
    while (more) {
        more = false
        var defCount: List<Int> = List<Int>(slots, 0)
        var defOp: List<Int> = List<Int>(slots, -1)
        var useCount: List<Int> = List<Int>(slots, 0)
        var initCount: List<Int> = List<Int>(slots, 0)
        var initOp: List<Int> = List<Int>(slots, -1)
        var nullSlots: List<Bool> = List<Bool>(slots, false)
        var bad: List<Bool> = List<Bool>(slots, false)
        // The `*T` addresses this body hands to a call: when the escape analysis proved the
        // parameter may retain what it receives (`epKindAt`), the pointer's own slot is marked,
        // so the `Deref` that took its address is not a safe use (`ilPromoteUseSafe`).
        var argEscape: List<Bool> = List<Bool>(slots, false)
        var ai: Int = 0
        while (ai < il.ops.size()) {
            val aop: *IlOp = *il.ops[ai]
            if (aop.kind == IlOpKind.Call || aop.kind == IlOpKind.CallVoid) {
                val at: Int = ilReuseMethodIndex(aop)
                if (at >= 0 && at < il.methods.size()) {
                    val base: Int = ilReuseArgBase(aop.kind)
                    var j: Int = base
                    while (j < aop.operands.size()) {
                        val slot: Int = aop.operands[j]
                        if (slot >= 0 && slot < slots
                            && epKindAt(il.methods[at].name, j - base) == EpKind.Escapes
                        ) {
                            argEscape[slot] = true
                        }
                        j = j + 1
                    }
                }
            }
            ai = ai + 1
        }
        var i: Int = 0
        while (i < il.ops.size()) {
            val op: *IlOp = *il.ops[i]
            // A declaration names the slot's *storage*, not a read or a write of it - `ilReuseDst`
            // answers -1 for it - so it must not mark the slot observed.
            if (op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit) {
                i = i + 1
                continue
            }
            val built: Int = ilPromoteInitReceiver(op, il)
            if (built >= 0 && built < slots) {
                initCount[built] = initCount[built] + 1
                initOp[built] = i
            }
            val writes: Bool = ilWritesDestination(op.kind)
            for ((operand, j) in op.operands) {
                val operandKind: IlOperandKind = ilReuseOperandKind(op, j)
                if ((operandKind == IlOperandKind.Var || operandKind == IlOperandKind.Value)
                    && operand >= 0 && operand < slots
                ) {
                    if (j == 0 && writes) {
                        defCount[operand] = defCount[operand] + 1
                        defOp[operand] = i
                        // The `?`-typed placeholder a null test reads: set by `SetVar_Null`, and
                        // unset by any other writer (the local merge reuses slots).
                        nullSlots[operand] = op.kind == IlOpKind.SetVar_Null
                    } else {
                        useCount[operand] = useCount[operand] + 1
                        if (!ilPromoteUseSafe(il, op, j, *argEscape)
                            && !ilPromoteNullTest(op, j, *nullSlots)
                        ) {
                            // A read that is not a safe spelling: the handle is observed (copied,
                            // stored, captured, `*`-ed, or handed to a `&T`) - never promote it.
                            bad[operand] = true
                        }
                    }
                }
            }
            i = i + 1
        }
        val c: Int = ilPromoteFind(
            il, *bad, *defCount, *defOp, *useCount, *initCount, *initOp, facts
        )
        if (c >= 0) {
            ilPromoteApply(il, c, defOp[c])
            changed = true
            more = true
        }
    }
    return changed
}

// Every body of a unit - the function and the lambdas it constructs - since a slot of one is not a
// slot of another.
fun ilPromoteRefsUnit(unit: *IlUnit, facts: *SemFacts): Bool {
    var changed: Bool = ilPromoteRefs(*unit.body, facts)
    for (*lambda in unit.lambdas) {
        if (ilPromoteRefs(lambda, facts)) {
            changed = true
        }
    }
    return changed
}
