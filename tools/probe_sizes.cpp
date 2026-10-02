// The RTL's layout probe: what `Str`, `Span`, `StrView` and the containers actually cost
// on this build, so a packing change is a measurement instead of an assumption (T74 in
// impl_specs/capability-matrix.md). The language's rule is 4-byte packing
// (specs/memory-model.md); these numbers are the check that the shim follows it.
//
//   bun build.js --cpp tools/probe_sizes.cpp --exe tools/probe_sizes.exe --release
//   ./tools/probe_sizes.exe
//
// Widths to watch: `Str` 32 (4 len + 4 cap + 24 inline), `StrView`/`Span<Char>` 12 packed
// (16 under host alignment), `List<StrView>` 56.
#include "src/rtl/simse.hpp"

#include <cstdio>

// `Attribute`/`XmlNode` are generated from their declaration (src/modules/xml/api.kt), so a
// standalone probe mirrors the emitted shape: a 4-byte-packed aggregate whose `Array<XmlNode>`
// field breaks the recursion.
SIMSE_PACK_PUSH
struct Attribute {
    Str name;
    Str value;
};
struct XmlNode {
    Str name;
    List<Attribute> attributes;
    Array<XmlNode> Children;
};
SIMSE_PACK_POP

// Is a short `Str` constant-initializable (and so are its array entries, with the
// initializer's bytes droppable at link time)? And can a `StrView` be?
constexpr Str kConstant = "abc";
constexpr StrView kEmpty{};

int main() {
    std::printf("sizeof(Str)            %zu\n", sizeof(Str));
    std::printf("sizeof(StrView)        %zu\n", sizeof(StrView));
    std::printf("sizeof(Span<Char>)     %zu\n", sizeof(Span<Char>));
    std::printf("sizeof(List<Str>)      %zu\n", sizeof(List<Str>));
    std::printf("sizeof(List<StrView>)  %zu\n", sizeof(List<StrView>));
    std::printf("sizeof(Int)            %zu\n", sizeof(Int));
    std::printf("sizeof(XmlNode)         %zu\n", sizeof(XmlNode));
    std::printf("sizeof(Attribute)       %zu\n", sizeof(Attribute));
    std::printf("sizeof(List<Attribute>) %zu\n", sizeof(List<Attribute>));
    std::printf("sizeof(Array<XmlNode>)  %zu\n", sizeof(Array<XmlNode>));
    std::printf("sizeof(AstXmlNode)     %zu\n", sizeof(AstXmlNode));
    std::printf("sizeof(AstNodeAttribute) %zu\n", sizeof(AstNodeAttribute));
    std::printf("constexpr Str ok:      %d\n", (int) kConstant.size());
    std::printf("constexpr StrView ok:  %d\n", (int) kEmpty.bytes.len);
    return 0;
}
