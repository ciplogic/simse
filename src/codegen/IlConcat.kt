// IlConcat.kt
//
// The concatenation expansion: the one buffer a `+`/`fmtStr` chain becomes, and what it
// needs declared at the top of a body. `Emitter` extension functions.

package codegen

import compiler

import sema
import common
import io
import linear
import optimizations
import profiling

// What a body's concatenations need declared at its top: whether any chain writes a byte (the
// shared `char*` cursor), whether any chain is in place (the `at` temporary), and how many
// integer counts the widest chain needs at once. One pass, so the body declares exactly what
// it uses.
data class IlConcatPool(
    var pointer: Bool,
    var at: Bool,
    var counts: Int
)

fun ilConcatPoolOf(il: *IlBody): IlConcatPool {
    var pool: IlConcatPool = IlConcatPool(false, false, 0)
    for (*op in il.ops) {
        if (op.kind == IlOpKind.Concat) {
            val inPlace: Bool = op.operands.size() > 1 && op.operands[1] == ilConcatDst(op)
            var counts: Int = 0
            var j: Int = 1
            while (j < op.operands.size()) {
                val part: Int = op.operands[j]
                if (inPlace && j == 1) {
                    pool.at = true
                } else if (part < 0) {
                    // A char literal writes; an empty string literal is the one part that does not.
                    val text: Str = il.pool[-1 - part]
                    if (text.size() > 0 && (text[0] != '\"' || cgLiteralByteLength(text) > 0)) {
                        pool.pointer = true
                    }
                } else {
                    pool.pointer = true
                    if (ilConcatNumberOk(ilConcatValueText(il, part))) {
                        counts = counts + 1
                    }
                }
                j = j + 1
            }
            if (counts > pool.counts) {
                pool.counts = counts
            }
        }
    }
    return pool
}

// The concatenation storage a body needs, at its top: declared where a jump cannot skip it, so
// one `char*` cursor serves every chain (`ilConcatStatements` recycles it on each `resize`) and
// the counts are one line. Nothing here collides across bodies - each has its own scope.
fun Emitter.ilConcatPreamble(il: *IlBody, out: *Str, level: Int): Unit {
    val pool: IlConcatPool = ilConcatPoolOf(il)
    if (pool.pointer) {
        this.ilLine(out, level, "char* __sm_catP;")
    }
    if (pool.at) {
        this.ilLine(out, level, "Int __sm_catAt;")
    }
    if (pool.counts > 0) {
        var names: List<Str> = List<Str>()
        var c: Int = 0
        while (c < pool.counts) {
            names.append(`__sm_catC@c`)
            c = c + 1
        }
        val cgJoinText: Str = cgJoin(names, ", ")
        this.ilLine(out, level, `Int @cgJoinText;`)
    }
}

