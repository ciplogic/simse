#pragma once

// Ref<T> - the counted reference (`&T`), and the two factories every box is made through.
//
// The language has exactly one counted-reference type, and a program spells it `&T`
// (specs/memory-model.md): there is no source-level `Ref<T>`. This header holds its *C++
// spelling* - the type the emitter writes for `&T` - together with the two definitions that
// name can have:
//
//   SmRef<T>            the RTL's own implementation (smref.hpp): one pointer, one word of
//                       count four bytes before the value, no atomics, no deleter
//   std::shared_ptr<T>  the bootstrap shim the tree was ported against: two pointers, a
//                       control block, a type-erased deleter, thread-safe counting
//
// `SIMSE_SMREF` selects the first; without it the shim stays, so a plain build is unchanged
// and the two can be compared by building both ways (`bun build.js --define SIMSE_SMREF`).
// The switch is C++-level only: the emitter writes `Ref` either way, so the emitted
// amalgamation is the same under both - which is what makes the comparison meaningful (the
// same program, two implementations of the one type it reached through).

#include <cstddef>
#include <memory>
#include <new>
#include <utility>

#include "smref.hpp"
#include "types.hpp"

template <class T>
using Ref = SmRef<T>;

// The box construction the emitter spells for the language's `&value`: one box holding a `T`
// built in place from the arguments. `std::make_shared` allocates the control block and the
// object together for the shim; `SmRef::make` is the `[count][value]` allocation itself.
template <class T, class... A>
Ref<T> makeRef(A&&... args) {
    return SmRef<T>::make(std::forward<A>(args)...);
}

// The same for a box whose payload is not `sizeof(T)`: `Array<T>`'s block, whose element count
// and elements occupy the rest of the one allocation (containers.hpp). The box's own
// destructor is the cleanup in both cases, so the shim's deleter is nothing but
// `destroy_at` + the matching free. `payloadBytes` is the payload's size; the header the
// box puts before it is `SmRef`'s business, not the caller's.
template <class T, class... A>
Ref<T> makeRefSized(std::size_t payloadBytes, A&&... args) {
    return SmRef<T>::makeSized(payloadBytes, std::forward<A>(args)...);
}
