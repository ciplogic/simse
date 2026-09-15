# `yield` example

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

`for` is the same loop, spelled: both forms iterate a machine, and the index form
counts the iterations for you (from `0`). `continue` still moves the machine on and
still counts the iteration it skipped, because the advance and the counter are the
first thing in the loop body (`specs/functions.md`, `impl_specs/for.md`):

```simse
for (value in everyOther(10)) {
    println(value.toString())
}

for ((value, index) in everyOther(10)) {
    if (value == 4) {
        continue                    // skipped, and still counted
    }
    println(index.toString() + "=" + value.toString())   // 0=0, 1=2, 3=6, 4=8
}
```

The machine is a struct on the stack. For a life that outlives the frame, box it with
`&`, as any other value: `val down = &countdown(3)`.

Run it (from the repository root):

```sh
./cmake-build-debug/simse_transpile.exe --root docs/examples/yield/src -o yield_out.cpp
./build.bat --cpp yield_out.cpp --exe yield_demo.exe
./yield_demo.exe
```

Expected output:

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

Implementation and the transformation, step by step: `impl_specs/yield.md`; the `for`
desugaring: `impl_specs/for.md`.
