// Scratch probe: SmDictionary (cppsrc/rtl/smdictionary.hpp) against
// std::unordered_map - content and ordering-independent semantics first, then
// speed, in one binary so both see the same key material and the same window.
//
//   tools\_probe.bat smdict_stress /O2 /DNDEBUG
//
// The op sequences are deterministic (a xorshift PRNG with a fixed seed), cover
// the growth points (16 -> 64 -> 256 -> ...), the free-list reuse a removal
// leaves behind, `compact()`, `clear()` and the dictops iteration path, and
// compare the whole content against the std reference after every phase.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

// The probe benchmarks both backings in one binary, and `SmDictionary` is the
// opt-in one: containers.hpp only defines it when SIMSE_DICT_SM is set.
#define SIMSE_DICT_SM 1
#include "cppsrc/rtl/containers.hpp"

static int failures = 0;
static int detailBudget = 12;

#define CHECK(condition, ...)                        \
    do {                                             \
        if (!(condition)) {                          \
            failures++;                              \
            if (detailBudget > 0) {                  \
                detailBudget--;                      \
                std::printf("FAIL line %d: ", __LINE__); \
                std::printf(__VA_ARGS__);            \
                std::printf("\n");                   \
            }                                        \
        }                                            \
    } while (0)

static std::uint64_t rngState = 0x0123456789abcdefull;

static std::uint64_t rng() {
    std::uint64_t x = rngState;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    rngState = x;
    return x;
}

static Str keyOf(std::uint64_t n) {
    return simse_fromStdString("key-" + std::to_string(n));
}

// Prebuilt keys for the timed loops: `keyOf` constructs a string (an std::string, a
// SmString, a to_string) and that cost would bury the container differences the
// benchmarks are about. The semantics tests keep building fresh keys.
static std::vector<Str> gKeys;

static void buildKeys(Int count) {
    gKeys.reserve((std::size_t) count);
    for (Int i = 0; i < count; i++) gKeys.push_back(keyOf((std::uint64_t) i));
}

static const Str& keyRef(std::uint64_t n) { return gKeys[(std::size_t) n]; }

static const Str& keyRefWrap(std::uint64_t n, Int modulo) {
    return gKeys[(std::size_t) (n % (std::uint64_t) modulo)];
}

// ---- content comparison ----------------------------------------------------

// Bounded diagnostic for the first content divergence: duplicates in the
// iteration (two rows holding one key) and per-key value mismatches against the
// reference. Printed once per comparison that finds something.
template <class TSmMap, class TStdMap>
static void diagnose(const char* phase, const TSmMap& sm, const TStdMap& ref) {
    std::vector<Str> keys;
    std::vector<Int> values;
    for (const auto& entry: sm) {
        keys.push_back(entry.first);
        values.push_back(entry.second);
    }
    std::printf("  diagnose '%s': size=%d iterated=%d\n", phase, (int) sm.size(), (int) keys.size());
    Int duplicates = 0;
    Int mismatches = 0;
    std::vector<bool> seen(keys.size(), false);
    for (std::size_t i = 0; i < keys.size(); i++) {
        if (seen[i]) continue;
        Int occurrences = 0;
        std::string row;
        for (std::size_t j = i; j < keys.size(); j++) {
            if (!(keys[j] == keys[i])) continue;
            occurrences++;
            seen[j] = true;
            row += " " + std::to_string((int) values[j]);
        }
        if (occurrences > 1 && duplicates < 3) {
            auto found = sm.find(keys[i]);
            std::printf("    duplicate key '%s' x%d, values%s, find()=%d\n",
                        simse_toStdString(keys[i]).c_str(), (int) occurrences, row.c_str(),
                        (int) (found == sm.end() ? -12345 : found->second));
        }
        if (occurrences > 1) duplicates++;
    }
    for (std::size_t i = 0; i < keys.size(); i++) {
        auto found = ref.find(keys[i]);
        if (found != ref.end() && found->second != values[i]) {
            if (mismatches < 3) {
                std::printf("    mismatch '%s': iterated=%d ref=%d find()=%d\n",
                            simse_toStdString(keys[i]).c_str(), (int) values[i], (int) found->second,
                            (int) sm.find(keys[i])->second);
            }
            mismatches++;
        }
    }
    std::printf("  diagnose: %d duplicate keys, %d value mismatches\n", (int) duplicates,
                (int) mismatches);
}

