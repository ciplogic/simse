// MergeConcat.kt
//
// The IL's n-ary concatenation (impl_specs/linear-il.md, "Concat"): a `+` chain over `Str`
// and an `fmtStr` whose format is a literal become *one* `Concat` instruction over every
// part, which the emitter expands into one length sum, one `resize` and one slot write
// per part, through a pointer that advances (cppsrc/rtl/_res.md's `strcat` section). The
// language's `+` is binary, so the lowerer
// leaves `a + b + c` as two instructions with a `Str` temporary between them, and an
// `fmtStr` call re-scans its format at run time; merging both back is what lets the emitter
// sum the lengths once, allocate once and write each part once - the shape Java 9's
// `StringConcatFactory` has.
//
// Three shapes are rewritten, and each *refuses* rather than guesses:
//
//   t = a + b                       (a `Str` temp, read exactly once)
//   s = t + c            ->   s = Concat(a, b, c)
//
//   t = n.toString()                (a number's text, read exactly once)
//   s = t + c            ->   s = Concat(n, c)
//
//   Pack   base, item...            (the `*List<Str>` an `fmtStr` argument packs into)
//   Deref  list, base
//   Call   s, fmtStr, "<fmt>", list
//                        ->   s = Concat(piece0, item0, ..., pieceN)
//
// A *part* is what the emitter can append: an owned `Str`, a `Char`, a `StrView` that came
// from the program's *pool* (a literal), or - only from a fold - a number or a bool (the
// fold's *receiver* may be a handle, a field's address, which the emitter dereferences:
// `ilConcatPartText`, cppsrc/codegen/IlCodeGen.kt). A `StrView` *slot* refuses the chain,
// because a view may look into the very `Str` the concat writes into, and so does a `+`
// operand that is a number: `s + n` in C++ appends
// `(char) n`, one byte, and that is what must stay - only a fold may turn a number into
// its digits. Two folds are refused for the same kind of reason: a `Char` (its C++ type is
// `Int8`, so "one character" and "a number" would be one part kind) and a float (its length
// is only known by formatting it, so the `toString` call stays and hands over one `Str`,
// made ahead of time, which is then a text part like any other).
//
// The *destination* is what the emitter writes into where the instruction stands, so it has
// to be a slot with a declaration of its own (a type node - a slot without one is declared
// by the instruction that assigns it, and this instruction is not one assignment any more),
// and the chain may read it at most once, as its *first* part: that is `s = s + x`, where
// the bytes already there are exactly what the rest is appended to, so the emitter need not
// clear and copy them.

package linear

import common

// The C++ symbol every fused body reaches, and the name the emitter records as reached: the
// `strcat` section's primitives are reached by it alone (cppsrc/rtl/rtl.kt), because the
// emitter writes those symbols itself and no program names one.
fun ilConcatSymbol(): Str {
    return "simse_strAddInt"
}

// The slot an instruction writes, or -1 when it writes memory or jumps instead.
fun ilConcatDst(op: *IlOp): Int {
    if (!ilWritesDestination(op.kind) || op.operands.size() == 0) {
        return -1
    }
    return op.operands[0]
}

// How many operand positions read each slot: the same rule `ilAnalyze` counts with, so a
// slot this pass consumes is one the codegen would fold or declare.
fun ilConcatUseCounts(il: *IlBody): List<Int> {
    var counts: List<Int> = List<Int>()
    var i: Int = 0
    while (i < il.vars.size()) {
        counts.append(0)
        i = i + 1
    }
    i = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        if (op.kind != IlOpKind.Declare && op.kind != IlOpKind.DeclareInit) {
            val dst: Int = ilConcatDst(op)
            var j: Int = 0
            while (j < op.operands.size()) {
                var read: Bool = true
                if (j == 0 && dst >= 0) {
                    read = false
                }
                val kind: IlOperandKind = ilOperandKind(op, j)
                if (kind != IlOperandKind.Var && kind != IlOperandKind.Value) {
                    read = false
                }
                if (read) {
                    val operand: Int = op.operands[j]
                    if (operand >= 0 && operand < counts.size()) {
                        counts[operand] = counts[operand] + 1
                    }
                }
                j = j + 1
            }
        }
        i = i + 1
    }
    return counts
}

