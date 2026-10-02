package fixtures

// A callable argument to a *generic* function: the declared signature's callable parameter
// mentions the function's own type parameter, which MSVC cannot deduce through a
// `std::function` when the argument is a lambda or a named function. The emitter gives such
// a function a forwarding overload that takes the callable as its own template parameter and
// converts it (`emitDeducedCallableOverload`), and the const-params pass refuses to fold a
// parameter whose type names a type parameter - that parameter is how the binding survives.

typealias IntFn = (Int) -> Int

fun plusOne(x: Int): Int {
    return x + 1
}

fun twice<T>(f: (T) -> T, value: T): T {
    return f(f(value))
}

fun combine<T>(f: (T) -> T, g: (T) -> T, value: T): T {
    return f(g(value))
}

fun List<T>.mapAll<T>(f: (T) -> T): List<T> {
    var out: List<T> = List<T>()
    for (value in this) {
        out.append(f(value))
    }
    return out
}

fun main(): Int {
    // A lambda: the forwarding overload.
    val lambda: Int = twice((x: Int) -> x+1, 5)
    println(lambda.toString())

    // A string-typed value, then a *literal*: the compiler materialises a `Str` where C++
    // would deduce `StrView` from the pool entry (`CgCall.call`).
    val hi: Str = "hi"
    val bang: Str = twice((x: Str) -> x+"!", hi)
    println(bang)
    val literal: Str = twice((x: Str) -> x+"!", "hi")
    println(literal)

    // A named function as the argument.
    val named: Int = twice(plusOne, 5)
    println(named.toString())

    // A `Func<...>` value: the declared signature already takes it.
    val fn: IntFn = (x: Int) -> x+2
    val boxed: Int = twice(fn, 5)
    println(boxed.toString())

    // Two callable parameters, mixed shapes.
    val two: Int = combine((x: Int) -> x+1, (x: Int) -> x * 2, 5)
    println(two.toString())
    val mixed: Int = combine(plusOne, fn, 5)
    println(mixed.toString())

    // A generic extension: the receiver is the first argument of the forwarding call.
    val items: List<Int> = listOf<Int>(1, 2, 3)
    val doubled: List<Int> = items.mapAll((v: Int) -> v * 2)
    println(doubled.size().toString())
    println(doubled[0].toString())
    println(doubled[2].toString())

    // A lambda's *omitted* parameter type, from the argument that fixes `T`.
    val inferred: Int = twice((x) -> x+1, 7)
    println(inferred.toString())
    val unannotated: List<Int> = items.mapAll((v) -> v+10)
    println(unannotated.size().toString())
    println(unannotated[0].toString())

    // A generic call's result used directly as a receiver: the compiler binds `T` from `4`
    // (`functionReturn`), so `toString` is the `Int` one and not an arbitrary overload.
    println(twice((x: Int) -> x * 3, 4).toString())

    // A generic call as an argument to another: `id(7)` is an `Int` too.
    val nested: Int = twice((x: Int) -> x+1, identity(id(7)))
    println(nested.toString())
    return 0
}

fun identity<T>(value: T): T {
    return value
}

fun id(value: Int): Int {
    return value
}
