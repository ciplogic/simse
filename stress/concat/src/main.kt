package concat

// The emitter's own concatenation (cppsrc/linear/MergeConcat.kt): a `+` chain over `Str`
// and an `fmtStr` whose format is a literal are fused into *one* instruction, which the
// emitter expands - the parts' lengths summed, one `resize`, one slot write per part - so the
// answer is one buffer with each part written once (`expected.cpp` shows the expansion, and
// cppsrc/rtl/_res.md's `strcat` section is where its primitives live).
//
// This program is the shape that rule has to keep honest: the chains it takes (a literal, a
// slot, a `Char`, a member, a call's result), a chain that starts from the destination
// itself (`acc = acc + ...`, appended *in place* onto bytes already reserved, against the
// same chain read into a fresh `Str`), the `fmtStr` shapes it takes (no `|` at all, one per
// item, pieces empty at either end) and the two it must *refuse* - a format that is a
// variable, and a format whose `|` count does not match its items, where the runtime's own
// `fmtStr` is what answers.

data class Tag(var name: Str, var count: Int)

fun pair(a: Str, b: Str): Str {
    return a + "-" + b
}

fun main(): Int {
    val a: Str = "alpha"
    val b: Str = "beta"

    // slots and literals; a `Char`; a call's result
    println(a + b + "!")
    println("x" + a)
    println(a + ':' + b)
    println(a + pair("p", "q"))
    println(pair(a, b))

    // a member, and a `toString` in the middle
    val tag: Tag = Tag("t", 3)
    println(tag.name + "=" + tag.count.toString())

    // literals only
    println("aa" + "bb")

    // a chain onto the destination itself, appended in place; and the same chain read into
    // a fresh one
    var acc: Str = "run"
    acc = acc + "-" + a + b
    println(acc)
    println("[" + acc + "]")

    // `fmtStr`: no `|`, one, and empty pieces at either end
    println(fmtStr("plain"))
    println(fmtStr("a=|", a))
    println(fmtStr("|x|", a, b))

    // a one-byte literal is a `Char` write; an empty one contributes nothing at all
    println(fmtStr("b=|=|", a, b))
    println("" + a + "")
    println("" + "")
    var bare: Str = "Z"
    bare = bare + ""
    println(bare)

    // refused: the format is not a literal, and the counts do not line up
    val format: StrView = "v=|"
    println(fmtStr(format, a))
    println(fmtStr("a=|b=|", a))

    return 0
}
