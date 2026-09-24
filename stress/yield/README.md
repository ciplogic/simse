# `yield`

A function whose body yields is a small state machine: the compiler lowers the body to
labels and gotos, gives the machine one field per value that lives across a yield, and
returns it by value. `..Int` in the signature says what it hands out.

```simse
fun everyOther(n: Int): ..Int {
    var i: Int = 0
    while (i < n) {
        if (i % 2 == 0) {
            yield i
        }
        i = i + 1
    }
}
```

Advance it with `advance()`, which answers `Bool` and leaves what it yielded in the
machine's `current` field:

```simse
val evens = everyOther(10)
while (evens.advance()) {
    println(evens.current.toString())
}
```

There is one protocol, `advance()` plus `current` - no `Opt<T>` per step to build and
unwrap, and no `value()` method: a `for` reads `current` itself, and a field read copies
the element once where a `value()` that returned it copied it twice.

A machine is also what `for` iterates: `for (v in m)` and `for ((v, i) in m)` are the
`while` above with the advance as the condition, so `continue` still moves the machine on
and the index still counts it. This case's program uses both, advances a machine by hand,
advances one after it finished (it stays finished) and names every member the machine
itself uses (`branch`, `current`, `advance`), which is what the mangled `_sm_f_*` fields
are for.

`expected.stdout` is this program's output:

```text
every other, up to 10:
0
2
4
6
8
countdown from 3:
3
2
1
after the end:
false
shadowing the protocol:
16
15
14
with for:
0
2
4
6
8
with for and an index:
0:0
1:2
2:4
3:6
4:8
skip 4, stop after 8:
0=0
1=2
3=6
4=8
```

The case runs in the harness (`bun tools/stress.js --filter yield`) through the
self-hosted compiler, so `yield` and `for` are covered end to end by the ring that emits
from the linear IL.

Implementation and the transformation, step by step: `impl_specs/yield.md`; the `for`
desugaring: `impl_specs/for.md`.
