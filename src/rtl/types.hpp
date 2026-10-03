#pragma once

#include <cstdint>
#include <string>
#include <type_traits>

// Fixed-width scalar types (specs/built-in-types.md).
// `Int` is the language's default integer type, and every size, length and index
// the RTL exposes is one too: 32-bit, so nothing converts a `std::size_t` down into
// one (impl_specs/rtl-abi.md).
// Char is a signed 8-bit value, exactly equivalent to Int8.
using Int8    = std::int8_t;
using Int16   = std::int16_t;
using Int32   = std::int32_t;
using Int64   = std::int64_t;
using Float32 = float;
using Float64 = double;
using Char    = std::int8_t;
using Int     = Int32; // default integer type; alias of Int32
using Bool    = bool;  // two-valued built-in (specs/built-in-types.md)

// The language's pointer spellings (specs/memory-model.md, "Memory operators on types").
// `RawPtr` is the opaque `void*` a native handle crosses as; the language desugars it to the
// pointer type with no pointee (Parser.parseType), so its emitted C++ is exactly this alias -
// and a hand-written `_res.md` section, which is C++ and not Simse, can name it too.
// `PtrOf<T>` is `T*` written with an angle bracket, and the language resolves it before
// emission; the alias is here so the two spellings are the same name on both sides.
using RawPtr = void*;
template <class T>
using PtrOf = T*;

// Str is the mutable inline byte-string type. `smstring.hpp` defines SmString
// (a NUL-terminated SmallVector<kStrInlineCapacity, Char>, the capacity defined in
// strsmallvector.hpp) and names it `Str`; the `<string>` include above is for the
// native boundary (std::filesystem, std::getline) that `simse_toStdString` /
// `simse_fromStdString` convert at. This header deliberately does not define `Str`
// itself; containers.hpp pulls smstring.hpp in once SmallVector exists.

// `*value` in Simse is the raw-pointer (address-of) form (specs/memory-model.md).
// The lvalue overload covers ordinary expressions; the forwarding overload binds
// temporaries. A pointer to a temporary is valid until the end of the full
// expression, i.e. for the duration of the call it is passed to.
template <class T>
T* simse_addressOf(T& value) {
    return &value;
}

template <class T>
T* simse_addressOf(T&& value) {
    return &value;
}

// Destroy a value of any type: the real destructor for a class that has one, nothing for a
// scalar. The generated `union class` code destroys its live arm by tag through this, and
// `if constexpr` keeps the pseudo-destructor out of the trivial instantiation entirely.
template <class T>
void simse_destroy(T& value) {
    if constexpr (!std::is_trivially_destructible_v<T>) {
        value.~T();
    }
}

// Whether a generated `union class` needs the managed form: one arm that is not trivially
// copyable is enough. A *generic* union picks its storage with this, so `Opt<Int>` stays a
// trivially copyable aggregate while `Opt<Str>` gets the destructor and copy/move members.
template <class... Ts>
inline constexpr Bool SmUnionManaged = !(std::is_trivially_copyable_v<Ts> && ...);

// The language's layout model is 4-byte packing (specs/memory-model.md): every
// type is aligned to at most 4 bytes. Definitions that follow the rule are
// bracketed with SIMSE_PACK_PUSH / SIMSE_PACK_POP; the C++ emitter wraps every
// generated aggregate in them, and the RTL wraps its own value containers.
//
// This under-aligns host-library members (`Str` is std::string, the counted reference is a
// `std::shared_ptr` while the shim is in use, `Func` is std::function, ...) which the host
// declares with 8-byte alignment. That is deliberate for the bootstrap shim and recorded in
// impl_specs/rtl-abi.md; `SIMSE_NO_PACK4` falls back to the host's default
// alignment for builds that need it.
#if defined(SIMSE_NO_PACK4)
#define SIMSE_PACK_PUSH
#define SIMSE_PACK_POP
#elif defined(_MSC_VER)
#define SIMSE_PACK_PUSH __pragma(pack(push, 4))
#define SIMSE_PACK_POP __pragma(pack(pop))
#else
#define SIMSE_PACK_PUSH _Pragma("pack(push, 4)")
#define SIMSE_PACK_POP _Pragma("pack(pop)")
#endif