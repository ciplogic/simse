package fixtures

// The prelude is rewritten like a module: the pass proves `Str.startsWith`, `StrView.startsWith`
// and `Str.endsWith` read `text`/`prefix`/`suffix`, so the emitted prelude takes `*Str` and a
// call site passes a pointer, never a copy - the code `StrView.startsWithPtr` used to generate.
// `StrView.find` stays by value on purpose: the resource text calls `self.find(`
// (src/rtl/_res.md), and the scan that decides is `bpCppCalled`
// (impl_specs/escape-analysis.md, "The prelude"). `--showBorrow` prints every decision.

fun main(): Int {
    var text: Str = "abcdef"
    var prefix: Str = "abc"
    val view: StrView = spanOfStr(*text)
    if (view.startsWith(prefix)) {
        println("view yes")
    }
    if (text.startsWith(prefix)) {
        println("str yes")
    }
    if (text.endsWith("def")) {
        println("ends yes")
    }
    return 0
}
