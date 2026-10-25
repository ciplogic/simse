# The for-walk: the span machine flattened where it is redundant

Status: in build (2026-10-25) - the plan the flat-scan bench rows motivated. Every step
lands under `--for-walk`, default off (`impl_specs/typed-il.md`'s flag convention). The
measurement below is in: the counter walk beats the machine on the flat scan and the
pointer pair buys nothing over the counter, so variant B builds and variant A is closed.

## Why

`for` over a container is the span machine (`impl_specs/for.md`): the parser's desugar
declares `_sm_for<n>` and the loop is `while (machine.advance()) { val v =
machine.current; ... }`, a `current` field read behind an `advance()` call. On the
compiler's own loops that shape measured at parity with the hand-written walk (`for.md`'s
table: 826 vs 824 ms median) - per-iteration overhead is not the cost. The cost is the
*shape*: the auto-vectorizers do not flatten a state-machine loop into a counted one,
where C++'s range-`for` over flat memory does vectorize. That is where the rows sit after
the span hoist took the while-indexed scans (sum 1.31) past the for-machine scans (words
1.05, particles 1.11, strlist 1.00, blur 0.95) - the machine is the loop tax left on the
flat rows.

The fix is a two-step lowering, and step one already exists: `spanForAt` extracts the span
once (the view hoisted to a function-scope slot). The missing step is to *not* build the
machine over that span - to lower the walk the way a C++ range-`for` lowers, in two
variants (B is the one to build first):

```text
variant B - the counter (the "indexer version")

var _sm_walk1: Span<T> = spanOf(xs)
var _sm_i1: Int = 0
while (_sm_i1 < _sm_walk1.len) {
    val v = _sm_walk1.ptr[_sm_i1]        // value form; the `*v` form binds *_sm_walk1.ptr[_sm_i1]
    _sm_i1 = _sm_i1 + 1
    ... the user body ...
}

variant A - the pointer pair, the counter taken out

var _sm_walk1: Span<T> = spanOf(xs)
var _sm_p1: *T = _sm_walk1.ptr
var _sm_end1: *T = *_sm_walk1.ptr[_sm_walk1.len]   // *p[i]: pointer-plus-offset, never dereferenced
while (_sm_p1 != _sm_end1) {
    val v = *_sm_p1                                // load through the pointer
    _sm_p1 = *_sm_p1[1]                            // the bump: address of the next element
    ... the user body ...
}
```

Read-then-step, both variants: the element is bound *before* the counter moves, or
element 0 is skipped (the sketch this proposal answers bumped first - that reading loses
the first element).

**The measurement** (2026-10-25, `tools/_walkprobe/`, the sum row's flat scan - build 1M
values, scan them eight passes - spelled the three ways, interleaved A/B,
`tools/_bench_ab.mjs`, 11 pairs, two runs; all checksums agree at 3998750656):

| shape | min (run 1 / run 2) | median (run 1 / run 2) |
| --- | --- | --- |
| the machine (`for (x in vals)`) | 9.5 / 9.2 ms | 10.1 / 10.0 ms |
| the counter walk (variant B) | 8.1 / 7.8 ms | 9.1 / 8.9 ms |
| the pointer pair (variant A) | 8.3 / 7.5 ms | 9.3 / 8.9 ms |

The counter walk takes ~15% off the machine at the min and ~10% at the median - the
auto-vectorizers do flatten it where they do not flatten the machine. The pointer pair is
parity with the counter at best, exactly the MSVC expectation, so variant A is closed: no
emitter work, no `RawPtrT<T>` surface owed. The pair leg's spelling also settled a
limitation the sketch had assumed away: `at != end` on two `*T` lowers as a *value*
comparison through the pointers (`*(at) != *(end)` - an out-of-bounds read at `end`), so
the address comparison variant A needed was never spellable at the source level; only the
pointer-vs-`null` case compares addresses. If a future pass ever wants the pair shape, a
typed raw pointer with address `==`/`!=` is the surface to design for it - not a
for-walk prerequisite. The pair leg stands as raw C++ (`tools/_walkprobe/pair_raw.cpp`),
the shape variant A would have had to emit, so the claim here is checkable.

## The contract: real iterators keep their machine

The walk is a lowering of *the span's own* machine, never a bypass of somebody's
iteration. The compiler reifies one machine per walk and knows the span machine's body -
it is the prelude's own `while (i < len) { yield this[i] }` (`for.md` quotes it); the walk
is that body inlined at the loop, under the same stability proof the span hoist uses. A
generator's `..T` has no such body to inline. The decision table, evaluated after
`spanForAt` has run (so a container receiver is already its span):

| the `for` | lowers as |
| --- | --- |
| receiver is a `Span<E>`/`StrView` (a container the span rewrite reached, or a span value), body does not yield, body storage-stable, `--for-walk` on | **the walk** |
| a machine receiver (`..T` - `iterValues` is the identity on it; a generator) | the machine |
| a user type's own `iterValues`/`iter` (`Point.walk()`, `Range`, `iterOf()`, a linq chain) | the machine |
| `Dictionary` (no span view) or an unknown receiver | the machine |
| any body containing `yield` | the machine |
| a body that is not storage-stable for the root | the machine |
| `--for-walk` off | the machine - and the emission is byte-identical to today's |

The yield row is not stylistic: the suspension machinery (`--showAsync`'s coloring,
`impl_specs/yield.md`'s machine lowering) keys on the machine structure, and `for.md`
still lists a `for` inside a yielding body as its own open problem - the walk adds nothing
there and must not perturb it.

## The gates (all existing functions)

- the receiver is a plain name (`ExprName`) whose type resolves - place chains widen later;
- after `spanForAt`, the wrap's receiver type is `Span<E>`/`StrView` (either
  `spanConversion` named the conversion, or the source iterated a span outright);
