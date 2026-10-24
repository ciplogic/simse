package fixtures

// A call's type is its declared return type (`Analyzer.semaPlainCallReturn`): three
// readers that could not see through a call and answered "unknown" - a union
// construction's arm match, a `for` whose iterable is a call's result, and a `Res`
// returned directly, whose out-parameter slot the caller forwards. The probes are `res`
// functions because the boundary shapes (a scalar, a container) are the ones a binding
// hits; the checker fix is in the calls themselves, not the boundary.

@SmGen("res", "probecall", "simse_probe_int")
fun probeInt(): Int64

@SmGen("res", "probecall", "simse_probeFlag")
fun probeFlag(): Bool

@SmGen("res", "probecall", "simse_probe_list")
fun probeList(): List<Int>

union class Value(var I: Int64, var F: Float64, var T: Str)

fun okSeven(): Res<Int> {
    val r: Res<Int> = Res<Int>.ok(7)
    return r
}

// The out-parameter ABI in return position: `return f()` forwards the caller's own slot.
fun returnDirect(): Res<Int> {
    return okSeven()
}

// The arm is the argument's type: `probeInt()` answers `Int64`, so the construction
// builds the `I` arm. Before calls resolved, this was "the argument's type is unknown".
fun unionFromCall(): Str {
    val v: Value = Value(probeInt())
    if (v.getTypeOf() == SmValueTypes.I) {
        return "I"
    }
    return "?"
}

// The iterable is a call's result: the declared `List<Int>` is what the loop's machine
// wraps. Before calls resolved, the wrap had no type to instantiate.
fun forOverCall(): Int {
    var total: Int = 0
    for (n in probeList()) {
        total = total + n
    }
    return total
}

fun main(): Int {
    val direct: Res<Int> = returnDirect()
    println("direct: " + direct.value().toString())
    println("union: " + unionFromCall())
    println("loop: " + forOverCall().toString())
    println("flag: " + probeFlag().toString())
    return 0
}
