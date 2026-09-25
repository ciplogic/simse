package strings

// ---- str-isempty ----
// `Str.isEmpty` is a *prelude body*, not a native (`cppsrc/rtl/rtl.kt`,
// `impl_specs/rtl-abi.md` T71), so the compiler emits it - but only when a program reaches
// it (`Codegen.kt`/`Codegen.cpp`, `reachesPreludeBody`).
//
// This program is the shape that rule has to get right: it never names `Str` as a type
// anywhere. Every receiver is a literal, and the only mention of the function is the call.
// Under the old rule the type test decided whether the body was emitted, and a name the
// program never used as a *type* emitted nothing at all - the generated C++ then called a
// function it never defined (`error C3861: 'isEmpty': identifier not found`). The rule now
// falls back to "the call reaches it" when no overload of the name is attributable, so
// what is called is what is emitted.
//
// A receiver whose type the program *does* name is covered by `stress/rtl-simse`.
fun partStrIsEmpty(): Int {
    println("".isEmpty())      // true
    println("x".isEmpty())     // false
    if ("".isEmpty()) {
        println("empty")       // empty
    }
    return 0
}

// ---- string-escapes ----
// The program's string literals, escapes included. The emitter writes every literal into
// one pool and computes the length index beside it, and the program's own build decodes
// the pool (`cppsrc/rtl/strtable.hpp`); this pins the two agreeing for the shapes that
// differ - an escape that is two source characters and one byte (`\n`), one that is two
// and two (`\\n`), a quote that must not end the literal (`\"`), the empty literal, a
// literal repeated, and prefixes of each other. A length that disagreed would shift every
// literal after it, and the `static_assert` in the generated C++ would stop the build.
fun partStringEscapes(): Int {
    val newline: Str = "a\nb"
    val escaped: Str = "a\\nb"
    val quote: Str = "say \"hi\""
    val tab: Str = "\t"
    val empty: Str = ""
    val longText: Str = "a literal well past the twenty-three byte inline capacity of Str"
    println(newline.size())
    println(escaped.size())
    println(quote.size())
    println(tab.size())
    println(empty.size())
    println(quote)
    println(escaped)
    println(longText.size())
    println("a")
    println("ab")
    println("abc")
    return 0
}

// ---- strings ----
// T17: the Str library (charAt, trim, split, case folding, search).

fun partStrings(): Int {
    val raw: Str = "  Hello,World  "
    val text: Str = raw.trim()
    println(text)
    println(text.toUpper())
    println(text.toLower())
    println(text.isEmpty())
    println(Str().isEmpty())
    println(text.size())

    val parts: List<Str> = text.split(",")
    println(parts.size())
    println(parts[0])
    println(parts[1])

    println(text.charAt(0))
    println(text.indexOf("World"))
    println(text.find("zzz"))
    println(text.lastIndexOf("l"))
    println(text.substr(6, 5))
    println(text.startsWith("Hello"))
    println(text.endsWith("World"))
    println(text.replace("World", "Simse"))
    return 0
}

// ---- text-processing ----
// The Str/Dictionary library on a realistic task: split a sentence into words,
// count them, report a few counts, and find the longest word. The keys are
// sorted before use so nothing depends on the dictionary's iteration order.

fun partTextProcessing(): Int {
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

// ---- the category's entry ----
fun main(): Int {
    partStrIsEmpty()
    partStringEscapes()
    partStrings()
    partTextProcessing()
    return 0
}
