package raw

// A backtick string is raw: no escape, and it may span lines; `@name` interpolates. This
// case pins what both become - the same bytes as the escaped double-quoted form, an empty
// one, a `when` label, the `\` and `"` that a normal string would have to escape, and the
// call an interpolation is desugared into (cppsrc/parser/ParserInterp.kt).

fun main(): Int {
    val text: Str = `line one
line "two" with \ back
	tabbed`
    println(text.size())
    println(text)
    val empty: Str = ``
    println(empty.size())
    println(empty.isEmpty())
    println(text == "line one\nline \"two\" with \\ back\n\ttabbed")
    println(text.startsWith("line one"))
    when (text) {
        `line one
line "two" with \ back
	tabbed` -> {
            println("matched")
        }

        else -> {
            println("no")
        }
    }
    val quoted: Str = `He said "hi" and left \ right`
    println(quoted.size())
    println(quoted)

    // Interpolation: a name becomes one `|` and one item of an `fmtStr` call, an `@` before a
    // non-identifier is the literal character, and a `|` in the text picks `fmtStrWith` with a
    // separator no piece holds (`@` unless a piece has one). A multi-line template carries an
    // escape, which the fusion refuses, so what runs there is the runtime `fmtStr` - which is
    // why every item below is a `Str` (a numeric item needs the fusion today).
    val who: Str = "world"
    val n: Int = 42
    println(`hello @who`)
    println(`@who!`)
    println(`@who@who`)
    println(`n=@n`)
    println(`@ who and @2`)
    println(`@`)
    println(`a|b=@who`)
    println(`|@who|`)
    println(`a@ b and @who |`)
    val multi: Str = `first @who
second @who`
    println(multi)
    val piped: Str = `x|y
@who`
    println(piped)
    val plain: Str = "@who"
    println(plain)
    return 0
}
