package app

// A `when (u)` arm that names no arm of the union: the checker qualifies a bare arm name
// against the tag's members (`expandUnionTagTest`) and reports the arm list instead of
// leaving a name that could not resolve. See `stress/unions` for the working shape.

union class U(var A: Int, var B: Str)

fun label(u: U): Str {
    when (u) {
        C -> {
            return "c"
        }

        else -> {
            return "other"
        }
    }
}

fun main(): Int {
    println(label(U(1)))
    return 0
}
