# `collections`

Fourteen parts in one project, one per case merged into it (`stress/README.md` has the
convention): `array-layout`, `bulk-list`, `containers`, `dictionary`, `for-array`,
`for-container`, `for-pointer`, `lambda-for`, `pack-args`, `span`, `span-convert`,
`for-promote`, `for-temporary`, `for-str`. One `main` calls `partArrayLayout()` ...
`partForStr()` in that order, so `expected.stdout` is those fourteen cases' output
concatenated, and `bun tools/stress.js --filter collections` runs the lot.

What follows documents the iteration half - it was `stress/for-array`'s README.

## `for` over an array and a span

`for` iterates whatever has an `iter` in scope (`specs/functions.md`), and the prelude
writes one, on the span:

```simse
fun Span<T>.iter<T>(): ..T { ... }
```

A `List`/`Array`/`Str` receiver is viewed as its span first (`spanOf`/`spanOfArray`/
`spanOfStr`, `impl_specs/for.md`), so `for (value in arr)` and `for (value in list)` run
the same machine over borrowed storage: the view is a pointer and a length, so the thing
it points at has to outlive the loop - the lowering hoists the iterated expression into a
function-scope slot, so even a temporary (`for (x in makeList())`) outlives it
(`for-temporary`). `for ((value, index) in arr)`, `continue` and `break` work the same way
they do over a list, because the loop is the same `while` the parser writes.

Two details are visible in the emitted C++ rather than in the language:

- **A machine's class carries its receiver's name** - `Span_iter_yieldable<T>`,
  `Span_iterPtr_yieldable<T>`. One prelude function name (`iter`) with one receiver (the
  span) needs one class per element type.
- **A prelude body is emitted for the receiver the program names**: a program that only
  iterates a list carries the span machine, not a list machine, because there is none -
  the rule is a reachability over the program's calls (by name), closed over the types
  they spell.

The `for-array` part's own output - the five lines that were that case's golden - is
inside the category's `expected.stdout`, in marker order.

Implementation: `impl_specs/for.md` (the desugaring and the machine), `specs/containers.md`
("Iteration").
