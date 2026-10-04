package fixtures

// `protocol` (specs/declarations.md, "Protocols"): a named method signature, satisfied
// structurally - any receiver function that matches it implements it, whether declared in a
// class body or as an extension. A generic function's `when T: P` clause is a *constraint*,
// checked at every instantiation; it is not an existential: `T` always names a concrete
// type when the body's C++ is written, and the call resolves to that type's declaration.

protocol Printable fun <T> T.toString(): Str

protocol Countable fun <T> T.countItems(): Int

// A generic protocol: `TDest` is the other side's type, matched together with the subject.
protocol Equality fun <T, TDest> T.equalsWith(other: *TDest): Bool

data class Crate(var apples: Int, var pears: Int) {
    // A class-body method satisfies the protocol the same way an extension does.
    fun toString(): Str {
        return "Crate"
    }
}

fun Crate.countItems(): Int {
    return this.apples + this.pears
}

fun Crate.equalsWith<TOther>(other: *TOther): Bool {
    return this.countItems() == 12
}

fun display<T>(value: *T) when T: Printable, Countable {
    val text: Str = value.toString()
    val count: Int = value.countItems()
    print(text)
    print(": ")
    print(count)
    print("\n")
}

// The receiver is a *value* here: the protocol's `T*` first parameter is the address the
// call takes itself.
fun countOne<T>(value: T) when T: Countable {
    print("count ")
    print(value.countItems())
    print("\n")
}

fun sameAs<T, U>(left: *T, right: *U) when T: Equality, U: Equality {
    if (left.equalsWith(right)) {
        print("same\n")
    } else {
        print("different\n")
    }
}

fun main(): Int {
    val full = Crate(5, 7)
    display(*full)
    countOne(full)
    val small = Crate(1, 2)
    sameAs(*full, *small)
    // A prelude type satisfies the protocols its declarations match: `Int` has a
    // `toString` (src/rtl/rtl.kt), reached through the same dispatch set.
    val n: Int = 4
    print("int ")
    print(n.toString())
    print("\n")
    return 0
}
