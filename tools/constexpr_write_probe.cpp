// Scratch: which inline-buffer write is legal in a C++20 constant expression?
//   A: plain assignment into an uninitialized union array member
//   B: std::construct_at on a value-initialized (live) union array member
//   C: plain assignment into a value-initialized member
// Not part of the build.
#include <memory>

template <class T, int N>
struct Vec {
    union Storage {
        T* heap;
        T inl[N];
        Storage() : heap(nullptr) {}
        constexpr explicit Storage(int) : inl() {}          // tag: array is live
        constexpr explicit Storage(int, int) {}             // tag: array NOT initialized
    };
    int len = 0;
    Storage buf;
    constexpr Vec(int) : buf(1) {}
    constexpr Vec(int, int) : buf(1, 1) {}
    constexpr T* raw() { return buf.inl; }
};

// C: assign into value-initialized elements.
constexpr int probeC() {
    Vec<char, 8> v(1);
    v.raw()[0] = 'a';
    v.raw()[1] = 'b';
    return v.raw()[0] + v.raw()[1];
}
static_assert(probeC() == 'a' + 'b');

// B: construct_at on live elements.
constexpr int probeB() {
    Vec<char, 8> v(1);
    std::construct_at(v.raw() + 0, 'x');
    return v.raw()[0];
}
static_assert(probeB() == 'x');

// A: assign into elements that were never initialized (heap member left active).
constexpr int probeA() {
    Vec<char, 8> v(1, 1);
    v.raw()[0] = 'q';
    return v.raw()[0];
}
static_assert(probeA() == 'q');

int main() { return 0; }
