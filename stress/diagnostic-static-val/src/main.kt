package app

// A file-level `val` is a static value binding like any other: assigning to it is
// the same error as assigning to a local `val` (specs/statics.md).

val origin: Str = "boot"

fun main(): Int {
    origin = "other"
    return 0
}
