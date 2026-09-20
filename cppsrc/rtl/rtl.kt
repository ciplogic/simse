// rtl.kt
//
// The bootstrap RTL surface: declarations the transpiler loads as a prelude into
// every program's module scope, so programs can call these operations without an
// explicit import. Most bodies are hand-written C++ elsewhere - a header for the type
// core and the platform, the `_res.md` sections beside this file for everything else
// (`@SmGen("res", section, symbol)`); a prelude declaration without a body is resolved
// but never emitted (impl_specs/native-interop.md),
// and a prelude function *with* a body is emitted into the amalgamation as any
// other function (`smToYield` below is the first one - impl_specs/for.md).
//
// The explicit `this` parameter makes a declaration an extension on its
// receiver's type, and the attribute names the C++ implementation
// (`@SmGen("cpp", symbol)`, which `native(symbol)` is sugar for);
// a function with a *body* writes the receiver type before the name instead
// (`fun Str.isEmpty()`), which is the form the parser marks a receiver - see
// `impl_specs/rtl-abi.md`, "The receiver spelling differs from a native's", and
// `impl_specs/generators.md` for where the C++ of a body-less one lives.

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
    val len = this.size();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

fun Array<T>.smToYieldPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.count();
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

fun Span<T>.smToYieldPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
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
//
// These are the C++ the *resource* holds now (`cppsrc/rtl/_res.md`, the `listops`
// section, impl_specs/generators.md): the declarations moved out of `listops.hpp`, so
// what a reader sees here is the language's surface and the resource is the one place its
// C++ lives. The declarations stay the same shape; the symbol each one reaches is the
// attribute's second argument.

@SmGen("res", "listops", "simse_list_append")
fun append<T>(this: List<T>, value: T): Unit

// The list literal: `listOf<Str>("a", "b")` is one `Pack` instruction - the list built
// from those values, in that order - and the compiler never emits a call for it. The
// declaration is what gives the call a signature to be checked against (a `*List<T>`
// parameter is what makes the trailing arguments pack), and the symbol is the fallback
// a position with no destination slot still reaches. `List<T>(...)` keeps the RTL's own
// construction: a count (`List<T>(n)`, `List<T>(n, value)`), which is why a literal is
// this function and not the type's name (specs/containers.md).
@SmGen("res", "listops", "simse_listOf")
fun listOf<T>(values: *List<T>): List<T>

@SmGen("res", "listops", "simse_list_removeAt")
fun removeAt<T>(this: List<T>, index: Int): Unit

@SmGen("res", "listops", "simse_list_removeRange")
fun removeRange<T>(this: List<T>, start: Int, end: Int): Unit

@SmGen("res", "dictops", "simse_list_contains")
fun contains<T>(this: List<T>, value: T): Bool

// In-place sort; the comparator is a `(T, T) -> Bool` lambda (std::sort).
@SmGen("res", "dictops", "simse_list_sort")
fun sort<T>(this: List<T>, less: (T, T) -> Bool): Unit

// ---- Array<T> -------------------------------------------------------------

// `Array<T>` is a fixed-length, reference-counted block of elements: one
// allocation holds the element count first, then the elements
// (specs/built-in-types.md). `arrayEmpty<T>()` is the shared empty array of `T`,
// so an empty array allocates nothing; `toArray`/`toList` convert between the
// fixed and the growable sequence, which is also how an array "grows": an array
// cannot be appended to, a list can.
@SmGen("res", "listops", "simse_arrayEmpty")
fun arrayEmpty<T>(): Array<T>

@SmGen("res", "listops", "simse_array_count")
fun count<T>(this: Array<T>): Int

@SmGen("res", "listops", "simse_list_toArray")
fun toArray<T>(this: List<T>): Array<T>

@SmGen("res", "listops", "simse_array_toList")
fun toList<T>(this: Array<T>): List<T>

// ---- Dictionary<K, V> -----------------------------------------------------

// `Dictionary<K, V>` is a value type (the RTL's own `SmDictionary`); these are the
// operations the language exposes on it. Keys/values are returned in the dictionary's
// iteration order, which is unspecified: sort for determinism. Each declaration's C++ is
// the `dictops` section of `cppsrc/rtl/_res.md`.
@SmGen("res", "dictops", "simse_dictionaryOf")
fun dictionaryOf<K, V>(): Dictionary<K, V>

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

