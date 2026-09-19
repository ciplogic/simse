#pragma once

// SmRef<T> - the counted reference the language spells `&T` (specs/memory-model.md), with
// *local*, single-threaded reference counting.
//
// It is `std::shared_ptr<T>` without the parts the language does not need, and with the
// count where the language's layout puts it. One handle is one pointer - `sizeof(SmRef<T>)`
// is a pointer, where a `std::shared_ptr` is two (no control-block pointer, no weak count,
// no type-erased deleter) - and the box is ONE allocation: the header the language fixes
// (specs/ref-counted-layout.md) followed by the value.
//
//     +----------------------+  <- the allocation's base, and what `release` frees
//     | Int _count           |  the header: one word
//     +----------------------+  <- 4 bytes earlier than the value
//     | T value              |  <- `_ptr`: the pointer the handle holds
//     +----------------------+
//
// **The count is always the four bytes before the value**, and the handle points straight
// at the value: nothing on the way in, nothing on the way out. The language packs to 4
// bytes (`specs/memory-model.md`, `SIMSE_PACK_PUSH`), so the header is one word and the
// value starts right after it - an 8-byte value simply spans two words at 4-byte alignment
// - which is the same rule `ArrayBlock::itemsOffset` follows. `SIMSE_NO_PACK4`, the escape
// hatch that honors the host's alignment instead, widens the header the same way the array
// block widens its element offset.
//
// Two things `specs/ref-counted-layout.md` lists are deliberately not materialized, exactly
// as they are not in the array block's shim (impl_specs/rtl-abi.md): the `typeId` (the spec
// stores it but uses it for nothing the language can do) and any form of polymorphism. The
// box holds the value and the count, and nothing else.
//
// The count is a plain `Int` - no atomics, no fences: a handle and its box belong to one
// thread, which is what "local" means here. Copying a handle increments, dropping the last
// one destroys the value in place and frees the allocation, and a null handle owns no count.
// Nothing else is stored, so nothing else has to be copied, dropped, or freed: the cleanup a
// `shared_ptr` would keep in a deleter is the *value's own destructor* here, which is why
// `Array<T>`'s block gained one (containers.hpp).

#include <cstddef>
#include <memory>
#include <new>
#include <type_traits>
#include <utility>

#include "types.hpp" // Int, Bool

template <class T>
class SmRef {
public:
    // A null handle: no box, no count.
    SmRef() noexcept : _ptr(nullptr) {}

    SmRef(std::nullptr_t) noexcept : _ptr(nullptr) {}

    // Adopts a box that already owns one count - what `make`/`makeSized` hand out, and what
    // a raw `T*` into a box is (a pointer *at* the value, with the count before it). A `T*`
    // that is not a box must not be adopted.
    explicit SmRef(T* adopted) noexcept : _ptr(adopted) {}

    SmRef(const SmRef& other) noexcept : _ptr(other._ptr) {
        this->retain();
    }

    SmRef(SmRef&& other) noexcept : _ptr(other._ptr) {
        other._ptr = nullptr;
    }

    ~SmRef() {
        this->release();
    }

    SmRef& operator=(const SmRef& other) noexcept {
        if (this != &other) {
            this->release();
            _ptr = other._ptr;
            this->retain();
        }
        return *this;
    }

    SmRef& operator=(SmRef&& other) noexcept {
        if (this != &other) {
            this->release();
            _ptr = other._ptr;
            other._ptr = nullptr;
        }
        return *this;
    }

    // The handle's own operations, in the spelling the generated code already uses for
    // `&T` (`get`, and `nullptr` for the language's `null`).
    T* get() const noexcept { return _ptr; }

    T& operator*() const { return *_ptr; }

    T* operator->() const noexcept { return _ptr; }

    Bool isNull() const noexcept { return _ptr == nullptr; }

    explicit operator bool() const noexcept { return _ptr != nullptr; }

    // How many handles own the box, `0` for a null handle. `std::shared_ptr` calls this
    // `use_count`; nothing the emitter writes spells it - it is here for the RTL and for a
    // C++-level check of the counting, since a Simse program cannot observe it.
    Int useCount() const noexcept { return _ptr == nullptr ? 0 : *this->countSlot(); }

    // The one allocation for a box of a `T`: `[count][T]`, the count one, `T` constructed in
    // place from the arguments - so a box is never "allocated and then assigned into".
    template <class... A>
    static SmRef<T> make(A&&... args) {
        void* base = ::operator new(headerBytes() + sizeof(T));
        T* value = std::construct_at(valueAt(base), std::forward<A>(args)...);
        *countAt(base) = 1;
        return SmRef<T>(value);
    }

    // The same for a box whose size is not `sizeof(T)`: `Array<T>`'s block carries its
    // element count *and* its elements in the one allocation (specs/built-in-types.md), so
    // the caller owns the size. `T` is still constructed in place here.
    template <class... A>
    static SmRef<T> makeSized(std::size_t boxBytes, A&&... args) {
        void* base = ::operator new(boxBytes);
        T* value = std::construct_at(valueAt(base), std::forward<A>(args)...);
        *countAt(base) = 1;
        return SmRef<T>(value);
    }

private:
    // The header is one `Int` under the language's packing rule; `SIMSE_NO_PACK4` honors the
    // host's alignment instead, so a payload the host aligns wider than 4 bytes is not
    // under-aligned in its box (the same branch `ArrayBlock::itemsOffset` takes).
    static constexpr std::size_t headerBytes() {
#if defined(SIMSE_NO_PACK4)
        constexpr std::size_t alignment = alignof(T) < sizeof(Int) ? sizeof(Int) : alignof(T);
        return alignment;
#else
        return sizeof(Int);
#endif
    }

    // The count, at the allocation's base - and therefore four bytes before the value, which
    // is the whole of the layout promise.
    static Int* countAt(void* base) { return reinterpret_cast<Int*>(base); }

    static T* valueAt(void* base) {
        return reinterpret_cast<T*>(static_cast<char*>(base) + headerBytes());
    }

    // The count, from the handle alone: `_ptr` read as an `Int*`, one `Int` back - the value
    // sits exactly `sizeof(Int)` bytes after its count, so this is the whole of the layout
    // promise, in one line. It holds whatever the *header's* size is: only the base the
    // allocation is freed through depends on that.
    Int* countSlot() const { return reinterpret_cast<Int*>(_ptr) - 1; }

    // The allocation's base, for `operator delete`: the count, less the rest of the header
    // the value was placed after (`headerBytes()`, one word under the packing rule - so
    // under the packing rule the base *is* the count).
    void* boxBase() const {
        return reinterpret_cast<char*>(this->countSlot()) - (headerBytes() - sizeof(Int));
    }

    void retain() noexcept {
        if (_ptr != nullptr) {
            Int* count = this->countSlot();
            (*count)++;
        }
    }

    void release() {
        if (_ptr == nullptr) {
            return;
        }
        Int* count = this->countSlot();
        (*count)--;
        if (*count == 0) {
            std::destroy_at(_ptr);
            ::operator delete(this->boxBase());
        }
    }

    T* _ptr;
};

// The class template's members are only checked where it is instantiated, so one
// instantiation keeps the definition honest in the builds where `Ref` is still the
// `std::shared_ptr` shim (`ref.hpp`) - a "potential definition" that is never compiled is a
// definition that rots.
template class SmRef<Int>;
