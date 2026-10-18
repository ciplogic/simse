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
//   - `Exact(Str)`   - the pattern held no `*` at all: the match is one `==` (the
//                      exact-word specialization).
//   - `Parts`        - everything else, as `GlobParts`: the anchored ends (`prefix` /
//                      `suffix`, `Opt<Str>`, absent where the pattern is `*`-open) and the
//                      middle segments between them (`middles`, empty segments dropped:
//                      `a**b` is `a*b`), with `minLen` - the segments' total - for the
//                      reject that runs before any byte is compared.
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
// them. `prefix`/`suffix` are absent where the pattern is `*`-open on that side; `minLen`
// is the segments' total, the cheapest reject there is.
data class GlobParts(var prefix: Opt<Str>, var suffix: Opt<Str>, var middles: List<Str>, var minLen: Int) {
}

union class Glob(var Exact: Str, var Parts: GlobParts) {
    // Whether `text` matches the compiled pattern. The tag decides the rule: an exact
    // word is one view `==` (both operands are places - the arm's storage and the
    // borrowed text - so nothing is copied), and a `Parts` rule walks. `at` is the first
    // byte the remaining segments may occupy, `end` the first byte they may not (the
    // suffix's wall); each middle must fit wholly before the wall, so the suffix always
    // has its place.
    fun matches(text: Str): Bool {
        val tag: SmGlobTypes = this.getTypeOf()
        when (tag) {
            SmGlobTypes.Exact -> {
                val word: *Str = this.getExact()
                return text == *word
            }

            SmGlobTypes.Parts -> {
                val parts: *GlobParts = this.getParts()
                if (text.size() < parts.minLen) {
                    return false
                }
                var at: Int = 0
                if (parts.prefix.hasValue()) {
                    // The arm's place, not a copy: `getValue` answers the live `Str`'s
                    // address, `startsWith` borrows it (`borrow fun`), so the segment is
                    // read where it lies - once compiled, never copied out per match.
                    val head: *Str = parts.prefix.getValue()
                    if (!text.startsWith(*head)) {
                        return false
                    }
                    at = head.size()
                }
                var end: Int = text.size()
                if (parts.suffix.hasValue()) {
                    val tail: *Str = parts.suffix.getValue()
                    end = end - tail.size()
                    if (at > end) {
                        return false
                    }
                }
                for (*mid in parts.middles) {
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
                if (parts.suffix.hasValue()) {
                    val tail: *Str = parts.suffix.getValue()
                    return text.endsWith(*tail)
                }
                return true
            }

            else -> {
                // The `None` arm: `globCompile` never builds one, and a `Glob()` default
                // matches nothing rather than guessing.
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
    var prefix: Opt<Str> = Opt<Str>.none()
    var suffix: Opt<Str> = Opt<Str>.none()
    var middles: List<Str> = List<Str>()
    var minLen: Int = 0
    var first: Int = 0
    var last: Int = parts.size() - 1
    if (pattern[0] != '*') {
        val head: Str = parts[0]
        prefix = Opt<Str>.some(head)
        minLen = minLen + head.size()
        first = first + 1
    }
    if (pattern[pattern.size() - 1] != '*') {
        val tail: Str = parts[last]
        suffix = Opt<Str>.some(tail)
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
    return Glob(GlobParts(prefix, suffix, middles, minLen))
}

// The one-shot spelling: compile, match, drop. The compiled form is thrown away, so a
// loop over many texts against one pattern wants `globCompile` + `.matches` instead.
fun globMatches(pattern: Str, text: Str): Bool {
    val compiled: Glob = globCompile(pattern)
    return compiled.matches(text)
}
