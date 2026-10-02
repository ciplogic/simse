package fixtures

// A lambda is a data class with one field per capture, plus a free method
// `<symbol>_invoke(self, args)` that implements the call. `self` is the closure passed by
// copy, so a call sees the closure's own copies of the captures and a step inside reaches
// nothing outside.

typealias IntFn = (Int) -> Int
typealias Taker = (Int) -> Unit

fun apply(value: Int, f: IntFn): Int {
    return f(value)
}

fun makeAdder(factor: Int): IntFn {
    return (v: Int) -> v+factor
}

// The by-value `self` means a closure cannot mutate the receiver it was built in. The
// reference a caller occasionally wants is a *raw pointer*: `*this` is the receiver's
// address, the closure captures the pointer by value, and a write through it reaches the
// original (unchecked, like every `*T`; the object must outlive the closure).
data class Counter(var n: Int) {
    fun add(v: Int): Int {
        this.n = this.n + v
        return this.n
    }

    fun run(f: IntFn): Int {
        return f(1)
    }

    fun bumpThroughPointer(): Int {
        var rawThis: *Counter = *this
        val bump: IntFn = (v: Int) -> rawThis.add(v)
        return this.run(bump)
    }
}

fun main(): Int {
    // A declared local: the parameter types come from the callable type on the left.
    val plusOne: IntFn = (v) -> v+1
    println(plusOne(41).toString())

    // The same, passed where the parameter's callable type supplies the parameter type.
    println(apply(41, (v) -> v+1).toString())

    // A block body with two returns.
    val big: IntFn = (v: Int) -> {
        if (v > 0) {
            return v * 100
        }
        return 0
    }
    println(big(3).toString())
    println(big(-1).toString())

    // A block body whose single statement answers nothing: the call is a statement, the
    // lambda answers `Unit`.
    val show: Taker = (n: Int) -> {
        println("n=" + n.toString())
    }
    show(7)

    // A capture is the closure's own copy: a step inside it shows, and a second call copies
    // the closure again, so the step does not accumulate across calls (both print 3).
    var total: Int = 0
    val addSome: Taker = (by: Int) -> {
        total += by
        println(total.toString())
    }
    addSome(3)
    addSome(3)
    println(total.toString())

    // A lambda's body calling one of its captured function values.
    val twice: IntFn = (v: Int) -> plusOne(plusOne(v))
    println(twice(40).toString())

    // A nested lambda capturing the outer lambda's frame.
    val compose: IntFn = (v: Int) -> {
        val inner: IntFn = (w: Int) -> w+1
        return inner(v) * 2
    }
    println(compose(5).toString())

    // An escaping closure: the class carries the captures into the caller's frame.
    val add10: IntFn = makeAdder(10)
    println(add10(5).toString())
    println(add10(0).toString())

    // A closure in a `Func`-typed slot converts at the boundary and calls through it.
    val boxed: IntFn = (v: Int) -> v-1
    println(boxed(42).toString())

    // The owner as a raw pointer: the mutation through the closure stays on the object.
    val counter: Counter = Counter(10)
    println(counter.bumpThroughPointer().toString())
    println(counter.n.toString())
    return 0
}
