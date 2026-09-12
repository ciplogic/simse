// Scratch: exercise every Str comparison with a raw C string on both sides, for
// an inline string and a heap (longer than 23 bytes) one. Not part of the build.
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>

int main() {
    Str s = "abc";
    int checks = 0;
    checks += (s == "abc") ? 1 : 0;
    checks += ("abc" == s) ? 1 : 0;
    checks += (s != "abd") ? 1 : 0;
    checks += ("abd" != s) ? 1 : 0;
    checks += (s < "abd") ? 1 : 0;
    checks += ("abb" < s) ? 1 : 0;
    checks += (s > "abb") ? 1 : 0;
    checks += ("abd" > s) ? 1 : 0;
    checks += (s <= "abc") ? 1 : 0;
    checks += ("abc" <= s) ? 1 : 0;
    checks += (s >= "abc") ? 1 : 0;
    checks += ("abc" >= s) ? 1 : 0;

    // 36 bytes: on the heap for SmString, past the standard::string SSO too.
    Str big = "0123456789abcdefghijklmnopqrstuvwxyz";
    const char* same = "0123456789abcdefghijklmnopqrstuvwxyz";
    const char* longer = "0123456789abcdefghijklmnopqrstuvwxyz!";
    checks += (big == same) ? 1 : 0;
    checks += (big < longer) ? 1 : 0;
    checks += (big > longer) ? 0 : 1;
    checks += (longer > big) ? 1 : 0;
    checks += (big != longer) ? 1 : 0;

    std::printf("comparison checks passed: %d/17\n", checks);
    return checks == 17 ? 0 : 1;
}
