package fixtures

// `native class` (specs/memory-model.md): a class whose generated struct keeps the host's
// alignment instead of the language's 4-byte packing, so a class that mirrors a native layout
// is not re-packed. `ref class` is the second class word: a handle-only type, built as
// `&Handle(...)` and held by `&Handle`/`*Handle` (a value construction is the diagnostic
// `stress/diagnostic-ref-value` pins).
//
// The point is in the emitted C++ (`expected.cpp`): `SockAddr` is written outside
// SIMSE_PACK_PUSH/POP and `Handle` inside them; the stdout only proves the values round-trip.

native class SockAddr(var family: Int16, var port: Int16, var address: Int, var extra: Int64)

ref class Handle(var id: Int, var flags: Int)

fun main(): Int {
    val addr: SockAddr = SockAddr(2, 80, 16843009, 7)
    println(addr.port.toString())
    println(addr.extra.toString())

    val handle: &Handle = &Handle(11, 3)
    println(handle.id.toString())
    return 0
}
