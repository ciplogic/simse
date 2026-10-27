package overloadshadow

// A program's own function and an imported same-name extension coexist (limitations.md's
// `attr` shape): both emit as C++ overloads of one symbol, and each call resolves by its
// own shape - the two-argument free call to the program's function, the receiver call to
// the extension - instead of the extension capturing the free call.

data class Node(var text: Str)

fun Node.attr(name: Str): Str {
    return this.text + "." + name
}

fun attr(name: Str, value: Str): Str {
    return name + "=" + value
}

fun main(): Int {
    println(attr("a", "b"))
    val n: Node = Node("node")
    println(n.attr("x"))
    return 0
}
