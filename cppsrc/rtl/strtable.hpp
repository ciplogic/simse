#pragma once

#include "strview.hpp"

// The string-literal pool a generated program carries (impl_specs/rtl-abi.md,
// "String literals: one table"): every literal the program mentions is written into
// one pool of bytes, and beside it the emitter writes two run-length encoded index
// series - where each entry starts and how long it is. This expands the two series and
// builds the `StrView` per entry out of the three, once, before `main` runs.
//
// The shape the emitter writes is:
//
//     static const Int __sm_stringCount = 537;
//     static const char __sm_stringPool[] =
//         " " "abc" ...;                     // every literal, adjacent: the standard
//                                            // concatenates them
//     static const Int16 __sm_stringStarts[] = {537,80,-142,40,5,...};
//     static const Int16 __sm_stringLens[] = {537,80,-142,40,5,...};
//     static_assert(sizeof(__sm_stringPool) - 1 == <total>,
//                   "the string pool and its length index disagree");
//     static StrView __sm_stringTable[__sm_stringCount];
//     static struct __SmStringTableInitType {
//         __SmStringTableInitType() {
//             Int starts[__sm_stringCount];          // expanded, then dropped: two
//             Int lens[__sm_stringCount];            // stack arrays, no allocation
//             simse_strTableExpand(__sm_stringStarts, starts, __sm_stringCount);
//             simse_strTableExpand(__sm_stringLens, lens, __sm_stringCount);
//             simse_strTableDecode(__sm_stringPool, starts, lens, __sm_stringTable,
//                                  __sm_stringCount);
//         }
//     } __sm_stringTableInit;
//
// Six things are deliberate:
//
//  - **Both series are stored as "what to subtract from the previous value"**, with an
//    implicit 0 before the first entry: `value[i] = value[i-1] - series[i]`. The literals
//    are ordered longest first, so a length series descends slowly and a *difference* of
//    it is a small number - mostly 0 between the many literals of equal length (85% of
//    them on the compiler's own table). The rebuild accumulates in `Int`, so an element
//    is only ever a *difference*, never an offset, and the pool's size is the one large
//    number in the structure.
//  - **Each series is run-length encoded**: its length first, then alternating blocks of
//    *non-repeating* values (a count, then that many values) and of *runs* (a count, then
//    that many `times, value` pairs), until the length is filled - which is what the
//    mostly-zero differences collapse to. The element type is a template parameter
//    because the emitter picks the width: `Int16` when every number in the stream fits
//    (which is what a compact stream is about), `Int` otherwise.
//  - The pool is the literal texts themselves, adjacent, so the C++ compiler decodes
//    every escape in the emitted program's own build and the emitter's decoding only has
//    to agree about *how many bytes* an escape costs (`literalByteLength`). The
//    `static_assert` on the pool's own `sizeof` is the check: a disagreement about any
//    escape shifts the total and stops the build instead of shifting every literal after
//    the mistake.
//  - The series are expanded into *stack* arrays in the initializer and dropped when it
//    returns: no heap, and the encoded statics are all the program carries.
//  - An entry is a 12-byte `StrView`, not the 32-byte owning `Str` the table used to
//    hold, and start-up allocates nothing for the literals - the point of the change.
//  - The pool is `const char` (a string literal, so read-only), while `StrView` holds the
//    language's mutable `Char*`; the constness is cast away here, in the one place the
//    pool is touched, and nothing writes through it.
//
// The offset series is independent of the length series only while no two literals share
// text; once substring sharing lands (pointing a shorter literal into a longer one) the
// offset increment is no longer the previous length, and the two series stay separate.

// Expands one run-length encoded series into `out`, which holds `count` values.
template <class T>
inline void simse_strTableExpand(const T* stream, Int* out, Int count) {
    Int at = 0;
    Int cursor = 1; // stream[0] is the series' own length
    while (at < count) {
        const Int literals = (Int) stream[cursor++];
        for (Int i = 0; i < literals && at < count; i++) out[at++] = (Int) stream[cursor++];
        if (at >= count) break;
        const Int runs = (Int) stream[cursor++];
        for (Int i = 0; i < runs && at < count; i++) {
            const Int times = (Int) stream[cursor++];
            const Int value = (Int) stream[cursor++];
            for (Int j = 0; j < times && at < count; j++) out[at++] = value;
        }
    }
}

// Fills `table` from the pool and the two expanded series: an offset increment and a
// byte count per entry, both rebuilt by subtracting the stored value from the one before.
inline void simse_strTableDecode(const char* pool, const Int* starts, const Int* lengths, StrView* table, Int count) {
    Char* bytes = const_cast<Char*>(reinterpret_cast<const Char*>(pool));
    Int delta = 0;  // this entry's offset increment, rebuilt from the start series
    Int length = 0; // this entry's byte count, rebuilt from the length series
    Int at = 0;
    for (Int i = 0; i < count; i++) {
        delta -= starts[i];
        length -= lengths[i];
        at += delta;
        table[i] = StrView(bytes + at, length);
    }
}