// ---- Str ------------------------------------------------------------------

// Scalar/string conversions. `Str` is a byte string, so `append` takes a Char.
@SmGen("res", "listops", "simse_str_append")
fun append(this: Str, value: Char): Unit

// In-place append of a whole Str. `out = out + text` copies the accumulated
// buffer every time, so emitters use this instead.
@SmGen("res", "listops", "simse_str_appendStr")
fun appendStr(this: Str, value: Str): Unit

// The same append for a text the caller only *borrows* (`*Str`): what is appended
// is the borrow's own pointee, nothing is copied on the way - which is what makes
// a join of a list's elements a run of appends with no element copied.
@SmGen("res", "listops", "simse_str_appendStrPtr")
fun appendStrPtr(this: Str, value: *Str): Unit

// The format text with each `|` replaced, in order, by one item: how an emitter
// writes a fixed shape (`"(", ")"`, `"<|::|>"`) without building a temporary per
// `+`. The shape is one item per `|`, and the text is written once into a reserved
// buffer; a call whose points and items do not line up - or that passes no item list -
// gets the format back, unfilled, rather than a half-filled result.
//
// The body is the language's own (`impl_specs/rtl-abi.md`): `charAt`, `append`,
// `appendStr` and `reserve` are the primitives it is written over, so nothing about
// the formatting is C++ any more.
fun fmtStr(fmt: *Str, items: *List<Str>): Str {
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

// Pre-allocates the buffer for a run of `append`/`appendStr` calls: the text is
// then written once, instead of the accumulated prefix being copied at every
// growth step. A *hint*, not a length - the string keeps its size, and a longer
// run grows it as usual.
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

// `isEmpty` is the language's own body, not C++ (impl_specs/rtl-abi.md): `size()` is the
// built-in it needs, so nothing here is a `@SmGen` declaration. Note the *receiver
// spelling*: a generated declaration writes its receiver as the explicit first parameter
// (`this: Str`), but a
// function *with* a body has to write the receiver type before the name - that form is
// what marks it an extension, and only the receiver-type form is resolved at a member
// call (the explicit `this` is a plain function whose first parameter is named `this`).
// It also drops the read-back a generated declaration's value receiver costs: the emitted
// parameter is `Str* self` either way, so `xmlAttr(...).isEmpty()` no longer spells `(*ptr)`.
fun Str.isEmpty(): Bool {
    return this.size() == 0
}

// Whole-string parses; a malformed string yields `Opt.none()` (no exceptions).
@SmGen("res", "strops", "simse_str_toInt")
fun toInt(this: Str): Opt<Int>

@SmGen("res", "strops", "simse_str_toFloat")
fun toFloat(this: Str): Opt<Float64>

// ---- Char -----------------------------------------------------------------

@SmGen("res", "strops", "simse_char_isDigit")
fun isDigit(this: Char): Bool

@SmGen("res", "strops", "simse_char_isAlpha")
fun isAlpha(this: Char): Bool

@SmGen("res", "strops", "simse_char_isAlphaOrDigit")
fun isAlphaOrDigit(this: Char): Bool

@SmGen("res", "strops", "simse_char_isSpace")
fun isSpace(this: Char): Bool

// ---- numeric / boolean toString -------------------------------------------

@SmGen("res", "listops", "simse_int_toString")
fun toString(this: Int): Str

// The generic numeric conversion is one template in the `strops` section
// (`simse_num_toString`); the scalar overloads all reach it.
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

// ---- min / max ------------------------------------------------------------

// The smaller/larger of two values: `<` on the type is all the body needs, so both are
// one generic the language itself writes (reified per instantiation, like any generic
// function) rather than a native per type.
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

// ---- time -----------------------------------------------------------------

// Milliseconds since an arbitrary fixed point, monotonic (it never goes backwards),
// for logging and for measuring a run. `Int64` because the value is large.
@SmGen("res", "timeops", "simse_nowMillis")
fun nowMillis(): Int64

// The same clock in microseconds: what the instrumented profiler measures with
// (`cppsrc/profiling/Profiling.kt` writes a `profileApp.measure(...)` per emitted body
// into a `--profile` build, and its destructor banks the difference here).
@SmGen("res", "timeops", "simse_nowMicros")
fun nowMicros(): Int64
