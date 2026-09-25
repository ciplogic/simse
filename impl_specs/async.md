# `Async<T>`: colorless async, ref-counted tasks

The goal: a program suspends where it waits (file I/O, and later sockets), without the
function coloring C#-style `async`/`await` forces on a codebase. The user writes the *leaves*
— the functions that actually wait — and the compiler works out which of their callers can
suspend too, transitively, up to `main`. A function no suspension reaches keeps its signature,
its direct call and its plain C++: **coloring stops where the suspensions do**, which is the
whole point. `docs/examples/async/src/main.kt` is the target program (`copyFile` → two file
leaves, `describe` deliberately synchronous), and `--showAsync` is how the inference is
inspected.

## The pieces

| piece | state |
| --- | --- |
| `x!!` — `Res` payload, or an early `return` of the failure | **done**, `cppsrc/parser/Propagate.kt` |
| the coloring pass + `--showAsync` | **done**, `cppsrc/sema/Async.kt` |
| the work pool: threads behind two queues, and a structural join | **done**, `cppsrc/rtl/tasks.kt`, the `tasks` section of `cppsrc/rtl/_res.md`, `stress/tasks` |
| `Async<T>` in the checker (the awaited type at a call site) | next |
| the push machine (a body that suspends → a state machine) | next |
| `asyncRunTransform` / `runAndForget` lowering over the pool | next |
| the file leaves, suspended rather than blocking | next |
| the corpus: file present, file absent | next |

## `Async<T>` is a marker, not a value

Like `..T` (`impl_specs/yield.md`), `Async<T>` is **not a value type**: it is a return-position
marker meaning "this body can suspend and finishes with `T`" — C#'s `async Task<T>` in one word.
`Async` is therefore a reserved name (the emitter's type table is flat by name; keep it unique
project-wide).

`Async<T>` is **equivalent to `&Async<T>`**: the task instance is a ref-counted heap frame, so
every reference to it — the runtime's root, a parent waiting on it, a child holding its parent —
is counted and the frame dies when the last one drops. That is what makes the lifetime work in
the cases that would otherwise need manual reasoning (a task outliving the frame that started
it, a parent kept alive by a child still running), with no GC and no ownership analysis.

## No coloring to write

```
async(f) = declared Async (f)  ∨  ∃ edge f → g with async(g)      -- least fixed point
```

and a call site suspends exactly when its resolved callee answers `Async<T>`; the value the
call produces is the `T`. There is **no `await`/`suspend` keyword in the source** — the call is
the suspension, which is the "invisible" half of the feature. `Async<Async<T>>` is not a thing.

The graph is **name-level**, the approximation the prelude reachability already uses
(`impl_specs/for.md`): a callee is the name a call spells (`f`, `f<T>`, or the member of `x.f`).
Consequences to keep in mind:

- a name two declarations share is one node, so a collision colors conservatively (an
  over-approximation: safe, just slower);
- a call *through a function-typed local or a lambda parameter* has no name to reach, so the
  pass cannot see it. Closed world is what makes the rest knowable — every module is scanned
  and nothing links separately. Until that is settled, treat an indirect call that could reach
  a suspension as a diagnostic rather than a silent miss.

`main` is colored like any other function, and becomes the root task the runtime drives.

## Status, calls, and `asyncRunTransform`

A task needs a **status** orthogonal to `branch`: `branch` says *where* in the body execution
stopped, the status says *why it is not running* - `Runnable`, `Running`, `Suspended`, `Done`.
It lives on the ref-counted task header, next to the count, because the runtime sees tasks
through the type-erased base rather than as the generated machine class. Three jobs:

- **the enqueue guard** - `Suspended -> Runnable` enqueues, and a second wake on an already
  `Runnable` or `Done` task is a no-op. Without it a duplicate completion runs the resumption
  body twice, from a branch whose awaited value is being written.
- **the late wake** - a finished task is still alive while its parent reads its result, so
  `Done` is what keeps a trailing completion from re-entering a machine that returned.
- **the sync-completion fast path** - the suspension is conditional on the child's status:
  `if (child.status != Done) { suspend }`, so a callee that never waited leaves the caller
  runnable, with no frame and no round trip.

