// fatal.kt
//
// The fatal-error path (specs/functions.md, "Fatal errors"): `panic` reports a bug and
// aborts the process; `assert(cond, message)` panics unless `cond` holds. Nothing unwinds
// and no destructors run - the process dies where it stands - so `Res<T>` remains the
// shape for failures a caller may handle and these the shape for bugs it may not.
//
// Both are `res` natives with explicit `simse_` symbols because a prelude function is
// bare in the emitted C++ (src/rtl/optres.kt's header explains the rule), and `assert`
// in particular would meet the C macro of that name. The C++ flushes stdout before it
// aborts, so output buffered ahead of the failure - a test runner's progress lines, for
// one (specs/testing.md) - still reaches the console.

package rtl

// `panic(message)`: the bug path. The message is on stderr as `panic: <message>`, then
// the process aborts.
@SmGen("res", "fatal", "simse_panic")
fun panic(message: Str): Unit

// `assert(cond, message)`: a fact about correct code. Panics - `panic: assert failed:
// <message>` on stderr - unless `cond` holds.
@SmGen("res", "fatal", "simse_assert")
fun assert(cond: Bool, message: Str): Unit
