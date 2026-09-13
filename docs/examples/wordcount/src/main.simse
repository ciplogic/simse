package wordcount

// A slightly larger program: a fixed sentence, a Dictionary to count words, a
// data class to hold one tally, an extension method on Str, an Opt to look up a
// count, and a sort with a tie-breaking comparator so the output is deterministic.

data class Tally(var word: Str; var count: Int)

fun Str.words(): List<Str> {
    return this.split(" ")
}

fun main(): Int {
    val text: Str = "the quick brown fox jumps over the lazy dog the fox"
    val counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    val words: List<Str> = text.words()

    var i: Int = 0
    while (i < words.size()) {
        val word: Str = words[i]
        val seen: Opt<Int> = counts.get(word)
        if (seen.hasValue()) {
            counts.insert(word, seen.value() + 1)
        } else {
            counts.insert(word, 1)
        }
        i = i + 1
    }

    val tallies: List<Tally> = List<Tally>()
    val keys: List<Str> = counts.keys()
    var k: Int = 0
    while (k < keys.size()) {
        tallies.append(Tally(keys[k], counts.get(keys[k]).value()))
        k = k + 1
    }
    tallies.sort((left: Tally, right: Tally) -> left.count > right.count)

    var t: Int = 0
    while (t < tallies.size()) {
        println(tallies[t].word + " " + tallies[t].count.toString())
        t = t + 1
    }
    return 0
}