- `semaBodyYields(body)` is false;
- `bpSpanStableNode(cond, body, root)` holds - no rebind of the root, no escape, no
  mutating call on it (a `mutates` callee on another named receiver is trusted, the
  place-promotion relaxation). Element writes through the walk are stable - they write
  *through* the buffer, they do not move it;
- the wrap is `iterValues` or `iter` - both are covered, and `promoteForAt` has already
  made its value/place decision before the walk runs; the walk honors it (value form
  copies the element, pointer form binds the place).

## The lowering's own rules

- **The step precedes the body** - `for.md`'s own `continue` lesson, verbatim: an index
  moved at the *end* of the body misses every `continue`-skipped iteration. The element
  binds at `i`, then `i = i + 1`, then the user body, so `continue` lands after the step.
  The parser's `-1`-and-pre-increment template counter disappears: the walk's counter
  *is* the index, starting at 0.
- **The indexed form gets cheaper for free**: `for ((v, i) in xs)` drops the separate
  `_sm_index<n>` counter entirely - `i` binds from the walk's own, which is also why
  variant B is the first to build: loops that need the index are not a special case.
- **Declarations stand where the machine stood**: `var _sm_walk<n>` (and the counter, or
  the pointer pair) replaces `var _sm_for<n>` in its statement slot - correct by
  construction, no block injection, so the placement and slot-merge hazards below cannot
  arise.
- **Variant B needs no emitter work**: span reads and writes already spell `.ptr[i]`
  directly (the `spanDirect` guards, landed with the span hoist). Variant A's pieces are
  existing spellings too (`*p[i]` as pointer-plus-offset is `Span.slice`'s own idiom,
  `Span.atPtr` is the source-level name for the element's address, and pointer `!=` is
  the same comparison `RawPtr` null checks use); the empty span is the `p == p` case -
  the pair compares equal and the loop never runs - but the end address over a null
  pointer must be *computed*, never dereferenced, which the first flag-on case pins.

## AST hygiene (the v3 lessons, as requirements)

Every synthetic node carries its role name (`Receiver`, `Index`, `Target`, `Callee`,
`Arg`) and the `for` token's line/column. A role-less child is invisible to
`xmlChildPtr`, and the extractor drops it silently - that was the entire "toArray typing
edge" the span hoist stalled on. A fresh node per site; the tree shares nothing between
uses. No `StmtBlock` injection of any kind - the v2-era block transparency in
`LinLowerer` merged shadow-renamed slots across scopes, and its removal is a precondition
this proposal inherits.

## The flag, and how both paths validate

`--for-walk`, default off:

- **off (the default)**: the desugar is exactly today's, byte for byte - the checked-in
  emission goldens, the published bootstrap chain and the fixed point are untouched;
- **on**: new stress cases carry the flag in their `compiler-args` with their own
  `expected.cpp.out` goldens - `for-walk` (value and indexed forms), `for-walk-pointer`
  (the `*v` form, a write through the loop variable reaches the container), and
  `for-walk-yield` pinning the *keep-the-machine* side of the contract: a generator
  receiver and a yielding body must come out unchanged by the pass;
- the loop (`bun tools/iterate.js --full` - the dotnet twin's pool) validates the default
  path; the flag path is the new cases plus a flag-on self-transpile spot check.

## Measurement and acceptance

1. **Before any code**: the machine tax in isolation - one bench row's flat scan spelled
   three ways (machine, counter walk, pointer pair), interleaved A/B with
   `tools/_bench_ab.mjs`; the rows to watch are sum, strlist, words, particles, blur, and
   the compiler's own self-transpile (`for.md`'s table is the baseline to beat or fail
   against). **Done** - the table above; the counter walk moves, the pair does not.
2. Variant B lands green under the flag; the A/B then decides whether variant A exists at
   all - **it does not**: parity with the counter, and its comparison was never spellable.
3. The default flips only after the Linux twin confirms (`bench/run-linux.js`, the i3),
   and the flag is deleted once trusted.
4. **The honest failure**: `for.md`'s own measurement says the machine was free on
   pointer-chasing work. On the flat scan the walk moves (the table above), so the
   proposal builds - the flag-on rows (sum, strlist, words, particles, blur, the
   self-transpile) decide the default flip.

## Order of work

1. The measurement (above) - done, pinned above.
2. `forWalkAt` beside `spanForAt`/`hoistSpanAt` (`src/sema/SemaCall.kt`, inside
   `promoteForLoops`' per-scope walk, after `promoteForAt`): variant B, the `iterValues`
   wrap first, then the `iter` wrap.
3. The three stress cases and both-path validation.
4. ~~Variant A, only if the A/B asks for it~~ - closed by the measurement: parity with the
   counter, and `*T != *T` compares values, not addresses.
5. The default flip (Linux first), then the flag's deletion.

## Risks

- **A body that moves the buffer mid-loop** - the stability gate is the same
  `bpSpanStableNode` the span hoist shipped under; the corpus's `place-reuse` shape (an
  element address reused across a call) is the adjacent hazard.
- **The suspension machinery** keys on machine structure - the yield gate keeps it whole,
  and `for-walk-yield` is the tripwire.
- **Blast radius under the flag** is the point of the flag: golden movement stays inside
  the new cases.
- **Vectorization is a hypothesis until measured** - see acceptance 4.
