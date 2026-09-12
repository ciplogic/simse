// Scratch: what does `constexpr Str` do today, per backing? (Not part of the build.)
#include "cppsrc/rtl/simse.hpp"

#if defined(SIMSE_STR_STD_STRING)
constexpr Str kName = "Expr.Binary";            // std::string backing
#else
constexpr Str kName = "Expr.Binary";            // SmString backing
#endif

static_assert(kName.size() == 11, "size must be computable at compile time");
static_assert(kName == "Expr.Binary", "comparison must be constexpr");

int main() {
    return (int) kName.size();
}
