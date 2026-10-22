package bench_words

// Count word frequencies over a text of N words drawn from a skewed
// vocabulary. Port of aardappel/goose bench/words_dictionary.goose: the std
// dictionary row, whose counterpart is unordered_map<string,int>
// (bench/cpp/words.cpp VARIANT 0). The output depends only on the multiset of
// words - never on the hash function or the table's probe sequence - so the
// checksums line up whatever the map internals.

import kit

val N: Int = 500000 // BENCH_N

fun main(): Int {
    val vocab: Int = N / 10 + 16
    var r: Int64 = 12345
    // A vocabulary of words.
    var dict: List<Str> = List<Str>()
    var i: Int = 0
    while (i < vocab) {
        var w: Str = Str()
        r = xsNext(r)
        val n: Int = 3 + Int(xsMod(r, 12))
        var j: Int = 0
        while (j < n) {
            r = xsNext(r)
            w.append(Char(97 + Int(xsMod(r, 26))))
            j++
        }
        dict.append(w)
        i++
    }
    // A text of N words drawn with a skew towards the front of the vocabulary.
    var text: Str = Str()
    i = 0
    while (i < N) {
        r = xsNext(r)
        val a: Int64 = xsMod(r, Int64(vocab))
        val k: Int = Int((a * a) / Int64(vocab))
        text.appendStr(dict[k])
        text.append(' ')
        i++
    }
    // Split; the trailing separator's empty part is not a word.
    var words: List<Str> = List<Str>()
    val tl: Int = text.size()
    var p: Int = 0
    while (p < tl) {
        val start: Int = p
        while (p < tl && byteAt(text, p) != 32) {
            p++
        }
        words.append(text.substr(start, p - start))
        while (p < tl && byteAt(text, p) == 32) {
            p++
        }
    }
    // Count. `getPtr` hands out the entry's place: a write through it reaches
    // the dictionary, and the deref write belongs first in its block.
    val counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    for (w in words) {
        val slot: *Int = counts.getPtr(w)
        if (slot != null) {
            *slot = *slot + 1
        } else {
            counts.insert(w, 1)
        }
    }
    val distinct: Int = counts.size()
    var total: Int64 = 0
    val keys: List<Str> = counts.keys()
    for (key in keys) {
        val slot: *Int = counts.getPtr(key)
        total += Int64(*slot) * (Int64(key.size()) + Int64(byteAt(key, 0)))
    }
    println(words.size())
    println(distinct)
    println(total)
    return 0
}
