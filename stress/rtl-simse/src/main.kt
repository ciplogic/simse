package fixtures

// The runtime operations whose bodies the *language* writes, not C++
// (`impl_specs/rtl-abi.md`, T70/T71): `min`, `max`, `fmtStr` and `Str.isEmpty`. They are
// prelude functions with a body, so each is emitted only when a program reaches it -
// which is also why this case exists: nothing in the compiler calls `min`/`max`, so
// without a case here their bodies would never be compiled at all.
//
// What each one pins:
//
//   - `min`/`max` are **generic** (`fun min<T>(a: T, b: T): T`), so they work for any
//     type with `<`: the instantiations below are `Int`, `Str` and `Float64`;
//   - the result of an **inferred** instantiation (`min(3, 2)`) is typed `T` at the call
//     site - the type pass substitutes the arguments of an *explicit* instantiation only
//     (`sema/TypeInfer.kt`, `functionReturn`) - so a destination has to state the type,
//     or the call has to name it: `min<Int>(3, 2)`. Both spellings are here, and the
//     inferred one needs a member call to go through a typed local.
//   - `fmtStr` fills one item per `|`, and a call whose points and items do not line up
//     (or that passes no items) gets the format back *unfilled* rather than a
//     half-filled result - the one shape it does not handle is the one it refuses.
//   - `Str.isEmpty` is a prelude body with a **receiver**: the receiver-type form
//     (`fun Str.isEmpty()`) is what marks it an extension - a `native` spells the
//     receiver as an explicit `this`, a function with a body cannot. Its call sites
//     below are the shares a receiver has: a literal, a local, and a member call on
//     `this` inside the body of a receiver function of its own.

fun main(): Int {
    // The inferred form: the destination spells the type.
    val least: Int = min(3, 2)
    val most: Int = max(3, 2)
    println(least.toString())                     // 2
    println(most.toString())                      // 3

    // The explicit form: the call names the type, so the result is typed anywhere.
    println(min<Str>("b", "a"))                   // a
    println(max<Str>("b", "a"))                   // b
    println(min<Float64>(2.5, 1.5).toString())    // 1.5

    // One item per point, in order, `||` included (an empty run between two points).
    println(fmtStr("[|::|]", "a", "b"))           // [a::b]
    println(fmtStr("|,|,|", "1", "2", "3"))       // 1,2,3
    println(fmtStr("||", "x", "y"))               // xy

    // The shape that does not line up comes back as the format, unfilled.
    println(fmtStr("no points", "x"))             // no points
    println(fmtStr("| |", "only-one"))            // | |

    // `Str.isEmpty` is the language's own body too (T71). Its receiver is an ordinary
    // parameter in the generated function (`Str* self`), so the call passes the
    // receiver's address - a local, and a field through `this`.
    println("a".isEmpty())                        // false
    println("".isEmpty())                        // true
    val text: Str = "b"
    println(text.isEmpty())                       // false
    println(Shell("").isEmpty())                   // true
    return 0
}

// The `this` receiver shape: a prelude body called on a field of the receiver.
data class Shell(var text: Str) {

    fun isEmpty(): Bool {
        return this.text.isEmpty()
    }
}
