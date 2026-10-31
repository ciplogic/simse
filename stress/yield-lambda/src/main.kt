package yieldlambda

// A lambda in a yielding body is a machine field typed with its closure class, and the
// class is defined on the fly, right before the machine struct that holds it
// (impl_specs/yield.md, "A lambda as a machine field"): `yldStampClosures` names each
// lambda's class before the rewrite (`<machineClass>_closure<n>`), the field spells it,
// the construction copies the machine fields it captures, and a call through the field
// goes to the free `<symbol>_invoke`. Before the stamp the local had no type a field
// could name, and the lowering refused it.

fun half(n: Int): Int {
    return n / 2
}

// Declared before the loop; called across a yield.
fun feed(limit: Int): ..Int {
    val f = (n: Int) -> half(n)
    var i: Int = 0
    while (i < limit) {
        yield f(i)
        i = i + 1
    }
}

// Declared inside the loop; captures a machine parameter and is rebuilt per iteration.
fun tally(base: Int, limit: Int): ..Int {
    var i: Int = 0
    while (i < limit) {
        val add = (n: Int) -> base + n
        yield add(i)
        i = i + 1
    }
}

// A generic machine: the closure is a template over the machine's own parameters, and the
// field inside the machine struct carries them (`..._closure1<T>`).
fun List<T>.firstOf<T>(): ..T {
    var i: Int = 0
    val choose = (x: T) -> x
    while (i < this.size()) {
        yield choose(this[i])
        i = i + 1
    }
}

fun main(): Int {
    for (v in feed(4)) {
        println(v.toString())
    }
    for (v in tally(100, 3)) {
        println(v.toString())
    }
    var xs: List<Int> = List<Int>(0)
    xs.append(7)
    xs.append(9)
    for (v in xs.firstOf()) {
        println(v.toString())
    }
    return 0
}