template <class TSmMap, class TStdMap>
static void compareContent(const char* phase, const TSmMap& sm, const TStdMap& ref) {
    CHECK(sm.size() == (Int) ref.size(), "%s: size %d vs ref %d", phase, (int) sm.size(),
          (int) ref.size());
    for (const auto& entry: ref) {
        auto found = sm.find(entry.first);
        CHECK(found != sm.end(), "%s: missing key '%s'", phase, simse_toStdString(entry.first).c_str());
        if (found != sm.end()) {
            CHECK(found->second == entry.second, "%s: value mismatch for '%s': %d vs %d", phase,
                  simse_toStdString(entry.first).c_str(), (int) found->second, (int) entry.second);
        }
        CHECK(sm.count(entry.first) == 1, "%s: count() != 1 for a present key", phase);
        CHECK(sm.has(entry.first), "%s: has() false for a present key", phase);
        CHECK(sm.findRow(entry.first) >= 0, "%s: findRow() < 0 for a present key", phase);
    }
    Int visited = 0;
    Int extra = 0;
    Int wrong = 0;
    for (const auto& entry: sm) {
        visited++;
        auto found = ref.find(entry.first);
        if (found == ref.end()) extra++;
        else if (found->second != entry.second) wrong++;
    }
    CHECK(extra == 0, "%s: %d entries with keys the reference does not have", phase, (int) extra);
    CHECK(wrong == 0, "%s: %d entries whose value differs from the reference", phase, (int) wrong);
    if (extra > 0 || wrong > 0) diagnose(phase, sm, ref);
    CHECK(visited == sm.size(), "%s: iteration visited %d of %d", phase, (int) visited,
          (int) sm.size());
    // The dictops path: keys()/values() are built by iterating, and their sorted
    // copy has to match the reference's.
    std::vector<std::string> smKeys;
    for (const auto& entry: sm) smKeys.push_back(simse_toStdString(entry.first));
    std::vector<std::string> refKeys;
    for (const auto& entry: ref) refKeys.push_back(simse_toStdString(entry.first));
    std::sort(smKeys.begin(), smKeys.end());
    std::sort(refKeys.begin(), refKeys.end());
    CHECK(smKeys == refKeys, "%s: key sets differ", phase);
}

template <class TSmMap, class TStdMap>
static void compareAbsent(const char* phase, const TSmMap& sm, const TStdMap& ref,
                          const std::vector<Str>& probes) {
    for (const Str& key: probes) {
        const bool inRef = ref.find(key) != ref.end();
        CHECK(sm.has(key) == inRef, "%s: membership differs for '%s'", phase,
              simse_toStdString(key).c_str());
        CHECK((sm.findRow(key) >= 0) == inRef, "%s: findRow differs for '%s'", phase,
              simse_toStdString(key).c_str());
        auto found = sm.find(key);
        CHECK((found != sm.end()) == inRef, "%s: find differs for '%s'", phase,
              simse_toStdString(key).c_str());
    }
}

// ---- semantics -------------------------------------------------------------

