#pragma once

#include <new>
#include <type_traits>
#include <utility>

#include "types.hpp"

// VoidEnum is the empty alternative a variant is given when the second arm means "no
// payload at all": `Opt<T>` is `Variant2<T, VoidEnum>` (optional.hpp) and `Res<T>` is
// `Variant2<T, Str>` (result.hpp), so one union serves both of the language's
// two-alternative types and the second type argument is what tells them apart - an
// optional holds nothing where a result holds a message.
enum class VoidEnum : Int { None = 0 };

// Variant2<A, B> is a tagged union of exactly two alternatives: a `Value` (an `A`) or an
// `Error` (a `B`), and the tag says which one is live. Storing a payload and an error
// message in one union rather than beside each other is the point: a `Res<T>` is
// typically a `T` *or* a message, so the failed case carries no T and the ok case
// allocates no message.
//
// `std::variant` was rejected as the implementation. Its `std::get` throws - a runtime
// with no exceptions has no use for that - and its valueless-by-exception state is a
// third state this type cannot enter. The accessors here are unchecked, like every other
// RTL operation: the caller tests `isOk()`/`hasValue()` first (specs/core-types.md).
//
// The two arm names and the factories are the *language's*, because the emitter writes
// them straight through: `Res<T>` is read as `.Value`/`.Error`, an `Opt<T>` as
// `.value()`/`.hasValue()`, and `Opt<T>.some(x)` / `Res<T>.err(m)` are static calls the
// checker types in `sema/TypeInfer.kt` and the emitter prints as `Opt<T>::some(x)` -
// which makes them members of the one storage type by construction.
//
// A default-constructed Variant2 holds the **second** alternative, the absent/failed one.
// That is what `null` in an `Opt<T>` context means (`Opt<Int>()` is an empty optional,
// `Codegen.nullTo`), and it is what makes a local the emitter declared but has not
// assigned yet read as "nothing" rather than as a made-up value.
//
// The payload is stored by value and copies follow the alternatives' own semantics: a
// `Str` deep-copies its text, a `&T` shares its object.
//
// **The storage comes in two forms and the choice is a performance one.** When both
// alternatives are trivially copyable - an `Opt<Int>`, an `Opt<StrView>`, an
// `Opt<NameKind>` - nothing needs managing, so that form declares no copy, move or
// destructor and the whole type stays trivially copyable: it is handed back in registers
// and a slot left in it costs nothing. When an alternative owns storage (`Str`, a node, a
// list handle) the union's live arm has to be constructed and destroyed by hand, which is
// what defeats triviality and is unavoidable. Being non-trivial by default cost the
// self-transpile ~3%: every `std::optional<Int>`-shaped slot in the compiler became a
// value with a destructor and a call to a copy.
//
// The arms are spelled out in each form rather than shared through a common base: the
// union's own destructor is deleted as soon as one arm has one, and a base that holds it
// would therefore carry a deleted destructor into *both* forms - which is exactly what
// the trivial one must not have.
template <class A, class B, Bool Managed>
struct Variant2Storage;

// Both arms trivially copyable. A setter starts the lifetime of the other arm by
// assigning to it, there is nothing to destroy, and `clear` is nothing.
template <class A, class B>
struct Variant2Storage<A, B, false> {
    // Which alternative is live: false is `Value`, true is `Error`. Every constructor
    // and every setter writes it, and `isOk`/`hasValue` read it, so the tag and the
    // union never disagree. Not part of the language's surface.
    Bool _second;

    union {
        // Success/`some`: the payload.
        A Value;
        // Failure/`none`: the message, or a `VoidEnum` when there is none.
        B Error;
    };

    // The constructor tags: an alternative is named in a constructor's initializer list,
    // which is also the only way a `Variant2<Str, Str>` can say which arm it is building.
    struct First {};
    struct Second {};

    // The absent/failed alternative: what a default-constructed variant is.
    Variant2Storage() : _second(true), Error() {}

    Variant2Storage(First, A value) : _second(false), Value(std::move(value)) {}
    Variant2Storage(Second, B value) : _second(true), Error(std::move(value)) {}

    void setFirst(A value) {
        _second = false;
        Value = std::move(value);
    }

    void setSecond(B value) {
        _second = true;
        Error = std::move(value);
    }

    void clear() {}
};

