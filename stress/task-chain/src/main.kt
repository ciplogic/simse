package fixtures

// The task header and the loop (`src/rtl/tasks.kt`, the `tasks` section of `src/rtl/_res.md`,
// impl_specs/async.md "The emitted shapes"): step one's proof that the status discipline and the
// release order hold, before the `suspend` lowering exists.
//
// `tasksChainTrace` drives a hand-written two-task chain - the root suspends on a child, the child
// runs a synchronous fake leaf that answers 42 and completes, the child releases the root and hands
// it back to the loop, the root resumes at its resume label and reads the child's result - and
// answers the steps it went through. There is no thread anywhere in it: the pool is not involved,
// which is what makes this a test of the tasks themselves rather than of the queues.
//
// The steps are the numbers 1..7, then the value the root read at its resume label (42 - the fake
// leaf's answer, which is what crossing a suspension has to carry), then 9 and 10. The numbered
// order is the proof: start, child created, root suspended, child's leaf ran, child released the
// parent *before* resuming it, child handed the root back, root resumed, 42 read out, child
// dropped, root done.

fun main(): Int {
    val steps: List<Int> = tasksChainTrace()
    for (step in steps) {
        println(step)
    }
    return 0
}
