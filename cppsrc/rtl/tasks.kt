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
