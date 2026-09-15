package util

// The `modules` stress project: one package in its own folder, imported by the
// program. Nothing here is special - the point is that the compiler scans a
// module root, sees both packages, and links them into one program.

data class Point(var x: Int, var y: Int) {
    fun manhattan(): Int {
        var total: Int = this.x
        if (total < 0) {
            total = 0 - total
        }
        if (this.y < 0) {
            total = total - this.y
        } else {
            total = total + this.y
        }
        return total
    }
}

fun twice(value: Int): Int {
    return value + value
}