// An arm that owns storage: the union's live alternative is destroyed and the new one
// placed by hand, and the copy, move and destructor are this form's - the compiler
// generates nothing for a union of non-trivial members.
template <class A, class B>
struct Variant2Storage<A, B, true> {
    Bool _second;

    union {
        A Value;
        B Error;
    };

    struct First {};
    struct Second {};

    Variant2Storage() : _second(true), Error() {}

    Variant2Storage(First, A value) : _second(false), Value(std::move(value)) {}
    Variant2Storage(Second, B value) : _second(true), Error(std::move(value)) {}

    // Built as the empty variant first and then moved over: which alternative the copy
    // ends up with is only known at run time, and the empty arm is the cheapest thing to
    // build while the setter places the real one.
    Variant2Storage(const Variant2Storage& other) : Variant2Storage() {
        if (other._second) {
            Error = other.Error;
        } else {
            this->setFirst(other.Value);
        }
    }

    Variant2Storage(Variant2Storage&& other) noexcept : Variant2Storage() {
        if (other._second) {
            Error = std::move(other.Error);
        } else {
            this->setFirst(std::move(other.Value));
        }
    }

    ~Variant2Storage() { this->clear(); }

    Variant2Storage& operator=(const Variant2Storage& other) {
        if (this != &other) {
            if (other._second) {
                this->setSecond(other.Error);
            } else {
                this->setFirst(other.Value);
            }
        }
        return *this;
    }

    Variant2Storage& operator=(Variant2Storage&& other) noexcept {
        if (this != &other) {
            if (other._second) {
                this->setSecond(std::move(other.Error));
            } else {
                this->setFirst(std::move(other.Value));
            }
        }
        return *this;
    }

    // Make the first alternative (`Value`) live, assigning in place when it already is.
    void setFirst(A value) {
        if (!_second) {
            Value = std::move(value);
            return;
        }
        Error.~B();
        _second = false;
        ::new ((void*) &Value) A(std::move(value));
    }

    // Make the second alternative (`Error`) live.
    void setSecond(B value) {
        if (_second) {
            Error = std::move(value);
            return;
        }
        Value.~A();
        _second = true;
        ::new ((void*) &Error) B(std::move(value));
    }

    // Destroy what is live. Every path that leaves the union with no live alternative
    // places one before returning, so a variant is never observed without an arm.
    void clear() {
        if (_second) {
            Error.~B();
        } else {
            Value.~A();
        }
    }
};

// Whether the arms need managing: `false` when both are trivially copyable, so the
// storage can be copied and destroyed like an integer.
template <class A, class B>
inline constexpr Bool Variant2Managed =
    !(std::is_trivially_copyable_v<A> && std::is_trivially_copyable_v<B>);

// The language's surface over that storage. It declares nothing of its own, so the
// trivial form stays trivially copyable all the way up.
SIMSE_PACK_PUSH
template <class A, class B>
struct Variant2 : Variant2Storage<A, B, Variant2Managed<A, B>> {
    using Storage = Variant2Storage<A, B, Variant2Managed<A, B>>;
    // The default form and the two tagged ones (`First`/`Second` + a value).
    using Storage::Storage;

    using First = typename Storage::First;
    using Second = typename Storage::Second;

    // ---- inspection ----------------------------------------------------------

    // `Res<T>.isOk()`: the payload alternative is live. The error is not tested by being
    // empty any more - the tag says which arm this is (specs/core-types.md).
    Bool isOk() const { return !this->_second; }

    // `Opt<T>.hasValue()`: the payload alternative is live.
    Bool hasValue() const { return !this->_second; }

    // `Opt<T>.value()`: the payload. Only valid when `hasValue()`, like every unchecked
    // access in the RTL.
    A& value() { return this->Value; }
    const A& value() const { return this->Value; }

    // ---- the language's factories -------------------------------------------

    // `Opt<T>.some(x)`.
    static Variant2 some(A value) { return Variant2(First(), std::move(value)); }

    // `Opt<T>.none()`, which is the default-constructed state.
    static Variant2 none() { return Variant2(); }

    // `Res<T>.ok(x)`.
    static Variant2 ok(A value) { return Variant2(First(), std::move(value)); }

    // `Res<T>.err(message)`.
    static Variant2 err(B error) { return Variant2(Second(), std::move(error)); }
};
SIMSE_PACK_POP
