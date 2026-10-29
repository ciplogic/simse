Generated C++
====
The `math` module's C++ - the declarations are modules/math/api.kt. The `libm` section is
the standard math library: one thin inline wrapper per function, so the emitted call names
a symbol the compiler owns (`simse_math_sin`, never a bare `sin` that could meet a macro
or a namespace surprise), and `<cmath>` is included exactly when a program reaches for
one of them. An unreached section is not emitted, so a program that never imports `math`
pays nothing.

!libm
====
The wrappers are the platform's `std::sin`/`std::cos`/... over `double` and `float`
(the module declares both widths). The compiler's literal fold (`src/parser/FoldMath.kt`)
knows the exact identities of these functions and replaces the constant calls ahead of
sema - `sin(0.0)` is `0.0` before anything downstream sees the program - and the float
division peephole lives in the same pass.

forward:
```cpp
#include <cmath>

inline double simse_math_sin(double x) { return std::sin(x); }
inline double simse_math_cos(double x) { return std::cos(x); }
inline double simse_math_tan(double x) { return std::tan(x); }
inline double simse_math_asin(double x) { return std::asin(x); }
inline double simse_math_acos(double x) { return std::acos(x); }
inline double simse_math_atan(double x) { return std::atan(x); }
inline double simse_math_atan2(double y, double x) { return std::atan2(y, x); }
inline double simse_math_sinh(double x) { return std::sinh(x); }
inline double simse_math_cosh(double x) { return std::cosh(x); }
inline double simse_math_tanh(double x) { return std::tanh(x); }
inline double simse_math_exp(double x) { return std::exp(x); }
inline double simse_math_log(double x) { return std::log(x); }
inline double simse_math_log2(double x) { return std::log2(x); }
inline double simse_math_log10(double x) { return std::log10(x); }
inline double simse_math_pow(double base, double exponent) { return std::pow(base, exponent); }
inline double simse_math_sqrt(double x) { return std::sqrt(x); }
inline double simse_math_cbrt(double x) { return std::cbrt(x); }
inline double simse_math_fabs(double x) { return std::fabs(x); }
inline double simse_math_floor(double x) { return std::floor(x); }
inline double simse_math_ceil(double x) { return std::ceil(x); }
inline double simse_math_round(double x) { return std::round(x); }

inline float simse_math_sinf(float x) { return std::sin(x); }
inline float simse_math_cosf(float x) { return std::cos(x); }
inline float simse_math_tanf(float x) { return std::tan(x); }
inline float simse_math_sqrtf(float x) { return std::sqrt(x); }
inline float simse_math_fabsf(float x) { return std::fabs(x); }
inline float simse_math_powf(float base, float exponent) { return std::pow(base, exponent); }
```
