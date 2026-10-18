// rtl.kt
//
// The prelude: declarations in every program's module scope, so these operations need no
// import. A declaration's C++ is named by its `@SmGen` attribute (`native(symbol)` is the
// `cpp` generator's sugar); one with a body is emitted like any other function.

package rtl

// `for (x in c)` is `for (x in c.iterValues())`: anything with an `iterValues` in scope is
// iterable, and a container walks itself in order (impl_specs/for.md). A *container's* `for`
// is rewritten to iterate its span (`spanOf`/`spanOfArray`/`spanOfStr`, sema's `for` rewrite),
// so the span's walks below are the only iterator machines a program carries - `List`, `Array`
// and `Str` name none of their own.
fun Span<T>.iterValues<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}

// The pointer form, `for (*x in c)`: the same walk handing out each element's *place*,
// so a mutation through the loop variable reaches the element. It is the short name
// because a pipeline (`modules/linq`) is pointer-typed end to end (impl_specs/for.md).
// The place comes from `atPtr`, not `* this[i]`: an index read through `operator get`
// is a *value*, so its address would be the address of a temporary that dies when this
// machine's `advance` returns (specs/functions.md).
fun Span<T>.iter<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this.atPtr(i)
        i = i + 1
    }
}

// `array.iterOf()`: the array's walk as a source of its own, the machine
// `spanOfArray(arr).iter()` builds (`for (*x in arr)` spells the same walk). It is the short
// form a *pipeline* starts from, where `spanOfArray(x).iter()` is the long one.
fun Array<T>.iterOf<T>(): ..*T {
    var view: Span<T> = spanOfArray(this)
    var i: Int = 0
    val len: Int = view.size()
    while (i < len) {
        yield view.atPtr(i)
        i = i + 1
    }
}

// The element as a *copy*: `..*T.toValues()` is the value machine a `for (x in m)` walks,
// spelled as a pipeline step. The copy lives in the machine's `value` field across the
// yield, so the place the machine hands out is the copy itself.
fun ..*T.toValues<T>(): ..T {
    while (this.advance()) {
        var value: T = *this.current
        yield value
    }
}

// And back: the place of what a value machine yielded (`this.current`), so a chain that
// needs `..*T` again (`select`, `where`) takes it without a `toList` round trip.
fun ..T.toPtrs<T>(): ..*T {
    while (this.advance()) {
        yield *this.current
    }
}

// A machine is already iterable: `x.iterValues()` on one *is* `x`, so `for (x in m)` and
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
borrow fun sort<T>(this: List<T>, less: (*T, *T) -> Bool): Unit