// Whether a format text is one the fusion may split: a string literal, and with no `\` in
// it - an escape could hide a `|` or a quote, and re-encoding the pieces would need a
// second decoder for the one `cgLiteralByteLength` already is.
fun ilConcatSplittable(text: *Str): Bool {
    if (text.size() < 2 || text[0] != '\"' || text[text.size() - 1] != '\"') {
        return false
    }
    var i: Int = 1
    while (i < text.size() - 1) {
        if (text[i] == '\\') {
            return false
        }
        i = i + 1
    }
    return true
}

// Whether a type node is a handle (`*T`/`&T`) rather than a value: what a `toString` reads
// its receiver through when the receiver is a field.
fun ilConcatIsHandle(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    return kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference
}

// The type of the *value* a slot holds, its handle peeled away: what an append writes, and
// what decides which `simse_str*` pair spells it (the emitter's `ilConcatStatements`).
fun ilConcatValueText(il: *IlBody, operand: Int): Str {
    if (operand < 0 || operand >= il.vars.size()) {
        return ""
    }
    val typeNode: AstXmlNode = ilVarType(il, operand)
    if (xmlIsEmpty(typeNode)) {
        return ""
    }
    if (!ilConcatIsHandle(*typeNode)) {
        return ilTypeText(*typeNode)
    }
    val inner: AstXmlNode = xmlChild(typeNode, AstNodeKind.Inner)
    if (xmlIsEmpty(inner)) {
        return ""
    }
    return ilTypeText(*inner)
}

// The kinds a number or a bool part can be: the ones the `strcat` section has a *count* and
// a *write* for. A number part is a folded `toString` receiver or a `fmtStr` item; a bool is
// always such a receiver (its `toString` is folded too, and written as a `StrView`).
fun ilConcatNumberOk(text: Str): Bool {
    return text == "Int8" || text == "Int16" || text == "Int32" || text == "Int64"
            || text == "Int" || text == "Bool"
}

// Whether a `+` operand may be a part *without* a fold: a literal, or a `Str`/`Char` value.
// A `StrView` value is refused (it could look into the destination) and so is a number
// (`s + n` appends `(char) n`, which is not the same as its digits).
fun ilConcatOperandOk(il: *IlBody, operand: Int): Bool {
    if (operand < 0) {
        return ilConcatConstantOk(il, operand)
    }
    if (operand >= il.vars.size()) {
        return false
    }
    val typeNode: AstXmlNode = ilVarType(il, operand)
    if (xmlIsEmpty(typeNode) || ilConcatIsHandle(typeNode)) {
        return false
    }
    val text: Str = ilTypeText(typeNode)
    return text == "Str" || text == "Char"
}

// A literal part: the pool's own first character says which kind it is.
fun ilConcatConstantOk(il: *IlBody, operand: Int): Bool {
    val index: Int = -1 - operand
    if (index < 0 || index >= il.pool.size()) {
        return false
    }
    val text: Str = il.pool[index]
    if (text.size() == 0) {
        return false
    }
    return text[0] == '\"' || text[0] == '\''
}

// Whether a *part* may be appended, as the emitter must spell it: a literal, a `Str`/`Char`
// value, or a number/bool (a value, or a handle a `toString` read through - a field's
// receiver - which has no text of its own to alias).
fun ilConcatPartOk(il: *IlBody, operand: Int): Bool {
    if (operand < 0) {
        return ilConcatConstantOk(il, operand)
    }
    if (operand >= il.vars.size()) {
        return false
    }
    val text: Str = ilConcatValueText(il, operand)
    if (text == "Str" || text == "Char") {
        return !ilConcatIsHandle(ilVarType(il, operand))
    }
    return ilConcatNumberOk(text)
}

// Whether a `toString` receiver may be folded into its digits: a number or a bool (see the
// header for why a `Char` and the floats are not).
fun ilConcatFoldOk(il: *IlBody, operand: Int): Bool {
    return ilConcatNumberOk(ilConcatValueText(il, operand))
}

