// rtl.kt
//
// The prelude: declarations in every program's module scope, so these operations need no
// import. A declaration's C++ is named by its `@SmGen` attribute (`native(symbol)` is the
// `cpp` generator's sugar); one with a body is emitted like any other function.

package rtl

// `for (x in c)` is `for (x in c.iter())`: anything with an `iter` in scope is iterable,
// and a container walks itself in order (impl_specs/for.md).
fun List<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

// The same walk over the fixed-length sequence and borrowed storage, counted by the
// operation each provides (`count()` for `Array`, `size()` for `Span`). The count is read
// once, before the loop: the `while` lives in the machine's `advance()`, so a count in the
// condition would be a call on every resumption.
fun Array<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.count();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

fun Span<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

// The pointer form, `for (*x in c)`: the same walk, handing out each element's *place*
// (`*this[i]`) instead of a copy, so a mutation through the loop variable reaches the
// element (impl_specs/for.md).
fun List<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

fun Array<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.count();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

fun Span<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

// A machine is already iterable: `x.iter()` on one *is* `x`, so `for (x in m)` and
// iterating `m` in a `while` see the same values with no wrapper object.

@SmGen("res", "listops", "simse_list_append")
fun append<T>(this: List<T>, value: T): Unit

// The list literal: `listOf<Str>("a", "b")` builds one `Pack` in that order, with no call
// emitted. The `*List<T>` parameter is what makes the trailing arguments pack, and
// `List<T>(n, value)` is the count construction rather than a literal (specs/containers.md).
@SmGen("res", "listops", "simse_listOf")
fun listOf<T>(values: *List<T>): List<T>

@SmGen("res", "listops", "simse_list_removeAt")
fun removeAt<T>(this: List<T>, index: Int): Unit

@SmGen("res", "listops", "simse_list_removeRange")
fun removeRange<T>(this: List<T>, start: Int, end: Int): Unit

@SmGen("res", "dictops", "simse_list_contains")
fun contains<T>(this: List<T>, value: T): Bool

// In-place sort; the comparator is a `(T, T) -> Bool` lambda.
@SmGen("res", "dictops", "simse_list_sort")
fun sort<T>(this: List<T>, less: (T, T) -> Bool): Unit

// `Array<T>` is a fixed-length, ref-counted block: the allocation holds the element count
// first, then the elements (specs/built-in-types.md). `arrayEmpty<T>()` is the shared empty
// array; `toArray`/`toList` convert between the fixed and the growable sequence.
@SmGen("res", "listops", "simse_arrayEmpty")
fun arrayEmpty<T>(): Array<T>

@SmGen("res", "listops", "simse_array_count")
fun count<T>(this: Array<T>): Int

@SmGen("res", "listops", "simse_list_toArray")
fun toArray<T>(this: List<T>): Array<T>

@SmGen("res", "listops", "simse_array_toList")
fun toList<T>(this: Array<T>): List<T>

// `Dictionary<K, V>` is a value type; keys and values come back in its iteration order,
// which is unspecified - sort for determinism.
@SmGen("res", "dictops", "simse_dictionaryOf")
fun dictionaryOf<K, V>(): Dictionary<K, V>

// `getPtr` reads the value's *place*: nothing is copied, and `null` answers "absent".
// The place is the dictionary's own storage, so it is valid until the next `insert`,
// `remove` or `clear`. `get` copies the value out, and `has` is `getPtr` with the pointer
// tested.
@SmGen("res", "dictops", "simse_dict_getPtr")
fun getPtr<K, V>(this: Dictionary<K, V>, key: K): *V

@SmGen("res", "dictops", "simse_dict_get")
fun get<K, V>(this: Dictionary<K, V>, key: K): Opt<V>

@SmGen("res", "dictops", "simse_dict_has")
fun has<K, V>(this: Dictionary<K, V>, key: K): Bool

@SmGen("res", "dictops", "simse_dict_insert")
fun insert<K, V>(this: Dictionary<K, V>, key: K, value: V): Unit

@SmGen("res", "dictops", "simse_dict_remove")
fun remove<K, V>(this: Dictionary<K, V>, key: K): Unit

@SmGen("res", "dictops", "simse_dict_size")
fun size<K, V>(this: Dictionary<K, V>): Int

@SmGen("res", "dictops", "simse_dict_keys")
fun keys<K, V>(this: Dictionary<K, V>): List<K>

@SmGen("res", "dictops", "simse_dict_values")
fun values<K, V>(this: Dictionary<K, V>): List<V>

@SmGen("res", "dictops", "simse_dict_clear")
fun clear<K, V>(this: Dictionary<K, V>): Unit

// `Str` is a byte string, so `append` takes a Char.
@SmGen("res", "listops", "simse_str_append")
fun append(this: Str, value: Char): Unit

// In-place append of a whole `Str`: `out = out + text` copies the accumulated buffer.
@SmGen("res", "listops", "simse_str_appendStr")
fun appendStr(this: Str, value: Str): Unit

// The same append for a borrowed text (`*Str`): nothing is copied on the way.
@SmGen("res", "listops", "simse_str_appendStrPtr")
fun appendStrPtr(this: Str, value: *Str): Unit

