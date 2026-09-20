# `for` over an array and a span

`for` iterates whatever has a `iter` in scope (`specs/functions.md`), and the prelude
writes one per container in Simse, with `yield`:

```simse
fun Array<T>.iter<T>(): ..T {
    var i: Int = 0
    while (i < this.count()) {
        yield this[i]
        i = i + 1
    }
}
```

`List<T>.iter` walks `size()` and indexes the list; a `Span<T>` walks `size()` over
borrowed storage, so the thing it points at has to outlive the loop. A machine is a
machine: `for (value in arr)`, `for ((value, index) in arr)`, `continue` and `break` work
the same way they do over a list, because the loop is the same `while` the parser writes.

Two details are visible in the emitted C++ rather than in the language:

- **A machine's class carries its receiver's name** - `Array_iter_yieldable`,
  `Span_iter_yieldable`. One prelude function name (`iter`) with several
  receivers needs several machine classes, and the receiver is what tells them apart.
- **A prelude body is emitted for the receiver the program names.** A program that only
  iterates a list carries the list machine, not the array's or the span's, or the emitted
  file would be mostly dead text for containers the program never mentions. The rule is
  a reachability over the program's calls (by name) closed over the types it spells -
  `xs.toArray()` reaches an `Array` because the prelude says `toArray` returns one.

`expected.stdout` is this program's output:

```text
3
4
99
5
0=3
111
111
```

The case runs in the harness (`bun tools/stress.js --filter for-array`) through both rings.

Implementation: `impl_specs/for.md` (the desugaring and the machine), `specs/containers.md`
("Iteration").
