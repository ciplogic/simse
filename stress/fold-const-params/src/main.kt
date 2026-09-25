package foldconstparams

// Constant parameters (impl_specs/const-params.md): a parameter every call site passes the same
// literal becomes a local in the body and the parameter goes away. Four shapes pin the decision:
//
//   logMe    - the same literal at both call sites, so it folds
//   maybe    - the call sites differ (true, then false), so it must not
//   Str.tag  - reached only through its receiver form, so there is no plain-name call site
//   markIt   - its name is also passed as a value, so its signature is part of a function type
//
// The stdout proves the semantics are unchanged; `expected.cpp` is what proves the fold actually
// happened, since the point of the pass is invisible in a program's stdout - the folded
// `logMe` is emitted as `void ns1_logMe()` with `val isDebug: Bool = false;` inside.

// Folds: `isDebug` is `false` at both call sites.
fun logMe(isDebug: Bool) {
    if (isDebug) {
        println("logMe: debug")
    } else {
        println("logMe: quiet")
    }
}

// Does not fold: the two call sites disagree.
fun maybe(flag: Bool) {
    if (flag) {
        println("maybe: yes")
    } else {
        println("maybe: no")
    }
}

// Does not fold: only ever called through its receiver form (`"x".tag(...)`), whose callee is an
// `ExprMember`, so the pass sees no plain-name call site at all.
fun Str.tag(on: Bool) {
    if (on) {
        println("tag(" + this + "): on")
    } else {
        println("tag(" + this + "): off")
    }
}

// Does not fold: `markIt` is also used as a *value* below, which makes its signature part of a
// function type - changing it would break the use, and the call sites alone look perfect.
fun markIt(level: Int) {
    println("markIt: " + level.toString())
}

fun apply(f: (Int) -> Unit) {
    f(7)
}

fun main(): Int {
    logMe(false)
    logMe(false)

    maybe(true)
    maybe(false)

    "x".tag(true)

    markIt(3)
    apply(markIt)
    return 0
}
