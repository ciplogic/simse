#pragma once

#include <cstdint>
#include <cstring>
#include <functional>
#include <iterator>
#include <type_traits>
#include <utility>

#include "types.hpp"

// SmDictionary<TKey, TValue> is the RTL's own value dictionary
// (specs/dictionary.md), the backing `Dictionary<K, V>` selects when
// SIMSE_DICT_SM is defined. containers.hpp owns that choice and pulls this header
// in (after SmallVector/List/Str exist), so it is not meant to be included on its
// own.
//
// The layout follows a .NET dictionary: one row per entry, chained by index, with
// a power-of-two bucket table holding each chain's head.
//
//     _rows     List<Entry>   entry i: (hash, next, first = key, second = value)
//     _buckets  List<Int>     length is a power of two; bucket b holds the newest
//                             row in b, -1 = empty
//
// The row carries its own chain link (`next`) and cached `hash` *next to* its key
// and value, so a probe's first cache line already holds everything it needs to
// reject or accept a candidate - one line per probe. (Splitting the links into a
// parallel list instead measures ~1.7x slower on hit-heavy lookups: the chain walk
// then needs a second dependent load to reach the key.)
//
// A row is live while `hash >= 0`: hashes are folded to 31 bits, so -1 is free as
// the tombstone of a removed row. `_count` counts live rows, so `_count !=
// _rows.size()` means "there are holes".
//
// Rows are append-only: an insert adds at the end, and a removed row stays a hole
// until the next packing. Holes are transient by design - removal is cheap and
// iteration, or a growth, is what pays for it. Taking an iterator
// (`begin`/`end`/`find`, i.e. everything but the plain `has`/`count`/`get`
// lookups) packs the rows first when `_count != _rows.size()`, and so does growing
// the bucket table, so the iterator is a plain pointer walk over `_rows` with no
// hole skipping. The first such call after a removal costs one pass over the rows;
// every later one is a single comparison.
//
// `_mask` is `_buckets.size() - 1`, kept as a field so a probe's bucket is one
// AND: `hash & _mask`. It is `kDictNoTable` (-1) until the first insert, when the
// table opens with 16 buckets.
//
// Invariants:
//   - `_mask` is -1 until the first insert (no bucket table at all), then
//     `_buckets.size() - 1` for a power-of-two table size;
//   - live row i is reachable from `_buckets[hash(i) & _mask]` by following the
//     `next` fields, ending in -1;
//   - a row is always in the bucket its *cached* hash selects, so growing the
//     table only re-masks cached hashes and never re-hashes a key;
//   - probing compares the cached hash first and `TKey::operator==` only on a
//     match, so unequal keys that collide are distinguished correctly.

// Hashes are folded to 31 bits, so a stored hash is never -1 - the tombstone a
// removed row carries. The fold is a shift-xor rather than an avalanche: the
// bucket table keeps the *low* bits, so folding the high half down is enough, and
// it costs one xor + one shift per lookup.

// `Str` keys: 8 bytes at a time (one unaligned word load), then the tail, each
// round an xor and a multiply. The length is folded in first so a prefix of a
// longer key does not hash alike, and the tail is zero-padded into a word. No read
// goes past `size`.
inline Int simse_dict_hashKey(const Str& key) {
    const char* data = key.data();
    const Int size = (Int) key.size();
    std::uint64_t hash = (std::uint64_t) (std::uint32_t) size;
    Int i = 0;
    while (i + 8 <= size) {
        std::uint64_t word = 0;
        std::memcpy(&word, data + i, sizeof(word));
        hash = (hash ^ word) * 1099511628211ull;
        i += 8;
    }
    if (i < size) {
        std::uint64_t word = 0;
        std::memcpy(&word, data + i, (std::size_t) (size - i));
        hash = (hash ^ word) * 1099511628211ull;
    }
    return (Int) ((hash ^ (hash >> 32)) & 0x7fffffffu);
}

