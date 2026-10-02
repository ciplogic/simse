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

    // Interpolation: the literal becomes one `fmtStrWith('@', ...)` call whose template keeps
    // an `@` per name and whose items are the names - a `@` that starts no name is a
    // diagnostic. A name followed by another identifier byte takes the parenthesized spelling
    // `@(name)`, whose `)` ends it. A multi-line template carries an escape, which the fusion
    // refuses, so what runs there is the runtime `fmtStrWith` - which is why every item of a
    // *multi-line* interpolation below is a `Str` (a numeric item needs the fusion today).
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
    println(`_sm_@(who)_@(n)`)
    println(`@(who)@(n)`)
    println(`(@(who))`)
    // A literal `@` in a string that interpolates is a diagnostic
    // (stress/diagnostic-interpolation): keep it in a `"..."` string instead.
    val at: Str = "a@ b and "
    println(at + `@who |`)
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