static void testStringKeys() {
    const std::uint64_t count = 40000; // crosses 16 -> 64 -> ... -> 65536 buckets
    SmDictionary<Str, Int> sm;
    std::unordered_map<Str, Int> ref;

    for (std::uint64_t n = 0; n < count; n++) {
        Str key = keyOf(n);
        const Int value = (Int) (n * 7 + 1);
        sm.insert_or_assign(key, value);
        ref[key] = value;
        if (n % 1000 == 0) compareContent("fill", sm, ref);
    }
    compareContent("after fill", sm, ref);

    // Overwrite: an existing key keeps its row and takes the new value.
    for (std::uint64_t n = 0; n < count; n += 3) {
        Str key = keyOf(n);
        sm.insert_or_assign(key, (Int) n);
        ref[key] = (Int) n;
    }
    compareContent("after overwrite", sm, ref);

    // operator[]: inserts a default value, then assigns.
    for (std::uint64_t n = count; n < count + 500; n++) {
        Str key = keyOf(n);
        sm[key] = (Int) n;
        ref[key] = (Int) n;
    }
    for (std::uint64_t n = 0; n < count + 500; n += 7) {
        sm[keyOf(n)] = sm[keyOf(n)] + 1;
        ref[keyOf(n)] = ref[keyOf(n)] + 1;
    }
    compareContent("after operator[]", sm, ref);

    // Erase: present keys, absent keys, and every other key (leaves a free list).
    for (std::uint64_t n = 1; n < count; n += 2) {
        CHECK(sm.erase(keyOf(n)) == 1, "erase of a present key returned 0");
        CHECK(ref.erase(keyOf(n)) == 1, "reference erase of a present key returned 0");
        if (n % 2001 == 1) compareContent("during erase", sm, ref);
    }
    for (std::uint64_t n = count + 600; n < count + 620; n++) {
        CHECK(sm.erase(keyOf(n)) == 0, "erase of an absent key returned 1");
        CHECK(ref.erase(keyOf(n)) == 0, "reference erase of an absent key returned 1");
    }
    compareContent("after erase", sm, ref);

    // Reuse the holes: the free list has to hand out the removed rows.
    for (std::uint64_t n = 0; n < 9000; n++) {
        Str key = keyOf(1000000 + n);
        sm.insert_or_assign(key, (Int) n);
        ref[key] = (Int) n;
    }
    compareContent("after hole reuse", sm, ref);

    // compact(): the content and the iteration set survive the repack.
    sm.compact();
    compareContent("after compact", sm, ref);
    // and an insert after compact appends instead of reusing a hole
    sm.insert_or_assign(keyOf(77), 777);
    ref[keyOf(77)] = 777;
    compareContent("after compact + insert", sm, ref);
    sm.compact(); // nothing to do now
    compareContent("after idempotent compact", sm, ref);

    // Absent-key probes, including keys that were removed.
    std::vector<Str> probes;
    for (std::uint64_t n = count + 500; n < count + 540; n++) probes.push_back(keyOf(n));
    for (std::uint64_t n = 1; n < 400; n += 2) probes.push_back(keyOf(n));
    compareAbsent("absent probes", sm, ref, probes);

    // clear(): a full cleanup, then reinsertion.
    sm.clear();
    ref.clear();
    CHECK(sm.size() == 0 && sm.begin() == sm.end(), "clear left rows behind");
    CHECK(sm.find(keyOf(2)) == sm.end(), "clear left a findable key");
    compareContent("after clear", sm, ref);
    sm.insert_or_assign(keyOf(2), 22);
    ref[keyOf(2)] = 22;
    sm.insert_or_assign(keyOf(64), 6464);
    ref[keyOf(64)] = 6464;
    compareContent("after clear + refill", sm, ref);

    // A copy is a deep, independent value.
    SmDictionary<Str, Int> copy = sm;
    sm.insert_or_assign(keyOf(9), 9);
    ref[keyOf(9)] = 9;
    compareContent("source after copy mutated", sm, ref);
    CHECK(copy.size() == 2, "copy shares rows with its source (size %d)", (int) copy.size());
    copy.insert_or_assign(keyOf(10), 10);
    CHECK(sm.size() == 3, "mutating the copy changed the source (size %d)", (int) sm.size());
}

// Int keys that share their low bits: multiples of 64 all land in the same few
// buckets of a 16/64/256-entry table, so this walks real chains.
static void testPowerOfTwoKeys() {
    SmDictionary<Int, Int> sm;
    std::unordered_map<Int, Int> ref;
    for (Int i = 0; i < 4000; i++) {
        const Int key = i * 64;
        sm.insert_or_assign(key, i);
        ref[key] = i;
        if (i % 500 == 0) {
            for (const auto& entry: ref) {
                auto found = sm.find(entry.first);
                CHECK(found != sm.end() && found->second == entry.second,
                      "powers-of-two probe lost key %d", (int) entry.first);
            }
            CHECK(sm.size() == (Int) ref.size(), "powers-of-two size %d vs %d", (int) sm.size(),
                  (int) ref.size());
        }
    }
    Int visited = 0;
    for (const auto& entry: sm) {
        visited++;
        CHECK(ref.find(entry.first) != ref.end(), "extra key %d", (int) entry.first);
    }
    CHECK(visited == sm.size(), "powers-of-two iteration %d of %d", (int) visited, (int) sm.size());
    for (Int i = 0; i < 4000; i += 2) {
        sm.erase(i * 64);
        ref.erase(i * 64);
    }
    CHECK(sm.size() == (Int) ref.size(), "powers-of-two after erase %d vs %d", (int) sm.size(),
          (int) ref.size());
    sm.compact();
    CHECK(sm.size() == (Int) ref.size(), "powers-of-two after compact");
    for (const auto& entry: ref) {
        auto found = sm.find(entry.first);
        CHECK(found != sm.end() && found->second == entry.second, "compacted powers-of-two lost %d",
              (int) entry.first);
    }
}

