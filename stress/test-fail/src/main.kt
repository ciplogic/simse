package app

// The failing test (specs/testing.md): an abort is not a value - the suite dies where the
// assertion fails, the `running` line ahead of it is flushed to stdout by the fatal path
// (src/rtl/_res.md, `fatal`), and later tests never run.

fun main(): Int {
    println("the program's own main never runs in a --tests build")
    return 0
}