// The `Str` ordering for `sort` (`specs/containers.md`): the two strings compared as *views*,
// in place, so `keys.sort(compareLessThan)` copies nothing per comparison.
fun compareLessThan(left: *Str, right: *Str): Bool {
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
// receiver is borrowed: `simse_lenOf` takes it by reference (src/rtl/_res.md, `lenops`).
@SmGen("res", "lenops", "simse_lenOf")
data fun size(this: Str): Int

@SmGen("res", "lenops", "simse_lenOf")
data fun size<T>(this: List<T>): Int

@SmGen("res", "listops", "simse_list_toArray")
borrow fun toArray<T>(this: List<T>): Array<T>

@SmGen("res", "listops", "simse_array_toList")
fun toList<T>(this: Array<T>): List<T>

// The construction convention: `initByValue` is an *extension* on the instance - it sets
// the receiver and returns nothing. `return (x)` (src/parser/Parser.kt) and
// `var y = T(x)` (src/linear/LinearForm.kt) build a `T` and call this on it. `Opt<T>` and
// `Res<T>` declare it through their `union class` bodies (src/rtl/optres.kt), so their
// arm constructors are the generated ones and no native stands for the convention here.

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

// `fmtStr` with the placeholder taken as a parameter - `fmtStrWith('@', "@=@", a, b)` - for a
// template whose *output* needs a `|` of its own. A literal separator and template are fused
// into the same one-buffer shape `fmtStr` gets (src/linear/MergeConcat.kt); a separator or
// template that is not a literal, a count that does not line up, or no list at all, is
// answered here, exactly as `fmtStr` answers those.
fun fmtStrWith(separator: Char, templateText: StrView, items: *List<Str>): Str {
    if (items == null) {
        return templateText
    }
    var points: Int = 0
    var i: Int = 0
    while (i < templateText.size()) {
        if (templateText.charAt(i) == separator) {
            points = points + 1
        }
        i = i + 1
    }
    if (points != items.size()) {
        return templateText
    }
    var out: Str = ""
    out.reserve(templateText.size())
    var used: Int = 0
    i = 0
    while (i < templateText.size()) {
        val ch: Char = templateText.charAt(i)
        if (ch == separator) {
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
// `fmtStr`/`fmtStrWith` whose format is a literal are fused into one `Concat` instruction
// (impl_specs/linear-il.md, "Concat"), which the emitter *expands* - one length sum, one
// `resize`, and one slot write per part through a pointer that advances
// (src/codegen/IlCodeGen.kt's `ilConcatStatements`) - and whose reach it records itself.
// This declaration is the anchor the section hangs on: the symbol below is the one the
// emitter records (`ilConcatSymbol`, src/linear/MergeConcat.kt), so reaching it emits the
// section's primitives (`strcat`, src/rtl/_res.md) and nothing else. No program calls it -
// the emitter writes those symbols - and the section's `forward` text *is* the declaration,
// so it places no prototype of its own.
@SmGen("res", "strcat", "simse_strAddInt")
fun strAddInt(target: *Char, value: Int64, count: Int): Unit

// Pre-allocates the buffer for a run of `append`/`appendStr`: a *hint*, not a length - the
// string keeps its size, and a longer run grows it as usual.
@SmGen("res", "listops", "simse_str_reserve")
fun reserve(this: Str, count: Int): Unit

// The first index at or after `from` where `sub` occurs, or -1 (the language's spelling of
// npos). Written over the machine intrinsics, the way libstdc++'s own `std::string::find`
// is: `memFindByte` (`memchr`) jumps to each candidate first byte, `memCompare`
// (`memcmp`) confirms the rest in word loads. An empty `sub` occurs at `from` itself when
// `from` is a legal position, which is `std::string`'s answer too.
fun Str.findFrom(sub: Str, from: Int): Int {
    val len: Int = this.size()
    val needle: Int = sub.size()
    if (needle == 0) {
        if (from > len) {
            return 0 - 1
        }
        return from
    }
    if (needle > len || from > len - needle) {
        return 0 - 1
    }
    val bytes: *Char = strBytes(this)
    val subBytes: *Char = strBytes(sub)
    var i: Int = memFindByte(bytes, from, len - from, subBytes[0])
    while (i >= 0 && i + needle <= len) {
        if (needle == 1 || memCompare(bytes, i, subBytes, 0, needle) == 0) {
            return i
        }
        i = memFindByte(bytes, i + 1, len - i - 1, subBytes[0])
    }
    return 0 - 1
}

// `find` returns -1 when `sub` is absent (the language's spelling of npos).
fun Str.find(sub: Str): Int {
    return this.findFrom(sub, 0)
}

// `indexOf` is the other spelling of `find`.
fun Str.indexOf(sub: Str): Int {
    return this.findFrom(sub, 0)
}

// The last index of `sub`, or -1; an empty `sub` is found at the end, as `rfind` answers.
fun Str.lastIndexOf(sub: Str): Int {
    val len: Int = this.size()
    val needle: Int = sub.size()
    if (needle == 0) {
        return len
    }
    if (needle > len) {
        return 0 - 1
    }
    val bytes: *Char = strBytes(this)
    val subBytes: *Char = strBytes(sub)
    var i: Int = len - needle
    while (i >= 0) {
        if (bytes[i] == subBytes[0]
            && (needle == 1 || memCompare(bytes, i, subBytes, 0, needle) == 0)
        ) {
            return i
        }
        i = i - 1
    }
    return 0 - 1
}

// `Str.substr(start, len)` clamps `start` to [0, size]; a `len` of -1 (or one running
// past the end) takes the rest. One block copy (`setBytes`), not a per-byte append.
fun Str.substr(start: Int, len: Int): Str {
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
fun Str.startsWith(prefix: Str): Bool {
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

fun Str.endsWith(suffix: Str): Bool {
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
fun Str.trim(): Str {
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
fun Str.toUpper(): Str {
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

fun Str.toLower(): Str {
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

// Whole-string parse of a signed 32-bit integer; a malformed string (or one with a trailing
// remainder, or a value outside the range) yields `Opt.none()`, not an exception. The
// semantics are `std::from_chars`'s, which this replaces: an optional `-` and nothing else
// in the way of a sign (a leading `+` fails), no whitespace skipped, at least one digit,
// the *whole* string consumed, and overflow is a refusal. The digits accumulate *negated*
// in an `Int64`, so `-2147483648` forms without passing through `+2147483648`, and the
// running value is clamped the moment it can no longer come back inside the range.
fun Str.toInt(): Opt<Int> {
    val len: Int = this.size()
    if (len == 0) {
        return simse_optNone<Int>()
    }
    val bytes: *Char = strBytes(this)
    var i: Int = 0
    var negative: Bool = false
    if (bytes[0] == '-') {
        negative = true
        i = 1
    }
    if (i == len) {
        return simse_optNone<Int>()
    }
    var value: Int64 = 0
    var overflow: Bool = false
    while (i < len) {
        val ch: Char = bytes[i]
        if (ch < '0' || ch > '9') {
            return simse_optNone<Int>()
        }
        if (!overflow) {
            if (value < 0 - 214748364) {
                overflow = true
            } else {
                val digit: Int = ch - '0'
                value = value * 10 - digit
            }
        }
        i = i + 1
    }
    if (overflow) {
        return simse_optNone<Int>()
    }
    if (!negative && value < 0 - 2147483647) {
        return simse_optNone<Int>()
    }
    if (negative) {
        val small: Int = value
        return simse_optSome<Int>(small)
    }
    val big: Int = 0 - value
    return simse_optSome<Int>(big)
}

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

// The decimal spelling of a signed 64-bit value, `INT64_MIN` included, written in the
// language: the digits are peeled *negatively* (`rest % 10` of a negative is negative, and
// division truncates toward zero), so the negation never has to cross `+9223372036854775807`
// and the most negative value needs no special case. The digits come out least-significant
// first, so they append into a scratch string and the result reads back off its end.
//
// A plain function rather than an extension on purpose: a body is emitted when its *name* is
// reached, and an extension must additionally have its receiver's type named somewhere -
// which a program printing a scalar it never spelled does not do (src/codegen/CgProtocol.kt
// carries the protocol-shaped half of that rule). The `toString` bodies below are the one
// caller group, and every width widens at the call.
fun intToStr(value: Int64): Str {
    var rest: Int64 = value
    if (rest > 0) {
        rest = 0 - rest
    }
    var reversed: Str = ""
    if (rest == 0) {
        reversed.append('0')
    }
    while (rest < 0) {
        val digit: Int = 0 - (rest % 10)
        val ch: Char = '0' + digit
        reversed.append(ch)
        rest = rest / 10
    }
    var out: Str = ""
    out.reserve(reversed.size() + 1)
    if (value < 0) {
        out.append('-')
    }
    var i: Int = reversed.size() - 1
    while (i >= 0) {
        out.append(reversed.charAt(i))
        i = i - 1
    }
    return out
}

fun Int.toString(): Str {
    return intToStr(this)
}

fun Int16.toString(): Str {
    return intToStr(this)
}

fun Int64.toString(): Str {
    return intToStr(this)
}

// A `Char` stringifies as its *numeric* value (the byte, signed): `'A'` is `"65"`.
fun Char.toString(): Str {
    val value: Int64 = this
    return intToStr(value)
}

// The two alias spellings a body cannot declare (`Int8` ≡ `Char` and `Int32` ≡ `Int` are one
// C++ type each, where two bodies would define one function twice): natives over the shared
// template, whose numeric output is the `intToStr` body's, byte for byte.
@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int8): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Int32): Str

// The floats keep `std::to_string` of themselves: a different format (`%f`-style), which is
// what the corpus pins.
@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Float32): Str

@SmGen("res", "strops", "simse_num_toString")
borrow fun toString(this: Float64): Str

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
