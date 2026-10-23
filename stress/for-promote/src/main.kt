package fixtures

// The `for` promotion (impl_specs/for.md, "iter"): a `for (x in c)` over a deep element (a
// `Str`, or a data class holding one) becomes the pointer wrap `iter` when the body only
// reads `x` - the element's place instead of a copy (and, for a `Str`, an allocation) per
// iteration. Scalar conversions are reads the proof holds through: a built-in conversion
// (`Int64(x)`) and the uniform `castAs<T>(x)` compute a value from what they are given and
// write through nothing, so a numeric scan like the strlist benchmark's still promotes.
// The emission golden pins the wrap (`iter(`) and the place binding (`Str*`).

fun partPromoted(words: List<Str>): Int64 {
    var total: Int64 = 0
    for (w in words) {
        total += Int64(w.size()) + castAs<Int64>(w.size() - 1)
    }
    return total
}

// A body that takes the element's address (`&w`) keeps the copying value form: the escape
// is exactly what the read-only proof exists to refuse, so the wrap stays `iterValues`.
fun partEscaped(words: List<Str>): Int {
    var total: Int = 0
    for (w in words) {
        total += firstByte(&w)
    }
    return total
}

fun firstByte(w: *Str): Int {
    if (w.size() == 0) {
        return 0
    }
    return Int(w[0]) & 255
}

fun main(): Int {
    var words: List<Str> = List<Str>(0)
    words.append("alpha")
    words.append("be")
    words.append("gamma!")
    println(partPromoted(words))
    println(partEscaped(words))
    return 0
}
