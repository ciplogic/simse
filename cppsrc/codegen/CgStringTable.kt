// CgStringTable.kt
//
// The program's string literals: one pool, one index, one spelling. The emitter's walk
// adds every literal it meets (`Codegen.kt`, `collectNames`); `sort` puts the pool in
// canonical order, so two rings that met the literals in a different order still agree on
// every index (impl_specs/rtl-abi.md, "String literals: one table", T52); and a literal
// *site* reads its entry as the owned `Str` it used to be handed (`spelling`).
//
// The entry is a `StrView` into the pool the emitter writes beside the index
// (`cppsrc/rtl/strtable.hpp`): 12 bytes and no start-up allocation, where the table used
// to hold a 32-byte owning `Str` each - that is the memory the pool and the index buy.
// Reading a comparison through the view instead of converting it is the next step; today
// every site asks for `toString()`, so the change is representation-only.
//
// The class owns the pooling and the lookup. Writing the pool and the index out stays the
// emitter's (`Emitter.emitStringTable`), because that is the one part that needs its
// output.
//
// The `Cg` prefix is not decoration: the driver scans a module root in path order and the
// amalgamated file defines its types in that order, so a data class that holds another
// *by value* needs its type's file to sort first - `Emitter` embeds this table, and
// `CgStringTable.kt` < `Codegen.kt` (`guide4ai.md`, "Gotchas").
//
// The split is the Kotlin ring's own, like `IlCodeGen.kt`: the hand-written ring keeps its
// string table inside `Codegen.cpp`, and the two agree byte for byte (T22's differential).
//
// It stays a `data class` for now: the language has `data class` and `enum class` only, and
// a ref-counted shape would want `&StringTable` (a counted handle) rather than a `class`
// declaration the parser does not have yet.

package codegen

// True for a hexadecimal digit. The two C++ escapes whose *length* is a run rather than a
// character (`\xHH...` and octal) are counted the way the C++ compiler counts them, so a
// literal outside the language's own escape set still lines up with the pool instead of
// shifting every literal after it (`cgLiteralByteLength`).
fun cgIsHexDigit(ch: Char): Bool {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')
}

// How many bytes a string literal's *source text* (quotes included) denotes: what the
// literal table's length index carries. The pool the emitter writes is the literal texts
// again, adjacent, so the C++ compiler decodes the bytes; this only has to agree with it
// about how many bytes each escape costs, and in a narrow literal every escape costs
// exactly one. The language's set is small (specs/built-in-types.md, `\n \r \t \0 \\ \'
// \"`), and the run forms above are counted so that an input outside it still lines up.
fun cgLiteralByteLength(text: Str): Int {
    if (text.size() < 2 || text[0] != '\"') {
        return text.size()
    }
    var count: Int = 0
    val end: Int = text.size() - 1
    var i: Int = 1
    while (i < end) {
        if (text[i] != '\\') {
            count = count + 1
            i = i + 1
            continue
        }
        i = i + 1
        if (i >= end) {
            break
        }
        val escape: Char = text[i]
        i = i + 1
        if (escape == 'x') {
            while (i < end && cgIsHexDigit(text[i])) {
                i = i + 1
            }
        } else if (escape >= '0' && escape <= '7') {
            var digits: Int = 1
            while (digits < 3 && i < end && text[i] >= '0' && text[i] <= '7') {
                i = i + 1
                digits = digits + 1
            }
        }
        count = count + 1
    }
    return count
}

// `{0,-142,40}`: the one-line form the emitter writes an index array in. The values are
// small by construction (`Emitter.emitStringTable`), so one line holds the whole array.
fun cgIntListText(values: List<Int>): Str {
    var text: Str = "{"
    var first: Bool = true
    for (value in values) {
        if (!first) {
            text.append(',')
        }
        text.appendStr(value.toString())
        first = false
    }
    text.append('}')
    return text
}

// The absolute value of one index number, for the width check `emitStringTable` makes.
fun cgMagnitudeOf(value: Int): Int {
    if (value < 0) {
        return 0 - value
    }
    return value
}

// Run-length encodes one index series into the stream `strtable.hpp` documents: the
// series' length, then alternating blocks of *non-repeating* values (a count, then the
// values) and of *runs* (a count, then that many `times, value` pairs), until the length
// is filled. A single value is written once, whichever block it lands in, so the encoding
// never costs more than a count per block - and the differences it is handed are mostly 0,
// which is what collapses.
fun cgRunLengthEncode(values: List<Int>): List<Int> {
    val count: Int = values.size()
    var stream: List<Int> = List<Int>()
    stream.append(count)
    var i: Int = 0
    while (i < count) {
        var literals: List<Int> = List<Int>()
        while (i < count && !(i + 1 < count && values[i] == values[i + 1])) {
            literals.append(values[i])
            i = i + 1
        }
        stream.append(literals.size())
        for (literal in literals) {
            stream.append(literal)
        }
        if (i >= count) {
            break
        }
        var runs: List<Int> = List<Int>()
        while (i + 1 < count && values[i] == values[i + 1]) {
            var j: Int = i
            while (j < count && values[j] == values[i]) {
                j = j + 1
            }
            runs.append(j - i)
            runs.append(values[i])
            i = j
        }
        stream.append(runs.size() / 2)
        for (value in runs) {
            stream.append(value)
        }
    }
    return stream
}

data class StringTable(
    // The pooled texts, in canonical order once `sort` has run.
    var entries: List<Str>,

    // Each text's index in `entries`. Provisional - every add records 0 - until `sort`
    // rebuilds it; only `spelling` and the emission read it, and both run after the sort.
    var indexAt: Dictionary<Str, Int>
) {

    // Pools `text` unless it is already there.
    fun add(text: Str): Unit {
        if (this.indexAt.has(text)) {
            return
        }
        this.indexAt.insert(text, 0)
        this.entries.append(text)
    }

    fun count(): Int {
        return this.entries.size()
    }

    fun entry(index: Int): Str {
        return this.entries[index]
    }

    // Canonical order, and the index rebuilt from it: an entry's index is its position, so
    // the table is the same whichever order the walk met the literals in. Longest first,
    // then alphabetical (`val` < `var`, but `vars` < `val`) - the order is the pool's own
    // layout, and it has to be the same in both rings. A `Str` is never its own length
    // *and* its own text, so the order is total and the non-stable sort is still
    // deterministic.
    fun sort(): Unit {
        this.entries.sort((left: Str, right: Str) -> (left.size() > right.size()) || ((left.size() == right.size()) && (left < right)))
        this.indexAt = Dictionary<Str, Int>()
        var i: Int = 0
        while (i < this.entries.size()) {
            this.indexAt.insert(this.entries[i], i)
            i = i + 1
        }
    }

    // The emitted spelling of `text`: its pool entry - a `StrView`, so a comparison or a
    // `+` reads it without building a `Str`, and an owned position converts it - when the
    // walk pooled it, and the literal itself when the *lowering* invented it (a literal
    // that is not in the parsed program was never pooled, and keeps its own spelling at the
    // site; impl_specs/rtl-abi.md).
    fun spelling(text: Str): Str {
        if (!this.indexAt.has(text)) {
            return text
        }
        return fmtStr("__sm_stringTable[|]", this.indexAt.get(text).value().toString())
    }
}
