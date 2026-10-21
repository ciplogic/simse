package fixtures

// The fatal-error path (specs/functions.md, "Fatal errors"; src/rtl/fatal.kt): `assert`
// passes silently, `panic` writes `panic: <message>` to stderr, flushes stdout, and
// aborts - nothing after it runs, no destructors, exit code 3 under MSVC. The stdout
// lines ahead of the failure are the flush made visible: a pipe would otherwise hold
// them buffered when the process dies.

fun main(): Int {
    println("before")
    assert(1 + 1 == 2, "math works")
    println("asserted")
    panic("boom")
    return 0
}
