// tasks.kt
//
// The work pool (impl_specs/async.md, "The runtime"): a set of *named queues*, each a small pool
// of worker threads, which is where a suspension's blocking work happens. The thread that drives
// the loop is a program's main logic and is not a worker; workers are the io side. They meet only
// at the queues, and only *values* cross one: a task frame's reference count is not atomic, so a
// counted handle must never be reachable from two threads, while a `Str`/`List` is uniquely owned
// and safe to hand over. A socket handle is an `Int64`, so it crosses like any other value.
//
// **A queue is identified by an `Int`** (`tasksQueue`), and a program chooses the ids it wants:
// the counts are the whole knob. A queue's *thread count* is that queue's concurrency (a blocking
// leaf holds its worker for the whole wait), and two queues of the same kind buy isolation - a
// slow kind cannot starve another - rather than more capacity. The ids the RTL's own leaves use
// are conventional and documented below; a program's own queues should use its own ids (16 and
// up, say) so a later default cannot collide with them.
//
// Conventional ids: 0 accept, 1 file read, 2 file write, 3 socket read, 4 socket write.
//
// This is the synchronous surface of it: submit, join, read what settled. The `suspend` lowering
// drives the same queues without a blocking join, which is why the pieces here are queues and
// slots rather than a single "read these files" convenience.

package rtl

// Make `id` a queue of `threads` workers, or add workers to a queue already made. A queue a
// submit created on its own has one worker; calling this afterwards only ever adds.
@SmGen("res", "tasks", "simse_tasksQueue")
fun tasksQueue(id: Int, threads: Int): Unit

// Mark `id` a *compute* queue: the scheduler hands it tasks (round-robin) rather than file jobs.
// A program that marks none keeps the single-threaded caller's queue.
@SmGen("res", "tasks", "simse_tasksCompute")
fun tasksCompute(id: Int, threads: Int): Unit

// The sync root's join: drive until every task has completed. A root that `forget`s work calls this
// after its own loop, because the caller's thread - not a task - is what would otherwise stop.
@SmGen("res", "tasks", "simse_tasksDrain")
fun tasksDrain(): Unit

// Hand a fresh, parentless task to the queues: the one thing `tasksSpawn` does. It resumes nobody,
// so the thread that runs it to completion frees it.
@SmGen("res", "tasks", "simse_tasksEnqueue")
fun tasksEnqueue(child: *Int8): Unit

// Hand `child` to the queues and step aside, so the caller does not wait for it. A `suspend`
// function like any other - the suspension is what yields the caller's thread so the spawned task
// gets a turn - and it answers `true`. `child` comes from a suspend function's factory
// (`handleTask(conn)` for `handle`), which the compiler declares alongside the function.
//
// `Bool`, not `Res<Bool>`: the prelude must not name a bare type a program may redefine (a program
// declaring its own `Res` with an `unInit` would otherwise bind this return type to it).
suspend fun tasksSpawn(child: *Int8): Bool {
    tasksEnqueue(child)
    return true
}

// Stop and join every queue. A program that never calls this still exits: the pool belongs to the
// runtime, not to the program.
@SmGen("res", "tasks", "simse_tasksStop")
fun tasksStop(): Unit

// Hands a file read to queue `id`. `slot` is where the text lands; the read happens on one of
// that queue's workers, and nothing is read back out until the join.
@SmGen("res", "tasks", "simse_tasksSubmitReadOn")
fun tasksSubmitReadOn(id: Int, slot: Int, path: Str): Unit

// The structural join: waits until `count` submitted jobs have settled, in whatever order the
// workers happened to finish them. The result is deterministic even though the completion order
// is not - the property the corpus depends on.
@SmGen("res", "tasks", "simse_tasksJoin")
fun tasksJoin(count: Int): Unit

// The text a settled job read; empty when the file could not be read.
@SmGen("res", "tasks", "simse_tasksText")
fun tasksText(slot: Int): Str

// Step-one scaffolding: drive a hand-written two-task chain (the root suspends on a child, the
// child runs a synchronous fake leaf that answers 42 and completes, the child hands the root back
// to the loop, the root resumes at its resume label and reads the child's result) and answer the
// steps it went through - plain numbers, with the value that crossed the suspension among them. It
// is here to prove the task header, the status discipline and the release order before the
// `suspend` lowering exists; `emitTask` replaces it, and a program never names a task
// (impl_specs/async.md, "The emitted shapes").
@SmGen("res", "tasks", "simse_tasksChainTrace")
fun tasksChainTrace(): List<Int>

// The task ABI's loop entry, and the reach marker for it: a suspending `main` starts its root
// task and drives the loop with this, so a program that suspends carries the `Task` base and
// the loop (impl_specs/async.md). A program that never suspends never reaches it.
@SmGen("res", "tasks", "simse_tasksRunLoop")
fun tasksRunLoop(): Unit

// The task protocol a lowered `suspend` body calls (impl_specs/async.md); a program never names
// any of them, and the loop's current task is the caller, so none takes a task argument.
@SmGen("res", "tasks", "simse_tasksBranch")
fun tasksBranch(): Int

@SmGen("res", "tasks", "simse_tasksFinish")
fun tasksFinish(): Unit

@SmGen("res", "tasks", "simse_tasksSuspendAt")
fun tasksSuspendAt(child: *Int8, at: Int): Unit

@SmGen("res", "tasks", "simse_tasksReleaseHandle")
fun tasksReleaseHandle(child: *Int8): Unit
