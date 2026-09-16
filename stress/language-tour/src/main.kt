package fixtures
// T14: exercises `when`, null nullability, the Str/Char library, numeric
// conversions, min/max, and enum toInt/fromInt.

enum class Color {
    Red,
    Green = 4,
    Blue
}

data class Box(var value: Int)

fun label(c: Color): Str {
    when (c) {
        Color.Red -> {
            return "red"
        }

        Color.Green -> {
            return "green"
        }

        else -> {
            return "other"
        }
    }
}

fun describe(n: Int): Str {
    when (n) {
        0 -> {
            return "zero"
        }

        1 -> {
            return "one"
        }

        else -> {
            return "many"
        }
    }
}

fun maybeRef(flag: Bool): &Box {
    if (flag) {
        return &Box(7)
    }
    return null
}

fun maybePointer(box: &Box, flag: Bool): *Box {
    if (flag) {
        return * box
    }
    return null
}

fun main(): Int {
    println(label(Color.Red))
    println(label(Color.Green))
    println(label(Color.Blue))
    println(describe(0))
    println(describe(5))

    println(Color.Green.toInt())
    val parsed: Opt<Color> = Color.fromInt(4)
    if (parsed.hasValue()) {
        println("parsed")
    }
    val missingColor: Opt<Color> = Color.fromInt(9)
    if (!missingColor.hasValue()) {
        println("missing color")
    }

    val present: &Box = maybeRef(true)
    if (present != null) {
        println(present.value)
    }
    val absent: &Box = maybeRef(false)
    if (absent == null) {
        println("absent")
    }
    val boxed: &Box = &Box(3)
    val rawPresent: *Box = maybePointer(boxed, true)
    if (rawPresent != null) {
        println(rawPresent.value)
    }
    val rawAbsent: *Box = maybePointer(boxed, false)
    if (rawAbsent == null) {
        println("null raw")
    }

    val text: Str = "hello world"
    println(text.find("world"))
    println(text.find("zzz"))
    println(text.substr(0, 5))
    println(text.startsWith("hello"))
    println(text.endsWith("world"))
    println(text.replace("world", "simse"))
    val number: Opt<Int> = "42".toInt()
    if (number.hasValue()) {
        println(number.value())
    }
    val badNumber: Opt<Int> = "nope".toInt()
    if (!badNumber.hasValue()) {
        println("bad int")
    }
    val fraction: Opt<Float64> = "2.5".toFloat()
    if (fraction.hasValue()) {
        println(fraction.value())
    }

    val letter: Char = 'a'
    println(letter.isAlpha())
    println(letter.isDigit())
    println('7'.isDigit())
    println(' '.isSpace())

    val small: Int8 = 5
    println(small.toString())
    println(true.toString())
    println(2.5.toString())

    println(min(3, 9))
    println(max(3, 9))

    return 0
}