// Growing packs the holes away (`growBuckets`), which shifts row indexes: every
// live key must still be findable, iteration must visit exactly `_count` rows, and
// the row `operator[]` hands back must be the row it just appended.
static void testGrowthPacksHoles() {
    SmDictionary<Str, Int> sm;
    std::unordered_map<Str, Int> ref;
    for (Int i = 0; i < 10; i++) {
        sm.insert_or_assign(keyOf((std::uint64_t) i), i);
        ref[keyOf((std::uint64_t) i)] = i;
    }
    for (Int i = 0; i < 5; i++) {
        sm.erase(keyOf((std::uint64_t) i));
        ref.erase(keyOf((std::uint64_t) i));
    }
    // The inserts below cross the 16-bucket load factor, so a growth (and with it a
    // pack of the five holes) happens in the middle of the loop.
    for (Int i = 100; i < 140; i++) {
        sm.insert_or_assign(keyOf((std::uint64_t) i), i);
        ref[keyOf((std::uint64_t) i)] = i;
    }
    compareContent("after growth over holes", sm, ref);

    // `operator[]` appends and uses the row index it appended at, with holes present
    // and across a growth.
    for (Int i = 200; i < 700; i++) {
        sm[keyOf((std::uint64_t) i)] = i;
        ref[keyOf((std::uint64_t) i)] = i;
        if (i % 97 == 0) {
            CHECK(sm[keyOf((std::uint64_t) i)] == i,
                  "operator[] returned the wrong row for key-%d (%d)", (int) i,
                  (int) sm[keyOf((std::uint64_t) i)]);
        }
    }
    compareContent("after growth + operator[]", sm, ref);
    // Erase across a growth boundary again: a hole created after the pack must be
    // invisible to a lookup and packed by the next iterator call.
    for (Int i = 100; i < 300; i += 3) {
        sm.erase(keyOf((std::uint64_t) i));
        ref.erase(keyOf((std::uint64_t) i));
    }
    Int visited = 0;
    for (const auto& entry: sm) {
        visited++;
        CHECK(ref.find(entry.first) != ref.end(), "packed iteration lost a key");
    }
    CHECK(visited == sm.size(), "iterated %d of %d after a pack", (int) visited, (int) sm.size());
    CHECK(sm.size() == (Int) ref.size(), "size after growth/erase %d vs %d", (int) sm.size(),
          (int) ref.size());
}

// Non-trivial values: a List value is copied in and out (the compiler's
// Dictionary<Str, List<...>> case) and an empty dictionary never allocates.
static void testListValues() {
    SmDictionary<Str, List<Int>> sm;
    for (Int i = 0; i < 200; i++) {
        Str key = keyOf((std::uint64_t) i);
        List<Int> values;
        for (Int j = 0; j <= i % 7; j++) values.push_back(i * 10 + j);
        sm.insert_or_assign(key, values);
    }
    CHECK(sm.size() == 200, "list-valued size %d", (int) sm.size());
    for (Int i = 0; i < 200; i++) {
        auto found = sm.find(keyOf((std::uint64_t) i));
        CHECK(found != sm.end(), "list-valued key %d missing", (int) i);
        if (found != sm.end()) {
            CHECK(found->second.size() == (i % 7) + 1, "list-valued length for %d: %d", (int) i,
                  (int) found->second.size());
            CHECK(found->second[0] == i * 10, "list-valued first item for %d", (int) i);
        }
    }
    // The value slot is mutable through operator[].
    sm[keyOf(3)].push_back(-1);
    CHECK(sm[keyOf(3)].size() == (3 % 7) + 2, "operator[] value is not the stored row");
    sm.erase(keyOf(3));
    CHECK(sm.find(keyOf(3)) == sm.end(), "erase of a list-valued key");
}

// ---- speed -----------------------------------------------------------------

