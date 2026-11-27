package for_string_literal

// A `for` over a string *literal* iterates the literal's bytes. The checker rewrites the
// receiver to the span view it rewrites a named `Str` to (`spanForAt`), which needs the
// literal's type - and the checker's own typing (`Analyzer.iteratedType`) had an arm for an
// integer literal (`0 until n`) and none for a string one. So the rewrite skipped, the
// machine stayed a member `iterValues()` on the literal, and the template's out slot had no
// type: `internal: the slot '_sm_item1' has neither a type nor an initializer`, on a program
// nothing is wrong with. A name, a call and a list literal all worked; only this shape.

fun partValue(): Int {
    var text: Str = ""
    for (ch in "abc") {
        text = text + ch.toString()
    }
    println(text)

    // `continue` lands after the step, and an empty literal walks nothing.
    var kept: Str = ""
    for (ch in "aXbXc") {
        if (ch == 'X') {
            continue
        }
        kept = kept + ch.toString()
    }
    println(kept)
    var empty: Int = 0
    for (ch in "") {
        empty = empty + 1
    }
    println(empty.toString())

    // An escape in the literal is one byte.
    var n: Int = 0
    for (ch in "a\tb\n") {
        n = n + 1
    }
    println(n.toString())
    return 0
}

// The indexed form counts the bytes from 0.
fun partIndexed(): Int {
    var places: Str = ""
    for ((ch, at) in "hi!") {
        places = places + at.toString() + ch.toString() + " "
    }
    println(places)
    return 0
}

// The `*v` form binds each byte's place.
fun partPointer(): Int {
    var total: Int = 0
    for (*p in "abc") {
        total = total + p.toString().size()
    }
    println(total.toString())
    return 0
}

// A literal under a literal, and a literal under a list literal.
fun partNested(): Int {
    var count: Int = 0
    for (line in listOf("ab", "cde")) {
        for (ch in line) {
            count = count + 1
        }
    }
    println(count.toString())
    var once: Int = 0
    for (a in "xy") {
        for (b in "zw") {
            once = once + 1
        }
    }
    println(once.toString())
    var inner: Int = 0
    for (s in listOf("", "a")) {
        for (ch in s) {
            inner = inner + 10
        }
    }
    println(inner.toString())
    return 0
}

fun main(): Int {
    partValue()
    partIndexed()
    partPointer()
    partNested()
    return 0
}
