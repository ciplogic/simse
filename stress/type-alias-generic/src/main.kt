package aliasgeneric

// A generic `typealias` carries its arguments into its target (`Handler<T> = (T) -> Unit`,
// so `Handler<Str>` answers `(Str) -> Unit`; `Matrix<T> = List<List<T>>`, so `Matrix<Int>`
// answers `List<List<Int>>`): every position that resolves through an alias - a lambda's
// expected callable type, a member call's receiver, a call through a callable value -
// walks the same substitution now (`semResolveTypeAlias`), where each walker used to stop
// at the first alias that carried type arguments.

typealias Handler<T> = (T) -> Unit
typealias IntHandler = Handler<Int>
typealias Matrix<T> = List<List<T>>

fun run(h: IntHandler, n: Int): Unit {
    h(n)
}

fun main(): Int {
    // The aliased callable type supplies the lambda's contract through two alias steps.
    run((v: Int) -> println(v.toString()), 3)

    // A nested generic alias in a container position: the extension calls on it
    // (`append`, the index read) resolve through the substituted target.
    val m: Matrix<Int> = List<List<Int>>(0)
    var row: List<Int> = List<Int>(0)
    row.append(1)
    m.append(row)
    println(m[0][0].toString())

    // A callable value held in an aliased type, called through it.
    val g: Handler<Str> = (s: Str) -> println(s)
    g("alias world")
    return 0
}
