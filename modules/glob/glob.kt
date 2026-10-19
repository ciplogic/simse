// glob.kt
//
// Glob matching over `Str` (a byte string, so patterns and texts match byte for byte -
// case-sensitive, no encoding anywhere). The only wildcard is `*` (any run, including the
// empty one); everything else in a pattern is a literal byte, and `*` has no escape - a
// pattern that needs a literal `*` cannot spell it, the same rule a raw string's `@` has.
// There is no `?` and no `[...]`: those would break the one optimization this module
// exists for, and a glob rich enough to want them is a regex.
//
// The compiled shape is a **tagged union** - the pattern's own shape is the tag, and the
// matcher reads the tag first, then runs that shape's rule:
//
//   - `Exact(Str)` - the pattern held no `*` at all: the match is one `==` (the
//                   exact-word specialization).
//   - `Parts`      - everything else, as `GlobParts`: the anchored ends and the middle
//                   segments between them, with `minLen` for the reject that runs before
//                   any byte is compared.
//
// The split is also the borrow proof: `Glob.matches` is a thin dispatcher whose only calls
// are the generated tag getters and `GlobParts.matches`, and `GlobParts.matches` is a body
// that only reads - the anchors and segments it holds, and the text through the prelude's
// `borrow fun` scans (`startsWith`, `findFrom`, `endsWith`) - so both prove borrow-clean
// and the text is never copied anywhere in the chain.
//
// The walk a `Parts` rule runs: one anchored `startsWith`, then each middle once, left to
// right, by `findFrom` - a `memchr` jump to the segment's first byte, then one `memcmp`
// (src/rtl/rtl.kt) - then the anchored `endsWith`. The suffix is the wall the middles may
// not cross, and that is what makes the greedy walk complete without backtracking: each
// leftmost fit lands at or before any placement's own position, inductively, and the
// end-anchored suffix closes the last gap. No backtracking, no recursion, no allocation at
// match time.
//
// Compile once, match many: `globCompile` builds the `Glob`, `.matches` tests texts
// against it. `globMatches` is the one-shot spelling.

package glob

// The segments of a `*`-carrying pattern: the anchored ends and the middle walk between
// them. An anchor of `""` is the pattern being `*`-open on that side (an empty literal
// segment matches everywhere, so the empty string is free as the marker); `minLen` is the
// segments' total, the cheapest reject there is.
data class GlobParts(var prefix: Str, var suffix: Str, var middles: List<Str>, var minLen: Int) {
    // Whether `text` matches these segments. `at` is the first byte the remaining segments
    // may occupy, `end` the first byte they may not (the suffix's wall); each middle must
    // fit wholly before the wall, so the suffix always has its place.
    //
    // The body only reads: the fields it holds, and the text through the prelude's
    // borrow-clean scans - the middles walk binds each segment's place (`*mid`), so a
    // segment is scanned where it lies in the list. That is what lets the auto-borrow
    // rewrite take `text` as `*Str`, and with it the whole match chain copy-free.
    //
    // The name is not `matches` on purpose: the borrow fixpoint is name-keyed, and a
    // dispatcher that calls another method of the same name would make the name depend on
    // itself - never provable, and with it neither method's `text` borrowed. Distinct
    // names are what let both halves prove independently.
    fun matchesText(text: Str): Bool {
        if (text.size() < this.minLen) {
            return false
        }
        var at: Int = 0
        if (this.prefix.size() > 0) {
            if (!text.startsWith(this.prefix)) {
                return false
            }
            at = this.prefix.size()
        }
        var end: Int = text.size()
        if (this.suffix.size() > 0) {
            end = end - this.suffix.size()
            if (at > end) {
                return false
            }
        }
        for (*mid in this.middles) {
            val found: Int = text.findFrom(*mid, at)
            if (found < 0) {
                return false
            }
            val after: Int = found + mid.size()
            if (after > end) {
                return false
            }
            at = after
        }
        if (this.suffix.size() > 0) {
            return text.endsWith(this.suffix)
        }
        return true
    }
}

union class Glob(var Exact: Str, var Parts: GlobParts) {
    // The tag decides the rule: an exact word is one view `==` (both operands are places -
    // the arm's storage and the borrowed text - so nothing is copied), and a `Parts`
    // pattern's rule lives in `GlobParts.matches`, whose own borrow proof this dispatcher
    // inherits. `None` (`Glob()`) matches nothing: `globCompile` never builds one.
    fun matches(text: Str): Bool {
        when (this.getTypeOf()) {
            SmGlobTypes.Exact -> {
                val word: *Str = this.getExact()
                return text == *word
            }

            SmGlobTypes.Parts -> {
                val parts: *GlobParts = this.getParts()
                return parts.matchesText(text)
            }

            else -> {
                return false
            }
        }
    }
}

// The pattern as the `Glob` a program matches with: the tag is the pattern's own shape, so
// an exact word compiles to the one-compare rule and everything else to its segments.
// `find` answers the first `*`, so a pattern without one never pays for the split.
fun globCompile(pattern: Str): Glob {
    if (pattern.find("*") < 0) {
        // The arm-constructor convention: `return (v)` builds the arm its type picks
        // (`Exact`, the one `Str` arm). The arm *owns* its `Str`, and `pattern` is only
        // borrowed here, so the one copy the ownership needs happens on the owned local.
        val owned: Str = pattern
        return (owned)
    }
    val parts: List<Str> = pattern.split("*")
    var prefix: Str = ""
    var suffix: Str = ""
    var middles: List<Str> = List<Str>()
    var minLen: Int = 0
    var first: Int = 0
    var last: Int = parts.size() - 1
    if (pattern[0] != '*') {
        val head: Str = parts[0]
        prefix = head
        minLen = minLen + head.size()
        first = first + 1
    }
    if (pattern[pattern.size() - 1] != '*') {
        val tail: Str = parts[last]
        suffix = tail
        minLen = minLen + tail.size()
        last = last - 1
    }
    var i: Int = first
    while (i <= last) {
        val mid: Str = parts[i]
        if (mid.size() > 0) {
            middles.append(mid)
            minLen = minLen + mid.size()
        }
        i = i + 1
    }
    return (GlobParts(prefix, suffix, middles, minLen))
}

// The one-shot spelling: compile, match, drop. The compiled form is thrown away, so a
// loop over many texts against one pattern wants `globCompile` + `.matches` instead.
fun globMatches(pattern: Str, text: Str): Bool {
    val compiled: Glob = globCompile(pattern)
    return compiled.matches(text)
}