// A concatenation as the statements the emitter expands it into (src/linear/MergeConcat.kt,
// impl_specs/linear-il.md, "Concat"): every part's *exact* length is summed, one `resize` makes
// the whole buffer, and then each part is written straight into the slot it owns while one
// `char*` advances by that part's length - the shape Java 9's `StringConcatFactory` has, with
// the C++ compiler seeing the straight-line form. So a chain of n parts touches the allocator
// once, computes each part once and copies each part once.
//
//   * a literal part contributes its own byte count as a compile-time integer and is copied
//     with a constant-size `std::memcpy` (one register or vector store) - a one-byte literal
//     as a `Char` write, and an empty one with nothing at all;
//   * a `Str` part contributes `.size()` and is copied with a runtime `memcpy`;
//   * an integer part contributes `simse_strCountDigits` (a bit scan) and is written by
//     `simse_strAddInt`; the count is kept in a temporary, because the sum and the pointer's
//     advance both read it. A `Char` is one byte; a `Bool` is a `StrView` over a two-entry
//     table (MergeConcat's `ilConcatNumberOk`); a float is never a part - its `toString` is not
//     folded, so it is a `Str` part.
//
// The expansion is its own C++ block: the temporaries it names cannot be jumped into, and they
// need no place in the function's declaration group.
//
// The destination is written *in place* exactly when the fusion left it as the first part
// (`s = s + x`, MergeConcat's `ilConcatDstOk`): then the bytes already there are the chain's
// prefix and every other part is written after them. No part may read the destination - the
// fusion refuses that - so the fresh chain needs no `clear`: one `resize` and the writes cover
// every byte the new value has.
//
// `out` receives the statements, at `level`; `false` leaves it untouched and `ilWhy` says why.
fun Emitter.ilConcatStatements(il: *IlBody, frame: *IlFrame, op: *IlOp, level: Int, out: *Str): Bool {
    val dst: Int = ilConcatDst(op)
    if (dst < 0 || dst >= il.vars.size()) {
        this.ilWhy = "a concatenation with no destination"
        return false
    }
    if (this.ilFolded(il, frame, dst)) {
        this.ilWhy = "a concatenation into a slot with no storage"
        return false
    }
    val name: Str = il.vars[dst].name
    // The first part is the destination exactly when the instruction is `s = s + ...`: the one
    // shape whose chain keeps the bytes that are already there.
    val inPlace: Bool = op.operands.size() > 1 && op.operands[1] == dst
    // The cursor and the counts are the *body's* names (`ilConcatPreamble` declares them at the
    // top): a `char*` is a cursor every `resize` recycles, and a chain's counts are dead before
    // the next chain starts, so one set per body serves them all. A chain can sit in a block the
    // crossing logic opened, which is why they cannot be declared per chain.
    val atName: Str = "__sm_catAt"
    val pName: Str = "__sm_catP"
    // Three things the expansion is made of, in the order it writes them: the compile-time byte
    // count, the runtime count terms, and the writes.
    var counts: Int = 0
    var fixed: Int = 0
    var sums: List<Str> = List<Str>()
    var prelude: List<Str> = List<Str>()
    var writes: List<Str> = List<Str>()
    var i: Int = 1
    while (i < op.operands.size()) {
        val part: Int = op.operands[i]
        if (inPlace && i == 1) {
            // The destination's own bytes: the prefix `at` measures, never a write.
        } else if (part < 0) {
            val text: Str = il.pool[-1 - part]
            if (text.size() == 0) {
                this.ilWhy = "a part of a concatenation"
                return false
            }
            if (text[0] == '\'') {
                fixed = fixed + 1
                writes.append(`*@pName = (char) (@text);`)
                writes.append(`@pName = @pName + 1;`)
            } else if (text[0] == '\"') {
                val len: Int = cgLiteralByteLength(text)
                fixed = fixed + len
                if (len == 1) {
                    // One byte: a `Char` write is what a one-byte copy folds to, spelled so
                    // it stays a byte store even where `memcpy` is a call. The literal's inner
                    // text becomes a character literal - the one shape that would not is a raw
                    // apostrophe, which a string may spell unescaped.
                    val inner: Str = text.substr(1, text.size() - 2)
                    var ch: Str = `'@inner'`
                    if (inner == "'") {
                        ch = "'\\''"
                    }
                    writes.append(`*@pName = (char) (@ch);`)
                    writes.append(`@pName = @pName + 1;`)
                } else if (len > 1) {
                    writes.append(`std::memcpy(@pName, @text, @len);`)
                    writes.append(`@pName = @pName + @len;`)
                }
                // An empty literal contributes nothing: no count, no write - just the
                // `resize` that every chain needs, and the part is done.
            } else {
                this.ilWhy = "a part of a concatenation"
                return false
            }
        } else {
            val kind: Str = ilConcatValueText(il, part)
            val value: Str = this.ilConcatPartText(il, frame, part)
            if (value == "") {
                this.ilWhy = "a part of a concatenation"
                return false
            }
            if (kind == "Str") {
                sums.append(`@value.size()`)
                writes.append(`std::memcpy(@pName, @value.data(), @value.size());`)
                writes.append(`@pName = @pName + @value.size();`)
            } else if (kind == "Char") {
                fixed = fixed + 1
                writes.append(`*@pName = (char) (@value);`)
                writes.append(`@pName = @pName + 1;`)
            } else if (kind == "Bool") {
                sums.append(`simse_strBoolView(@value).len`)
                writes.append(`std::memcpy(@pName, simse_strBoolView(@value).ptr, simse_strBoolView(@value).len);`)
                writes.append(`@pName = @pName + simse_strBoolView(@value).len;`)
            } else if (ilConcatNumberOk(kind)) {
                val count: Str = `__sm_catC@counts`
                counts = counts + 1
                prelude.append(`@count = simse_strCountDigits(@value);`)
                sums.append(count)
                writes.append(`simse_strAddInt(@pName, @value, @count);`)
                writes.append(`@pName = @pName + @count;`)
            } else {
                this.ilWhy = "a part of a concatenation"
                return false
            }
        }
        i = i + 1
    }
    if (op.operands.size() < 2) {
        this.ilWhy = "a concatenation with no part"
        return false
    }
    // The length sum: the constant part first, then the runtime terms.
    var rest: Str = ""
    if (fixed > 0 || sums.size() == 0) {
        rest = ilIntText(fixed)
    }
    var s: Int = 0
    while (s < sums.size()) {
        if (rest == "") {
            rest = sums[s]
        } else {
            val sumsText: Str = sums[s]
            rest = `@rest + @sumsText`
        }
        s = s + 1
    }
    // Every part is expressible, so the buffer is written where the instruction stands: a slot
    // with no type node has no declaration of its own (its `Declare` prints nothing).
    if (!this.ilDeclaredAtTop(il, dst)) {
        var declared: Str = this.ilDeclTypeText(il, dst)
        if (declared == "") {
            declared = "Str"
        }
        this.ilLine(out, level, `@declared @name;`)
    }
    // The last write's advance is dead - nothing reads the cursor after it - so it goes.
    if (writes.size() > 0) {
        writes.removeAt(writes.size() - 1)
    }
    var p: Int = 0
    while (p < prelude.size()) {
        this.ilLine(out, level, prelude[p])
        p = p + 1
    }
    // `at` reads the destination's size *before* the one `resize` moves it, and the counts read
    // only the parts, so the order is: the counts, `at`, the resize, the pointer, the writes.
    if (inPlace) {
        this.ilLine(out, level, `@atName = @name.size();`)
        this.ilLine(out, level, `@name.resize(@atName + @rest);`)
        if (writes.size() > 0) {
            this.ilLine(out, level, `@pName = @name.data() + @atName;`)
        }
    } else {
        this.ilLine(out, level, `@name.resize(@rest);`)
        if (writes.size() > 0) {
            this.ilLine(out, level, `@pName = @name.data();`)
        }
    }
    p = 0
    while (p < writes.size()) {
        this.ilLine(out, level, writes[p])
        p = p + 1
    }
    return true
}

