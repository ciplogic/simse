package broken

// A sema error: `Missing` is not a declared type. The case expects the
// transpile to fail with the diagnostic below, so a regression that starts
// accepting this program - or that loses the message - fails the harness.

fun main(): Int {
    val absent: Missing = 0
    return 0
}
