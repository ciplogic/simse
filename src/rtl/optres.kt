// optres.kt
//
// `Opt<T>` and `Res<T>` (specs/core-types.md), written as the `union class`es they always
// were: `Opt<T>` is one `Value` arm under the implicit `None` tag, and `Res<T>` adds the
// `Error: Str` arm. The generated storage chooses its form per instantiation, so an
// `Opt<Int>` is a trivially copyable aggregate while an `Opt<Str>` carries the destructor
// and the copy/move members that keep its text alive.
//
// The member surface the language spells - `hasValue()`, `isOk()`, `value()`, `error()` -
// is declared here with `simse_`-prefixed names: a *prelude* function is bare in the emitted
// C++, and generated bodies have locals named `value` (a local would shadow the function),
// so the checker renames a call on an `Opt`/`Res` receiver to the prefixed spelling
// (`expandResOptMember`, src/sema/SemaAnalyze.kt). The same goes for the static constructor
// spellings (`Opt<Int>.some(x)`, `Res<Str>.ok(x)`, ...): the checker rewrites each call onto
// the builder functions below (`expandResOptCtor`), which place the arm through its
// generated setter - by *name*, so the `Res<Str>` instantiation, where `Value: T` and
// `Error: Str` are both `Str`, cannot make the construction ambiguous.

package rtl

// `Opt<T>` models an optional value: it either holds a `T` or is empty, and it is the
// language's way of saying "no value" instead of a null pointer. An empty optional builds
// nothing - the absence is the tag's `None`, not a second object beside the payload.
// `hasValue()` tests it and `value()` reads the payload unchecked, as every RTL access is.
union class Opt<T>(var Value: T) {
    // `o.hasValue()` and `o.isOk()`: the tag is `Value`.
    fun simse_optHasValue(): Bool {
        return this.isOfType(SmOptTypes.Value)
    }

    // `o.value()`: the payload; only valid when `hasValue()`.
    fun simse_optValue(): T {
        return this.Value
    }
}

// `Res<T>` reports a fallible operation: success carries a `T`, failure carries an error
// message as a `Str`. There being no exceptions, functions that can fail return a `Res`.
// `isOk()` reads the tag - an empty message is not what makes a result ok - and the payload
// and the message are reached as `value()` and `error()`, unchecked like every RTL access.
union class Res<T>(var Value: T, var Error: Str) {
    // `r.isOk()` and `r.hasValue()`: the payload arm is live.
    fun simse_resHasValue(): Bool {
        return this.isOfType(SmResTypes.Value)
    }

    // `r.value()`: the payload; only valid when `isOk()`.
    fun simse_resValue(): T {
        return this.Value
    }

    // `r.error()`: the message; only valid when the tag says `Error`.
    fun simse_resError(): Str {
        return this.Error
    }
}

// `Opt<T>.some(value)`: the one-arm construction.
fun simse_optSome<T>(value: T): Opt<T> {
    var result: Opt<T> = Opt<T>()
    result.setValue(value)
    return result
}

// `Opt<T>.none()`: the empty one.
fun simse_optNone<T>(): Opt<T> {
    var result: Opt<T> = Opt<T>()
    return result
}

// `Res<T>.ok(value)`: the payload arm.
fun simse_resOk<T>(value: T): Res<T> {
    var result: Res<T> = Res<T>()
    result.setValue(value)
    return result
}

// `Res<T>.err(message)`: the message arm.
fun simse_resErr<T>(message: Str): Res<T> {
    var result: Res<T> = Res<T>()
    result.setError(message)
    return result
}
