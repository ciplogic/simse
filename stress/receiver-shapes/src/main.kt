package fixtures

// The receiver of a method whose receiver is a *value* is the raw pointer the call
// site passed (`T* self`, the shape `impl_specs/rtl-abi.md` records), and the three
// ways a receiver reaches a call are three different spellings:
//
//   - a method that calls another method on `this` passes that pointer as it is -
//     `ns1_add(self, 1)`. Spelling it `simse_addressOf((*self))` (a dereference and
//     then the address of the dereference) is the same pointer, but it *reads* like a
//     copy of the whole receiver at every self-call;
//   - `*this` (a borrow of the receiver) is the same pointer, so it is `self` too;
//   - a call on a *place* - a local, a field, an element - still takes the place's
//     address (`simse_addressOf(holder.counter)`), which is the rule
//     `stress/pointer-place` pins from the other side: a receiver that is not `this`
//     must reach the caller's storage.
//
// The program is trivial on purpose - what this case decides is the shape of the
// emitted C++ at those call sites, which `expected.cpp` locks byte for byte.

data class Counter(var n: Int) {
    fun bump(): Unit {
        this.add(1)
    }

    fun add(delta: Int): Unit {
        this.n = this.n + delta
    }

    fun twice(): Int {
        this.bump()
        this.bump()
        return this.n
    }

    fun self(): *Counter {
        return *this
    }
}

data class Holder(var counter: Counter)

fun bumpTwice(counter: *Counter): Unit {
    counter.bump()
    counter.bump()
}

fun main(): Int {
    // Two self-calls reach the receiver the caller named, not a copy of it.
    val counter: Counter = Counter(0)
    println(counter.twice().toString())           // 2

    // A borrow of the receiver is the receiver: the write lands in `counter`.
    val through: *Counter = counter.self()
    through.add(3)
    println(counter.n.toString())                 // 5

    // A receiver that is a field of a local object.
    val holder: Holder = Holder(Counter(10))
    holder.counter.bump()
    println(holder.counter.n.toString())          // 11

    // ... and one that is a pointer parameter.
    bumpTwice(*counter)
    println(counter.n.toString())                 // 7
    return 0
}
