package strings

// ---- str-isempty ----
// `Str.isEmpty` is a *prelude body*, not a native (`src/rtl/rtl.kt`,
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
// the pool (`src/rtl/strtable.hpp`); this pins the two agreeing for the shapes that
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
    keys.sort(compareLessThan)
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

// ---- concat ----

// The emitter's own concatenation (src/linear/MergeConcat.kt): a `+` chain over `Str`
// and an `fmtStr`/`fmtStrWith` whose format and separator are literals are fused into
// *one* instruction. The chains it takes (a literal, a slot, a `Char`, a member, a call's
// result), a chain onto the destination itself (`acc = acc + ...`), the `fmtStr` shapes it
// takes and the ones it must *refuse* (a format or separator that is a variable) are below.
data class Tag(var name: Str, var count: Int)

fun pair(a: Str, b: Str): Str {
    return a + "-" + b
}

fun partConcat(): Int {
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

// ---- raw-strings ----

// A backtick string is raw: no escape, and it may span lines; `@name` interpolates. This
// part pins what both become - the same bytes as the escaped double-quoted form, an empty
// one, a `when` label, the `\` and `"` that a normal string would have to escape, and the
// call an interpolation is desugared into (src/parser/ParserInterp.kt).
fun partRawStrings(): Int {
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

    // A name followed by another identifier byte takes the parenthesized spelling `@(name)`;
    // a multi-line template carries an escape, which the fusion refuses, so the runtime
    // `fmtStrWith` answers there - which is why each item of a multi-line interpolation below
    // is a `Str`.
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

// ---- when-strings ----

// The `when`-over-strings lowering (src/parser/Parser.kt): a `when` whose subject is a string
// tests a *view* of the subject, with each label's test guarded by the subject's length. The
// shapes pinned here: the empty text, one-byte labels, an arm whose labels span two lengths,
// a label that can never match, a `StrView` subject, a subject read through a pointer, and an
// arm that is a constant rather than a literal.
fun classify(op: Str): Str {
    when (op) {
        "" -> {
            return "empty"
        }

        "|", "^", "&" -> {
            return "bitwise"
        }

        "==", "!=" -> {
            return "equality"
        }

        "<", ">", "<=", ">=" -> {
            return "relational"
        }

        "<<" -> {
            return "shift"
        }

        else -> {
            return "other"
        }
    }
}

// A subject read through a pointer - the shape the compiler's own `when`s have. The view the
// tests compare against is taken from the dereference, so the `Str` is not copied per label.
fun classifyPtr(op: *Str): Str {
    when (*op) {
        "", "|" -> {
            return "empty-or-bar"
        }

        "==", "ab" -> {
            return "two"
        }

        else -> {
            return "other"
        }
    }
}

// The same guards, read through a view.
fun viewKind(v: StrView): Str {
    when (v) {
        "a", "b" -> {
            return "letter"
        }

        "ab" -> {
            return "pair"
        }

        else -> {
            return "?"
        }
    }
}

// An arm that is a constant, not a literal.
fun fixed(op: Str): Str {
    val head: Str = "head"
    when (op) {
        head -> {
            return "HEAD"
        }

        else -> {
            return "other"
        }
    }
}

fun partWhenStrings(): Int {
    val ops: List<Str> = listOf<Str>("", "|", "^", "&", "==", "!=", "<", ">", "<=", ">=", "<<", "zz", "||")
    for (op in ops) {
        println(op + " -> " + classify(op))
    }
    println(viewKind("a") + " " + viewKind("ab") + " " + viewKind("abc"))
    val words: List<Str> = listOf<Str>("", "|", "ab", "zz")
    for (word in words) {
        println(word + " ~ " + classifyPtr(&word))
    }
    println(fixed("head") + " " + fixed("other"))
    return 0
}

// ---- the category's entry ----
fun main(): Int {
    partStrIsEmpty()
    partStringEscapes()
    partStrings()
    partTextProcessing()
    partConcat()
    partRawStrings()
    partWhenStrings()
    return 0
}