template <class TMap, class TMakeKey>
static double timeFill(TMap& map, Int count, TMakeKey makeKey) {
    const auto begin = std::chrono::steady_clock::now();
    for (Int i = 0; i < count; i++) {
        map.insert_or_assign(makeKey((std::uint64_t) i), i);
    }
    const auto end = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

template <class TMap, class TMakeKey>
static double timeLookup(const TMap& map, Int probeCount, Int keyCount, TMakeKey makeKey,
                         bool present) {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    for (Int i = 0; i < probeCount; i++) {
        // Present probes revisit the first `keyCount` keys; absent ones use keys
        // `keyCount`..`2*keyCount`, which the map never holds (the key table is
        // built for both ranges, so no loop constructs a string).
        const std::uint64_t index = present ? (std::uint64_t) (i % keyCount)
                                            : (std::uint64_t) (keyCount + (i % keyCount));
        auto found = map.find(makeKey(index));
        if (found != map.end()) sink = sink + found->second;
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

template <class TMap>
static double timeIterate(const TMap& map, Int rounds) {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    for (Int round = 0; round < rounds; round++) {
        for (const auto& entry: map) sink = sink + entry.second;
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

template <class TMap>
static double timeErase(TMap& map, Int count, Int stride) {
    const auto begin = std::chrono::steady_clock::now();
    for (Int i = 0; i < count; i += stride) map.erase(keyRef((std::uint64_t) i));
    const auto end = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

static void reportSpeed() {
    const Int count = 200000;
    const Int lookups = 2000000;
    std::printf("\nspeed (%d entries, %d lookups)\n", (int) count, (int) lookups);

    for (int round = 0; round < 3; round++) {
        // Interleave the two maps in the same window (this machine throttles).
        SmDictionary<Str, Int> sm;
        std::unordered_map<Str, Int> ref;
        const double smFill = timeFill(sm, count, &keyRef);
        const double refFill = timeFill(ref, count, &keyRef);
        const double smHit = timeLookup(sm, lookups, count, &keyRef, true);
        const double refHit = timeLookup(ref, lookups, count, &keyRef, true);
        const double smMiss = timeLookup(sm, lookups, count, &keyRef, false);
        const double refMiss = timeLookup(ref, lookups, count, &keyRef, false);
        const double smIter = timeIterate(sm, 5);
        const double refIter = timeIterate(ref, 5);
        const double smErase = timeErase(sm, count, 2);
        const double refErase = timeErase(ref, count, 2);
        std::printf("  round %d  fill %7.1f/%7.1f  hit %7.1f/%7.1f  miss %7.1f/%7.1f  "
                    "iter %6.1f/%6.1f  erase %6.1f/%6.1f  ms (sm/std)\n",
                    round + 1, smFill, refFill, smHit, refHit, smMiss, refMiss, smIter, refIter,
                    smErase, refErase);
    }

    std::printf("layout: SmDictionary<Str, Int> %d B, row %d B, "
                "List<Str> %d B, unordered_map node ~%d B\n",
                (int) sizeof(SmDictionary<Str, Int>), (int) sizeof(SmDictRow<Str, Int>),
                (int) sizeof(List<Str>), (int) (sizeof(void*) * 4 + sizeof(Str) + sizeof(Int)));
}

// A value the size of `AstXmlNode` (176 B), the compiler's biggest dictionary
// value: copies and row alignment matter more than the key compare here.
SIMSE_PACK_PUSH
struct AstXmlSize {
    Int kind;
    Str name;
    List<Int> attributes;
    char padding[84];
};
struct BigValue {
    char bytes[176];
};
SIMSE_PACK_POP

// The compiler's hot shape: a dictionary per function/scope with a couple of
// dozen entries, filled, read a lot, then dropped or cleared.
template <class TMap>
static double timeSmallChurn() {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    for (Int round = 0; round < 30000; round++) {
        TMap map;
        for (Int i = 0; i < 24; i++) map.insert_or_assign(keyRef((std::uint64_t) (round + i)), i);
        for (Int i = 0; i < 72; i++) {
            auto found = map.find(keyRef((std::uint64_t) (round + i % 30)));
            if (found != map.end()) sink = sink + found->second;
        }
        for (Int i = 0; i < 8; i++) map.erase(keyRef((std::uint64_t) (round + i * 3)));
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

// The emitter's save/restore pattern (`val saved = this.nameKinds` per lambda).
template <class TMap>
static double timeCopy() {
    TMap source;
    for (Int i = 0; i < 150; i++) source.insert_or_assign(keyRef((std::uint64_t) i), i);
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    for (Int round = 0; round < 20000; round++) {
        TMap copy = source;
        sink = sink + copy.size();
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

// 176-byte values: `Dictionary<Str, AstXmlNode>` in the compiler.
template <class TMap>
static double timeBigValue() {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    TMap map;
    for (Int i = 0; i < 20000; i++) {
        BigValue value{};
        value.bytes[0] = (char) i;
        map.insert_or_assign(keyRef((std::uint64_t) i), value);
    }
    for (Int i = 0; i < 200000; i++) {
        auto found = map.find(keyRef((std::uint64_t) (i % 20000)));
        if (found != map.end()) sink = sink + found->second.bytes[0];
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

// Read-biased access at one table size, varying only the value size: this isolates
// the cost of the row's stride (does a hit's key/value share a cache line with
// nothing else, or sit in a second array?).
template <class TMap>
static double timeReadBiased() {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    TMap map;
    for (Int i = 0; i < 2000; i++) {
        AstXmlSize value{};
        value.kind = i;
        map.insert_or_assign(keyRef((std::uint64_t) i), value);
    }
    for (Int i = 0; i < 400000; i++) {
        auto found = map.find(keyRef((std::uint64_t) (i % 2200)));
        if (found != map.end()) sink = sink + found->second.kind;
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

// The same shape with a scalar value: same keys, same table size, same lookups.
template <class TMap>
static double timeReadBiasedSmallValue() {
    volatile Int sink = 0;
    const auto begin = std::chrono::steady_clock::now();
    TMap map;
    for (Int i = 0; i < 2000; i++) map.insert_or_assign(keyRef((std::uint64_t) i), i);
    for (Int i = 0; i < 400000; i++) {
        auto found = map.find(keyRef((std::uint64_t) (i % 2200)));
        if (found != map.end()) sink = sink + found->second;
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

// The compiler's per-function pattern: one long-lived dictionary, cleared and
// refilled per scope (codegen's `beginScope`).
template <class TMap>
static double timeClearRefill() {
    volatile Int sink = 0;
    TMap map;
    const auto begin = std::chrono::steady_clock::now();
    for (Int round = 0; round < 30000; round++) {
        map.clear();
        for (Int i = 0; i < 24; i++) map.insert_or_assign(keyRef((std::uint64_t) (round + i)), i);
        for (Int i = 0; i < 72; i++) {
            auto found = map.find(keyRef((std::uint64_t) (round + i % 30)));
            if (found != map.end()) sink = sink + found->second;
        }
    }
    const auto end = std::chrono::steady_clock::now();
    (void) sink;
    return std::chrono::duration<double, std::milli>(end - begin).count();
}

int main() {
    buildKeys(700000);
    testStringKeys();
    testGrowthPacksHoles();
    testPowerOfTwoKeys();
    testListValues();
    if (failures == 0) std::printf("semantics: all checks passed\n");
    else std::printf("semantics: %d FAILURES\n", failures);
    reportSpeed();

    // The compiler's shape: many small dictionaries that live for one function or
    // lambda, not one huge one.
    std::printf("\ncompiler-shaped workloads (ms, sm/std)\n");
    for (int round = 0; round < 3; round++) {
        const double smChurn = timeSmallChurn<SmDictionary<Str, Int>>();
        const double stdChurn = timeSmallChurn<std::unordered_map<Str, Int>>();
        const double smCopy = timeCopy<SmDictionary<Str, Int>>();
        const double stdCopy = timeCopy<std::unordered_map<Str, Int>>();
        const double smBig = timeBigValue<SmDictionary<Str, BigValue>>();
        const double stdBig = timeBigValue<std::unordered_map<Str, BigValue>>();
        const double smRead = timeReadBiased<SmDictionary<Str, AstXmlSize>>();
        const double stdRead = timeReadBiased<std::unordered_map<Str, AstXmlSize>>();
        const double smReadInt = timeReadBiasedSmallValue<SmDictionary<Str, Int>>();
        const double stdReadInt = timeReadBiasedSmallValue<std::unordered_map<Str, Int>>();
        const double smClear = timeClearRefill<SmDictionary<Str, Int>>();
        const double stdClear = timeClearRefill<std::unordered_map<Str, Int>>();
        std::printf("  round %d  small-churn %7.1f/%7.1f  copy %6.1f/%6.1f  big-value %6.1f/%6.1f  "
                    "read-176B %6.1f/%6.1f  read-4B %6.1f/%6.1f  clear-refill %6.1f/%6.1f\n",
                    round + 1, smChurn, stdChurn, smCopy, stdCopy, smBig, stdBig, smRead,
                    stdRead, smReadInt, stdReadInt, smClear, stdClear);
    }
    std::printf("\n%s\n", failures == 0 ? "OK" : "FAILED");
    return failures == 0 ? 0 : 1;
}
