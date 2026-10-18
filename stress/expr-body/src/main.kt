package exprbody

// `fun f(): T = expr` (Kotlin's expression body): the block `return expr` is, desugared in
// the parser, so every downstream stage sees the function it already knew. The return type
// must be spelled - a named function's type is its declaration; a lambda's result is the
// only one the language infers.

fun five(): Int = 5

fun twiceValue(n: Int): Int = n * 2

fun greet(name: Str): Str = "hello $name"

// The `data` (pure) mark and an expression body are independent.
data fun square(n: Int): Int = n * n

// A method in a class body, and an extension.
data class Pt(var x: Int, var y: Int) {
    fun shifted(d: Int): Pt = Pt(this.x + d, this.y + d)
}

fun Str.shouted(): Str = this + "!"

// A `Unit` answer: the expression is the call.
fun announce(): Unit = println("announced")

fun main(): Int {
    println(five())
    println(twiceValue(21))
    println(greet("simse"))
    println(square(6))
    val p: Pt = Pt(1, 2).shifted(10)
    println(p.x + p.y)
    println("hey".shouted())

    // The body is a `return`, so a `Unit`-returning one is the call as it stands.
    announce()
    return 0
}
