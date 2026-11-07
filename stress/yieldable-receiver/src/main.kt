package fixtures

import linq

// A pipeline whose every operator is declared through the protocol receiver
// (`fun Yieldable<*T>.where<T>(...)`, modules/linq/linq.kt): the receiver names the
// machine contract (`protocol fun Yieldable<TStateMachine, TItem>
// TStateMachine.advance(): Bool`, src/rtl/rtl.kt) where `..*T` spelled the machine
// pattern - the same extensions, the same machine classes, and the emission golden pins
// the C++ byte for byte. `toPtrs`/`toValues` (the prelude's own protocol receivers) turn
// the chain between its pointer and value forms, so both flavors of the contract are
// reached. Lambdas are annotated (`(x: *Int) -> ...`): the pipeline's callable parameters
// bind `T`, and `it` is not a shape this contract spells.

fun main(args: List<Str>): Unit {
    val xs: List<Int> = listOf(1, 2, 3, 4, 5, 6)

    // where + select + take, drained by toList: the pointer chain end to end.
    val evens: List<Int> = xs.iter()
        .where((x: *Int) -> *x % 2 == 0)
        .select((x: *Int) -> *x * 10)
        .take(2)
        .toList()
    var text: Str = ""
    for (v in evens) {
        text = text + v.toString() + " "
    }
    println(text)

    // skip, then the prelude's two conversions: places back to values and to places again.
    val tail: List<Int> = xs.iter().skip(4).toPtrs().toValues().toList()
    var text2: Str = ""
    for (p in tail) {
        text2 = text2 + p.toString() + " "
    }
    println(text2)

    // forEach: the effect terminal over a protocol receiver, capturing by reference.
    var sink: &List<Int> = &List<Int>()
    xs.iter().where((x: *Int) -> *x > 3).forEach((x: *Int) -> {
        sink.append(*x)
    })
    var text3: Str = ""
    for (s in *sink) {
        text3 = text3 + s.toString() + " "
    }
    println(text3)
}
