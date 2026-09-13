package tour

// A compact program for the README: an extension method on Str, a Dictionary,
// an Opt lookup, a while loop, and printing. Deterministic by construction (the
// keys are sorted before printing).

fun Str.words(): List<Str> {
    return this.split(" ")
}

fun main(): Int {
    val text: Str = "one two two three three three"
    val counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    val words: List<Str> = text.words()

    var i: Int = 0
    while (i < words.size()) {
        val seen: Opt<Int> = counts.get(words[i])
        if (seen.hasValue()) {
            counts.insert(words[i], seen.value() + 1)
        } else {
            counts.insert(words[i], 1)
        }
        i = i + 1
    }

    val keys: List<Str> = counts.keys()
    keys.sort((left: Str, right: Str) -> left < right)

    var k: Int = 0
    while (k < keys.size()) {
        val count: Int = counts.get(keys[k]).value()
        println(keys[k] + " = " + count.toString())
        k = k + 1
    }
    return 0
}
