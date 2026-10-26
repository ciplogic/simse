// Diagnostics.kt
//
// `--diagnostics <topics>`: per-stage trace output, on stderr, off unless asked for. The
// flag takes a comma-separated topic list (`--diagnostics lowering,il`) or `all`. The
// topics are the stages in compile order:
//
// - `driver`  - the CLI's own spans: the output written, the wall time;
// - `parse`   - every file and prelude module parsed, with its declaration count;
// - `gen`     - the source generators: the generated modules, the resource sections;
// - `borrow`  - the auto-borrow pass: the fixpoint's tables, then what `--showBorrow`
//               reports (the flag bridges to that report);
// - `escape`  - the escape-parameter pass, bridged to `--showEscape` the same way;
// - `sema`    - the analysis and the rewrites: which `for` was span-converted, promoted,
//               place-promoted, span-hoisted, walked or refused (with the reason);
// - `async`   - the suspension coloring: how many task functions the program reached;
// - `lowering`- the linear stages per body: the statement head after each one;
// - `il`      - the extracted IL per body, op counts after the fusion and the reuse;
// - `codegen` - the emitter's phases: sections, reached names, task/prototype/body passes.
//
// Convention: `diagTrace(topic, text)` prints nothing unless the topic is on, but its
// arguments are built eagerly - so anything past a constant goes behind
// `if (diagOn(topic)) { ... }`, and the flag-off path pays nothing.

package compiler
import io

var diagEnabledTopics: List<Str> = List<Str>()

// The topic list from the flag's argument: comma-separated, spaces trimmed, empties dropped.
// Built by slicing (`substr`): a `Char` is a numeric scalar, so its `toString` is a number,
// not the glyph.
fun diagSetTopics(spec: Str): Unit {
    var out: List<Str> = List<Str>()
    var start: Int = -1
    var i: Int = 0
    while (i <= spec.size()) {
        val atEnd: Bool = i == spec.size()
        var boundary: Bool = atEnd
        if (!atEnd) {
            val ch: Char = spec[i]
            boundary = ch == ',' || ch == ' ' || ch == '\t'
        }
        if (boundary) {
            if (start >= 0 && i > start) {
                val topic: Str = spec.substr(start, i - start)
                if (!out.contains(topic)) {
                    out.append(topic)
                }
            }
            start = -1
        } else {
            if (start < 0) {
                start = i
            }
        }
        i++
    }
    diagEnabledTopics = out
}

fun diagOn(topic: Str): Bool {
    for (*enabled in diagEnabledTopics) {
        if (enabled == "all" || enabled == topic) {
            return true
        }
    }
    return false
}

// One trace line: `[diagnostics:<topic>] <text>`, only when the topic is on.
fun diagTrace(topic: Str, text: Str): Unit {
    if (diagOn(topic)) {
        eprintln(`[diagnostics:@topic] @text`)
    }
}
