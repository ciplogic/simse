package fixtures

// The lifetime of a task across a compute queue. A worker used to run a task and then read its
// status to decide whether to free it, while the task's completion resumed its parent on another
// worker of the same queue - and the parent's resume label releases the finished task. When the
// parent ran first, it freed the frame while the first worker was still in (or just out of)
// `run()`: a heap-use-after-free, deterministic under ASan (it aborted 2 of 5 runs before the
// guard) and rare in a plain build.
//
// `taskWorker`/`Loop::drive` now retain the task across the run and read the status before their
// release, and the count is atomic, since that guard release and the parent's handle release
// meet on two threads. The program spawns workers on a four-thread compute queue; each suspends
// on `leaf` many times, so a leaf completes on one worker while its parent resumes on another.
// Each worker's total lands in its own slot, so the output is deterministic; the crash is the
// best-effort part, the way `task-array` is (the deterministic evidence is ASan).

var results: Array<Int> = arrayEmpty<Int>()

suspend fun leaf(): Int {
    return 7
}

suspend fun work(id: Int): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < 2000) {
        total = total + leaf()
        i = i + 1
    }
    results[id] = total
    return total
}

fun main(): Int {
    results = Array<Int>(64)
    tasksCompute(16, 4)
    var i: Int = 0
    while (i < 64) {
        tasksEnqueue(workTask(i))
        i = i + 1
    }
    tasksDrain()
    var sum: Int = 0
    var j: Int = 0
    while (j < 64) {
        sum = sum + results[j]
        j = j + 1
    }
    println(sum.toString())
    return 0
}
