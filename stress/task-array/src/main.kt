package fixtures

// `Array<T>` across the task pool. A default-constructed `Array<T>` used to hand out one
// *static* empty block per element type, and a counted reference's count is local to one
// thread (`smref.hpp`) - so every worker copied that one block and raced its count, freeing
// it under a live handle (a double free, caught by ASan under the express example's load).
// An empty `Array` is a null block now: no block, no count, nothing to race.
//
// Sixteen long-lived tasks each loop on a default-constructed array that is assigned over
// (the shape the bug lived in), with a managed element type (`Str`) so the block cannot be
// folded away. Each task's total is stored in its own slot, so the work is observable and the
// output is deterministic.

var results: Array<Int> = arrayEmpty<Int>()

suspend fun work(id: Int): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < 50000) {
        var empty: Array<Str> = Array<Str>()
        var items: List<Str> = List<Str>()
        items.append("x")
        var block: Array<Str> = items.toArray()
        empty = block
        total = total + empty.count()
        i = i + 1
    }
    results[id] = total
    return total
}

fun main(): Int {
    results = Array<Int>(16)
    tasksCompute(16, 16)
    var i: Int = 0
    while (i < 16) {
        tasksEnqueue(workTask(i))
        i = i + 1
    }
    tasksDrain()
    var sum: Int = 0
    var j: Int = 0
    while (j < 16) {
        sum = sum + results[j]
        j = j + 1
    }
    println(sum.toString())
    return 0
}