// The destination may not be read by the parts - the fresh shape clears it, and the
// in-place one appends to it - except as the *first* part of the in-place shape
// (`s = s + x`), whose bytes are exactly what the rest is appended to.
fun ilConcatDstOk(dst: Int, parts: *List<Int>): Bool {
    var seen: Int = 0
    var first: Bool = false
    var i: Int = 0
    while (i < parts.size()) {
        if (parts[i] == dst) {
            seen = seen + 1
            if (i == 0) {
                first = true
            }
        }
        i = i + 1
    }
    if (seen == 0) {
        return true
    }
    return seen == 1 && first && parts.size() >= 2
}

// The pool index of `text`, appending it when the pool does not hold it yet: a split piece
// is a literal the program never wrote, so it is usually a new entry (the emitter then
// spells it as the literal itself - `StringTable.spelling`).
fun ilConcatPoolIndex(il: *IlBody, text: Str): Int {
    var i: Int = 0
    while (i < il.pool.size()) {
        if (il.pool[i] == text) {
            return i
        }
        i = i + 1
    }
    il.pool.append(text)
    return il.pool.size() - 1
}

// One body's rewrite, in place: `ops`/`lines` are the working copy the pass writes, and
// `replaced` names the slots whose definition it merged away, so the `Declare` that
// belongs to such a slot can go with it.
data class IlConcatFuser(
    var il: *IlBody,
    var uses: List<Int>,
    var ops: List<IlOp>,
    var lines: List<Int>,
    var replaced: Dictionary<Int, Bool>,
    var changed: Bool,
    // The instructions a rewrite has taken off the end of the walk but not yet committed to:
    // a refusal puts them back (`rollback`), a commit drops them (`emitConcat`). Last taken
    // first, so restoring walks them backwards.
    var taken: List<IlOp>,
    var takenLines: List<Int>
) {

    fun keep(op: *IlOp, line: Int): Unit {
        this.ops.append(*op)
        this.lines.append(line)
    }

    fun emitConcat(dst: Int, parts: *List<Int>, line: Int): Unit {
        var operands: List<Int> = List<Int>()
        operands.append(dst)
        var i: Int = 0
        while (i < parts.size()) {
            operands.append(parts[i])
            i = i + 1
        }
        this.ops.append(IlOp(IlOpKind.Concat, operands))
        this.lines.append(line)
        this.taken = List<IlOp>()
        this.takenLines = List<Int>()
        this.changed = true
    }

    // Takes the last instruction the walk kept, off it: what a refusal has to put back and a
    // commit has already folded into a part.
    fun take(): IlOp {
        val last: IlOp = this.ops[this.ops.size() - 1]
        val line: Int = this.lines[this.lines.size() - 1]
        this.ops.removeAt(this.ops.size() - 1)
        this.lines.removeAt(this.lines.size() - 1)
        this.taken.append(last)
        this.takenLines.append(line)
        return last
    }

    // Puts back what a refused rewrite had already consumed. A consume only ever *removes*
    // the last instruction, so cutting the lists back is not enough: the taken instructions
    // go back in the order they were taken from (`taken` is the reverse).
    fun rollback(opsAt: Int, linesAt: Int): Unit {
        if (this.ops.size() > opsAt) {
            this.ops.removeRange(opsAt, this.ops.size())
        }
        if (this.lines.size() > linesAt) {
            this.lines.removeRange(linesAt, this.lines.size())
        }
        var t: Int = this.taken.size() - 1
        while (t >= 0) {
            this.ops.append(this.taken[t])
            this.lines.append(this.takenLines[t])
            t = t - 1
        }
        this.taken = List<IlOp>()
        this.takenLines = List<Int>()
    }

    // Drops the previous instruction and hands its parts back, in order.
    fun absorb(parts: *List<Int>): Unit {
        val last: IlOp = this.take()
        this.replaced.insert(ilConcatDst(*last), true)
        var i: Int = 1
        while (i < last.operands.size()) {
            parts.append(last.operands[i])
            i = i + 1
        }
    }

    // Drops a `toString` call the fold absorbed: the *receiver* becomes the part, so the
    // digits are written into the concatenation instead of a `Str` of their own.
    fun absorbToString(receiver: Int): Unit {
        val last: IlOp = this.take()
        this.replaced.insert(ilConcatDst(*last), true)
    }

    // The receiver of a `toString` call this pass may fold, or -1: a method whose only
    // argument is its receiver, named `toString`, on a number or a bool.
    fun foldReceiver(op: *IlOp): Int {
        if (op.kind != IlOpKind.Call || op.operands.size() != 3) {
            return -1
        }
        val methodAt: Int = ilOperandAt(*op.operands, 1)
        if (methodAt < 0 || methodAt >= this.il.methods.size()) {
            return -1
        }
        val method: IlMethod = this.il.methods[methodAt]
        if (method.kind != IlMethodKind.Method || method.argCount != 1) {
            return -1
        }
        if (method.name != "toString") {
            return -1
        }
        val receiver: Int = ilOperandAt(*op.operands, 2)
        if (!ilConcatFoldOk(this.il, receiver)) {
            return -1
        }
        return receiver
    }

    // One `+` operand as parts: a sub-concat the previous instruction built (absorbed), a
    // `toString` the fold absorbs (its receiver becomes the part), or a leaf part.
    fun appendOperand(operand: Int, parts: *List<Int>): Bool {
        if (operand >= 0 && operand < this.uses.size() && this.uses[operand] == 1
            && this.ops.size() > 0
        ) {
            val last: IlOp = this.ops[this.ops.size() - 1]
            if (last.kind == IlOpKind.Concat && ilConcatDst(*last) == operand) {
                // The list itself, not a copy of it: `absorb` appends the parts it hands back.
                this.absorb(parts)
                return true
            }
            if (ilConcatDst(*last) == operand) {
                val receiver: Int = this.foldReceiver(*last)
                if (receiver >= 0) {
                    this.absorbToString(receiver)
                    parts.append(receiver)
                    return true
                }
            }
        }
        if (!ilConcatOperandOk(this.il, operand)) {
            return false
        }
        parts.append(operand)
        return true
    }

    // `dst = lhs + rhs` where `dst` is a `Str`: the parts, or an empty list when the
    // instruction is not that shape or an operand cannot be a part. The merged call stands
    // where the *consumer* stands, and the instructions it folds are the ones just before
    // it, so nothing can have written a part in between.
    fun plusParts(op: *IlOp): List<Int> {
        var parts: List<Int> = List<Int>()
        if (op.kind != IlOpKind.BinaryOp || op.operands.size() < 4) {
            return parts
        }
        if (ilPoolText(this.il, ilOperandAt(*op.operands, 1)) != "+") {
            return parts
        }
        val dst: Int = ilConcatDst(op)
        if (dst < 0 || dst >= this.il.vars.size()) {
            return parts
        }
        if (ilTypeName(this.il, this.il.vars[dst].typeIndex) != "Str") {
            return parts
        }
        // A slot with no type node has no declaration of its own for the emitter to assign
        // (`ilDeclaredAtTop`), which the expansion needs; the instruction stays as it is.
        val dstType: AstXmlNode = ilVarType(this.il, dst)
        if (xmlIsEmpty(dstType)) {
            return parts
        }
        val opsAt: Int = this.ops.size()
        val linesAt: Int = this.lines.size()
        if (!this.appendOperand(ilOperandAt(*op.operands, 2), *parts)
            || !this.appendOperand(ilOperandAt(*op.operands, 3), *parts)
        ) {
            this.rollback(opsAt, linesAt)
            return List<Int>()
        }
        if (!ilConcatDstOk(dst, *parts)) {
            this.rollback(opsAt, linesAt)
            return List<Int>()
        }
        return parts
    }

    // The `Pack`/`Deref`/`Call` shape of `fmtStr("<literal>", item...)`: the parts, or an
    // empty list. Consumes the two instructions the call's list took, so it runs only once
    // every check has passed.
    fun fmtStrParts(op: *IlOp): List<Int> {
        var parts: List<Int> = List<Int>()
        if (op.kind != IlOpKind.Call) {
            return parts
        }
        val methodAt: Int = ilOperandAt(*op.operands, 1)
        if (methodAt < 0 || methodAt >= this.il.methods.size()) {
            return parts
        }
        val method: IlMethod = this.il.methods[methodAt]
        if (method.kind != IlMethodKind.Function || method.argCount != 2) {
            return parts
        }
        if (method.name != "fmtStr") {
            return parts
        }
        if (op.operands.size() < 4) {
            return parts
        }
        val dst: Int = ilConcatDst(op)
        if (dst < 0 || dst >= this.il.vars.size()) {
            return parts
        }
        if (ilTypeName(this.il, this.il.vars[dst].typeIndex) != "Str") {
            return parts
        }
        // A slot with no type node has no declaration of its own for the emitter to assign.
        val dstType: AstXmlNode = ilVarType(this.il, dst)
        if (xmlIsEmpty(dstType)) {
            return parts
        }
        // The format must be a literal: a value the emitter can split at compile time.
        val fmtOperand: Int = ilOperandAt(*op.operands, 2)
        val listOperand: Int = ilOperandAt(*op.operands, 3)
        if (fmtOperand >= 0 || listOperand < 0) {
            return parts
        }
        val fmtIndex: Int = -1 - fmtOperand
        if (fmtIndex < 0 || fmtIndex >= this.il.pool.size()) {
            return parts
        }
        val fmt: Str = this.il.pool[fmtIndex]
        if (!ilConcatSplittable(*fmt)) {
            return parts
        }
        // The argument list: the `Pack` and the `Deref` that took its address, adjacent.
        if (this.ops.size() < 2) {
            return parts
        }
        val deref: IlOp = this.ops[this.ops.size() - 1]
        val pack: IlOp = this.ops[this.ops.size() - 2]
        if (deref.kind != IlOpKind.Deref || pack.kind != IlOpKind.Pack) {
            return parts
        }
        val derefDst: Int = ilConcatDst(*deref)
        val packDst: Int = ilConcatDst(*pack)
        if (listOperand != derefDst || ilOperandAt(*deref.operands, 1) != packDst) {
            return parts
        }
        if (derefDst < 0 || packDst < 0 || derefDst >= this.uses.size() || packDst >= this.uses.size()) {
            return parts
        }
        if (this.uses[derefDst] != 1 || this.uses[packDst] != 1) {
            return parts
        }
        // One `|` per item is the count the runtime itself checks; the split has to agree.
        var pipes: Int = 0
        var i: Int = 1
        while (i < fmt.size() - 1) {
            if (fmt[i] == '|') {
                pipes = pipes + 1
            }
            i = i + 1
        }
        val items: Int = pack.operands.size() - 1
        if (pipes != items) {
            return parts
        }
        // Every item is a part too, and the whole answer is all-or-nothing.
        i = 1
        while (i < pack.operands.size()) {
            if (!ilConcatPartOk(this.il, pack.operands[i])) {
                return parts
            }
            i = i + 1
        }
        // Build the parts first - the literal's own pieces around the items, an empty
        // piece dropped - so nothing is consumed before the answer is known.
        var start: Int = 1
        var item: Int = 0
        i = 1
        while (i <= fmt.size() - 1) {
            if (i == fmt.size() - 1 || fmt[i] == '|') {
                val piece: Str = fmt.substr(start, i - start)
                if (piece.size() > 0) {
                    parts.append(-1 - ilConcatPoolIndex(this.il, fmtStr("\"|\"", piece)))
                }
                start = i + 1
                if (i < fmt.size() - 1) {
                    parts.append(pack.operands[item + 1])
                    item = item + 1
                }
            }
            i = i + 1
        }
        if (parts.size() == 0) {
            // `fmtStr("")`: one empty part, which is the empty `Str` the runtime answers.
            parts.append(-1 - ilConcatPoolIndex(this.il, "\"\""))
        }
        if (!ilConcatDstOk(dst, *parts)) {
            return List<Int>()
        }
        // The call's list took two instructions, and they are the two before it.
        this.take()
        this.take()
        this.replaced.insert(derefDst, true)
        this.replaced.insert(packDst, true)
        return parts
    }

    // Walk the body's instructions in order, keeping each one the fusion does not consume.
    // In order is what makes an instruction's own `Concat` the *previous* instruction for
    // the next one, so a chain of any length folds in a single pass.
    fun run(): Unit {
        var i: Int = 0
        while (i < this.il.ops.size()) {
            val op: *IlOp = *this.il.ops[i]
            val line: Int = this.il.lines[i]
            if (op.kind == IlOpKind.Call) {
                val parts: List<Int> = this.fmtStrParts(op)
                if (parts.size() > 0) {
                    this.emitConcat(ilConcatDst(op), *parts, line)
                    i = i + 1
                    continue
                }
            }
            if (op.kind == IlOpKind.BinaryOp) {
                val parts: List<Int> = this.plusParts(op)
                if (parts.size() > 0) {
                    this.emitConcat(ilConcatDst(op), *parts, line)
                    i = i + 1
                    continue
                }
            }
            this.keep(op, line)
            i = i + 1
        }
    }
}

