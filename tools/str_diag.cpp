// Scratch: diagnose the Str inline fast paths (not part of the build).
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>
#include <cstring>

static void report(const char* what, const Str& value) {
    std::printf("%-28s size=%d empty=%d strlen=%zu data=\"%s\"\n", what, (int) value.size(),
                (int) value.empty(), std::strlen(value.data()), value.data());
}

int main() {
    Str empty;
    report("default Str()", empty);

    Str literal("abc");
    report("Str(\"abc\")", literal);

    Str pointerCount("abc", 3);
    report("Str(\"abc\", 3)", pointerCount);

    Str fromEmptyLiteral("");
    report("Str(\"\")", fromEmptyLiteral);

    Str pushed;
    pushed.push_back('x');
    report("default + push_back('x')", pushed);
    std::printf("  invariant data()[size()]=='\\0': %d\n", pushed.data()[pushed.size()] == '\0');

    Str counted;
    for (int i = 0; i < 5; i++) counted.push_back((char) ('a' + i));
    report("5x push_back on default", counted);

    // A buffer that is not NUL-terminated at `count` (the count overload must
    // not read past it).
    char raw[8];
    std::memcpy(raw, "abcdefgh", 8);
    Str partial(raw, 4);
    report("Str(non-terminated, 4)", partial);

    return 0;
}
