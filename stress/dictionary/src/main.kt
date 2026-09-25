package fixtures
// Exercises the Dictionary surface and the List extras (T20): insert, get, getPtr, has,
// size, keys/values (sorted for determinism), contains, sort, remove, clear.

fun main(): Int {
    var counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    counts.insert("b", 2)
    counts.insert("a", 1)
    counts.insert("c", 3)
    counts.insert("b", 20)

    println(counts.size())
    println(counts.get("a").value())
    println(counts.has("z"))

    // `getPtr` hands out the value's place: `null` when the key is absent, and a write
    // through it reaches the entry. The write is the *first* statement of its block on
    // purpose: a statement starting with `*` after another statement is read as a
    // multiplication continuation (`*p = v` on its own line means `... * p = v`), so a
    // deref write belongs first in a block or in parentheses.
    val present: *Int = counts.getPtr("b")
    if (present != null) {
        *present = 21
        println(*present)
    }
    println(counts.get("b").value())
    println(counts.getPtr("z") == null)

    var names: List<Str> = counts.keys()
    names.sort((left: Str, right: Str) -> left < right)
    var i: Int = 0
    while (i < names.size()) {
        println(names[i])
        i = i + 1
    }
    println(names.contains("c"))
    println(names.contains("z"))

    var values: List<Int> = counts.values()
    values.sort((left: Int, right: Int) -> left < right)
    var j: Int = 0
    while (j < values.size()) {
        println(values[j])
        j = j + 1
    }

    counts.remove("a")
    println(counts.size())
    counts.clear()
    println(counts.size())
    return 0
}
