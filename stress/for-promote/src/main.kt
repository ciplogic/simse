package fixtures

// `for (x in c)` over a *deep* element - a `Str`, or a data class holding one - is promoted to the
// pointer wrap (`iterPtr`) when the body only reads `x`: the value form copies, and for a `Str`
// allocates, per element, which the pointer form does not (impl_specs/for.md, "iterPtr"). A scalar
// element, and a body that writes through the variable, keep the value wrap. The emitted machine
// names are the golden; the sum checks the two forms agree.
data class Entry(var key: Str, var count: Int)

fun main(): Int {
    var entries: List<Entry> = List<Entry>()
    entries.append(Entry("a", 1))
    entries.append(Entry("b", 2))
    var names: List<Str> = List<Str>()
    names.append("xy")
    var nums: List<Int> = List<Int>()
    nums.append(7)

    var total = 0
    for (e in entries) {
        total = total + e.count + e.key.size()
    }
    for (n in names) {
        total = total + n.size()
    }
    for (i in nums) {
        total = total + i
    }

    // A body that writes through the variable is not promoted: the pointer form would mutate the
    // list, the value form mutates the copy. The second loop reads the (unchanged) elements.
    var cells: List<Entry> = List<Entry>()
    cells.append(Entry("c", 3))
    for (e in cells) {
        e.count = e.count + 100
    }
    for (e in cells) {
        total = total + e.count
    }
    println(total.toString())
    return 0
}
