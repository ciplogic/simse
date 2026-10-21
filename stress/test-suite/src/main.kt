package app

// The `--tests` build (specs/testing.md): the marked functions in the compiled modules
// become a generated suite whose main replaces this program's own - the line below is
// what must NOT print. The suite calls the tests in module (canonical-path) then
// declaration order and prints a `running <package>.<name>` line ahead of each.

fun twice(value: Int): Int {
    return value + value
}

fun main(): Int {
    println("the program's own main never runs in a --tests build")
    return 0
}
