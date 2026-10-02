// Scratch: what the `for` protocol costs per element, so a protocol change is a
// measurement instead of an assumption (`impl_specs/for.md`). The three shapes are the
// ones on the table: the `next()` + `Opt<*T>` the emitter writes today, the `advance(*T)`
// out-parameter the machine already emits and nothing calls, and the proposed `current`
// field + `advance()` ("yieldable holds a pointer to what it yielded"). An index walk is
// the baseline the protocol is compared against.
//
//   bun build.js --release --cpp tools/_loop_protocol.cpp --exe tools/_loop_protocol.exe
//   ./tools/_loop_protocol.exe
//
// The element type is `AstNodeAttribute`-shaped (an enum and a `Str`, 36 bytes) because
// that is what the compiler's own hottest loop walks. The machines are hand-written to the
// shape the emitter generates, and the address of the element is not taken through a
// `simse_addressOf`-style helper here: the point is the protocol, not the addressing.
#include "src/rtl/simse.hpp"

#include <chrono>
#include <cstdio>

struct Attr {
    Int name{};
    Str value;
};

// The emitted shape: `next()` returns an `Opt<*T>` per element.
struct NextMachine {
    List<Attr>* self{};
    Int i{};
    Int len{};
    Int branch{};
    Opt<Attr*> next() {
        if (branch == -1) return Opt<Attr*>::none();
        if (branch == 1) {
            i++;
        } else {
            i = 0;
            len = self->size();
        }
        if (i >= len) {
            branch = -1;
            return Opt<Attr*>::none();
        }
        branch = 1;
        return Opt<Attr*>::some(&(*self)[i]);
    }
};

// The `advance` the machine already carries and no emitted loop calls.
struct AdvanceMachine {
    List<Attr>* self{};
    Int i{};
    Int len{};
    Int branch{};
    Bool advance(Attr** value) {
        if (branch == -1) return false;
        if (branch == 1) {
            i++;
        } else {
            i = 0;
            len = self->size();
        }
        if (i >= len) {
            branch = -1;
            return false;
        }
        branch = 1;
        *value = &(*self)[i];
        return true;
    }
};

// The proposal: the machine holds what it yielded, `advance()` only moves.
struct CurrentMachine {
    List<Attr>* self{};
    Attr* current{};
    Int i{};
    Int len{};
    Int branch{};
    Bool advance() {
        if (branch == -1) return false;
        if (branch == 1) {
            i++;
        } else {
            i = 0;
            len = self->size();
        }
        if (i >= len) {
            branch = -1;
            return false;
        }
        branch = 1;
        current = &(*self)[i];
        return true;
    }
};

using Clock = std::chrono::steady_clock;

int main() {
    const Int elements = 64;
    const Int rounds = 200000;
    List<Attr> items;
    for (Int i = 0; i < elements; i++) {
        items.push_back(Attr{i, "attribute"});
    }

    Int sink = 0;
    // The body is a *search* with a runtime key, the shape `xmlAttr` has: an early exit at
    // a data-dependent point, so the optimizer can neither vectorize the scan nor hoist it
    // out of the round loop (a plain sum of a field measured 0.3 ns an element because it
    // was both vectorized and hoisted, which prices nothing).
    const auto timeIt = [&](const char* name, auto body) {
        const auto begin = Clock::now();
        for (Int r = 0; r < rounds; r++) {
            items[r % elements].name = r;
            sink += body(r % (elements + 1));
        }
        const auto end = Clock::now();
        const double ns = (double) std::chrono::duration_cast<std::chrono::nanoseconds>(end - begin).count();
        const double perElement = ns / (double) (rounds * elements);
        std::printf("%-18s %7.2f ns/element   (%d rounds x %d elements)\n", name, perElement, (int) rounds, (int) elements);
    };

    timeIt("index while", [&](Int key) {
        List<Attr>* attrs = &items;
        Int count = attrs->size();
        Int i = 0;
        while (i < count) {
            if ((*attrs)[i].name == key) return i;
            i++;
        }
        return -1;
    });

    timeIt("next() + Opt", [&](Int key) {
        NextMachine m{};
        m.self = &items;
        Int i = 0;
        while (true) {
            Opt<Attr*> step = m.next();
            if (!step.hasValue()) break;
            Attr* attr = step.value();
            if (attr->name == key) return i;
            i++;
        }
        return -1;
    });

    timeIt("advance(*T)", [&](Int key) {
        AdvanceMachine m{};
        m.self = &items;
        Attr* attr = nullptr;
        Int i = 0;
        while (m.advance(&attr)) {
            if (attr->name == key) return i;
            i++;
        }
        return -1;
    });

    timeIt("current + advance", [&](Int key) {
        CurrentMachine m{};
        m.self = &items;
        Int i = 0;
        while (m.advance()) {
            if (m.current->name == key) return i;
            i++;
        }
        return -1;
    });

    std::printf("sink %d\n", (int) sink);
    return 0;
}
