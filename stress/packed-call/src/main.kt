package fixtures

// A packed call - a list-valued last parameter taking the arguments from its own position
// on, which is the language's list literal, `listOf("a", "b")` - has an element type, and
// it is the elements' own: the type pass (`semBindCallArgs`) and the checker
// (`semaPlainCallReturn`) each bind the list's element parameter from the types of the
// elements, when they agree.
//
// Neither used to. The literal's type was the type pass's to refuse, so everything that
// consumed one in the same statement went untyped - `auto` in the C++, five for a `for`
// over a literal - and that loop did not compile at all: the step it emitted was not a
// member of the machine's class. A name in between (`val xs = listOf(...)`; `for (x in
// xs)`) hid it, which is why the compiler's own tree never met it.

fun main(args: List<Str>): Unit {
    // A member call on the literal, and an index into one.
    println(listOf(1, 2, 3).size().toString())
    println(listOf("x", "y")[1])

    // A `for` over a literal: the machine, the loop variable and every read typed, the
    // deep element promoted to the pointer wrap like a named list's.
    var total: Int = 0
    for (w in listOf("ab", "cde")) {
        total = total + w.size()
    }
    println(total.toString())

    // A literal of literals, a `for` over it, and a `for` over its loop variable.
    var cells: Int = 0
    for (row in listOf(listOf(1, 2), listOf(3, 4, 5))) {
        for (v in row) {
            cells = cells + v
        }
    }
    println(cells.toString())
}