// One part's value, spelled the way its operand is (`ilOperandNode`/`expr`): a pooled literal
// becomes its table entry, a lowering-invented piece the literal itself, and a slot its name.
// A *handle* slot is dereferenced: a `toString` the fusion folded away reads its receiver
// through `*T`/`&T` (a field's address, MergeConcat's `ilConcatValueText`), and what the part
// writes is that value, never the pointer.
fun Emitter.ilConcatPartText(il: *IlBody, frame: *IlFrame, part: Int): Str {
    val node: AstXmlNode = this.ilOperandNode(il, frame, part, 0)
    if (xmlIsEmpty(node)) {
        return ""
    }
    val text: Str = this.expr(node, 0, xmlEmptyNode())
    if (part >= 0) {
        var typeNode: AstXmlNode = ilVarType(il, part)
        if (this.isHandleType(*typeNode)) {
            return `(*@text)`
        }
    }
    return text
}

// The right-hand side of one instruction, as C++: the computed expression, or - for an
// aggregate construction - the brace form, which has no expression node.
fun Emitter.ilValueText(il: *IlBody, frame: *IlFrame, opIndex: Int, expected: *AstXmlNode): Opt<Str> {
    if (opIndex < 0 || opIndex >= il.ops.size()) {
        return ()
    }
    val op: *IlOp = *il.ops[opIndex]
    if (op.kind == IlOpKind.CallCtor) {
        val boxed: Opt<Str> = this.ilBoxedCtorText(il, frame, op)
        if (boxed.hasValue()) {
            return boxed
        }
    }
    if (op.kind == IlOpKind.Concat) {
        // The fusion expands a concatenation where it *writes* it (`ilConcatStatements`), and a
        // destination is always a slot with a type of its own, so this cannot be reached.
        this.ilWhy = "a concatenation in a value position"
        return ()
    }
    if (op.kind == IlOpKind.Cast) {
        // `h.getAs<T>()`: the destination's type *is* the cast's target - the extractor set it
        // from the type argument (LinearForm.kt, `IlCallNode`'s sibling in `IlExtractor.call`) -
        // so `RawPtr` to `*T` is one `reinterpret_cast` and the IL needs no type operand.
        val dst: Int = this.ilOpOperand(op.operands, 0)
        val dstType: AstXmlNode = ilVarType(il, dst)
        if (xmlIsEmpty(dstType)) {
            this.ilWhy = "a cast with no target type"
            return ()
        }
        val operand: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
        if (xmlIsEmpty(operand)) {
            this.ilWhy = "a cast with no operand"
            return ()
        }
        val typeText: Str = this.type(dstType)
        val exprText: Str = this.expr(operand, 0, xmlEmptyNode())
        return (
                `reinterpret_cast<@typeText>(@exprText)`
                )
    }
    if (op.kind == IlOpKind.Pack) {
        // `List<T>{v1, v2, ...}`: `List` is `SmallVector<T, 4>`, so a short list stays
        // inline and allocates nothing - which is why a pack builds a `List`, not an `Array`.
        val slot: Int = this.ilOpOperand(op.operands, 0)
        val slotType: AstXmlNode = ilVarType(il, slot)
        if (xmlIsEmpty(slotType)) {
            return ()
        }
        var values: List<Str> = List<Str>()
        var j: Int = 1
        while (j < op.operands.size()) {
            val value: AstXmlNode = this.ilOperandNode(il, frame, op.operands[j], 0)
            if (xmlIsEmpty(value)) {
                return ()
            }
            values.append(this.expr(value, 0, xmlEmptyNode()))
            j = j + 1
        }
        val typeText2: Str = this.type(slotType)
        val cgJoinText2: Str = cgJoin(values, ", ")
        return (`@typeText2{@cgJoinText2}`)
    }
    if (op.kind == IlOpKind.CallCtor) {
        val typeAt: Int = this.ilOpOperand(op.operands, 1)
        if (typeAt >= 0 && typeAt < il.types.size()) {
            val found: *Str = this.closureTypes.getPtr(il.types[typeAt])
            if (found != null) {
                val closureType: Str = *found
                var captured: List<Str> = List<Str>()
                var j: Int = 2
                while (j < op.operands.size()) {
                    val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[j], 0)
                    if (xmlIsEmpty(arg)) {
                        return ()
                    }
                    captured.append(this.expr(arg, 0, xmlEmptyNode()))
                    j = j + 1
                }
                val cgJoinText3: Str = cgJoin(captured, ", ")
                return (`@closureType{@cgJoinText3}`)
            }
        }
    }
    val node: AstXmlNode = this.ilOpValueNode(il, frame, opIndex, 0)
    if (xmlIsEmpty(node)) {
        return ()
    }
    return (this.expr(node, 0, expected))
}