// The slots the *kept* instructions write: a `Declare` for a slot with none of these is
// dead storage and goes (`ilDeclGroups` would otherwise print a variable nothing assigns).
fun ilConcatWrittenSlots(ops: *List<IlOp>, count: Int): List<Bool> {
    var written: List<Bool> = List<Bool>()
    var i: Int = 0
    while (i < count) {
        written.append(false)
        i = i + 1
    }
    i = 0
    while (i < ops.size()) {
        val op: *IlOp = *ops[i]
        if (op.kind != IlOpKind.Declare && op.kind != IlOpKind.DeclareInit) {
            val dst: Int = ilConcatDst(op)
            if (dst >= 0 && dst < written.size()) {
                written[dst] = true
            }
        }
        i = i + 1
    }
    return written
}

// The body's instruction list, with every fusable concatenation merged. Answers whether the
// body changed; a body with nothing to fuse is left exactly as it was.
fun ilFuseConcat(il: *IlBody): Bool {
    // `--no-concat`: the pass's own switch, so one binary produces both shapes and the two
    // can be measured against each other - the same program, the same compiler, one flag.
    // Nothing is fused and no reach is recorded, so the `strcat` section stays out of the
    // program as well.
    if (ilNoConcat()) {
        return false
    }
    var fuser: IlConcatFuser = IlConcatFuser(
        il, ilConcatUseCounts(il), List<IlOp>(), List<Int>(), Dictionary<Int, Bool>(), false,
        List<IlOp>(), List<Int>()
    )
    fuser.run()
    if (!fuser.changed) {
        return false
    }
    // A declaration whose slot the fusion left with no writer at all: drop it, so the
    // emitted C++ does not carry a variable nothing assigns.
    val written: List<Bool> = ilConcatWrittenSlots(*fuser.ops, il.vars.size())
    var ops: List<IlOp> = List<IlOp>()
    var lines: List<Int> = List<Int>()
    var i: Int = 0
    while (i < fuser.ops.size()) {
        val op: IlOp = fuser.ops[i]
        if (op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit) {
            val slot: Int = ilOperandAt(*op.operands, 0)
            if (slot >= 0 && slot < written.size() && !written[slot] && fuser.replaced.has(slot)) {
                i = i + 1
                continue
            }
        }
        ops.append(op)
        lines.append(fuser.lines[i])
        i = i + 1
    }
    il.ops = ops
    il.lines = lines
    return true
}

// `--no-concat`: skip the pass above, so a `+` chain over `Str` and an `fmtStr` with a
// literal format keep the shape the lowering gave them (one allocation per `+` link, the
// format re-scanned by the runtime `fmtStr`) - the fusion `off` side of an A/B, and a way
// to read what the fusion would have done (`--showLinearRepresentation`). Off by default:
// the fusion is a pure optimization and this only removes it.
var ilNoConcatFlag: Bool = false

fun ilNoConcat(): Bool {
    return ilNoConcatFlag
}

fun ilSetNoConcat(value: Bool): Unit {
    ilNoConcatFlag = value
}
