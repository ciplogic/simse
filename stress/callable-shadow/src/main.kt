package fixtures

// A call through a callable *local* whose name a declaration also owns: `step(10)` with
// `step` a local of callable type must be the *value's* call - the value's own signature
// is the conversion truth (CgCall's rule) - and never the prelude `Range.step` extension
// spelled as a plain call with its receiver as the first argument. The extension is
// exactly what used to win: the emitter resolved the name against declarations before
// considering the local, because the local's type came through a `typealias` and the
// callable-value rule's kind test did not see through it - so `step(10)` was spelled
// `step(simse_addressOf(10))` and the argument arrived as a `Range*` receiver. Both
// spellings that reach a callable value are pinned: through an alias (`Step`) and
// through a capture, which the rewrite turns into the same shape.

typealias Step = (Int) -> Int

fun twice(x: Int): Int {
    return x * 2
}

fun makeStep(): Step {
    return twice
}

fun runStepper(step: Step): Int {
    // A *parameter* named `step` of callable type: the same shadow, one scope out.
    return step(7)
}

fun main(args: List<Str>): Unit {
    val step: Step = twice
    val captured: Step = makeStep()
    val total: Int = step(10) + runStepper(captured) + captured(5)
    println(total.toString())
}
