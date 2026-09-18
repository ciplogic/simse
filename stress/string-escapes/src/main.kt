package fixtures

// The program's string literals, escapes included. The emitter writes every literal into
// one pool and computes the length index beside it, and the program's own build decodes
// the pool (`cppsrc/rtl/strtable.hpp`); this pins the two agreeing for the shapes that
// differ - an escape that is two source characters and one byte (`\n`), one that is two
// and two (`\\n`), a quote that must not end the literal (`\"`), the empty literal, a
// literal repeated, and prefixes of each other. A length that disagreed would shift every
// literal after it, and the `static_assert` in the generated C++ would stop the build.
fun main(): Int {
    val newline: Str = "a\nb"
    val escaped: Str = "a\\nb"
    val quote: Str = "say \"hi\""
    val tab: Str = "\t"
    val empty: Str = ""
    val longText: Str = "a literal well past the twenty-three byte inline capacity of Str"
    println(newline.size())
    println(escaped.size())
    println(quote.size())
    println(tab.size())
    println(empty.size())
    println(quote)
    println(escaped)
    println(longText.size())
    println("a")
    println("ab")
    println("abc")
    return 0
}