// The format text with each `|` replaced, in order, by one item - `fmtStr("|:|", a, b)`.
// The shape must have one `|` per item and the items must all be present; a call whose
// counts do not line up (or that passes no list) gets the format back, unfilled.
// `fmt` is a `StrView`, so a literal format is taken as it stands, without a copy.
fun fmtStr(fmt: StrView, items: *List<Str>): Str {
    if (items == null) {
        return fmt
    }
    var points: Int = 0
    var i: Int = 0
    while (i < fmt.size()) {
        if (fmt.charAt(i) == '|') {
            points = points + 1
        }
        i = i + 1
    }
    if (points != items.size()) {
        return fmt
    }
    var out: Str = ""
    out.reserve(fmt.size())
    var used: Int = 0
    i = 0
    while (i < fmt.size()) {
        val ch: Char = fmt.charAt(i)
        if (ch == '|') {
            out.appendStr(items[used])
            used = used + 1
        } else {
            out.append(ch)
        }
        i = i + 1
    }
    return out
}

// The emitter's own concatenation, not the program's: a `+` chain over `Str` and an
// `fmtStr` whose format is a literal are fused into one `Concat` instruction
// (impl_specs/linear-il.md, "Concat"), which the emitter *expands* - one length sum, one
// `resize`, and one slot write per part through a pointer that advances
// (cppsrc/codegen/IlCodeGen.kt's `ilConcatStatements`) - and whose reach it records itself.
// This declaration is the anchor the section hangs on: the symbol below is the one the
// emitter records (`ilConcatSymbol`, cppsrc/linear/MergeConcat.kt), so reaching it emits the
// section's primitives (`strcat`, cppsrc/rtl/_res.md) and nothing else. No program calls it -
// the emitter writes those symbols - and the section's `forward` text *is* the declaration,
// so it places no prototype of its own.
@SmGen("res", "strcat", "simse_strAddInt")
fun strAddInt(target: *Char, value: Int64, count: Int): Unit

// Pre-allocates the buffer for a run of `append`/`appendStr`: a *hint*, not a length - the
// string keeps its size, and a longer run grows it as usual.
@SmGen("res", "listops", "simse_str_reserve")
fun reserve(this: Str, count: Int): Unit

// `find` returns -1 when `sub` is absent (the language's spelling of npos).
@SmGen("res", "strops", "simse_str_find")
fun find(this: Str, sub: Str): Int

@SmGen("res", "strops", "simse_str_find")
fun indexOf(this: Str, sub: Str): Int

@SmGen("res", "strops", "simse_str_lastIndexOf")
fun lastIndexOf(this: Str, sub: Str): Int

@SmGen("res", "strops", "simse_str_substr")
fun substr(this: Str, start: Int, len: Int): Str

@SmGen("res", "strops", "simse_str_charAt")
fun charAt(this: Str, index: Int): Char

@SmGen("res", "strops", "simse_str_startsWith")
fun startsWith(this: Str, prefix: Str): Bool

@SmGen("res", "strops", "simse_str_endsWith")
fun endsWith(this: Str, suffix: Str): Bool

@SmGen("res", "strops", "simse_str_replace")
fun replace(this: Str, from: Str, to: Str): Str

@SmGen("res", "strops", "simse_str_trim")
fun trim(this: Str): Str

@SmGen("res", "strops", "simse_str_split")
fun split(this: Str, separator: Str): List<Str>

@SmGen("res", "strops", "simse_str_split")
fun split(this: Str, separator: Char): List<Str>

@SmGen("res", "strops", "simse_str_toUpper")
fun toUpper(this: Str): Str

@SmGen("res", "strops", "simse_str_toLower")
fun toLower(this: Str): Str

// Written in the language, not C++: `size()` is the built-in it needs. Note the receiver
// spelling (impl_specs/rtl-abi.md): a body writes the receiver type before the name
// (`Str.isEmpty`), which is what marks it an extension resolved at a member call, where a
// body-less declaration writes it as the explicit first parameter (`this: Str`).
fun Str.isEmpty(): Bool {
    return this.size() == 0
}

// Whole-string parses; a malformed string yields `Opt.none()`, not an exception.
@SmGen("res", "strops", "simse_str_toInt")
fun toInt(this: Str): Opt<Int>

@SmGen("res", "strops", "simse_str_toFloat")
fun toFloat(this: Str): Opt<Float64>

@SmGen("res", "strops", "simse_char_isDigit")
fun isDigit(this: Char): Bool

@SmGen("res", "strops", "simse_char_isAlpha")
fun isAlpha(this: Char): Bool

@SmGen("res", "strops", "simse_char_isAlphaOrDigit")
fun isAlphaOrDigit(this: Char): Bool

@SmGen("res", "strops", "simse_char_isSpace")
fun isSpace(this: Char): Bool

@SmGen("res", "listops", "simse_int_toString")
fun toString(this: Int): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Int8): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Int16): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Int32): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Int64): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Float32): Str

@SmGen("res", "strops", "simse_num_toString")
fun toString(this: Float64): Str

@SmGen("res", "strops", "simse_char_toString")
fun toString(this: Char): Str

@SmGen("res", "strops", "simse_bool_toString")
fun toString(this: Bool): Str

// The smaller/larger of two values: `<` on the type is all the body needs.
fun min<T>(a: T, b: T): T {
    if (a < b) {
        return a
    }
    return b
}

fun max<T>(a: T, b: T): T {
    if (a > b) {
        return a
    }
    return b
}

// Milliseconds since an arbitrary fixed point, monotonic (never goes backwards).
@SmGen("res", "timeops", "simse_nowMillis")
fun nowMillis(): Int64

// The same clock in microseconds: what the instrumented profiler measures with.
@SmGen("res", "timeops", "simse_nowMicros")
fun nowMicros(): Int64