// `&Ctor(args)`: the box built in place, `makeRef<C>(args...)` - the extractor gives the
// construction the `&C` slot as its destination (LinearForm.kt, `ExprRef`). Empty for a
// construction whose destination is a value, which is spelled its own way.
fun Emitter.ilBoxedCtorText(il: *IlBody, frame: *IlFrame, op: *IlOp): Opt<Str> {
    val dst: Int = this.ilOpOperand(op.operands, 0)
    if (dst < 0 || dst >= il.vars.size()) {
        return ()
    }
    val dstType: AstXmlNode = ilVarType(il, dst)
    if (xmlIsEmpty(dstType) || xmlKind(dstType) != AstNodeCategory.TypeReference) {
        return ()
    }
    val inner: *AstXmlNode = xmlChildPtr(dstType, AstNodeKind.Inner)
    if (xmlIsEmpty(inner)) {
        return ()
    }
    var args: List<Str> = List<Str>()
    var i: Int = 2
    while (i < op.operands.size()) {
        val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
        if (xmlIsEmpty(arg)) {
            return ()
        }
        args.append(this.expr(arg, 0, xmlEmptyNode()))
        i = i + 1
    }
    val typeText3: Str = this.type(inner)
    val cgJoinText4: Str = cgJoin(args, ", ")
    return (`makeRef<@typeText3>(@cgJoinText4)`)
}
