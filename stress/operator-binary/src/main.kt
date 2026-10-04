package fixtures

// `operator fun compareTo`/`equals`/`plus` (specs/functions.md): the Kotlin convention for
// the comparison operators, `==`/`!=` and `+`. One `compareTo` derives all four
// comparisons (`<` is `compareTo(other) < 0`); `==` is `equals`, `!=` its negation; `+` is
// `plus`, and an unannotated local infers the operator's return type.

data class Version(var major: Int, var minor: Int) {
    operator fun compareTo(other: Version): Int {
        if (this.major != other.major) {
            return this.major - other.major
        }
        return this.minor - other.minor
    }

    operator fun equals(other: Version): Bool {
        return this.compareTo(other) == 0
    }

    operator fun plus(other: Version): Version {
        return Version(this.major + other.major, this.minor + other.minor)
    }
}

// The extension spelling, and a `plus` whose answer is another type: the local's type is
// the declaration's return type (`Str`), not the receiver's.
data class Tag(var name: Str)

operator fun Tag.plus(other: Tag): Str {
    return this.name + "+" + other.name
}

fun main(): Int {
    val a: Version = Version(1, 2)
    val b: Version = Version(1, 3)
    val same: Version = Version(1, 2)
    println("lt " + (a < b).toString())
    println("le " + (a <= same).toString())
    println("gt " + (b > a).toString())
    println("ge " + (a >= same).toString())
    println("eq " + (a == same).toString())
    println("ne " + (a != b).toString())
    val sum = a + b
    println("sum " + sum.major.toString() + "." + sum.minor.toString())
    var running: Version = Version(0, 0)
    running += a
    println("step " + running.major.toString() + "." + running.minor.toString())
    val joined = Tag("x") + Tag("y")
    println("tag " + joined)
    if (a < b) {
        println("ordered")
    }
    return 0
}
