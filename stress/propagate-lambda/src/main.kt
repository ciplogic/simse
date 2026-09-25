package fixtures

// `!!` inside a lambda: a lambda has no declared return type, so the failure propagates into the
// result type its *parameter* names - the `ReturnType` of the function type the lambda is passed
// to (cppsrc/parser/Propagate.kt). The enclosing function's own return type is irrelevant here,
// which is why `main` below does not return a `Res`.

fun readNumber(text: Str): Res<Int> {
    val parsed: Opt<Int> = text.toInt()
    if (!parsed.hasValue()) {
        return Res<Int>.err("not a number: " + text)
    }
    return Res<Int>.ok(parsed.value())
}

// The parameter's function type is the lambda's contract: `(Str) -> Res<Int>`.
fun applyOne(text: Str, f: (Str) -> Res<Int>): Res<Int> {
    return f(text)
}

// The same thing through a differently-shaped lambda result: `(Int) -> Res<Str>`, so the
// propagated `Res<Int>` message has to be carried into a `Res<Str>` (the remap path).
fun describe(number: Int, f: (Int) -> Res<Str>): Res<Str> {
    return f(number)
}

fun main(): Int {
    val good: Res<Int> = applyOne("21", (text: Str) -> {
        val value: Int = readNumber(text)!!
        return Res<Int>.ok(value + value)
    })
    println(good.isOk())
    println(good.value.toString())

    val bad: Res<Int> = applyOne("abc", (text: Str) -> {
        val value: Int = readNumber(text)!!
        return Res<Int>.ok(value + value)
    })
    println(bad.isOk())
    println(bad.error)

    val renamed: Res<Str> = describe(7, (number: Int) -> {
        val value: Int = readNumber(number.toString())!!
        return Res<Str>.ok("value=" + value.toString())
    })
    println(renamed.value)

    val failed: Res<Str> = describe(7, (number: Int) -> {
        val value: Int = readNumber("nope")!!
        return Res<Str>.ok("value=" + value.toString())
    })
    println(failed.isOk())
    println(failed.error)
    return 0
}
