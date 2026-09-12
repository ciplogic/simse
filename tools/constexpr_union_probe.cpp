// Scratch: can a union-backed small buffer be built in a constant expression?
// Decides the SmallVector/SmString constexpr design. Not part of the build.
#include <memory>
#include <cstring>

template <class T, int N>
struct S {
    union Storage {
        T* heap;
        T inl[N];
        Storage() : heap(nullptr) {}
        constexpr explicit Storage(int) : inl() {}
    };
    int len = 0;
    int cap = N;
    Storage buf;

    constexpr S(int) : buf(0) {}

    void reserve(int) {}   // stub: inline-only probe

    template <class V>
    constexpr void push(const V& value) {
        T* slot = buf.inl + len;
        if (std::is_constant_evaluated()) {
            *slot = value;            // elements are live: value-initialized
        } else {
            std::construct_at(slot, value);
        }
        len++;
    }

    constexpr int size() const { return len; }
    constexpr const T* data() const { return buf.inl; }
};

constexpr int probe() {
    S<char, 8> s(0);
    s.push('a');
    s.push('b');
    return s.size() * 100 + s.data()[1];
}

static_assert(probe() == 298, "constexpr inline construction must work");

int main() { return probe() == 298 ? 0 : 1; }
