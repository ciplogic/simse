package math

// The `math` module: the standard math library over `Float64` (and `Float32` for the
// functions a float-heavy program actually calls), one `@SmGen("res", "libm", ...)`
// declaration per wrapper in `_res.md`'s `libm` section. Every declaration is marked
// `data` - read-only, a pure function of its arguments - so the borrow fixpoint trusts
// callers through them and the purity machinery may reuse repeated calls.
//
// The compiler folds the exact identities of these calls ahead of sema
// (`src/parser/FoldMath.kt`): `sin(0.0)` becomes `0.0`, `cos(0.0)` becomes `1.0`,
// `log(1.0)` becomes `0.0`, `pow(x, 1.0)` becomes `x`, and so on - only identities
// that hold for the correctly rounded function, never an evaluated approximation, so
// a folded program is bit-identical to the unfolded one. The fold is gated on the
// callee resolving to *these* declarations (the wrapper symbols); a program that
// declares its own `sin` is left alone.
//
// A program reaches the module with `--module modules/math` and `import math`.

@SmGen("res", "libm", "simse_math_sin", "data")
fun sin(x: Float64): Float64

@SmGen("res", "libm", "simse_math_cos", "data")
fun cos(x: Float64): Float64

@SmGen("res", "libm", "simse_math_tan", "data")
fun tan(x: Float64): Float64

@SmGen("res", "libm", "simse_math_asin", "data")
fun asin(x: Float64): Float64

@SmGen("res", "libm", "simse_math_acos", "data")
fun acos(x: Float64): Float64

@SmGen("res", "libm", "simse_math_atan", "data")
fun atan(x: Float64): Float64

@SmGen("res", "libm", "simse_math_atan2", "data")
fun atan2(y: Float64, x: Float64): Float64

@SmGen("res", "libm", "simse_math_sinh", "data")
fun sinh(x: Float64): Float64

@SmGen("res", "libm", "simse_math_cosh", "data")
fun cosh(x: Float64): Float64

@SmGen("res", "libm", "simse_math_tanh", "data")
fun tanh(x: Float64): Float64

@SmGen("res", "libm", "simse_math_exp", "data")
fun exp(x: Float64): Float64

@SmGen("res", "libm", "simse_math_log", "data")
fun log(x: Float64): Float64

@SmGen("res", "libm", "simse_math_log2", "data")
fun log2(x: Float64): Float64

@SmGen("res", "libm", "simse_math_log10", "data")
fun log10(x: Float64): Float64

@SmGen("res", "libm", "simse_math_pow", "data")
fun pow(base: Float64, exponent: Float64): Float64

@SmGen("res", "libm", "simse_math_sqrt", "data")
fun sqrt(x: Float64): Float64

@SmGen("res", "libm", "simse_math_cbrt", "data")
fun cbrt(x: Float64): Float64

@SmGen("res", "libm", "simse_math_fabs", "data")
fun abs(x: Float64): Float64

@SmGen("res", "libm", "simse_math_floor", "data")
fun floor(x: Float64): Float64

@SmGen("res", "libm", "simse_math_ceil", "data")
fun ceil(x: Float64): Float64

@SmGen("res", "libm", "simse_math_round", "data")
fun round(x: Float64): Float64

@SmGen("res", "libm", "simse_math_sinf", "data")
fun sin(x: Float32): Float32

@SmGen("res", "libm", "simse_math_cosf", "data")
fun cos(x: Float32): Float32

@SmGen("res", "libm", "simse_math_tanf", "data")
fun tan(x: Float32): Float32

@SmGen("res", "libm", "simse_math_sqrtf", "data")
fun sqrt(x: Float32): Float32

@SmGen("res", "libm", "simse_math_fabsf", "data")
fun abs(x: Float32): Float32

@SmGen("res", "libm", "simse_math_powf", "data")
fun pow(base: Float32, exponent: Float32): Float32
