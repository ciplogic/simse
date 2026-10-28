#include "src/rtl/simse.hpp"
#include <cstdio>
#include <cstdint>
using Int = int32_t;
using Int64 = int64_t;
using Char = int8_t;
void blur(Str* src, Str* dst, Int w) {
    const uint8_t* sp = (const uint8_t*)src->data();
    uint8_t* dp = (uint8_t*)dst->data();
    Int y = 1;
    while (y < w - 1) {
        Int x = 1;
        while (x < w - 1) {
            Int i = y * w + x;
            Int s = sp[i - w - 1] + sp[i - w] * 2 + sp[i - w + 1]
                + sp[i - 1] * 2 + sp[i] * 4 + sp[i + 1] * 2
                + sp[i + w - 1] + sp[i + w] * 2 + sp[i + w + 1];
            dp[i] = (uint8_t)((s >> 4) & 255);
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
