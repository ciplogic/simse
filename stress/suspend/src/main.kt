package fixtures

// The task machinery (`cppsrc/linear/Task.kt`, `emitTask` in `cppsrc/codegen/Codegen.kt`,
// impl_specs/async.md): a `suspend` function is a task the loop drives, and every call is a real
// suspension - there is no fast path - so a call site is `<f>_smNew` / `tasksSuspendAt` /
// `return` / `LTk:` / `<f>_smResult` / `tasksReleaseHandle`.
//
// This case is about the two shapes that share a body with the *loop* lowering:
//
//   * `main` suspends and then runs a loop (the resume label before the loop's labels);
//   * `doubled` suspends *inside* a loop (the resume label among the loop's labels).
//
// Both are ordinary code for the async lowering, which runs after the linearizer has turned the
// loops into labels and gotos - but they are what a crash in that lowering showed up on, so they
// are pinned here rather than left in a scratch directory.

suspend fun answer(): Int {
    return 42
}

// A suspension inside a loop: the value crosses the suspension, so it is a field of the task,
// and the resume label sits between the loop's own labels.
suspend fun doubled(n: Int): Int {
    var sum: Int = 0
    var i: Int = 0
    while (i < n) {
        val value: Int = answer()
        sum = sum + value
        i = i + 1
    }
    return sum
}

fun main(): Int {
    // A suspension before a loop: `result`, then the loop's labels.
    val a: Int = answer()
    var b: Int = 0
    var i: Int = 0
    while (i < 2) {
        b = b + a
        i = i + 1
    }
    println(a)
    println(b)
    println(doubled(3))
    return 0
}
