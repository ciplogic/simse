// Scratch: the Array<T> layout, checked rather than assumed
// (specs/built-in-types.md): one allocation, the element count first, the
// elements immediately after it, and one shared zero-length block for every
// empty array of a given element type.
//
//   tools\_probe.bat array_layout_probe /O2 /DNDEBUG
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>

int main() {
    Array<Int> values(3);
    values[0] = 7;
    values[1] = 8;
    values[2] = 9;

    std::printf("sizeof(Array<Int>)            %zu\n", sizeof(Array<Int>));
    std::printf("sizeof(ArrayBlock<Int>)       %zu\n", sizeof(ArrayBlock<Int>));
    std::printf("items offset                  %zu\n", ArrayBlock<Int>::itemsOffset());
    std::printf("block bytes for 3 elements    %zu\n", ArrayBlock<Int>::blockBytes(3));

    // The count is the first thing in the allocation.
    const Int storedCount = *reinterpret_cast<const Int*>(values._block.get());
    std::printf("count stored at offset 0      %d\n", storedCount);

    // The elements follow immediately and are contiguous.
    const char* items = reinterpret_cast<const char*>(values._block->items());
    const char* block = reinterpret_cast<const char*>(values._block.get());
    std::printf("(items - block)               %lld\n", (long long) (items - block));
    std::printf("stride between elements       %lld\n",
                (long long) ((const char*) &values[1] - (const char*) &values[0]));
    std::printf("elements                      %d %d %d\n", values[0], values[1], values[2]);

    // Assignment shares the block; the shared empty block is one per type.
    Array<Int> alias = values;
    alias[0] = 70;
    std::printf("visible through the other     %d\n", values[0]);
    Array<Int> first = arrayEmpty<Int>();
    Array<Int> second = arrayEmpty<Int>();
    std::printf("empty count                   %d\n", first.count());
    std::printf("empty blocks shared           %d\n", (int) (first._block == second._block));
    std::printf("empty block is allocated      %d\n", (int) (first._block != nullptr));

    // The same block reaches the language surface: `List<T>.toArray()` on an empty
    // list returns the shared empty array, so it allocates nothing.
    {
        List<Int> none;
        Array<Int> fromList = simse_list_toArray(none);
        std::printf("empty list toArray count      %d\n", fromList.count());
        std::printf("empty list toArray shared     %d\n", (int) (fromList._block == first._block));
    }

    // Element types with lifetimes: constructed in place, destroyed with the
    // block (this scope is the leak/UB check - run under a debugger or the ASan
    // build if it misbehaves).
    {
        Array<Str> names(2);
        names[0] = "hello";
        names[1] = "world";
        std::printf("heap strings                  %s %s\n", names[0].c_str(), names[1].c_str());
    }

    // With the standard backings the element type is a host type; the offset
    // stays the language's under packing.
    std::printf("Str items offset              %zu\n", ArrayBlock<Str>::itemsOffset());

    // The AST node's own layout (specs/xml-node.md): the children are one handle
    // to a block, the attributes a value list. Those two numbers are what a
    // parsed node costs per copy, and `blockBytes(n)` what its children cost.
    std::printf("sizeof(XmlNode)               %zu\n", sizeof(XmlNode));
    std::printf("sizeof(Str)                   %zu\n", sizeof(Str));
    std::printf("sizeof(Array<XmlNode>)        %zu\n", sizeof(Array<XmlNode>));
    std::printf("sizeof(List<Attribute>)       %zu\n", sizeof(List<Attribute>));
    std::printf("children block bytes for 2    %zu\n", ArrayBlock<XmlNode>::blockBytes(2));

    // The compiler's AST node (cppsrc/rtl/astxml.hpp): same shape, but the role
    // and the attribute keys are enums, so the attribute list is much smaller.
    std::printf("sizeof(AstXmlNode)             %zu\n", sizeof(AstXmlNode));
    std::printf("sizeof(AstNodeAttribute)      %zu\n", sizeof(AstNodeAttribute));
    std::printf("sizeof(List<AstNodeAttribute>) %zu\n", sizeof(List<AstNodeAttribute>));
    std::printf("children block bytes for 2    %zu (AstXmlNode)\n", ArrayBlock<AstXmlNode>::blockBytes(2));
    return 0;
}
