# Escape analysis: the improvement plan

Status: planning baseline for implementation in steps. Each step is independently landable
(`bun tools/iterate.js --full` green, one commit each), ordered by (value / effort). The
record of what already landed is `impl_specs/capability-matrix.md` (search "viral
auto-borrow", "out-parameter ABI"); this file is only the forward plan. Where things
stand: **all six steps are resolved** — 1–4 landed (plus the follow-up sema resolution
commit `f776d19`), 6 by the landed machinery rather than a seed (its section says why),
and 5's twin ABI landed as `8739921` with `stress/escape-twin` pinning it. What remains
here is the smaller cleanups section below.

The two analyses, in one line each:

- **Borrowness** (`src/parser/BorrowParams.kt`, pre-sema, name-keyed fixpoint grown from
  `data`/`borrow` marks): a body that writes nothing observable and calls only
  borrow-clean names gets its heavy parameters rewritten to `*T`, so callers stop
  copying. Observability: `--showBorrow`.
- **Retention** (`src/parser/EscapeParams.kt`, feeds `src/linear/PromoteRefs.kt`): which
  parameters/receivers a callee may keep, so a non-escaping `&T` local lowers to a stack
  payload plus a raw `T*`. Observability: `--showEscape`.

The landed return-side ABI (`sret`: `void f(T* _sm_out, ...)`, call sites write through)
lives in `CgCore.kt` (`collectSretFns`/`sretEligible`), `CgEmitFn.kt` (signature, body
flag), `IlEmit.kt` (`ilSretCallee`, the Return arm, the two call arms).

Worked example every step must keep green (already pinned): `stress/escape-viral` —
`Res`/`Opt` paths, `+` chains, `Point3D` by value, `List<Point3D>`/`List<Str>` walks,
build-and-return through `_sm_out`.

---

## Step 1 — Deduce `plus`: writes-through-fresh-arguments (the per-argument refinement)

**Why.** `StrView.plus` is *marked* `borrow` today (an author's word) because its body
calls two natives the fixpoint cannot see through: `result.resize(...)` and
`memCopy(strBytes(*result), ...)`. The mark is sound but hand-written; the general rule —
*a call that writes only through arguments derived from locals the body itself built is
safe* — would prove `plus` and every user function with the same shape (building into a
fresh local via `resize`/`memCopy`-style primitives). This is the "more situations"
ask: precision, not new marks.

**Facts on the ground (verified).**
- `result.setBytes(...)` is already `@SmGen("cpp", "simse_str_setBytes", "mutates")`
  (`src/rtl/intrinsics.kt:38`) and the existing **mutates relaxation** in
  `BpFacts.walk` handles it: a `mutates`-marked callee whose receiver is a body-built
  local (`written` and not `taken`) does not dirty the body.
- `resize` has **no Simse declaration at all** — the call `result.resize(n)` resolves
  through the hand-written-header fallback, so the fixpoint sees an unknown name and
  blocks `plus`. (Verified: `grep -rn "fun resize" src/ modules/` finds nothing; the
  dirty trace said `plus: resize memCopy`.)
- `memCopy(dst: *Char, ...)` (`src/rtl/intrinsics.kt:11`) writes through its **first
  parameter** — not a receiver — so the receiver-shaped relaxation cannot fire for it.

**Implementation.**
1. Declare `resize` in the prelude, marked `mutates`, with a `cpp` wrapper in
   `src/rtl/_res.md` beside `simse_str_setBytes`: `inline void simse_str_resize(Str& s,
   Int count) { s.resize((size_t) count); }` and `@SmGen("cpp", "simse_str_resize",
   "mutates") fun resize(this: Str, count: Int): Unit`. (Check how the header-fallback
   call is spelled today — `result.resize(n)` as a raw member — and make sure adding the
   declaration does not change the emitted call shape; the corpus goldens will say.)
2. Extend the `mutates` mark grammar to name **written parameter positions**:
   `"mutates"` stays "writes the receiver"; add `"mutates:0"` (or `"writes:0,2"`) meaning
   writes only those 0-based parameter positions. `bpMutatesMark`
   (`src/parser/BorrowParams.kt:66`) parses it into the `mutates` set's entry — today the
   set is `Dictionary<Str, Bool>`; make it carry the position list (an empty list =
   receiver-only, exactly today's meaning).
3. Extend the relaxation in `BpFacts.walk` (the block at the `mutates.has(calleeName)`
   test): for a position-marked callee, check each written position's *argument* with a
   small recursive predicate `argIsFresh(node)`: an `ExprName` that is `written` and not
   `taken`; an `ExprDeref` of such; a call to a `data`-marked function of any of those
   (`strBytes(*result)` qualifies — `strBytes` is `data fun`). Anything else: not fresh,
   the call dirts the body (sound refusal).
4. Mark `memCopy` `mutates:0`. Leave `plus`'s `borrow` mark in place until the deduction
   proves out, then **remove it** (this step's acceptance: `--showBorrow` on any
   `a + b`-returning probe still shows the caller borrowing, with no mark on `plus`).

**Two-build rule.** The prelude edits (`intrinsics.kt`, `_res.md`) are visible to the
running compiler: stage first
(`./build.bat --fast --cpp ../simse_bootstrap/builds/simse_bootstrap.cpp --exe simse.exe`), then build, or the
build dies mid-flight (`ai/contributing.md`, "The two-build rule").

**Verify.** The loop; `--showBorrow` on the escape-viral probe; a new part in
`stress/escape-viral` whose body builds a `Str` via `setBytes`/`resize`/`memCopy` on a
fresh local and takes a `Str` parameter (must borrow).

## Step 2 — `print`/`println` trusted by the borrow fixpoint

**Why.** `EscapeParams.kt` already maps the two emitter-intrinsics as borrow-shaped
(`epBuiltinBorrow`, `src/parser/EscapeParams.kt:76`) — "`print`/`println` take the value
by `const T&` and write it where it stands". The borrow pass does not: any body that
prints is dirty (`borrow-dirty main: println` in the traces), which is nearly every
driver-shaped function. One-line-seed symmetry between the two analyses.

**Implementation.** In `bpBorrowParams` (the pass entry, after `bpCollectDecls` runs),
seed `reads` with `print` and `println` — gated on the same `bpNoBorrow()` off-switch
`epBuiltinBorrow` respects (keep the switches' meanings aligned: `--no-borrow` disables
the rewrite, the seed may stay for `--showBorrow`).

**Verify.** A probe `fun show(label: Str, n: Int): Unit { println(label); println(n) }`
— wait, `Unit` returns have no heavy params to borrow in that shape; use
`fun show(label: Str): Str { println(label); return label + "!" }` — must show
`borrow+ show label`. Corpus: expect *many* golden refreshes (every printing case's
helpers may flip to borrowed signatures) — additive shape changes only; read the diffs.

## Step 3 — Methods in the out-parameter ABI

**Why.** `sretEligible` excludes receiver-having functions (`CgCore.kt`, the
`!xmlIsEmpty(fn.receiver)` refusal) because their call sites spell as member calls the
free-name rewrite could not express when the ABI landed. Builders written as extensions —
`fun Str.quoted(): Str`, `fun Pt.shifted(d: Int): Pt` — are common user shapes and today
still copy at the boundary.

**Implementation.**
1. `sretEligible`: drop the receiver refusal (keep every other gate).
2. `ilSretCallee` (`src/codegen/IlEmit.kt`): re-allow `IlMethodKind.Method`. The call
   text: the by-value path spells a method call through `ilCallNode`'s member node —
   which `this.expr()` renders as `recv.name` (member syntax). That is *wrong* for
   extensions (their C++ is the free function `qualify(pkg, name)`), which is why the
   gate narrowed originally. The fix: for Method-kind, do not render the member node;
   build the free-function spelling directly — `this.qualify(this.functionPackage(
   method.name), method.name)` plus the receiver *argument* text (the receiver operand's
   slot text via `ilSlotNode`, exactly the argument the by-value member path passes) —
   then prepend `&dst` and append the remaining argument texts. Mirror the shape the
   emitter already uses for extension calls (`CgCall.kt`'s member-call resolution).
3. Class-body methods (`HasReceiver` from the class, no written receiver child): the
   emitted name is qualified the same way; the receiver argument is `this` — same
   construction.

**Verify.** Extend `stress/escape-viral` with `fun Point3D.shifted(d: Int): Point3D` and
a `Str`-returning extension; the golden signatures must read
`void ns1_shifted(ns1_Point3D* _sm_out, ns1_Point3D* self, Int d)` and call sites
`ns1_shifted(&p, &p, 10)`-shaped. Watch the corpus for member-heavy cases (minui).

## Step 4 — Deep-container elements in the loop promotion

**Why.** `bpDeepElement` (`src/parser/BorrowParams.kt:770`) answers true only for `Str`
and data classes holding one. A `List<List<Str>>` or `List<Dictionary<K, V>>` element is
a `TypeGeneric` — never deep — so its value-form loop copies the whole inner container
per element. Deep-ness is recursive through the containers that own heap storage.

**Implementation.** In `bpDeepElement`, add the `TypeGeneric` arm: `List<...>`/
`Dictionary<...>`/`Opt<...>`/`Res<...>` of a deep element are deep; `Span`/`Array` are
not (views / counted blocks — the existing exclusion stands, `for.md`'s measurement
guidance: scalars and views stay value-form). Recursion depth is finite (types are
acyclic).

**Verify.** A `List<List<Str>>` walk part in escape-viral: the loop machine must be
`iter(` (pointer) not `iterValues(`, and the stdout unchanged.

## Step 5 — The `&T`-returning twin ABI (the `createUser` sketch)

**Why.** The user-facing goal: `fun createUser(name: Str, age: Int): &User` reads as a
handle, but at call sites where the handle never escapes, the object should be stack
storage addressed by a raw `T*` — the box appears only on the branches that store the
handle (`users.add(user)`). No runtime means no lazy heapify; the static equivalent is a
**per-call-site twin ABI**, and the pieces now exist: `PromoteRefs` already lowers a
non-escaping `&T` local to stack+pointer when its definition is a same-frame `Box` or
`CallCtor`; the sret machinery already builds `void f(T* out, ...)` twins.

**Implementation sketch (the biggest step; land last).**
1. **Fresh-handle-return analysis** (pre-sema, beside the sret set): a `&T`-returning
   free function is *fresh-handle* when every `return` returns `&Ctor(...)` (an
   `ExprRef` of a construction) or `return (ctor)` — the value is built in the callee,
   never a parameter or a field.
2. **The twins.** For a fresh-handle function, emit *both*:
   the authored `Ref<T> f(...)` (its `return &Ctor` stays a `makeRef`), and a value twin
   `void f_v(T* _sm_out, ...)` — the same body with the final `Box`-of-`Ctor` replaced
   by storing the `Ctor` into `_sm_out` (the sret body path gives the store-and-return;
   only the value construction needs retargeting, and `ilBoxedCtorText` is the branch
   that boxes today).
3. **The call-site choice.** At a call whose destination is a `&T` local, ask the
   retention fixpoint (`EscapeParams`' per-name answers, or reuse `PromoteRefs`' own
   candidate test on the slot): if the local is written once (this call) and never
   escapes (the `ilPromoteRefs` candidate rule, `src/linear/PromoteRefs.kt:233`), emit
   `T _sm_stkN; f_v(&_sm_stkN, args); local = &_sm_stkN;` — the promoted shape, straight
   from the source spelling. Else the authored box ABI, unchanged.
4. `--no-borrow`'s sibling `--no-escape` already gates the promotion half; the twin
   emission should respect it (off = always the box ABI).

**Verify.** The user's exact sketch as a stress part:
```kt
fun createUser(name: Str, age: Int): &User { return &User(name, age) }
val user = createUser("John", 100)   // stack payload, raw pointer, no makeRef
if (user.age > 100) { return 1 }
users.append(user)                    // the one box, on the branch that keeps it
```
`--showEscape` names the promoted local; the emitted `main` shows `_sm_stkN` and exactly
one `makeRef` at the `append`. Both escape and non-escape call sites of the *same*
function in one program.

## Step 6 — sret callees are non-retaining (widen the trust both ways)

**Status: resolved by the landed machinery, nothing left to land.** The verify scenario
("a body that calls an sret builder and holds a `&T` local across it — the local must
still promote") passes today without an sret name set, and the two proposed seeds are
unsound as rules:

- The sret ABI already writes the result into *caller* storage, so a `&T` local boxed on a
  builder's result points at the sret destination directly (`&makeUser(n)` lowers to
  `makeUser(&_sm_base1, ...); h = &_sm_base1;` — no copy, no second box), and a handle
  passed *to* a builder converts at the call site against the parameter's own type
  (`*T` reads through before the call, so the box is never the call's argument).
- Every sret-eligible name has a body (eligibility demands one), so the retention fixpoint
  always votes on its parameters from that body — the table never holds an `Unknown` an
  sret seed could repair; what propagates through downstream `Unknown`s is honest.
- Trusting sret names in either pass would be *unsound*: sret constrains only the result
  channel. An sret-eligible function can still write through a `*T` parameter (the borrow
  pass must keep refusing it) and still retain a parameter into a field or a static (the
  retention pass must keep marking `Escapes`). "Never weaken a refusal" is exactly this.

---

## Smaller cleanups (any step's ride-along)

- **Ambiguous native overloads**: the `initByValue`-targeted deref
  (`LinearFormCall.kt`, `calleeName == "initByValue"` block) is the pattern; a call that
  resolves no single Simse target and whose every overload's matching parameter is
  by-value should spell an auto-borrowed name as the explicit deref generally, not just
  for the one name. Generalize carefully — C++ overload sets that *want* the pointer
  (`parseModule(Span, *Str)`) must keep the raw spelling; the rule needs the overload
  set's parameter texts, which the extractor can see through `functionsByName`.
- **`repeat`/`forEach` dirty through callable params** (seen in the traces:
  `repeat: body`, `forEach: f`): a call to a *parameter* is an opaque call today. A
  parameter of callable type that the body only calls (never stores) is a known
  signature — the call dirts nothing the caller lent. Small predicate on the callee
  node being an `ExprName` that resolves to a callable-typed parameter of the enclosing
  declaration.
- **`bpCallTrusted` surface table** (`BorrowParams.kt`): if the prelude grows more
  collision-safe spellings (`simse_opt*`/`simse_res*` today), the twin table must grow
  with them — worth a comment on `optres.kt` pointing at the table so the next accessor
  does not silently un-borrow every caller.

## Cross-cutting rules for the implementer

- **Never weaken a refusal**: every refinement here *adds* proofs; a shape the analysis
  cannot see stays refused (sound-by-default is the pass's contract).
- **The two-build rule** bites on any prelude/scanner change: stage from the published
  bootstrap first (`ai/contributing.md`). If the build dies mid-flight, that is the cause
  before any other theory.
- **Observability first**: reproduce with `--showBorrow` / `--showEscape` before
  touching code; the reports name the blocking callees (`borrow-dirty name: culprits`)
  and the per-arm decisions. The dirty-trace instrumentation added this session
  (`bpInferReads`' `borrow-dirty` under `bpShow()`) is the debugging tool of record.
- **One commit per validated step**, the loop green before each; golden refreshes are
  read-then-copy, never `--update` on goldens (`stress/README.md`).
- **Where proofs are marks today** (`plus`, the union surface), the mark's comment must
  say what the deduction will replace it — the plan's Step 1 is the template.
