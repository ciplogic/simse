package app

// A `ref class` has no value form (specs/declarations.md): it is built as `&C(...)` and held
// by `&C` or `*C`, so a construction anywhere else builds a value, and that is the error.
// This is the tree/destructor guarantee: a `Node` child cannot be a value (infinite size) and
// a resource-owning class cannot be copied into a second owner.

ref class Node(var value: Int)

fun main(): Int {
    val root: Node = Node(1)
    println(root.value.toString())
    return 0
}
