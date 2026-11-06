package fixtures

// A function *name* standing where a callable element is expected - the two positions the
// typed-IL residue is made of (impl_specs/typed-il.md, "Status"): a generic constructor's
// fill argument (`List<Step>(4, twice)`, whose parameter is the bare `T`) and an index
// store (`table[1] = plusOne`, whose place is the list's element). Both reach the IL as a
// `GetStatic` into a `?`-typed slot today: the name holds no slot of its own, the type
// rules cannot type the reference itself, and - unlike a call argument against a
// declared callable parameter - nothing in the position names the type until the
// *receiver's* binding (`T = Step`) is asked. The C++ is right either way (the slot folds
// and the name is spelled at its use), so this case pins the emission: the fix must keep
// it byte for byte while the IL gains the type.

typealias Step = (Int) -> Int

fun twice(x: Int): Int {
    return x * 2
}

fun plusOne(x: Int): Int {
    return x + 1
}

fun makeTable(): List<Step> {
    var table: List<Step> = List<Step>(4, twice)
    table[1] = plusOne
    table[3] = plusOne
    return table
}

fun main(args: List<Str>): Unit {
    val table: List<Step> = makeTable()
    var total: Int = 0
    var i: Int = 0
    while (i < table.size()) {
        val stepFn: Step = table[i]
        total = total + stepFn(10)
        i++
    }
    println(total.toString())
}