// Scalar keys are returned as they are (a shift-xor for the wide ones): a `Str` is
// what a dictionary is keyed by, and wrapping an integer in a multiply chain just
// to land in a different bucket is not worth the cycles.
inline Int simse_dict_hashKey(Bool value) { return value ? 1 : 0; }
inline Int simse_dict_hashKey(Int8 value) { return (Int) (std::uint8_t) value; }
inline Int simse_dict_hashKey(Int16 value) { return (Int) (std::uint16_t) value; }
inline Int simse_dict_hashKey(Int value) { return (value ^ (value >> 16)) & 0x7fffffff; }

inline Int simse_dict_hashKey(Int64 value) {
    return (Int) ((value ^ (value >> 32)) & 0x7fffffffull);
}

// Any other key type keeps the std backing's contract: a key type that worked
// with `std::unordered_map` (i.e. has `std::hash` and `==`) works here. Enums land
// here, and MSVC's "hash" for them is the value itself.
template <class TKey>
Int simse_dict_hashKey(const TKey& key) {
    const std::uint64_t hash = (std::uint64_t) std::hash<TKey>{}(key);
    return (Int) ((hash ^ (hash >> 32)) & 0x7fffffffu);
}

// The first bucket-table size and the mask its hashes are ANDed with (the size is
// a power of two, so its mask is size - 1 and the bucket index needs no modulo):
// 16 entries, growing by 4x so re-bucketing happens log4(n) times instead of
// log2(n). `kDictNoTable` is the `_mask` of a dictionary that has no table yet.
inline constexpr Int kDictInitialBuckets = 16;
inline constexpr Int kDictInitialMask = kDictInitialBuckets - 1;
inline constexpr Int kDictNoTable = -1;

// One dictionary row (.NET's `Entry`): the chain link and the cached hash live
// *with* the key and value, and the key is spelled `first` / the value `second` so
// an entry is also the `(key, value)` pair the dictionary surface hands out
// (`it->second`, `for (entry: dict) entry.first`). 4-byte packed like every other
// RTL value type (specs/memory-model.md).
SIMSE_PACK_PUSH
template <class TKey, class TValue>
struct SmDictRow {
    Int hash;      // cached key hash; < 0 marks a removed row
    Int next;      // next row in this bucket's chain (-1 = chain end). The bucket
                   // holds the *newest* row of the bucket, so `next` walks towards
                   // older rows - the same link .NET's `Entry.next` is.
    TKey first;    // the key
    TValue second; // the value
};
SIMSE_PACK_POP

template <class TKey, class TValue>
class SmDictionary {
public:
    using Key = TKey;
    using Value = TValue;
    using Entry = SmDictRow<TKey, TValue>;
    using Row = Int;

    SmDictionary() = default;
    SmDictionary(const SmDictionary&) = default;
    SmDictionary(SmDictionary&&) noexcept = default;
    SmDictionary& operator=(const SmDictionary&) = default;
    SmDictionary& operator=(SmDictionary&&) noexcept = default;
    ~SmDictionary() = default;

    // ---- queries -----------------------------------------------------------

    Int size() const { return _count; }
    Bool empty() const { return _count == 0; }

    // The row holding `key`, or -1 when absent.
    Row findRow(const TKey& key) const {
        if (_count == 0) return -1; // no table at all yet: `_mask` is -1
        return findRowFast(key, simse_dict_hashKey(key));
    }

    // The hot path: walk one key's bucket chain for `key`, with the hash already
    // computed. One row load per candidate, its cached hash compared before the key.
    Row findRowFast(const TKey& key, Int keyHash) const {
        Int candidateIndex = _buckets[keyHash & _mask];
        while (candidateIndex != -1) {
            const Entry& rowData = _rows[candidateIndex];
            if (rowData.hash == keyHash && rowData.first == key) return candidateIndex;
            candidateIndex = rowData.next;
        }
        return -1;
    }

    // `has`: membership, without a copy of the value (`get` returns `Opt<V>` and
    // so copies it out). It never packs holes away - only the iterator-producing
    // calls do.
    Bool has(const TKey& key) const { return findRow(key) >= 0; }

    // The std surface: 0 or 1.
    Int count(const TKey& key) const { return findRow(key) >= 0 ? 1 : 0; }

    // ---- iteration ---------------------------------------------------------

