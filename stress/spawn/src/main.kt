package fixtures

// `tasksSpawn` (impl_specs/async.md): the compiler declares a factory per suspend function
// (`workTask` for `work`), and `tasksSpawn` hands the child to the queues and steps aside, so the
// caller does not wait for it. With no compute queue made, every task runs on the caller's own
// queue, so the order is the FIFO order the rest of the runtime pins: the three spawned tasks run
// before `main` resumes to print `done`.

suspend fun work(n: Int): Int {
    println(n)
    return n
}

suspend fun main(): Int {
    tasksSpawn(workTask(1))
    tasksSpawn(workTask(2))
    tasksSpawn(workTask(3))
    println("done")
    return 0
}
