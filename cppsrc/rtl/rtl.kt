// rtl.kt
//
// The bootstrap RTL surface: declarations the transpiler loads as a prelude into
// every program's module scope, so programs can call these operations without an
// explicit import. Most bodies are hand-written C++ (cppsrc/rtl/listops.hpp,
// cppsrc/rtl/strops.hpp), linked into the generated program; prelude declarations
// without a body are resolved but never emitted (impl_specs/native-interop.md),
// and a prelude function *with* a body is emitted into the amalgamation as any
// other function (`smToYield` below is the first one - impl_specs/for.md).
//
// The explicit `this` parameter makes each declaration an extension on its
// receiver's type; the explicit symbol names the C++ implementation.

package rtl

// ---- iteration -------------------------------------------------------------

// A `for (x in c)` is `for (x in c.smToYield())`: anything with a `smToYield` in
// scope is iterable, and the loop is the `while` the parser writes around it
// (impl_specs/for.md). A container walks itself in order, and this is written in
// the language's own `yield` - so the machine is an ordinary one, reified per
// element type like any other generic function.
fun List<T>.smToYield<T>(): ..T {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + 1
    }
}

// The same walk over the fixed-length sequence and over borrowed storage: both index
// from `0` and count with the operation their type provides (`Array` counts with
// `count()`, a `Span` with `size()`). Per-container `smToYield`s are why a machine's
// class carries its receiver's name (`List_smToYield_yieldable`): the function name
// alone would name every container's machine the same way.
fun Array<T>.smToYield<T>(): ..T {
    var i: Int = 0
    while (i < this.count()) {
        yield this[i]
        i = i + 1
    }
}

fun Span<T>.smToYield<T>(): ..T {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + 1
    }
}

// The pointer form, `for (*x in c)`: the same walk, but it hands out the *place* of
// each element - `*this[i]`, the element's address - instead of a copy. That is the
// form for a container of aggregates: nothing is copied per iteration, and a mutation
// through the loop variable reaches the element in the container (impl_specs/for.md).
// It is a separate wrap rather than a parameter of `smToYield` because its element
// type is `*T`, which is what the machine hands out.
fun List<T>.smToYieldPtr<T>(): ..*T {
    var i: Int = 0
    while (i < this.size()) {
        yield * this[i]
        i = i + 1
    }
}

fun Array<T>.smToYieldPtr<T>(): ..*T {
    var i: Int = 0
    while (i < this.count()) {
        yield * this[i]
        i = i + 1
    }
}

fun Span<T>.smToYieldPtr<T>(): ..*T {
    var i: Int = 0
    while (i < this.size()) {
        yield * this[i]
        i = i + 1
    }
}

// A machine is already iterable: `x.smToYield()` on one *is* `x`, so `for (x in m)` and
// iterating `m` by hand in a `while` see exactly the same values, with no wrapper object
// and no extra step. That identity is the compiler's (`TypeInfer.kt` types the call as
// the receiver, `Codegen.kt` emits the receiver itself): `..T` is not a spellable type,
// so a function could not take one (impl_specs/for.md).

// ---- List<T> --------------------------------------------------------------

native("simse_list_append") fun append<T>(this: List<T>, value: T): Unit
native("simse_list_removeAt") fun removeAt<T>(this: List<T>, index: Int): Unit
native("simse_list_removeRange") fun removeRange<T>(this: List<T>, start: Int, end: Int): Unit
native("simse_list_contains") fun contains<T>(this: List<T>, value: T): Bool
// In-place sort; the comparator is a `(T, T) -> Bool` lambda (std::sort).
native("simse_list_sort") fun sort<T>(this: List<T>, less: (T, T) -> Bool): Unit

// ---- Array<T> -------------------------------------------------------------

// `Array<T>` is a fixed-length, reference-counted block of elements: one
// allocation holds the element count first, then the elements
// (specs/built-in-types.md). `arrayEmpty<T>()` is the shared empty array of `T`,
// so an empty array allocates nothing; `toArray`/`toList` convert between the
// fixed and the growable sequence, which is also how an array "grows": an array
// cannot be appended to, a list can.
native("simse_arrayEmpty") fun arrayEmpty<T>(): Array<T>
native("simse_array_count") fun count<T>(this: Array<T>): Int
native("simse_list_toArray") fun toArray<T>(this: List<T>): Array<T>
native("simse_array_toList") fun toList<T>(this: Array<T>): List<T>

