// Diagnostics.kt
//
// `--diagnostics <topics>`: per-stage trace output, on stderr, off unless asked for. A topic
// is a stage name - `sema`, `lowering`, `il`, `codegen` - and the flag takes a comma-separated
// list (`--diagnostics lowering,il`) or `all`. `diagTrace` is the one call site: it prints
// nothing unless the topic is on, so the trace lines are free when the flag is absent.

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
