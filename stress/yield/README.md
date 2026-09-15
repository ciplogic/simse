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

Advance it with `next()`, which hands out `Opt<T>`, or with `advance(*T)`, which writes
through your pointer instead of copying an optional and answers `Bool`:

```simse
val evens = everyOther(10)
var step: Opt<Int> = evens.next()
while (step.hasValue()) {
    println(step.value().toString())
    step = evens.next()
}

val down = countdown(3)
var slot: Int = 0
var more: Bool = down.advance(*slot)
while (more) {
    println(slot.toString())
    more = down.advance(*slot)
}
```

A machine is also what `for` iterates: `for (v in m)` and `for ((v, i) in m)` are the
`while` above with the advance as their first statement, so `continue` still moves the
machine on and the index still counts it.

`expected.stdout` is this program's output:

```text
every other, up to 10:
0
2
4
6
8
countdown from 3, through a pointer:
3
2
1
after the end:
false
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

The case runs in the harness (`bun tools/stress.js --filter yield`) through both rings -
the hand-written compiler and the self-hosted one - so `yield` and `for` are covered
end to end by the ring that emits from the linear IL.

Implementation and the transformation, step by step: `impl_specs/yield.md`; the `for`
desugaring: `impl_specs/for.md`.
