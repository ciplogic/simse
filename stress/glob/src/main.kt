package globs

import glob

// Glob matching (modules/glob/glob.kt): `*` is the only wildcard, so a compiled pattern is
// an exact word, an anchored prefix and/or suffix (`Opt<Str>` each), and the middle
// segments matched once each, left to right, by `findFrom` - no backtracking. This case
// pins every path: the exact word, each anchor alone, both together, the middle walk
// (including the wall the suffix puts up), the empty-segment collapse, and the length
// rejects.

// ---- exact ----

// A pattern with no `*` is one `==`, the specialization the common "is this file that
// file" test wants.
fun partExact(): Int {
    val g: Glob = globCompile("main.kt")
    println(g.matches("main.kt"))
    println(g.matches("main2.kt"))
    println(g.matches("main"))
    println(g.matches(""))

    // Byte-exact: `Str` is bytes, so the case is whatever the bytes say.
    val cased: Glob = globCompile("Makefile")
    println(cased.matches("makefile"))

    // The empty pattern matches only the empty text.
    val empty: Glob = globCompile("")
    println(empty.matches(""))
    println(empty.matches("x"))
    return 0
}

// ---- anchors ----

// A trailing `*` anchors the prefix; a leading one anchors the suffix; both is the
// "wrapped" test, rejected early by `minLen` when the text cannot hold both.
fun partAnchors(): Int {
    println(globMatches("main.*", "main.kt"))
    println(globMatches("main.*", "main.c"))
    println(globMatches("main.*", "main."))
    println(globMatches("main.*", "domain.kt"))

    println(globMatches("*.kt", "main.kt"))
    println(globMatches("*.kt", "main.c"))
    println(globMatches("*.kt", "some/deep/path.kt"))
    println(globMatches("*.kt", "kt"))
    println(globMatches("*.kt", "Main.KT"))

    println(globMatches("src/*", "src/main.kt"))
    println(globMatches("src/*", "src/"))
    println(globMatches("src/*", "source/main.kt"))

    // Both anchors: prefix, suffix, and the text between them may be empty.
    println(globMatches("a*b", "ab"))
    println(globMatches("a*b", "axxb"))
    println(globMatches("a*b", "a b"))
    println(globMatches("a*b", "ba"))

    // The overlap reject: prefix and suffix cannot share bytes, and `minLen` (4) says so
    // before any compare runs.
    println(globMatches("ab*ba", "aba"))
    println(globMatches("ab*ba", "abba"))
    return 0
}

// ---- middles ----

// The segments between the anchors, matched once each, left to right: each `findFrom`
// starts where the last one ended, and no segment may cross the suffix's wall. The
// leftmost-fit walk is complete because the suffix is pinned to the end - these are the
// cases a backtracking matcher exists for, matching here without one.
fun partMiddles(): Int {
    println(globMatches("a*b*c", "abc"))
    println(globMatches("a*b*c", "axxbxxc"))
    println(globMatches("a*b*c", "abcbc"))
    println(globMatches("a*b*c", "ac"))

    // The wall: the first `bc` may end no later than the suffix's first byte, so the
    // leftmost fit at 1 is the one (a·bc·empty·bc).
    println(globMatches("a*bc*bc", "abcbc"))
    println(globMatches("a*bc*bc", "abcbcbc"))
    println(globMatches("a*bc*bc", "abcb"))

    // Leftmost with room to spare, and a middle that must skip past another's bytes.
    println(globMatches("*bc*c", "bcbc"))
    println(globMatches("x*ab*b", "xaabbb"))

    // Empty segments collapse: `a**b` is `a*b`, and a lone `*` (or `**`) matches
    // everything, the empty text included.
    println(globMatches("a**b", "axb"))
    println(globMatches("**", ""))
    println(globMatches("*", ""))
    println(globMatches("*", "anything at all"))
    return 0
}

// ---- many ----

// The shape the module exists for: compile once, test many. The compiled pattern carries
// no allocation per match - a walk, a compare, and one `memchr`-driven scan per middle.
fun partMany(): Int {
    val source: Glob = globCompile("*.kt")
    val names: List<Str> = listOf<Str>("main.kt", "driver.c", "src/glob.kt", "notes", "a.b.kt")
    var kept: Int = 0
    for (name in names) {
        if (source.matches(name)) {
            kept = kept + 1
        }
    }
    println(kept)

    // The same glob, rejecting the one name whose bytes do not line up.
    val build: Glob = globCompile("build*")
    var hits: Int = 0
    for (name in names) {
        if (build.matches(name)) {
            hits = hits + 1
        }
    }
    println(hits)

    // Every middle segment at least the size of its text: the `findFrom` short-circuit.
    val wide: Glob = globCompile("*connect*disconnect*")
    println(wide.matches("disconnect"))
    println(wide.matches("connect then disconnect"))
    return 0
}

fun main(): Int {
    partExact()
    partAnchors()
    partMiddles()
    partMany()
    return 0
}
