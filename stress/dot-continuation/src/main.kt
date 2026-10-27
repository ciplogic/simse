package dotcontinuation

// A continuation line starting with `.` extends the expression above it
// (limitations.md's chain gap): the one newline an expression may span, so a long method
// chain reads as it computes - tried tentatively, and a newline that starts no `.` ends
// the expression exactly as before.

data class Box(var v: Int)

fun Box.bumped(): Box {
    this.v = this.v + 1
    return this
}

fun main(): Int {
    val b: Box = Box(1)
        .bumped()
        .bumped()
        .bumped()
    println(b.v)
    val s: Str = "abc"
        .toUpper()
    println(s)
    // A newline with no `.` after it still ends the expression: two statements.
    val first: Box = Box(0)
        .bumped()
    val second: Box = first.bumped()
    println(second.v)
    return 0
}
