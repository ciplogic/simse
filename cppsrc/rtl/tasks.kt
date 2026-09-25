// tasks.kt
//
// The work pool (impl_specs/async.md, "The runtime"): a small set of worker threads behind two
// queues, which is where a suspension's work will actually happen. The thread that calls
// `tasksStart` is the *loop* - a program's main logic - and the workers are the io side. They
// meet only at the queues, and only *values* cross one: a task frame's reference count is not
// atomic, so a counted handle must never be reachable from two threads, while a `Str`/`List`
// is uniquely owned and safe to hand over.
//
// This is the synchronous surface of it: submit, join, read what settled. The `Async`/`runAsync`
// lowering drives the same queues without a blocking join, which is why the pieces here are
// queues and slots rather than a single "read these files" convenience.

package rtl

// `ioThreads` workers, each taking jobs off the work queue. The caller's thread is the loop and
// is not counted, so `tasksStart(2)` is one thread of main logic and two of io. A pool that is
// already running is left alone.
@SmGen("res", "tasks", "simse_tasksStart")
fun tasksStart(ioThreads: Int): Unit

// Stops and joins the workers. A program that never calls this still exits: the pool belongs to
// the runtime, not to the program.
@SmGen("res", "tasks", "simse_tasksStop")
fun tasksStop(): Unit

// Hands a file read to the pool. `slot` is where the text lands; the read happens on a worker,
// and nothing is read back out until the join.
@SmGen("res", "tasks", "simse_tasksSubmitRead")
fun tasksSubmitRead(slot: Int, path: Str): Unit

// The structural join: waits until `count` submitted jobs have settled, in whatever order the
// workers happened to finish them. The result is deterministic even though the completion order
// is not - the property the corpus depends on.
@SmGen("res", "tasks", "simse_tasksJoin")
fun tasksJoin(count: Int): Unit

// The text a settled job read; empty when the file could not be read.
@SmGen("res", "tasks", "simse_tasksText")
fun tasksText(slot: Int): Str
