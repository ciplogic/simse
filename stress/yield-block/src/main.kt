package yieldblock

// A local the hoisting cannot move (a construction the backend routes through
// `initByValue`) keeps its C++ scope inside a machine's `advance`, and the `yield` inside
// that scope suspends and resumes *there* (`impl_specs/yield.md`): the resumption label
// the dispatcher jumps to is the one the block holds. Before the block-aware rewrite a
// statement inside such a block never reached the machinery - the suspend marker inside
// it was never expanded, and the dispatcher jumped to a label nothing defined.

fun tally(words: List<Str>): ..Str {
    var i: Int = 0
    while (i < words.size()) {
        var acc = Str(words[i])
        acc.appendStr("!")
        yield acc
        i = i + 1
    }
}

fun main(): Int {
    var xs: List<Str> = List<Str>(0)
    xs.append("a")
    xs.append("b")
    for (s in tally(xs)) {
        println(s)
    }
    return 0
}
