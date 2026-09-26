package raw

// A backtick string is raw: no escape, no interpolation, and it may span lines. This case
// pins what it becomes - the same bytes as the escaped double-quoted form, an empty one, a
// `when` label, and the `\` and `"` that a normal string would have to escape.

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
    return 0
}
