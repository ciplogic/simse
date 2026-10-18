package trailinglambda

// ---- call-forms ----

// A trailing `{ ... }` is the call's last argument, so a multi-line lambda body needs no
// arrow line: `f(x) { body }` is `f(x, { body })`, and with no other argument the braces
// open the call itself (`twice { 41 }`). The parameter list inside the braces is
// tentative - names then `->` - so `{ f(a) }` is a body, not a parameter named `f`.

fun apply(n: Int, f: (Int) -> Int): Int { return f(n) }
fun twice(f: () -> Int): Int { return f() + f() }
fun combine(a: Int, b: Int, f: (Int, Int) -> Int): Int { return f(a, b) }

fun partCallForms(): Int {
    println(apply(5) { x -> x + 1 })
    println(apply(5) { x: Int -> x - 1 })
    println(twice { 41 })
    println(combine(3, 4) { a, b -> a * 10 + b })

    // A body of several statements, the multi-line form the trailing brace exists for.
    var seen: Int = 0
    apply(3) { x ->
        val doubled: Int = x + x
        seen = doubled
        doubled
    }
    println(seen)

    // The brace form after a generic instantiation is the same call.
    println(width<Str>("abc") { s -> s.size() })
    return 0
}

// A generic callee with a trailing block: the explicit instantiation fixes `T`, the way
// it does for a parenthesized lambda.
fun width<T>(value: T, f: (T) -> Int): Int {
    return f(value)
}

// ---- it ----

// With no declared parameter, a body that names `it` gets it as the single implicit
// parameter (Kotlin's rule); a nested lambda's `it` is its own, and a body that names
// nothing takes no parameter at all.

fun partIt(): Int {
    println(apply(5) { it * 2 })

    // `it` in a multi-statement body; the last statement is the value (the same rewrite a
    // single-expression body takes).
    apply(4) {
        val tripled: Int = it + it + it
        println(tripled)
        tripled
    }

    // The outer `it` is an Int, the inner one the list's element: each lambda's own.
    var names: List<Str> = listOf<Str>("a", "bb", "ccc")
    apply(2) {
        names.forEach { println(it) }
        it
    }

    // A zero-parameter body never names `it` (the callable is `() -> Int`).
    println(twice { 7 })
    return 0
}

// ---- unit-bodies ----

// A callable returning `Unit` takes the block whose last statement is not a value: a
// write, or a call returning nothing. That is the `repeat`/`forEach` shape.

var total: Int = 0

fun bump(value: Int): Unit {
    total = total + value
}

fun partUnitBodies(): Int {
    // `repeat(n) { it }` - the iteration index, the canonical trailing-lambda call.
    var out: Str = ""
    repeat(4) {
        out = out + it.toString()
    }
    println(out)

    // A `Unit` block ending in a named call.
    repeat(3) { bump(it + 1) }
    println(total)

    // `xs.forEach { it }`: each element in order, and the paren-less form for an array.
    val words: List<Str> = listOf<Str>("x", "yy")
    words.forEach { println(it) }
    var numbers: List<Int> = List<Int>()
    numbers.append(7)
    numbers.append(8)
    numbers.toArray().forEach { println(it) }

    // `forEach` with a named parameter, and a body that reads the captured value.
    var widths: Int = 0
    words.forEach { w -> widths = widths + w.size() }
    println(widths + total)
    return 0
}

fun main(): Int {
    partCallForms()
    partIt()
    partUnitBodies()
    return 0
}