// ---- Dictionary<K, V> -----------------------------------------------------

// `Dictionary<K, V>` is a value type (`std::unordered_map`); these are the
// operations the language exposes on it. Keys/values are returned in the
// dictionary's iteration order, which is unspecified: sort for determinism.
native("simse_dictionaryOf") fun dictionaryOf<K, V>(): Dictionary<K, V>
native("simse_dict_get") fun get<K, V>(this: Dictionary<K, V>, key: K): Opt<V>
native("simse_dict_has") fun has<K, V>(this: Dictionary<K, V>, key: K): Bool
native("simse_dict_insert") fun insert<K, V>(this: Dictionary<K, V>, key: K, value: V): Unit
native("simse_dict_remove") fun remove<K, V>(this: Dictionary<K, V>, key: K): Unit
native("simse_dict_size") fun size<K, V>(this: Dictionary<K, V>): Int
native("simse_dict_keys") fun keys<K, V>(this: Dictionary<K, V>): List<K>
native("simse_dict_values") fun values<K, V>(this: Dictionary<K, V>): List<V>
native("simse_dict_clear") fun clear<K, V>(this: Dictionary<K, V>): Unit

// ---- Str ------------------------------------------------------------------

// Scalar/string conversions. `Str` is a byte string, so `append` takes a Char.
native("simse_str_append") fun append(this: Str, value: Char): Unit

// In-place append of a whole Str. `out = out + text` copies the accumulated
// buffer every time, so emitters use this instead.
native("simse_str_appendStr") fun appendStr(this: Str, value: Str): Unit

// `find` returns -1 when `sub` is absent (the language's spelling of npos).
native("simse_str_find") fun find(this: Str, sub: Str): Int
native("simse_str_find") fun indexOf(this: Str, sub: Str): Int
native("simse_str_lastIndexOf") fun lastIndexOf(this: Str, sub: Str): Int
native("simse_str_substr") fun substr(this: Str, start: Int, len: Int): Str
native("simse_str_charAt") fun charAt(this: Str, index: Int): Char
native("simse_str_startsWith") fun startsWith(this: Str, prefix: Str): Bool
native("simse_str_endsWith") fun endsWith(this: Str, suffix: Str): Bool
native("simse_str_replace") fun replace(this: Str, from: Str, to: Str): Str
native("simse_str_trim") fun trim(this: Str): Str
native("simse_str_split") fun split(this: Str, separator: Str): List<Str>
native("simse_str_toUpper") fun toUpper(this: Str): Str
native("simse_str_toLower") fun toLower(this: Str): Str
native("simse_str_isEmpty") fun isEmpty(this: Str): Bool

// Whole-string parses; a malformed string yields `Opt.none()` (no exceptions).
native("simse_str_toInt") fun toInt(this: Str): Opt<Int>
native("simse_str_toFloat") fun toFloat(this: Str): Opt<Float64>

// ---- Char -----------------------------------------------------------------

native("simse_char_isDigit") fun isDigit(this: Char): Bool
native("simse_char_isAlpha") fun isAlpha(this: Char): Bool
native("simse_char_isAlphaOrDigit") fun isAlphaOrDigit(this: Char): Bool
native("simse_char_isSpace") fun isSpace(this: Char): Bool

// ---- numeric / boolean toString -------------------------------------------

native("simse_int_toString") fun toString(this: Int): Str
native("simse_num_toString") fun toString(this: Int8): Str
native("simse_num_toString") fun toString(this: Int16): Str
native("simse_num_toString") fun toString(this: Int32): Str
native("simse_num_toString") fun toString(this: Int64): Str
native("simse_num_toString") fun toString(this: Float32): Str
native("simse_num_toString") fun toString(this: Float64): Str
native("simse_char_toString") fun toString(this: Char): Str
native("simse_bool_toString") fun toString(this: Bool): Str

// ---- min / max ------------------------------------------------------------

native("simse_min") fun min(a: Int, b: Int): Int
native("simse_max") fun max(a: Int, b: Int): Int

// ---- time -----------------------------------------------------------------

// Milliseconds since an arbitrary fixed point, monotonic (it never goes backwards),
// for logging and for measuring a run. `Int64` because the value is large.
native("simse_nowMillis") fun nowMillis(): Int64
