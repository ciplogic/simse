// Scratch probe: does 4-byte packing of aggregates that embed host-library types
// (std::string / shared_ptr / std::function) misbehave on this target? Places the
// objects so the 8-byte-aligned members land at 4-mod-8 addresses.
#include <cstdio>
#include <functional>
#include <memory>
#include <string>

#pragma pack(push, 4)
struct P1 {
    int i;
    std::string s;
};
struct P2 {
    int i;
    std::shared_ptr<int> p;
};
struct P3 {
    int i;
    std::function<int(int)> f;
};
#pragma pack(pop)

static_assert(alignof(P1) == 4 && alignof(P2) == 4 && alignof(P3) == 4, "packed");

int main() {
    char *raw = new char[4096];
    // raw is 16-aligned; the packed structs therefore put their string/shared_ptr/
    // function members at offsets 4 mod 8, i.e. below their natural alignment.
    P1 *p1 = new (raw) P1{1, "a string long enough to spill out of the SSO buffer"};
    P2 *p2 = new (raw + 64) P2{2, std::make_shared<int>(7)};
    P3 *p3 = new (raw + 128) P3{3, [](int x) { return x * 3; }};
    P1 *array = new (raw + 256) P1[2];
    array[0] = P1{4, "first element with a long string that spills"};
    array[1] = P1{5, "second element with a long string that spills"};

    long long sum = 0;
    for (int i = 0; i < 3000000; i++) {
        std::string copy = p1->s;                 // copy at 4-mod-8
        copy += "tail";
        sum += (long long) copy.size();
        std::string moved = std::move(copy);      // move at 4-mod-8
        sum += (long long) moved.size();
        *p2->p = *p2->p + 1;                      // shared_ptr at 4-mod-8
        sum += *p2->p;
        sum += p3->f(2);                          // std::function at 4-mod-8
        sum += (long long) array[i % 2].s.size();
        std::string swapped = array[0].s;
        array[0].s = array[1].s;                  // assignment at 4-mod-8
        array[1].s = swapped;
    }
    std::printf("packed probe ok: sum=%lld first=%s second=%s\n", sum, array[0].s.c_str(),
                array[1].s.c_str());
    return 0;
}
