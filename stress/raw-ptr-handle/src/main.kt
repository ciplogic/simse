package fixtures

// `rawPtr(address)` (src/rtl/rtl.kt): the pointer-sized-integer escape hatch a P/Invoke
// layer needs - the constants a C API spells as addresses, and the untyped slots a
// declaration passes NULL through. Zero answers the null handle; the bits travel as
// they are, so the -1 a SQLITE_TRANSIENT wants is spelled and never dereferenced.

fun main(): Int {
    val nullHandle: RawPtr = rawPtr(Int64(0))
    val transient: RawPtr = rawPtr(Int64(-1))
    println("zero is null: " + (nullHandle == null).toString())
    println("minus one is not: " + (transient != null).toString())
    println("round trip: " + (transient != rawPtr(Int64(0))).toString())
    return 0
}
