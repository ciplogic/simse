// rtl.kt
//
// The prelude: declarations in every program's module scope, so these operations need no
// import. A declaration's C++ is named by its `@SmGen` attribute (`native(symbol)` is the
// `cpp` generator's sugar); one with a body is emitted like any other function.

package rtl

// `for (x in c)` is `for (x in c.iter())`: anything with an `iter` in scope is iterable,
// and a container walks itself in order (impl_specs/for.md).
borrow fun List<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

// The same walk over `Array`/`Span` (counted by `count()`/`size()`). The count is read once,
// before the loop: the `while` lives in the machine's `advance()`, so a count in the
// condition would be a call on every resumption.
borrow fun Array<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.count();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

borrow fun Span<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

// The pointer form, `for (*x in c)`: the same walk handing out each element's *place*
// (`*this[i]`), so a mutation through the loop variable reaches the element (impl_specs/for.md).
borrow fun List<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

borrow fun Array<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.count();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

borrow fun Span<T>.iterPtr<T>(): ..*T {
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
borrow fun listOf<T>(values: *List<T>): List<T>

@SmGen("res", "listops", "simse_list_removeAt")
fun removeAt<T>(this: List<T>, index: Int): Unit

@SmGen("res", "listops", "simse_list_removeRange")
fun removeRange<T>(this: List<T>, start: Int, end: Int): Unit

@SmGen("res", "dictops", "simse_list_contains")
borrow fun contains<T>(this: List<T>, value: T): Bool

// In-place sort. The comparator takes its two elements *by pointer* (`(*T, *T) -> Bool`):
// `std::sort` hands each element as a `T&`, so a pointer reads it where it lives - a by-value
// comparator copies both elements per comparison, a heap copy per compare for a `Str`.
// `compareLessThan` below is the `Str` ordering for the common case.
@SmGen("res", "dictops", "simse_list_sort")
fun sort<T>(this: List<T>, less: (*T, *T) -> Bool): Unit

// The `Str` ordering for `sort` (`specs/containers.md`): the two strings compared as *views*,
// in place, so `keys.sort(compareLessThan)` copies nothing per comparison.
borrow fun compareLessThan(left: *Str, right: *Str): Bool {
    return spanOfStr(left) < spanOfStr(right)
}

// `Array<T>` is a fixed-length, ref-counted block: the allocation holds the element count
// first, then the elements (specs/built-in-types.md). `arrayEmpty<T>()` is the shared empty
// array; `toArray`/`toList` convert between the fixed and the growable sequence.
@SmGen("res", "listops", "simse_arrayEmpty")
fun arrayEmpty<T>(): Array<T>

@SmGen("res", "listops", "simse_array_count")
data fun count<T>(this: Array<T>): Int

// `lenOf`: the read-only length operation the language cannot otherwise spell for a `Str`
// or a `List` (`SmallVector`-backed). `data` tells the optimizer a repeated call on an
// unchanged value is one call, with no name whitelist anywhere (`linear/ReusePure.kt`). The
// receiver is borrowed: `simse_lenOf` takes it by reference (cppsrc/rtl/_res.md, `lenops`).
@SmGen("res", "lenops", "simse_lenOf")
data fun size(this: Str): Int

@SmGen("res", "lenops", "simse_lenOf")
data fun size<T>(this: List<T>): Int

@SmGen("res", "listops", "simse_list_toArray")
borrow fun toArray<T>(this: List<T>): Array<T>

@SmGen("res", "listops", "simse_array_toList")
fun toList<T>(this: Array<T>): List<T>

// `Opt<T>.hasValue()`: the `Variant2` tag test (specs/core-types.md), read only - so a body that
// tests an optional and reads its parameter is borrow-clean, which is why it is declared instead of
// left as a built-in member with nothing to mark (cppsrc/rtl/_res.md's `optops`).
//
// A receiver may be a type with an `unInit` - it is emitted as a pointer (`Res* self`), never a
// copy, so the checker allows it (cppsrc/sema/Sema.kt). `Res<T>.isOk()` and `value()` stay built-ins
// for the one remaining reason: `value()` answers the type argument, which the emitter writes
// through without substituting `memberCallReturn`'s return type, so a `T`-returning declaration
// would emit `T` where a concrete type belongs.
@SmGen("res", "optops", "simse_opt_hasValue")
borrow fun hasValue<T>(this: Opt<T>): Bool

// The construction convention: `initByValue` is an *extension* on the instance - it sets
// the receiver and returns nothing. `return (x)` (cppsrc/parser/Parser.kt) and
// `var y = T(x)` (cppsrc/linear/LinearForm.kt) build a `T` and call this on it.
@SmGen("res", "optops", "simse_opt_initByValue")
fun initByValue<T>(this: Opt<T>, value: T): Unit

@SmGen("res", "optops", "simse_opt_initByValueNone")
fun initByValue<T>(this: Opt<T>): Unit

// The same convention for `Res<T>`: `return (x)` in a `Res<T>` function is `Res<T>.ok(x)`.
@SmGen("res", "optops", "simse_res_initByValue")
fun initByValue<T>(this: Res<T>, value: T): Unit

// `Str`'s two constructions. The empty one is an empty method - the receiver is already the
// default (empty) `Str`, so there is nothing to set and `Str`'s own default construction is
// the RTL's. The copy sets it from `value`.
fun Str.initByValue(): Unit {
}

@SmGen("res", "strops", "simse_str_initByValue")
fun initByValue(this: Str, value: Str): Unit

// `Dictionary<K, V>` is a value type; keys and values come back in its iteration order,
// which is unspecified - sort for determinism.
@SmGen("res", "dictops", "simse_dictionaryOf")
fun dictionaryOf<K, V>(): Dictionary<K, V>

// `getPtr` reads the value's *place*: nothing is copied, and `null` answers "absent".
// The place is the dictionary's own storage, so it is valid until the next `insert`,
// `remove` or `clear`. `get` copies the value out, and `has` is `getPtr` with the pointer
// tested.
@SmGen("res", "dictops", "simse_dict_getPtr")
borrow fun getPtr<K, V>(this: Dictionary<K, V>, key: K): *V

@SmGen("res", "dictops", "simse_dict_get")
borrow fun get<K, V>(this: Dictionary<K, V>, key: K): Opt<V>

@SmGen("res", "dictops", "simse_dict_has")
borrow fun has<K, V>(this: Dictionary<K, V>, key: K): Bool

@SmGen("res", "dictops", "simse_dict_insert")
fun insert<K, V>(this: Dictionary<K, V>, key: K, value: V): Unit

@SmGen("res", "dictops", "simse_dict_remove")
fun remove<K, V>(this: Dictionary<K, V>, key: K): Unit

@SmGen("res", "dictops", "simse_dict_size")
data fun size<K, V>(this: Dictionary<K, V>): Int

@SmGen("res", "dictops", "simse_dict_keys")
borrow fun keys<K, V>(this: Dictionary<K, V>): List<K>

@SmGen("res", "dictops", "simse_dict_values")
borrow fun values<K, V>(this: Dictionary<K, V>): List<V>

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
borrow fun fmtStr(fmt: StrView, items: *List<Str>): Str {
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
borrow fun find(this: Str, sub: Str): Int

@SmGen("res", "strops", "simse_str_find")
borrow fun indexOf(this: Str, sub: Str): Int

@SmGen("res", "strops", "simse_str_lastIndexOf")
borrow fun lastIndexOf(this: Str, sub: Str): Int

// `Str.substr(start, len)` clamps `start` to [0, size]; a `len` of -1 (or one running
// past the end) takes the rest. One block copy (`setBytes`), not a per-byte append.
borrow fun Str.substr(start: Int, len: Int): Str {
    var begin = start
    if (begin < 0) {
        begin = 0
    }
    if (begin > this.size()) {
        begin = this.size()
    }
    var count = this.size() - begin
    if (len >= 0 && len < count) {
        count = len
    }
    var out: Str
    if (count > 0) {
        out.setBytes(strBytes(this), begin, count)
    }
    return out
}

@SmGen("res", "strops", "simse_str_charAt")
borrow fun charAt(this: Str, index: Int): Char

// A byte comparison, prefix first: a longer prefix cannot match.
borrow fun Str.startsWith(prefix: Str): Bool {
    val count = prefix.size()
    if (count > this.size()) {
        return false
    }
    var i = 0
    while (i < count) {
        if (this.charAt(i) != prefix.charAt(i)) {
            return false
        }
        i = i + 1
    }
    return true
}

borrow fun Str.endsWith(suffix: Str): Bool {
    val count = suffix.size()
    val len = this.size()
    if (count > len) {
        return false
    }
    var i = 0
    while (i < count) {
        if (this.charAt(len - count + i) != suffix.charAt(i)) {
            return false
        }
        i = i + 1
    }
    return true
}

@SmGen("res", "strops", "simse_str_replace")
borrow fun replace(this: Str, from: Str, to: Str): Str

// Leading and trailing space bytes stripped, one block copy of what is left.
borrow fun Str.trim(): Str {
    var begin = 0
    var end = this.size()
    while (begin < end) {
        val ch: Char = this.charAt(begin)
        if (!ch.isSpace()) {
            break
        }
        begin = begin + 1
    }
    while (end > begin) {
        val ch: Char = this.charAt(end - 1)
        if (!ch.isSpace()) {
            break
        }
        end = end - 1
    }
    var out: Str
    if (end > begin) {
        out.setBytes(strBytes(this), begin, end - begin)
    }
    return out
}

@SmGen("res", "strops", "simse_str_split")
borrow fun split(this: Str, separator: Str): List<Str>

@SmGen("res", "strops", "simse_str_split")
borrow fun split(this: Str, separator: Char): List<Str>

// ASCII/byte case folding (the string type is a byte string).
borrow fun Str.toUpper(): Str {
    var out: Str
    out.reserve(this.size())
    var i = 0
    while (i < this.size()) {
        var ch = this.charAt(i)
        if (ch >= 'a' && ch <= 'z') {
            ch = ch - 32
        }
        out.append(ch)
        i = i + 1
    }
    return out
}

borrow fun Str.toLower(): Str {
    var out: Str
    out.reserve(this.size())
    var i = 0
    while (i < this.size()) {
        var ch = this.charAt(i)
        if (ch >= 'A' && ch <= 'Z') {
            ch = ch + 32
        }
        out.append(ch)
        i = i + 1
    }
    return out
}

// Written in the language, not C++: `size()` is the built-in it needs. Note the receiver
// spelling (impl_specs/rtl-abi.md): a body writes the receiver type before the name
// (`Str.isEmpty`), which is what marks it an extension resolved at a member call, where a
// body-less declaration writes it as the explicit first parameter (`this: Str`).
//
// `data`: a pure function (see `size` above).
data fun Str.isEmpty(): Bool {
    return this.size() == 0
}

// Whole-string parses; a malformed string yields `Opt.none()`, not an exception.
@SmGen("res", "strops", "simse_str_toInt")
borrow fun toInt(this: Str): Opt<Int>

@SmGen("res", "strops", "simse_str_toFloat")
borrow fun toFloat(this: Str): Opt<Float64>

// The `Char` predicates, written in the language: each is a byte-range test (or one of the
// two checks), so the borrow-cleanliness is *proved* from the body
// (impl_specs/escape-analysis.md) and no `borrow` mark is needed. A byte test, not the C
// locale's `isspace`: space, tab, newline and carriage return count, form feed and vertical
// tab do not.
fun Char.isDigit(): Bool {
    return this >= '0' && this <= '9'
}

fun Char.isAlpha(): Bool {
    return (this >= 'a' && this <= 'z') || (this >= 'A' && this <= 'Z')
}

fun Char.isAlphaOrDigit(): Bool {
    return this.isAlpha() || this.isDigit()
}

fun Char.isSpace(): Bool {
    return this == ' ' || this == '\t' || this == '\n' || this == '\r'
}

@SmGen("res", "listops", "simse_int_toString")
borrow fun toString(this: Int): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int8): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int16): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int32): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int64): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Float32): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Float64): Str

@SmGen("res", "strops", "simse_char_toString")
borrow fun toString(this: Char): Str

@SmGen("res", "strops", "simse_bool_toString")
borrow fun toString(this: Bool): Str

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