    // Rows in slot order (insertion order until a removal reuses a hole, then the
    // reused row appears where the hole was). Packing first makes this a pointer
    // into `_rows`: no holes to skip, and an iterator is two words.
    class const_iterator {
    public:
        using iterator_category = std::forward_iterator_tag;
        using value_type = Entry;
        using difference_type = Int;
        using pointer = const Entry*;
        using reference = const Entry&;

        const_iterator() = default;
        explicit const_iterator(const Entry* row) : _row(row) {}

        const Entry& operator*() const { return *_row; }
        const Entry* operator->() const { return _row; }

        const_iterator& operator++() {
            ++_row;
            return *this;
        }
        const_iterator operator++(int) {
            const_iterator copy = *this;
            ++_row;
            return copy;
        }

        friend Bool operator==(const const_iterator& left, const const_iterator& right) {
            return left._row == right._row;
        }
        friend Bool operator!=(const const_iterator& left, const const_iterator& right) {
            return !(left == right);
        }

    private:
        const Entry* _row = nullptr;
    };
    using iterator = const_iterator;

    const_iterator begin() const {
        packHoles();
        return const_iterator(_rows.data());
    }
    const_iterator end() const {
        packHoles();
        return const_iterator(_rows.data() + _rows.size());
    }
    const_iterator cbegin() const { return begin(); }
    const_iterator cend() const { return end(); }

    // `find` hands out an iterator too, so it packs first (row indexes shift when
    // holes are packed away, which is why the lookup happens after the pack).
    const_iterator find(const TKey& key) const {
        packHoles();
        const Row row = findRow(key);
        return row < 0 ? const_iterator(_rows.data() + _rows.size()) : const_iterator(&_rows[row]);
    }

    // ---- modifiers ---------------------------------------------------------

    // `insert_or_assign`: assigns `value` to `key`'s row, inserting one when
    // absent. The value-taking path, so an existing value is *replaced*.
    void insert_or_assign(const TKey& key, const TValue& value) { assignOrInsert(key, value); }

    // `operator[]`: the value slot of `key`, inserting a default-constructed value
    // when absent - the std contract, including that it never touches an existing
    // value (`sm[k]` on a present key is a read).
    TValue& operator[](const TKey& key) {
        const Int hash = simse_dict_hashKey(key);
        if (_count != 0) {
            const Row row = findRowFast(key, hash);
            if (row >= 0) return _rows[row].second;
        }
        return _rows[appendRow(key, hash, TValue())].second;
    }

    // `erase`: 1 when a row was removed, 0 when the key was absent. The row is
    // unlinked from its bucket chain and pushed on the free list, so every other
    // row keeps its index (and the bucket table and links stay valid); `compact()`
    // reclaims the holes.
    Int erase(const TKey& key) {
        if (_count == 0) return 0;
        const Int hash = simse_dict_hashKey(key);
        const Int bucket = hash & _mask;
        Row previous = -1;
        Row candidateIndex = _buckets[bucket];
        while (candidateIndex != -1) {
            Entry& rowData = _rows[candidateIndex];
            if (rowData.hash == hash && rowData.first == key) {
                if (previous < 0) _buckets[bucket] = rowData.next;
                else _rows[previous].next = rowData.next;
                // Tombstone the row: `hash < 0` marks it dead, and it stays a hole
                // until `compact()` packs the rows (`next` is not read once dead).
                rowData.hash = -1;
                rowData.next = -1;
                _count--;
                releaseRow(candidateIndex);
                return 1;
            }
            previous = candidateIndex;
            candidateIndex = rowData.next;
        }
        return 0;
    }

    // Remove every row and the bucket table: the full cleanup. Keys and values
    // are released; the next insert opens a fresh 16-bucket table. The row list
    // keeps its buffer (SmallVector), so a clear-and-refill loop reuses it.
    void clear() {
        _rows.clear();
        _buckets.clear();
        _count = 0;
        _mask = kDictNoTable;
    }

