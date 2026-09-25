// CgStringTable.kt
//
// The program's string literals: one pool, one index, one spelling. The emitter's walk adds
// every literal it meets; `sort` puts the pool in canonical order (longest first, then
// alphabetical) and rebuilds the index from it, so the table is the same whichever order
// the walk met the literals in (impl_specs/rtl-abi.md).
//
// A literal *site* reads a `StrView` into the pool (`spelling`); the pool and the two index
// series are the emitter's to write (`Emitter.emitStringTable`).
//
// The name matters: a module root is scanned in path order and the amalgamation defines its
// types in that order, so a data class holding another *by value* needs its file to sort
// first - `Emitter` embeds this table, and `CgStringTable.kt` < `Codegen.kt`.

package codegen

// A hexadecimal digit. The two escapes whose length is a *run* rather than one character
// (`\xHH...` and octal) are counted the way the C++ compiler counts them.
// How many bytes a string literal's source text (quotes included) denotes. The decoder itself
// is `common`'s (`litByteLength`, common/literals.kt), because the parser's `when` lowering
// reads the same byte counts: one escape table, one answer.
fun cgLiteralByteLength(text: *Str): Int {
    return litByteLength(text)
}

// `{0,-142,40}`: the one-line form an index array is written in. The values are small by
// construction, so one line holds the array.
fun cgIntListText(values: *List<Int>): Str {
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

// The absolute value of one index number, for the width check.
fun cgMagnitudeOf(value: Int): Int {
    if (value < 0) {
        return 0 - value
    }
    return value
}

// Run-length encodes one index series: the series' length, then alternating blocks of
// *non-repeating* values (a count, then the values) and of *runs* (a count, then that many
// `times, value` pairs), until the length is filled. A single value is written once,
// whichever block it lands in, and the differences it is handed are mostly 0, which is what
// collapses.
fun cgRunLengthEncode(values: *List<Int>): List<Int> {
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

    // Each text's index in `entries`. Provisional until `sort` rebuilds it; only `spelling`
    // and the emission read it, and both run after the sort.
    var indexAt: Dictionary<Str, Int>
) {

    // Pools `text` unless it is already there.
    fun add(text: *Str): Unit {
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

    // Canonical order, and the index rebuilt from it: an entry's index is its position. A
    // `Str` is never its own length *and* its own text, so the order is total and the
    // non-stable sort is still deterministic.
    fun sort(): Unit {
        this.entries.sort((left: Str, right: Str) -> (left.size() > right.size())
        || ((left.size() == right.size()) && (left < right)))
        this.indexAt = Dictionary<Str, Int>()
        var i: Int = 0
        while (i < this.entries.size()) {
            this.indexAt.insert(this.entries[i], i)
            i = i + 1
        }
    }

    // The emitted spelling of `text`: its pool entry when the walk pooled it, and the
    // literal itself when the *lowering* invented it (a literal that is not in the parsed
    // program was never pooled).
    fun spelling(text: Str): Str {
        if (!this.indexAt.has(text)) {
            return text
        }
        return fmtStr("__sm_stringTable[|]", this.indexOf(text).toString())
    }

    // The pool index of `text`, or -1 when the walk never pooled it: the raw number, for a
    // writer that needs it rather than a site's spelling (`Emitter.emitResourceTable`).
    fun indexOf(text: *Str): Int {
        val index: *Int = this.indexAt.getPtr(text)
        if (index == null) {
            return -1
        }
        return * index
    }
}