A *call* to an async function creates the task and awaits it: a call is a spawn and a wait in
one, and it is where a suspension comes from. **Concurrency has exactly one constructor**,
`asyncRunTransform`: a list of items, a transformer, one task per item, joined structurally, one
result per item in input order.

```simse
fun asyncRunTransform<T, TResult>(
    items: *List<T>, transformer: (T, *TaskToken) -> Res<TResult>
): List<Res<TResult>>
```

`runAsync` (a fixed-arity tuple of thunks returning a `Pair`) was in the design and is **dropped**:
it is the same machinery, and two compiler-known constructs for one idea is one too many. What the
drop costs is honest and small - two tasks of *different* types can no longer be started together
directly, so a heterogeneous fan-out models its jobs as items and dispatches in the transformer,
while same-typed thunks are simply items whose transformer calls them. `Pair` drops off the
requirements with it.

The fan-out is **dynamic** (`items.size()`), so unlike a fixed-arity form it cannot be unrolled
into N children: the lowering needs a **list of child handles in the frame** and a
**pending-children count** the join drains. That is why `asyncRunTransform` is compiler-known -
like `listOf` and its `Pack` instruction (`impl_specs/linear-il.md`) - with the signature above,
which is the price of the one shape a server actually wants. It is also why the language needs no
`spawn` keyword and no `join`: **a task is never a first-class value**, `Async<T>` stays a
return-position marker, and a task's handle is always the compiler's storage for a suspension.

The token is a **borrow** (`*TaskToken`), not a copy: a copy would carry its own cancelled flag, so
cancellation would not be shared. `items` is borrowed too, which only the structural join makes
safe - a task may borrow what outlives it, and nothing else. Cancellation is **cooperative and
explicit**: `token.validate(): Res<Unit>` is the check, `token.validate()!!` is how a transformer
surfaces it (the message carried into *that item's* `Res<TResult>` by the ordinary `!!` remap
path), and `asyncRunTransform` cancels the token when an item fails so the rest notice at their next
check. A task that never checks runs to completion and keeps its result. Testing the token
automatically at every suspension was the alternative and is **not** taken: it would put a token
reference in every task header and a test at every resume, taxing every async function in the
program to serve one pattern.

Results are per item rather than `Res<List<TResult>>`, so a failure is information about *that*
item; a caller who wants "stop at the first failure" folds the list.

**`!!` inside a lambda works, so the transformer can be an inline lambda.** A lambda has no
declared return type, but it does not need one: the target is the `ReturnType` of the *parameter's
function type*, which is reachable syntactically from the callee's declaration.
`cppsrc/parser/Propagate.kt` resolves the callee by name, takes its `Param` at the lambda's argument
position, and reads the result out of the `TypeFunction` node; the pass runs over lambda arguments
first, so their `!!` belongs to *their* result and the enclosing function need not answer a `Res`
itself. `stress/propagate-lambda` covers both paths inside a lambda - the identity one and the
remap one - with a `main` that returns an `Int`.

The cost of that reach is that the lookup is **name-level**, one of the approximations this compiler
uses twice now (the coloring pass does the same): a name two declarations share resolves to the
first, which can cost the identity path but never correctness, because the remap form is always
right. Because a lambda's target is often a *prelude* declaration (`asyncRunTransform`), the pass
runs once in the driver, after every module including the prelude is parsed, rather than per module
as it did when the target was only ever the enclosing function's own return type. A lambda the pass
cannot place - no resolvable callee, or a parameter that is not a function type - is left alone
unless it holds a `!!`, which is reported rather than handed to the C++ compiler.

The API takes results rather than **write-back closures**, and that is not stylistic: Simse
lambdas capture **by value**, so `(item) -> { text = readFile(); }` assigns to the lambda's own copy
of `text` and the enclosing local never changes - silently, with no diagnostic. Reference captures
are a deferred feature, and even with them two tasks writing one location is cross-task shared
state, which is what the pool rule forbids.

The status and the pending-children count remain, but they are the *lowering's* business rather
than an ABI a program can see. Slice 1 keeps **structural joins only**: a join that awaits all of
its children has a deterministic result whatever order the pool answered in. A "first one wins"
race does not, so it is a separate, deliberate construct rather than something the queue gives
away.

