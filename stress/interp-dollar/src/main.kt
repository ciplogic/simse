package interpdollar

// `$name` and `${expr}` in an ordinary quoted string - Kotlin's spelling, so a copied
// Kotlin string works as it stands. The desugar (ParserInterp.kt) is the same
// `fmtStrWith` call a backtick `@name` becomes, with the separator chosen per string from
// bytes the text does not contain (`@` first, `|`, `~`, `^`, `%` next) because a quoted
// string may hold a literal `@` - an email address - where a raw string may not. `\$` is
// a literal dollar, and a `$` that starts no item is one as written.

fun main(): Int {
    val n: Int = 5
    val name: Str = "simse"

    // Names, several, and on both sides of a literal.
    println("n is $n")
    println("n=$n name=$name!")
    val full: Str = "$name has $n letters? no"
    println(full)

    // `${expr}`: one expression, any the language takes. (A string literal is not one of
    // them - the string token ends at the first quote, so an item cannot hold one; bind it
    // to a name outside.)
    println("twice is ${n + n}")
    println("sum: ${1 + 2 + 3}")
    println("size ${name.size()} chars")
    println("sized ${name.size() + n} total")
    var range: Str = ""
    for (i in 1..3) {
        range = range + "${i * i},"
    }
    println(range)

    // A literal `@` (and `|`) only moves the separator choice down the list.
    val email: Str = "user@host $n"
    println(email)
    val both: Str = "a@b $n c|d"
    println(both)

    // `\$` is the escaped literal dollar; a `$` that starts no item is one as written.
    val lit: Str = "cost \$5 only"
    println(lit)
    val lone: Str = "100% done, $ and $ done"
    println(lone)

    // An escape is never a marker: `\x40` is one byte of output (`@`), not a separator.
    val esc: Str = "esc \x40 sep | tail ${n * 2}"
    println(esc)

    // A template with escapes fuses like any other (the split is on the source's own
    // separator bytes, the pieces re-pooled as written), so a tab and an item still build
    // one buffer.
    val tab: Str = "x\ty $n"
    println(tab)
    val hand: Str = fmtStrWith('@', "hand @ tab\t@", listOf<Str>("a", "b"))
    println(hand)
    return 0
}
