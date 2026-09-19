package fixtures

// A `*`-marked resource is **binary** (specs/resources.md, "Markers"): the value as written is
// hex that stands for the bytes, and by the time the program sees it there is nothing special
// about it - it is a `Str` with a length, which may hold a `\0` in the middle, exactly like any
// other string. So this case reads the bytes back one at a time and asks for the length, which
// is what the string table's own encoding has to survive.
//
// The markers may be written on a section title (`*Pictures`, `!*Hidden`) or on a single
// entry's key (`*Dark`, `!Gone`), they may be written together in either order, and a marked
// section marks every entry under it - nothing takes a marker back.
//
// Every `Resources.get` result is bound to a local before anything is called on it: a member
// chained straight onto a *static* call has no inferred type (guide4ai.md's gotcha).

fun main(): Int {
    // `48 69 0a`, written over three lines: the length says three bytes, and each byte is
    // what the hex said - `H`, `i`, newline.
    val icon: StrView = Resources.get("Pictures:Icon")
    val iconFirst: Char = icon.at(0)
    val iconSecond: Char = icon.at(1)
    val iconThird: Char = icon.at(2)
    println(icon.size())
    println(iconFirst.toString())
    println(iconSecond.toString())
    println(iconThird.toString())

    // `00ff41`: a leading NUL and a byte above 0x7f. The length is three, not one - the pool
    // carries a NUL as a byte - and the high byte reads back as the signed `Char` it is.
    val raw: StrView = Resources.get("Pictures:Raw")
    val rawFirst: Char = raw.at(0)
    val rawSecond: Char = raw.at(1)
    val rawThird: Char = raw.at(2)
    println(raw.size())
    println(rawFirst.toString())
    println(rawSecond.toString())
    println(rawThird.toString())

    // Hex is bytes, not necessarily text: `5065746572` is "Peter", and a binary value prints
    // like any other string.
    val name: StrView = Resources.get("Pictures:Name")
    println(name)

    // A key-level `*` inside an unmarked section, and a key-level `!` beside it.
    val text: StrView = Resources.get("Notes:Text")
    val dark: StrView = Resources.get("Notes:Dark")
    val darkSecond: Char = dark.at(1)
    println(text)
    println(dark.size())
    println(darkSecond.toString())
    println(Resources.has("Notes:Gone"))

    // `!` and `*` together on a section: the entries are binary *and* the program does not
    // carry them.
    println(Resources.has("Hidden:Secret"))
    println(Resources.count())
    return 0
}
