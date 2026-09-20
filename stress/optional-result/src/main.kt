package fixtures

// `Opt<T>` and `Res<T>` are one storage type with two arms - `Variant2<T, VoidEnum>` and
// `Variant2<T, Str>` (cppsrc/rtl/variant2.hpp) - so this case pins the states and the
// operations that union has to support:
//
//   pick     - an empty optional built by `null` (the default-constructed arm, which
//              `Codegen.nullTo` emits as `Opt<T>()`) and by `none()`, and one that
//              carries text (`Opt<Str>`, an owning `Str` *inside* the union);
//   label    - a result whose message is empty: `err("")` is a failure now that the tag,
//              and not the message's length, says which arm is live;
//   main     - copies and re-assignments of both (a slot declared first and assigned
//              later, which is what the emitter's temporaries are), and a container of
//              them (`List<Opt<Int>>`).

fun half(n: Int): Opt<Int> {
    if (n % 2 != 0) {
        return Opt<Int>.none()
    }
    return Opt<Int>.some(n / 2)
}

fun label(n: Int): Res<Str> {
    if (n < 0) {
        return Res<Str>.err("")
    }
    return Res<Str>.ok("positive")
}

// A message longer than the inline buffer, so the union's error arm owns a heap block:
// copying a failed result copies it through the union.
fun longError(): Res<Int> {
    return Res<Int>.err("a message that does not fit in the inline buffer")
}

fun echo(r: Res<Int>): Res<Int> {
    val copy: Res<Int> = r
    if (!copy.isOk()) {
        return Res<Int>.err(copy.Error)
    }
    return Res<Int>.ok(copy.Value)
}

fun main(): Int {
    // `null` in an `Opt<T>` context is the empty optional.
    val empty: Opt<Int> = null
    println(empty.hasValue())

    var found: Opt<Int> = half(8)
    println(found.value())
    // Re-assignment replaces the live arm.
    found = half(9)
    println(found.hasValue())
    found = Opt<Int>.some(3)
    println(found.value())

    // A payload that owns storage, copied out of the union.
    var text: Opt<Str> = Opt<Str>.some("hello")
    val copy: Opt<Str> = text
    println(copy.value().size())
    text = Opt<Str>.none()
    println(text.hasValue())
    println(copy.value())

    // An empty message is still a failure.
    val bad: Res<Str> = label(-1)
    println(bad.isOk())
    println(bad.Error.size())
    val good: Res<Str> = label(7)
    println(good.isOk())
    println(good.Value)

    // The two arms inside one container.
    var counts: List<Opt<Int>> = List<Opt<Int>>()
    counts.append(half(4))
    counts.append(half(5))
    println(counts[0].value())
    println(counts[1].hasValue())

    // A failed result copied and handed back: the message owns storage, so the copy
    // crosses the union's arms and the payload arm is never built.
    val failed: Res<Int> = longError()
    println(failed.isOk())
    val again: Res<Int> = echo(failed)
    println(again.Error)
    println(echo(Res<Int>.ok(9)).Value)

    return 0
}
