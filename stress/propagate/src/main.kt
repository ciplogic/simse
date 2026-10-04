package fixtures

// `!!` (src/parser/Propagate.kt): the payload of a `Res`, or its failure propagated out of
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

// A `return (...)` in a function that also propagates: the parenthesized form constructs the
// return type (`Res<T>` declares `initByValue`, so `return (x)` is `Res<T>.ok(x)`), and the
// `!!` failure path stands beside it.
fun passthrough(text: Str): Res<Int> {
    val first: Res<Int> = readNumber(text)
    val value: Int = first!!
    return (value + 1)
}

// `!!` inside an `if` body: the region is a container of statements, and the expansion steps
// through it. Before, the container was read as one statement, so a `!!` in a control-flow
// body was reported "must be the whole right-hand side" instead of expanded.
fun pick(text: Str, fallback: Int): Res<Int> {
    val parsed: Res<Int> = readNumber(text)
    if (fallback > 0) {
        val value: Int = parsed!!
        return Res<Int>.ok(value * 2)
    }
    return Res<Int>.ok(fallback)
}

// The same under a `while` and a nested `if`: a local declared *inside* the body is in scope
// for the operand's type, which is why the local walk steps through the regions too.
fun sumAll(texts: List<Str>): Res<Int> {
    var total: Int = 0
    var i: Int = 0
    while (i < texts.size()) {
        val item: Str = texts[i]
        if (item != "skip") {
            val count: Res<Int> = readNumber(item)
            val value: Int = count!!
            total = total + value
        }
        i = i + 1
    }
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

    println(passthrough("9").value.toString())
    val badPass: Res<Int> = passthrough("no")
    println(badPass.isOk())
    println(badPass.error)

    println(pick("5", 1).value.toString())
    val badPick: Res<Int> = pick("no", 1)
    println(badPick.isOk())
    println(badPick.error)

    var texts: List<Str> = List<Str>()
    texts.append("1")
    texts.append("skip")
    texts.append("3")
    println(sumAll(texts).value.toString())
    var badTexts: List<Str> = List<Str>()
    badTexts.append("1")
    badTexts.append("x")
    val badSum: Res<Int> = sumAll(badTexts)
    println(badSum.isOk())
    println(badSum.error)
    return 0
}
