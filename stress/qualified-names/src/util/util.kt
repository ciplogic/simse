package util

// One of two packages in this project that both declare a `describe()` method:
// each package's symbol is emitted under its own `ns<index>_` prefix
// (impl_specs/rtl-abi.md), so neither shadows the other in the amalgamated
// translation unit.

data class Point(var x: Int; var y: Int) {
    fun describe(): Str {
        return "point " + this.x.toString() + "," + this.y.toString()
    }
}

fun twice(value: Int): Int {
    return value + value
}
