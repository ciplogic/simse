// Scratch: soak the Str inline/heap transitions and the "NUL at size()"
// invariant (not part of the build).
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>
#include <cstring>

int main() {
    int failures = 0;
    for (int i = 0; i < 20000; i++) {
        const int len = i % 200;
        Str t;
        for (int j = 0; j < len; j++) t.push_back((char) ('a' + (j % 26)));
        if ((int) t.size() != len || t.data()[len] != '\0') failures++;
        if (std::strlen(t.data()) != (std::size_t) t.size()) failures++;

        // A long string assigned a short one must return to the inline buffer.
        Str u = "0123456789abcdefghijklmnopqrstuvwxyz0123456789";
        u = t;
        if ((int) u.size() != len || std::strcmp(u.data(), t.data()) != 0) failures++;

        // Push/pop across the inline boundary.
        Str v = t;
        v.push_back('!');
        if ((int) v.size() != len + 1 || v.data()[len + 1] != '\0') failures++;
        v.pop_back();
        if ((int) v.size() != len || std::strcmp(v.data(), t.data()) != 0) failures++;

        // Erase/shift down keeps the terminator.
        Str w = t;
        w.erase(0, len / 2);
        if ((int) w.size() != len - len / 2 || w.data()[w.size()] != '\0') failures++;

        // Append a string, then reset to empty.
        Str x = t;
        x.append(t);
        if ((int) x.size() != len * 2 || x.data()[x.size()] != '\0') failures++;
        x.clear();
        if (!x.empty() || x.data()[0] != '\0') failures++;

        // Copy/assign/move round trip.
        Str y = t;
        Str z(std::move(y));
        if (std::strcmp(z.data(), t.data()) != 0) failures++;
        Str r = t.substr(len / 2);
        if ((int) r.size() != len - len / 2 || std::strcmp(r.data(), t.data() + len / 2) != 0) failures++;
    }
    std::printf("str stress failures: %d\n", failures);
    return failures == 0 ? 0 : 1;
}