    // Pack the rows without the holes `erase` left: one pass (the live row count is
    // `_count`), row order preserved, then the bucket table is relinked. Called by
    // the iterator-producing calls, so an explicit call is never required; it is a
    // no-op once there is nothing to pack, and it is a *const* operation because it
    // changes the representation rather than the dictionary's value.
    void compact() const {
        if (_count == _rows.size()) return;
        packRows();
        if (_mask != kDictNoTable) {
            _buckets.assign(_buckets.size(), -1);
            relinkAll();
        }
    }

private:
    void packHoles() const { compact(); }
    // A dictionary is empty (and allocation-free) until the first insert.
    void openBuckets() {
        _buckets.assign(kDictInitialBuckets, -1);
        _mask = kDictInitialMask;
    }

    // The value path of an insert-or-replace. The empty bucket is the fast case: no
    // row hashes there, so the key cannot be present and the chain walk (and the key
    // compare) is skipped altogether.
    template <class TValueArg>
    void assignOrInsert(const TKey& key, TValueArg&& value) {
        const Int hash = simse_dict_hashKey(key);
        if (_count != 0) {
            const Row row = findRowFast(key, hash);
            if (row >= 0) {
                _rows[row].second = std::forward<TValueArg>(value);
                return;
            }
        }
        (void) appendRow(key, hash, std::forward<TValueArg>(value));
    }

    // Append the row for `key` at the end and make it its bucket's head; the caller
    // knows the key is absent. This is where the table opens and grows.
    template <class TValueArg>
    Row appendRow(const TKey& key, Int hash, TValueArg&& value) {
        if (_mask == kDictNoTable) openBuckets();
        const Int bucket = hash & _mask;
        _rows.push_back(Entry{hash, _buckets[bucket], key, std::forward<TValueArg>(value)});
        _buckets[bucket] = _rows.size() - 1;
        _count++;
        if (_count > _mask) growBuckets();
        // Growing may pack the holes away (see `growBuckets`), and packing preserves
        // row order, so the row just appended is still the last one.
        return _rows.size() - 1;
    }

    // Move the live rows together, preserving their order. The bucket table is left
    // for the caller to relink - both `compact()` and `growBuckets()` do.
    void packRows() const {
        List<Entry> packed;
        packed.reserve(_count);
        for (Row row = 0; row < _rows.size(); row++) {
            if (_rows[row].hash < 0) continue;
            packed.push_back(std::move(_rows[row]));
        }
        _rows = std::move(packed);
    }

    // The bucket table is full at load factor 1, so a table of 4x the entries keeps
    // chains short; the row list grows on its own (and is *not* grown to the bucket
    // count, which would waste up to 4x the entry memory). Growing already walks every
    // row to rebuild the chains, so this is also where a dictionary that has seen
    // removals is packed dense again: the pass is nearly free here, and it leaves the
    // rows smaller and the later iterator calls with nothing to pack.
    void growBuckets() {
        if (_count != _rows.size()) packRows();
        _mask = (_mask + 1) * 4 - 1;
        _buckets.assign(_mask + 1, -1);
        relinkAll();
    }

    // Rebuild every bucket chain, ascending by row: each row is prepended as it is
    // visited, which restores exactly the newest-first order the chains had before
    // (the same order .NET's Resize produces).
    void relinkAll() const {
        const Int mask = _mask;
        for (Row row = 0; row < _rows.size(); row++) {
            Entry& rowData = _rows[row];
            if (rowData.hash < 0) continue;
            const Int bucket = rowData.hash & mask;
            rowData.next = _buckets[bucket];
            _buckets[bucket] = row;
        }
    }

    // Release a removed row's payload, so a Str key or a value holding a tree does
    // not stay alive until the row is packed away.
    void releaseRow(Row row) {
        if constexpr (std::is_default_constructible_v<TKey>) _rows[row].first = TKey();
        if constexpr (std::is_default_constructible_v<TValue>) _rows[row].second = TValue();
    }

    // `compact()` is a representation change reached from const iterator calls, so
    // the members it touches are mutable: the dictionary's *value* (the live rows
    // and their order) is unchanged.
    mutable List<Entry> _rows;
    mutable List<Int> _buckets;
    Row _count = 0;
    Int _mask = kDictNoTable;
};
