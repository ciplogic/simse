// The per-iteration derivation shape (what today's name-root reads emit: one
// spanOfStr derived inside the loop, reads through .ptr[j]); the store span is
// hoisted. Measures whether MSVC's LICM hoists even the single derivation.
#include "src/rtl/simse.hpp"
#include <cstdio>
#include <cstdint>
using Int = int32_t;
using Int64 = int64_t;
using Char = int8_t;
template <class T> struct Span { T* ptr; Int len; };
inline Span<Char> spanOfStr(Str* text) {
    return Span<Char>{ reinterpret_cast<Char*>(text->data()), (Int)text->size() };
}
void blur(Str* src, Str* dst, Int w) {
    Span<Char> dspan = spanOfStr(dst);
    Char* dp = dspan.ptr;
    Int y = 1;
    while (y < w - 1) {
        Int x = 1;
        while (x < w - 1) {
            Int i = y * w + x;
            Span<Char> sspan = spanOfStr(src);
            Char* sp = sspan.ptr;
            Int s = (Int(sp[i - w - 1]) & 255) + (Int(sp[i - w]) & 255) * 2 + (Int(sp[i - w + 1]) & 255)
                + (Int(sp[i - 1]) & 255) * 2 + (Int(sp[i]) & 255) * 4 + (Int(sp[i + 1]) & 255) * 2
                + (Int(sp[i + w - 1]) & 255) + (Int(sp[i + w]) & 255) * 2 + (Int(sp[i + w + 1]) & 255);
            dp[i] = Char((s >> 4) & 255);
            x++;
        }
        y++;
    }
}
static uint64_t xs(uint64_t x) {
    x ^= x << 13; x ^= x >> 7; x ^= x << 17; return x;
}
int main() {
    const Int W = 2048;
    Str a; Str b;
    uint64_t r = 12345;
    for (Int i = 0; i < W * W; i++) {
        r = xs(r);
        a.append(1, (char)(r % 256));
        b.append(1, (char)0);
    }
    for (Int p = 0; p < 8; p++) { blur(&a, &b, W); blur(&b, &a, W); }
    Int64 total = 0;
    for (Int k = 0; k < (Int)a.size(); k++) total += (Int64)((uint8_t)a.data()[k]);
    printf("%lld\n", (long long)total);
    return 0;
}
