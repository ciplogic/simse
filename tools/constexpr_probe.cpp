// Scratch: is `Str` usable in a constant expression? (Not part of the build.)
//
//   tools\_probe.bat constexpr_probe
#include "cppsrc/rtl/simse.hpp"

constexpr Str kName = "Expr.Binary";

static_assert(kName.size() == 11, "size must be computable at compile time");
static_assert(kName == "Expr.Binary", "comparison must be constexpr");

int main() {
    return (int) kName.size();
}
