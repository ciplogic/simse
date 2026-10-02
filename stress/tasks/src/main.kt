package fixtures

// The work pool (`src/rtl/tasks.kt`, the `tasks` section of `src/rtl/_res.md`,
// impl_specs/async.md): *named queues*, each a small pool of worker threads, and a structural
// join. The thread that drives the loop is a program's main logic; the workers are the io side.
// The two meet only at the queues, and only values cross one.
//
// A queue is an `Int` the program chooses, and the thread count is the whole knob. Two queues
// here - one for file reads with two workers, one with a single worker - show that each queue
// serves its own submissions: the ids are the app's, the counts are the app's, and the join is
// the one shared completion side. Four reads land in their slots whatever order the two queues
// finished them in.

fun main(): Int {
    val one: Str = "stress/tasks/data/one.txt"
    val two: Str = "stress/tasks/data/two.txt"

    tasksQueue(1, 2)
    tasksQueue(2, 1)

    tasksSubmitReadOn(1, 0, one)
    tasksSubmitReadOn(2, 1, two)
    tasksSubmitReadOn(1, 2, one)
    tasksSubmitReadOn(2, 3, two)
    tasksJoin(4)

    var slot: Int = 0
    while (slot < 4) {
        // The content, not the byte count: a file's line ending is the editor's business, and
        // what this case proves is that the right text crossed the queues into the right slot.
        println(tasksText(slot).trim())
        slot = slot + 1
    }
    tasksStop()
    return 0
}
