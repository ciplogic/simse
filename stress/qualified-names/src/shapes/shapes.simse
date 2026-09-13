package shapes

// The second `describe()`: this one takes a Rect, and the call sites resolve it
// by receiver type, not by name.

data class Rect(var width: Int; var height: Int) {
    fun describe(): Str {
        return "rect " + this.width.toString() + "x" + this.height.toString()
    }
}

fun area(rect: Rect): Int {
    return rect.width * rect.height
}
