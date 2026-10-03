package fixtures

// ---- union-class ----
// `union class` (specs/declarations.md): a discriminated union - one field live at a time,
// named by the implicit `Sm<Name>Types` tag enum. `getTypeOf`/`isOfType` read the tag, a
// generated `get<Field>` answers an `Opt` (empty when the tag says another arm), and
// `set<Field>` moves the tag as it writes. `U()` starts `None`; `U(value)` picks the arm by
// the value's type; direct field reads and writes reach the storage under the same rules.
// A comparison against a union value is its tag comparison - the generated `==`/`!=`
// against the tag enum - so `when (u)` with bare arm names is the same `when` as
// `when (u.getTypeOf())` with qualified labels; the language never checks either for
// exhaustiveness.

union class DoubleOrFloat(var IntValue: Int, var DoubleValue: Float64)

// Two fields of *distinct* enum types: `SideOrSide(Left.L)` is unambiguous, even though
// both arms are enums.
enum class Left { L }

enum class Right { R }

union class SideOrSide(var L: Left, var R: Right)

// A zero-field union: the tag alone, always `None`.
union class Marker()

// A user method in the body works over the same storage, like any data-class method.
union class WithMethod(var I: Int) {
    fun doubled(): Int {
        return this.I * 2
    }
}

fun label(u: DoubleOrFloat): Str {
    var out: Str = ""
    when (u) {
        IntValue -> {
            out = "int"
        }

        DoubleValue -> {
            out = "float"
        }

        else -> {
            out = "none"
        }
    }
    return out
}

// The same shorthand with every arm named: `None` is a tag member like any other.
fun tagged(u: DoubleOrFloat): Str {
    var out: Str = ""
    when (u) {
        None -> {
            out = "none"
        }

        IntValue -> {
            out = "int"
        }

        DoubleValue -> {
            out = "float"
        }
    }
    return out
}

fun labelSide(s: SideOrSide): Str {
    var out: Str = ""
    when (s.getTypeOf()) {
        SmSideOrSideTypes.L -> {
            out = "L"
        }

        SmSideOrSideTypes.R -> {
            out = "R"
        }

        else -> {
            out = "none"
        }
    }
    return out
}

// An extension method on a union class, outside its body.
fun DoubleOrFloat.describe(): Str {
    return "d:" + label(this)
}

fun partUnionClass(): Int {
    // `U()` is the `None` construction; the tag starts there anyway.
    var a: DoubleOrFloat = DoubleOrFloat()
    println(a.isOfType(SmDoubleOrFloatTypes.None))
    println(a.getTypeOf() == SmDoubleOrFloatTypes.None)
    println(a.getIntValue().hasValue())
    println(tagged(a))

    a.setIntValue(5)
    val i = a.getIntValue()
    println(i.value())
    println(a.getDoubleValue().hasValue())
    println(a.isOfType(SmDoubleOrFloatTypes.IntValue))
    println(a == IntValue)
    println(a != DoubleValue)
    println(a == SmDoubleOrFloatTypes.IntValue)
    println(label(a))
    println(tagged(a))
    println(a.describe())

    // `U(value)`: the argument's type picks the arm.
    var b = DoubleOrFloat(2.5)
    val d = b.getDoubleValue()
    println(d.hasValue())
    println(d.value())
    println(label(b))

    // A value of an arm's type in a variable, and the explicit-type declaration form.
    var five: Int = 5
    var c: DoubleOrFloat = DoubleOrFloat(five)
    println(label(c))
    println(c.IntValue)

    // Direct access: a read, and a write that moves the storage but not the tag.
    c.IntValue = 11
    println(c.IntValue)
    println(label(c))
    c.setNone()
    println(label(c))
    println(tagged(c))

    // Two distinct enum arms.
    var s = SideOrSide(Left.L)
    println(s.isOfType(SmSideOrSideTypes.L))
    s.setR(Right.R)
    println(labelSide(s))

    // A zero-field union and a body method.
    var m = Marker()
    println(m.isOfType(SmMarkerTypes.None))
    var w = WithMethod(4)
    println(w.doubled())
    w.I = 6
    println(w.doubled())
    return 0
}

fun partConstructionReturn(): Int {
    // `return U(value)` and the parenthesized `return (value)` both construct through the
    // arm's `initByValue`.
    println(label(make(7)))
    println(label(makeParen(8)))
    return 0
}

fun make(v: Int): DoubleOrFloat {
    return DoubleOrFloat(v)
}

fun makeParen(v: Int): DoubleOrFloat {
    return (v)
}

fun main(): Int {
    partUnionClass()
    partConstructionReturn()
    return 0
}