Independence is **`runAndForget(...)`**: the accept loop hands a request to a task the loop owns
and never joins - the root's rule one level down - so N requests stay in flight while it keeps
accepting. It is the same start-one machinery a call uses, minus the join, and a task nobody
joined is freed when it completes because the loop holds the only reference to it.

## The machine is *push*, where `yield` is *pull*

`yield` is driven by its caller (`advance()`), an `Async` task is driven by what it waits on:

| | `..T` yieldable | `Async<T>` task |
| --- | --- | --- |
| who drives it | the caller pulls, `advance() -> Bool` | the callee pushes: it resumes the caller |
| lifetime | a stack value, or `&`-boxed | a counted handle; it outlives the frame that started it |
| result | `current`, re-read per step | delivered once, into the caller |

The lowering still reuses `linear/Yield.kt` wholesale — the field collection, the `branch`
dispatcher, the resume labels, the label/goto form, the IL and its emitter. What differs is the
code *at* the point, exactly as the surface differs:

- **a suspension** (`val x = f(args)`, `f` async): create the child task, hand it a counted
  reference to `this`, set the branch, `return` (this is the suspension); the resume label reads
  the child's result into `x`.
- **`return v`**: store `v` as the result, release the parent reference as described below,
  resume the parent.
- anything the body does between suspensions is ordinary linear code, unchanged.

**Sync completion.** A callee that finishes without suspending must return its result directly —
no allocation, no resume round trip. Without this an async call that never actually waits would
cost a frame and a trampoline bounce, which is the performance objection to coloring in the
first place.

