package escapeparams

// The escape-parameter analysis (`src/parser/EscapeParams.kt`): which parameters a callee may
// *retain*, proved whole-program before sema, and the stack promotion's reading of it
// (`linear/PromoteRefs.kt`). A parameter proved to retain keeps a caller's `&T` local a box;
// one that does not lets the box become a stack value and a raw pointer. `print`/`println`,
// which the emitter spells with no declaration, are mapped as borrows; a body-less declaration
// is trusted only by its mark.

data class Cell(var value: Int)

// A file-level `*Cell`: `stash` retains its parameter, so a caller's handle keeps its box.
var saved: *Cell = null

fun stash(p: *Cell): Unit {
    saved = p
}

// The receiver is handed to `stash`: retention propagates through the call.
fun Cell.leak(): Unit {
    stash(this)
}

// `c` is handed to a retaining parameter: it stays a `Ref` (`makeRef`).
fun escaped(): Int {
    var c: &Cell = &Cell(1)
    stash(*c)
    return c.value
}

// The receiver's own escape keeps the caller's handle a `Ref`.
fun propagated(): Int {
    var c: &Cell = &Cell(2)
    c.leak()
    return c.value
}

// A method that only writes through its receiver is not retaining it: the box promotes to a
// stack `Cell` and a `Cell*`.
fun kept(): Int {
    var c: &Cell = &Cell(3)
    c.value = c.value + 1
    return c.value
}

// `print` mapped as borrow: the handle promotes and is read through (`*(x)`).
fun printed(): Unit {
    var x: &Str = &Str("abc")
    print(x)
}

// A union class: `&Opt<Int>(v)` boxes a value built through `initByValue`; non-escaping, it
// promotes to the stack (`Opt<Int> _sm_stk0; Opt<Int>* o;`).
fun opt(): Int {
    var o: &Opt<Int> = &Opt<Int>(7)
    if (!o.hasValue()) {
        return -1
    }
    return o.value()
}

// A generic data class: `&Gen<Int>(v)` promotes through the value `CallCtor` (`Gen<Int>{v}`).
data class Gen<T>(var value: T)

fun generic(): Int {
    var g: &Gen<Int> = &Gen<Int>(9)
    return g.value
}

fun main(): Int {
    println(escaped())
    println(propagated())
    println(kept())
    printed()
    print(";")
    println(opt())
    println(generic())
    return 0
}
