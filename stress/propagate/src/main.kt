package fixtures

// `!!` (cppsrc/parser/Propagate.kt): the payload of a `Res`, or its failure propagated out of
// the enclosing function. Both failure paths are covered: `doubled` returns the same `Res`
// type its operand has (so the union is moved), and `describe` returns a different one (the
// message is carried into a rebuilt `Res<Str>` - nothing has to be remapped, because a
// `Res`'s error arm is always a `Str`).

fun readNumber(text: Str): Res<Int> {
    val parsed: Opt<Int> = text.toInt()
    if (!parsed.hasValue()) {
        return Res<Int>.err("not a number: " + text)
    }
    return Res<Int>.ok(parsed.value())
}

fun doubled(text: Str): Res<Int> {
    val first: Res<Int> = readNumber(text)
    val value: Int = first!!
    return Res<Int>.ok(value + value)
}

fun describe(text: Str): Res<Str> {
    val first: Res<Int> = readNumber(text)
    val value: Int = first!!
    return Res<Str>.ok("value=" + value.toString())
}

// Two propagations in one body: the second reads its payload, and the failure of either
// leaves through the same `return`.
fun chained(text: Str): Res<Int> {
    val first: Res<Int> = readNumber(text)
    val value: Int = first!!
    val again: Res<Int> = doubled(value.toString())
    val total: Int = again!!
    return Res<Int>.ok(total)
}

fun main(): Int {
    println(doubled("21").value.toString())
    println(describe("7").value)

    val bad: Res<Int> = doubled("abc")
    println(bad.isOk())
    println(bad.error)

    val badText: Res<Str> = describe("abc")
    println(badText.isOk())
    println(badText.error)

    println(chained("5").value.toString())

    val badChain: Res<Int> = chained("xyz")
    println(badChain.isOk())
    println(badChain.error)
    return 0
}
