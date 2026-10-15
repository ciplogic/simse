package app

// The base chain must be acyclic: A's layout would be B's fields then its own, and B's
// A's fields then its own. The checker reports the cycle at the declaration
// (specs/declarations.md, "Inheritance").

data class A(val x: Int) : B
data class B(val y: Int) : A

fun main(): Int {
    return 0
}
