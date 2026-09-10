#pragma once

#include <optional>
#include <utility>

// Opt<T> models an optional value (specs/core-types.md), like std::optional.
// It either holds a T or is empty. Absence is explicit: code must test before
// extracting the payload; there is no null dereference.
template <class T>
struct Opt {
    std::optional<T> _value{};

    Opt() = default;
    static Opt<T> some(T value) {
        Opt<T> result;
        result._value = std::move(value);
        return result;
    }
    static Opt<T> none() { return Opt{}; }

    bool hasValue() const { return _value.has_value(); }
    explicit operator bool() const { return hasValue(); }

    T& value() { return *_value; }
    const T& value() const { return *_value; }
};