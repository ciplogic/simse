package fixtures

// The indexer shapes are fixed: `get` takes the index, `set` the index and the value
// (specs/functions.md). A `get` with no parameters indexes nothing.

data class Box(var value: Int)

operator fun Box.get(): Int {
    return this.value
}

fun main(): Int {
    val box: Box = Box(1)
    return box[0]
}
