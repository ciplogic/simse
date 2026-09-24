// Optimize.kt
//
// The linear form's optimization passes (impl_specs/linear-lowering.md): a table each pass fills
// from its own file, and `linOptimizeBody`, the entry point the pipeline calls once per round of
// its fixpoint loop. A pass rewrites one body's statement list in place and answers honestly
// whether it changed it, and sees only a fully linear body (impl_specs/linear-il.md).

package optimizations

import common
import linear

// One pass: its name, and the rewrite, which answers whether the body it was given changed.
data class LinOptPass(
    var name: Str,
    var run: (*List<AstXmlNode>) -> Bool
)

var linOptPasses: List<LinOptPass>

fun getLinOptPasses(): *List<LinOptPass> {
    return * linOptPasses
}

// One pass's self-registration, so registering reads as one line at the end of its own file.
// It answers `Bool` because a static's initializer is an expression - the value is never read.
fun registerLinOptPass(name: Str, run: (*List<AstXmlNode>) -> Bool): Bool {
    getLinOptPasses().append(LinOptPass(name, run))
    return true
}

// Every registered pass, over one body, until a whole round changes nothing. The guard bounds a
// bug, not the work: every pass only removes statements or renames jumps, so it cannot oscillate.
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

// The name of the pass at `index`, or "".
fun linOptPassName(index: Int): Str {
    val passes: *List<LinOptPass> = getLinOptPasses()
    if (index < 0 || index >= passes.size()) {
        return ""
    }
    return passes[index].name
}
