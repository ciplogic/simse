package fixtures

// The work pool (`cppsrc/rtl/tasks.kt`, the `tasks` section of `cppsrc/rtl/_res.md`,
// impl_specs/async.md): `io` worker threads behind a work queue and a completion queue, and a
// structural join. The thread that calls `tasksStart` is the *loop* - a program's main logic -
// and the workers are the io side; the two meet only at the queues, and only values cross one.
//
// Four reads on two workers: the queue feeds them one at a time, and the join waits for all
// four however the workers happened to finish them.

fun main(): Int {
    val one: Str = "stress/tasks/data/one.txt"
    val two: Str = "stress/tasks/data/two.txt"
    tasksStart(2)
    tasksSubmitRead(0, one)
    tasksSubmitRead(1, two)
    tasksSubmitRead(2, one)
    tasksSubmitRead(3, two)
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
