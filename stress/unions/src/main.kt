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

// Arms that own storage switch the generated C++ to the managed form: the live arm is
// destroyed by tag when the value dies or a setter replaces it, and the copy/move members
// keep a `Str`'s heap text or a `List`'s block alive.
union class IntOrStr(var I: Int, var S: Str)

union class IntOrList(var I: Int, var L: List<Int>)

// ---- union-class-generics ----
// A generic union class: one non-generic `SmRes2Types` tag enum, a C++ template struct and
// template methods, and a construction whose explicit type arguments bind the fields for
// type matching. `Res2<T>` has the same shape the prelude's `Res<T>` is written with
// (src/rtl/optres.kt); `Opt2<T>` is `Opt<T>` as a single `Value` arm plus the implicit
// `None` tag. The generated storage picks its form per instantiation, so `Opt2<Int>` stays
// trivially copyable while `Opt2<Str>` is managed.
union class Res2<T>(var Value: T, var Error: Str)

union class Opt2<T>(var Value: T)

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

fun strLen(u: IntOrStr): Int {
    val s = u.getS()
    if (s.hasValue()) {
        return s.value().size()
    }
    return -1
}

fun strKind(u: IntOrStr): Str {
    var out: Str = "?"
    when (u) {
        I -> {
            out = "int"
        }

        S -> {
            out = "str"
        }

        else -> {
            out = "none"
        }
    }
    return out
}

fun mkStr(text: Str): IntOrStr {
    return (text)
}

fun ok2<T>(value: T): Res2<T> {
    return (value)
}

fun err2<T>(message: Str): Res2<T> {
    return (message)
}

// The instantiation where two arms' types coincide: at `Res2<Str>` the `Value: T` and
// `Error: Str` arms are both `Str`, and the arm constructor picks the *earlier* one - so
// this is the `Value` arm, the convention `Res<Str>` always had for `return (text)`.
fun okStr2(text: Str): Res2<Str> {
    return (text)
}

fun isOk2<T>(r: Res2<T>): Bool {
    return r.isOfType(SmRes2Types.Value)
}

fun optValue2<T>(o: Opt2<T>): Opt<T> {
    return o.getValue()
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

fun partManaged(): Int {
    // A text longer than the inline buffer: the arm owns heap storage.
    var u = IntOrStr("a-string-longer-than-the-inline-buffer")
    println(strLen(u))
    println(strKind(u))

    // A copy (copy constructor) and a return (move) must keep the text alive.
    var v = u
    println(strLen(v))
    println(strLen(mkStr("returned-through-a-function-call")))

    // Switching arms destroys the old one and places the new; switching back re-places.
    u.setI(7)
    println(u.getI().value())
    println(u.getS().hasValue())
    u.setS("second")
    val s2 = u.getS()
    println(s2.value())

    // Assignment between managed unions.
    v = u
    println(strLen(v))

    // `setNone` destroys the live string.
    u.setNone()
    println(u.getS().hasValue())
    println(strKind(u))

    // A `List` arm: the managed path follows the type, not `Str` specifically.
    var numbers: List<Int> = listOf<Int>(1, 2, 3)
    var w = IntOrList(numbers)
    val l = w.getL()
    println(l.value().size())
    w.setI(2)
    println(w.getL().hasValue())
    var x = w
    println(x.getI().value())
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

fun partUnionGenerics(): Int {
    // Side by side with the prelude's own `Res<T>` (the same shape, src/rtl/optres.kt) in
    // the same uses.
    var a: Res<Int> = Res<Int>.ok(7)
    var b: Res2<Int> = ok2(7)
    println(a.isOk())
    println(isOk2(b))
    println(a.Value)
    val bv = b.getValue()
    println(bv.value())

    var e: Res<Int> = Res<Int>.err("no")
    var e2: Res2<Int> = err2<Int>("no")
    println(e.isOk())
    println(isOk2(e2))
    println(e.Error)
    val e2e = e2.getError()
    println(e2e.value())

    // The tag `when` over the generic union, and a setter switch.
    var out: Str = ""
    when (b) {
        Value -> {
            out = "value"
        }

        Error -> {
            out = "error"
        }

        else -> {
            out = "none"
        }
    }
    println(out)
    b.setError("later")
    println(isOk2(b))
    val later = b.getError()
    println(later.value())
    var none2 = Res2<Int>()
    println(isOk2(none2))
    println(optValue2(Opt2<Int>()).hasValue())

    // The built-in `Opt<T>` side by side with `Opt2<T>`.
    var o: Opt<Int> = Opt<Int>.some(9)
    var o2: Opt2<Int> = Opt2<Int>(9)
    println(o.value())
    val o2v = o2.getValue()
    println(o2v.value())
    var empty: Opt<Int> = Opt<Int>.none()
    var empty2: Opt2<Int> = Opt2<Int>()
    println(empty.hasValue())
    println(empty2.getValue().hasValue())

    // A `Str` payload through the single-arm `Opt2<T>`, and a managed element through
    // `Res2<T>`, including the colliding instantiation (`T = Str` coincides with the
    // `Error: Str` arm; the arm constructor picks the earlier `Value` arm).
    var s: Opt2<Str> = Opt2<Str>("payload-longer-than-the-inline-buffer!")
    val sv = s.getValue()
    println(sv.value().size())
    var numbers: List<Int> = listOf<Int>(4, 5)
    var l: Res2<List<Int>> = ok2(numbers)
    val lv = l.getValue()
    println(lv.value().size())
    var collided = okStr2("collided")
    println(isOk2(collided))
    println(collided.getValue().value())
    collided.setError("boom")
    println(isOk2(collided))
    println(collided.getError().value())
    return 0
}

fun main(): Int {
    partUnionClass()
    partManaged()
    partUnionGenerics()
    partConstructionReturn()
    return 0
}