**No cycle.** The parent holds a counted reference to the child (it must read the child's result
after the child finishes) and the child holds one to the parent (it must resume it) — so during
a suspension the two hold each other. The order of release is what keeps that from being a leak:
the child **drops its parent reference before resuming the parent**, and the parent **drops its
child reference after reading the result**. The only interval in which both counts are live is
the suspension, and it always ends in one of those two releases. The child additionally needs a
**self-reference for the duration of its completion step** (the parent may drop the last
external reference to the child while the child's own frame is on the stack).

Where the parent's drop goes is exact, and it is what makes the free lock-free:

- **read the result out first, then drop the handle** (`result = child.result; child = null;`),
  never the other way round - and the result must be a *value*. A borrow of the child's own
  storage would dangle the moment the child dies; `Res<Str>` and friends are values, so the
  parent can let the child go immediately.
- the last release then happens **on the loop thread, at the resume label**, in ordinary
  single-threaded code, which is the whole reason no lock is needed to free a task.
- the child's death is deferred to the end of its **completion step** by the self-reference
  above, because the parent drops its handle while that step's frame is still on the stack.
- an **awaited child's handle is not user-visible**: it is the compiler's storage for the
  suspension, dropped at the resume label every time. The slot is dead afterwards, so the
  existing local merging (`cppsrc/optimizations/usedef/MergeLocals.kt`) can reuse it - the
  handle costs a frame slot during the wait and nothing after it. A **`spawn`ed task's handle
  is** user-visible: an ordinary `Async<T>` value whose lifetime its own scope decides, dropped
  on `join` or when the name goes out of scope. Two constructions, two lifetimes, one rule.
- a callee that completed **synchronously** has no frame at all: nothing was created, nothing
  suspends, nothing is freed. That is the sync-completion fast path paying off.
- the root is the same rule one level up - the runtime holds `main`'s handle and drops it when
  the root completes.

## The runtime: a loop, and a small pool behind queues

The target is a small Express-like HTTP server, and it decides the shape: a **loop thread**
hosting **one task per request**, and a **small pool of worker threads (about four)** doing the
work that has to block or burn CPU - file reads and writes, gzip. The two sides meet at
**queues**, which is why queues belong to the ABI rather than to a runtime's internals:

| queue | direction | carries |
| --- | --- | --- |
| work | loop -> pool | a job: a value-only input (a path, a buffer, a level) |
| completion | pool -> loop | the job's result value, and which task was waiting for it |

**Synchronization is the queue's, and nothing else's.** The counts stay non-atomic: a
non-atomic count is only wrong when two threads touch it at once, and the design makes that
impossible rather than making it cheap to do. A reference is **transferred under the queue's
lock** - the enqueue retains, the dequeue takes ownership, both inside the same critical
section - and otherwise a task has exactly one owner. That is the whole of the requirement, and
it is affordable exactly where it is paid: a mutex is tens of nanoseconds uncontended, and the
two acquisitions a job costs sit next to a file read or a socket send.

The lock covers the queue *item*, never a task. A worker never reads or writes a task field: it
puts the result value into the completion item and the loop copies it into the task. So no task
state is shared, no ordering question arises about it, and `free` needs no lock of its own -
because **only the loop destroys a task**. A per-task mutex would buy nothing and add a
re-entrancy hazard: a free that runs under a lock is a deadlock waiting for a destructor to touch
the same queue, and the loop, being single-threaded, can free in the ordinary way.

Three structures, and only two of them are shared:

| structure | who touches it | protection |
| --- | --- | --- |
| ready queue (which task runs next) | the loop only | none - single-threaded, and the FIFO order determinism rests on |
| work queue | loop -> pool | one mutex + condition variable |
| completion queue | pool -> loop | one mutex + condition variable |

**Values cross the boundary; handles never do.** This is the rule the whole thing rests on. The
language's `&T` counts are *not* atomic (`specs/memory-model.md`, single-threaded counting), so a
counted handle must never be reachable from two threads at once. A `Str`/`List`/`Dictionary` is
uniquely owned by value semantics, so *moving* one across a queue is safe with no lock and no
atomic refcount; sharing an `&T` would not be. Every task frame, every machine's state and every
status transition therefore stays on the loop thread - the pool sees inputs and answers outputs
and knows nothing about tasks.

That is what "functional style" has to mean, made checkable: a function is **pool-schedulable**
when it takes and returns only value types (no `&T`, no `*T`), reads no file-level `var` static,
and calls only pool-schedulable functions and RTL jobs. Closed world makes that a static check.
Such a function can be handed to the pool as a job; anything else can only run on the loop.

The loop's idle point is a blocking pop on the completion queue: the one place the runtime
waits, and the only place a lock is ever taken. (`README.md` and `docs/state-of-the-field.md`
promise **no threads** today. That becomes "no threads for a program's own logic; a fixed pool
inside the RTL for work that is a pure function of its inputs", and `specs/memory-model.md`
needs that sentence before the pool lands - the counts stay single-threaded, and the reason they
may is the rule above.)

## The runtime

Single-threaded and deterministic, because the repository's strongest invariants are
byte-identical output and behaviour that does not vary between runs — so **the resume order is
pinned** (FIFO), and nothing depends on an unordered container.

- `main`'s task is the root; the runtime drives it to completion and answers its exit code.
- A job is submitted to the work queue and the submitting task suspends; the pool answers on the
  completion queue, and the loop marks the waiting task `Runnable`, enqueues it and runs it.
  Nothing spins: when the ready queue is empty the loop blocks on the completion queue, which is
  all that is left of the "close the world" step.
- The leaves are the RTL's: `readFileTextAsync(path): Async<Res<Str>>` and
  `writeFileAsync(path, text): Async<Res<Int>>`, returning `Res` because a file may not exist.
  A missing file is then an ordinary value: `!!` propagates it, no exception and no callback.
  They are `@SmGen("res", ...)` declarations, so the runtime's C++ lives in a resource section
  like the rest of the platform's operations (`impl_specs/generators.md`).

The corpus case is the program in `docs/examples/async/`, run twice: the file present (the copy
succeeds and the byte count is printed) and the file absent (the failure propagates out of
`copyFile` through `!!` and `main` reports it).

## The emitted shapes (`linear/Async.kt`, `codegen`) - what is left to write

The design above is settled; these are the concrete shapes step 4 has to produce, recorded so it can
be written without re-deriving anything. Each async function becomes a task class, reified per
instantiation and named like the machine is (`<fn>_task`, `<outer>_<fn>_task` for an extension).

**The class:** `branch` (0 = start, `k` = the k-th suspension, `-1` = done - the `yield` convention,
so the dispatcher is the same chain of conditional jumps `emitMachine` already prints, with the
resume entry where `advance()` was); the parameters and every local that lives across a suspension;
`result` (the `T` of `Async<T>`, what `return v` stores); the refcount, `status` and `parent` in the
shared header; one child-handle field per suspension; and a pending-children count in a body that
fans out.

**A suspension** (`val x = f(args)`, `f` async):

```cpp
_sm_task1 = ns_f_task_new(args);      // counted handle, status Runnable, branch 0
if (_sm_task1->status != Done) {      // it really suspended
    _sm_task1->parent = this;         // the child holds the parent (counted)
    this->branch = k;
    return;                           // suspend
}
Lk:;                                  // resumed by the child
x = _sm_task1->result;                // read the value out *before* the drop
_sm_task1 = null;                     // Done, so this is the last reference: it frees
```

The `if` *is* the sync-completion fast path: a callee that ran to completion never suspends the
caller, so there is no status write, no branch and no round trip - and no frame, because nothing
suspended. That is why the check is worth having on every call.

**A `return v`:**

```cpp
this->result = v;
this->branch = -1;
this->status = Done;
auto p = this->parent;
this->parent = null;                  // release the parent *before* resuming it
if (p != null) p->resume();           // the child resumes the caller
return;
```

The order matters twice over: the parent must not be kept alive by a finished child, and the
completing child needs a **self-reference across `p->resume()`** because the parent drops its last
external reference to it inside that call.

**Where it hooks:** `linLowerYield` is called from the pipeline in `cppsrc/linear/Linear.kt`; this
lowering goes after it, keyed on the coloring table (`cppsrc/sema/Async.kt`), and refuses a body
that also yields - a `for` machine is a local and cannot live across a suspension, which is the
restriction already recorded above. The emitter gains `emitTask` beside `emitMachine`, and the
suspension is spelled with instruction forms that already exist (`Call`, `GetField`, `Assign`,
`IfTrue`, `Return`) - the one thing the IL does not have today is a `Return` out of a `void` resume
entry, which is the shape the machine's own method already emits.

**Order of assembly, so each step is verifiable on its own:** the task header and the loop in the
`tasks` section first (a hand-written two-task chain proves the status discipline, the release order
and the loop's idle behaviour with no compiler change at all); then `Async<T>` in the checker; then
the lowering and `emitTask`, tested against a **synchronous fake leaf** so the whole ABI is exercised
with no threading; then `asyncRunTransform`/`runAndForget`; then the file leaves over the pool and
the corpus case.

## Known restrictions to diagnose, not to miscompile

- **A `for`/`yield` machine inside a suspending body.** A machine is a local, and a local cannot
  live across a suspension (the same reason a machine cannot cross a `yield` —
  `impl_specs/yield.md`). Either the machine becomes a field or the construct is reported.
- **An indirect call** that could reach a suspension (above).
- **`Async<T>` in a value position** (a parameter, a field, a local): a task is not a value.

## Status

Done: `!!` (`cppsrc/parser/Propagate.kt`, `stress/propagate`), **including inside a lambda** -
the target is the parameter's function-type result, so a transformer can be an inline lambda
(`stress/propagate-lambda`); the coloring with its dump -
`cppsrc/sema/Async.kt`, `--showAsync`, checked against `docs/examples/async/src/main.kt`
(`copyFile` and `main` inferred async, the two leaves declared, `describe` and the whole prelude
synchronous, and the compiler's own 903 declarations all synchronous); and the **work pool**
(`cppsrc/rtl/tasks.kt` + the `tasks` section, `stress/tasks`), which is the half of the runtime
that has nothing to do with tasks: N `std::thread`s behind a work queue and a completion queue,
a reference transferred under the queue's lock, values crossing and handles never, and a
structural join. Its surface is synchronous on purpose - submit, join, read what settled - so it
could be built and verified before any of the machine work started.

Next, in order: `Async<T>` in the checker (a call to an async function is typed as its payload);
the task header (status, parent, pending-children count) with its queues over the pool's; the push
machine in `linear/`; then `asyncRunTransform`/`runAndForget` lowering (the dynamic fan-out, which
needs a child-handle list and a pending count in the frame), and the file leaves suspended rather
than blocking.
