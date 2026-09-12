package text

// The Str/Dictionary library on a realistic task: split a sentence into words,
// count them, report a few counts, and find the longest word. The keys are
// sorted before use so nothing depends on the dictionary's iteration order.

fun main(): Int {
    val text: Str = "the quick brown fox jumps over the lazy dog the extraordinary fox"
    val words: List<Str> = text.split(" ")

    var counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
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

    println(words.size())
    println(counts.size())
    println(counts.get("the").value())
    println(counts.get("fox").value())
    println(counts.has("cat"))

    var longest: Str = ""
    var keys: List<Str> = counts.keys()
    keys.sort((left: Str, right: Str) -> left < right)
    var k: Int = 0
    while (k < keys.size()) {
        if (keys[k].size() > longest.size()) {
            longest = keys[k]
        }
        k = k + 1
    }
    println(longest)

    val shout: Str = text.toUpper()
    println(shout.startsWith("THE"))
    println(text.replace("fox", "cat").endsWith("cat"))
    return 0
}
