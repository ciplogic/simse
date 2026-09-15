// Scratch: prints a few RTL type sizes (not part of the build).
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>

// A data-class-shaped aggregate, with and without the 4-byte packing rule.
struct MixedDefault {
    Int a;
    Int64 b;
    Int c;
};
SIMSE_PACK_PUSH
struct MixedPacked {
    Int a;
    Int64 b;
    Int c;
};
SIMSE_PACK_POP

int main() {
    std::printf("SmallVector<int,4>          %zu (align %zu)\n", sizeof(SmallVector<int, 4>),
                alignof(SmallVector<int, 4>));
    std::printf("SmallVector<Attribute,4>    %zu (align %zu)\n", sizeof(SmallVector<Attribute, 4>),
                alignof(SmallVector<Attribute, 4>));
    std::printf("List<int>                   %zu\n", sizeof(List<int>));
    std::printf("List<Str>                   %zu\n", sizeof(List<Str>));
    std::printf("List<Attribute>             %zu\n", sizeof(List<Attribute>));
    std::printf("Array<Int>                  %zu (align %zu)\n", sizeof(Array<Int>), alignof(Array<Int>));
    std::printf("Attribute                   %zu\n", sizeof(Attribute));
    std::printf("XmlNode                     %zu (align %zu)\n", sizeof(XmlNode), alignof(XmlNode));
    std::printf("MixedDefault  {Int,Int64,Int} %zu (align %zu)\n", sizeof(MixedDefault),
                alignof(MixedDefault));
    std::printf("MixedPacked   {Int,Int64,Int} %zu (align %zu)\n", sizeof(MixedPacked),
                alignof(MixedPacked));
#ifdef SIMSE_NO_PACK4
    std::printf("packing: host default\n");
#else
    std::printf("packing: SIMSE_PACK (4-byte)\n");
#endif
    return 0;
}
