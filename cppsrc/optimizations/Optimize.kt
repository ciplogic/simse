// Optimize.kt
//
// The linear form's optimization passes (impl_specs/linear-lowering.md): a table of
// passes that each rewrite one body's statement list in place and answer whether they
// changed anything, and the one entry point the pipeline calls.
//
//   linOptimizeBody   `linFinishForEmission`, once per round of its fixpoint loop.
//
// A pass takes the body by pointer and rewrites it *in place* (the list itself, and the
// nodes it replaces through it), so nothing is copied to hand a body from one pass to
// the next; what it answers is whether the body it was given is a different body than
// the one it read, which is exactly what the round's `changed` flag is. A pass that
// always claims a change never lets the pipeline's round end, and one that never claims
// one stops it early - so a pass has to report honestly.
//
// The passes are **only** about the *shape* of a body that is already fully linear: one
// scope, every declaration at the top, every statement one operation, every branch a
// label and a jump. That is why this package exists next to `linear` rather than inside
// it - a body with a scope left in it is not a body these passes may reason about
// (impl_specs/linear-il.md, "the frame is flat").
//
// **The table is filled by the passes themselves**, each from its own file, with a
// file-level static whose initializer is the registration (`SourceGen.kt`'s table and
// `Scanner.kt`'s token matchers are the same shape). The table starts empty, and a
// registration is an append, so the order the initialization pass runs in cannot matter:
// storage starts empty as a guarantee, and nothing here overwrites it.
//
// The passes run in the order the table holds them - a file whose name sorts earlier
// registers earlier, so the order is readable off the directory. That is a *convention*
// and not a promise between passes: each one has to be correct on its own, since the
// pipeline loops until no pass reports a change.

package optimizations

import common
import linear

// One pass: its name (for a diagnostic and for reading the table by eye) and the rewrite
// itself, which answers whether the body it was given changed.
data class LinOptPass(
    var name: Str,
    var run: (*List<AstXmlNode>) -> Bool
)

var linOptPasses: List<LinOptPass>

fun getLinOptPasses(): *List<LinOptPass> {
    return * linOptPasses
}

// One pass's self-registration: what a pass file's file-level static is initialized with,
// so registering reads as one line at the end of the file that holds the pass. It answers
// `Bool` because a static's initializer is an expression and every static has a type - the
// value itself is never read.
fun registerLinOptPass(name: Str, run: (*List<AstXmlNode>) -> Bool): Bool {
    getLinOptPasses().append(LinOptPass(name, run))
    return true
}

// Every registered pass, over one body, until a whole round changes nothing. The body is
// rewritten in place, so the caller's own `List` is the one the passes saw; the answer is
// whether any pass changed it.
//
// The guard bounds a bug rather than the work: the pipeline's own loop already runs the
// passes to a fixpoint, and every pass here only removes statements or renames jumps - a
// monotone rewrite that cannot oscillate between two shapes.
fun linOptimizeBody(stmts: *List<AstXmlNode>): Bool {
    var changed: Bool = false
    var round: Bool = true
    var guard: Int = 0
    while (round && guard < 16) {
        guard = guard + 1
        round = false
        val passes: *List<LinOptPass> = getLinOptPasses()
        var i: Int = 0
        while (i < passes.size()) {
            if (passes[i].run(stmts)) {
                round = true
                changed = true
            }
            i = i + 1
        }
    }
    return changed
}

// The name of the pass at `index`, or "" - the table read by eye (and by a test).
fun linOptPassName(index: Int): Str {
    val passes: *List<LinOptPass> = getLinOptPasses()
    if (index < 0 || index >= passes.size()) {
        return ""
    }
    return passes[index].name
}
