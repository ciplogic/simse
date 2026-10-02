package concat

// The emitter's own concatenation (src/linear/MergeConcat.kt): a `+` chain over `Str`
// and an `fmtStr`/`fmtStrWith` whose format and separator are literals are fused into
// *one* instruction, which the emitter expands - the parts' lengths summed, one `resize`,
// one slot write per part - so the answer is one buffer with each part written once
// (`expected.cpp` shows the expansion, and src/rtl/_res.md's `strcat` section is where
// its primitives live).
//
// This program is the shape that rule has to keep honest: the chains it takes (a literal, a
// slot, a `Char`, a member, a call's result), a chain that starts from the destination
// itself (`acc = acc + ...`, written *in place* onto the bytes already there, against the
// same chain read into a fresh `Str`), the `fmtStr`/`fmtStrWith` shapes it takes (no
// placeholder at all, one per item, pieces empty at either end, a separator that is a
// parameter), a one-byte literal and an empty one, and the ones it must *refuse* - a
// format or a separator that is a variable, and counts that do not line up - where the
// runtime's own `fmtStr`/`fmtStrWith` is what answers.

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

    // `fmtStrWith`: the placeholder is a parameter, so a literal `|` stays in the output
    println(fmtStrWith('@', "x=@", a))
    println(fmtStrWith('@', "|@|", a))
    println(fmtStrWith('@', "c=@:@", a, b))
    println(fmtStrWith('@', "plain"))
    println(fmtStrWith('@', "n=@", tag.count))

    // refused like `fmtStr`: a template or a separator that is a variable, and counts that do
    // not line up (the runtime answers)
    val withFormat: StrView = "w=@"
    println(fmtStrWith('@', withFormat, a))
    val withSep: Char = '@'
    println(fmtStrWith(withSep, "s=@", a))
    println(fmtStrWith('@', "a=@b=@", a))

    return 0
}
