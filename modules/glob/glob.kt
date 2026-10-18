// glob.kt
//
// Glob matching over `Str` (a byte string, so patterns and texts match byte for byte -
// case-sensitive, no encoding anywhere). The only wildcard is `*` (any run, including the
// empty one); everything else in a pattern is a literal byte, and `*` has no escape - a
// pattern that needs a literal `*` cannot spell it, the same rule a raw string's `@` has.
// There is no `?` and no `[...]`: those would break the one optimization this module
// exists for, and a glob rich enough to want them is a regex.
//
// The compiled shape is the pattern split at its `*`s, the way a `*`-only glob is always
// matched fastest:
//
//   - `exact`   - the whole pattern, when it holds no `*` at all: the match is one
//                 `==` (the exact-word specialization).
//   - `prefix`  - the first segment, when the pattern does not start with `*`: an
//                 anchored `startsWith`, so nothing before it is scanned.
//   - `suffix`  - the last segment, when the pattern does not end with `*`: an anchored
//                 `endsWith`, and the wall the middle segments may not cross - which is
//                 what makes the greedy middle walk below complete without backtracking.
//   - `middles` - the segments between the anchors (empty segments dropped: `a**b` is
//                 `a*b`), each matched once, left to right, by `findFrom` - a `memchr`
//                 jump to the segment's first byte, then one `memcmp` (src/rtl/rtl.kt).
//
// The prefix and the suffix are `Opt<Str>` (absent when the pattern is `*`-open on that
// side), the middles are the leftovers, and `minLen` (the segments' total) rejects a text
// that cannot hold them before any byte is compared. A match walks the text once: an
// earlier segment's leftmost fit never costs a later one, because the suffix is pinned to
// the end - if any placement of the segments exists, the greedy one is inside it
// (each greedy find lands at or before the placement's own position, inductively, and the
// wall ends the walk). No backtracking, no recursion, no allocation at match time.
//
// Compile once, match many: `globCompile` builds the `Glob`, `.matches` tests texts
// against it. `globMatches` is the one-shot spelling.

package glob

data class Glob(var exact: Opt<Str>, var prefix: Opt<Str>, var suffix: Opt<Str>, var middles: List<Str>, var minLen: Int) {
    // Whether `text` matches the compiled pattern. `at` is the first byte the remaining
    // segments may occupy, `end` the first byte they may not (the suffix's wall); each
    // middle must fit wholly before the wall, so the suffix always has its place.
    fun matches(text: Str): Bool {
        if (this.exact.hasValue()) {
            return text == this.exact.value()
        }
        if (text.size() < this.minLen) {
            return false
        }
        var at: Int = 0
        if (this.prefix.hasValue()) {
            val head: Str = this.prefix.value()
            if (!text.startsWith(head)) {
                return false
            }
            at = head.size()
        }
        var end: Int = text.size()
        if (this.suffix.hasValue()) {
            end = end - this.suffix.value().size()
            if (at > end) {
                return false
            }
        }
        for (mid in this.middles) {
            val found: Int = text.findFrom(mid, at)
            if (found < 0) {
                return false
            }
            val after: Int = found + mid.size()
            if (after > end) {
                return false
            }
            at = after
        }
        if (this.suffix.hasValue()) {
            return text.endsWith(this.suffix.value())
        }
        return true
    }
}

// The pattern as the `Glob` a program matches with: an exact word is its own answer, and
// otherwise the `*`-split segments land as prefix, suffix and middles. `find` answers the
// first `*`, so a pattern without one never pays for the split.
fun globCompile(pattern: Str): Glob {
    if (pattern.find("*") < 0) {
        var empty: List<Str> = List<Str>()
        return Glob(Opt<Str>.some(pattern), Opt<Str>.none(), Opt<Str>.none(), empty, pattern.size())
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
    return Glob(Opt<Str>.none(), prefix, suffix, middles, minLen)
}

// The one-shot spelling: compile, match, drop. The compiled form is thrown away, so a
// loop over many texts against one pattern wants `globCompile` + `.matches` instead.
fun globMatches(pattern: Str, text: Str): Bool {
    val compiled: Glob = globCompile(pattern)
    return compiled.matches(text)
}
