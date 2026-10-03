package app

// Two `union class` field names collide with the generated tag storage, each its own
// diagnostic: `None` with the empty member of the implicit tag enum, `_type` with the tag
// field the struct holds. See `stress/unions` for the working shape.

union class U(var None: Int)

fun main(): Int {
    return 0
}
