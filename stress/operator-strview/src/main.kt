package fixtures

// `operator` functions on `StrView` (specs/functions.md): `<`, `<=`, `==`, `!=`, `>`, `>=`
// compare the bytes in place (`compareTo`, `equals`) and `+` builds the owned `Str`
// (`plus`), all declared in the prelude (src/rtl/StrView.kt). A `Str` or literal operand
// is read as a view of itself (`spanOfStr`) at the operator call site, so a mixed
// comparison copies nothing and a literal stays a pool entry.

fun echo(view: StrView): StrView {
    return view
}

// Two borrowed strings: the operands are `*Str`, so the view is taken from the pointer
// (no `spanOfStr` of a place - there is no literal in sight for the parser to wrap).
fun sameText(left: *Str, right: *Str): Bool {
    return left == right
}

fun minText(left: *Str, right: *Str): Bool {
    return left < right
}

fun main(): Int {
    val text: Str = "banana"
    val view: StrView = spanOfStr(text)
    // A view against a literal, and a literal against a view.
    println("eq " + (view == "banana").toString())
    println("ne " + (view != "banana!").toString())
    println("lt " + (view < "bananas").toString())
    println("le " + (view <= "banana").toString())
    println("gt " + (view > "apple").toString())
    println("ge " + (view >= "banana").toString())
    // Equal prefixes of different lengths: the shorter view is the smaller one.
    println("prefix " + (view.slice(0, 4) < "banana").toString())
    // The empty view is the smallest, and equal texts are equal.
    println("emptyEq " + (text.substr(2, 0) == "").toString())
    println("emptyLt " + ("" < "a").toString())
    println("shortLt " + ("a" < "ab").toString())
    println("equal " + (text.substr(0, 6) == text).toString())
    // A `Str` variable against a literal, a view and the other way round.
    println("owned " + (text == "banana").toString())
    val other: Str = "banana"
    println("mixed " + (other == view).toString())
    println("mixedRev " + (view == other).toString())
    println("mixedLt " + (other < view).toString())
    // A call's answer (a temporary) against a literal.
    println("call " + (echo(view) == "banana").toString())
    println("substr " + (text.substr(0, 3) == "ban").toString())
    // Two `*Str` operands: no literal, so no parser wrap was involved.
    val banana: Str = "banana"
    println("ptrEq " + sameText(*text, *banana).toString())
    println("ptrLt " + minText(*banana, *text).toString())
    // `+` builds a `Str`; an unannotated local infers the operator's return type.
    val joined = view + "!"
    println("joined " + joined)
    println("both " + (view + view))
    val suffix: Str = "?"
    println("owned " + (view + suffix))
    println("pre " + ("^" + view))
    println("into " + (suffix + text))
    return 0
}
