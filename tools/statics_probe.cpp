// Scratch: the runtime shape of static storage (specs/statics.md,
// impl_specs/statics.md), written out by hand the way the emitter will write it:
// value-initialized globals, one generated initialization pass called first thing
// in `main`, and - for generic objects - per-instantiation storage behind an
// accessor that guarantees the field initializers run exactly once, whether the
// pass (a collected instantiation) or the first use (one the collection did not
// see) gets there first.
//
//   tools\_probe.bat statics_probe /O2 /DNDEBUG
#include "cppsrc/rtl/simse.hpp"

#include <cstdio>

// Counts how often a field initializer of `Cache<T>` actually ran. One initializer
// per instantiation, no matter how many times the field is read.
Int initRuns = 0;

// ---- what the emitter will generate for: -----------------------------------
// package counters
//   var requests: Int = 0
//   val origin: Str = "boot"
//   object Defaults { var retries: Int = 3   var label: Str = "simse"  var bias: Int }
//   object Cache<T> { var count: Int = 0     var label: Str = "c" }
Int ns1_requests{};
Str ns1_origin{};

SIMSE_PACK_PUSH
struct ns1_Defaults {
    Int retries;
    Str label;
    Int bias;
};
SIMSE_PACK_POP
ns1_Defaults ns1_Defaults_instance{};

// Generic object: zero-initialized variable-template storage, a per-instantiation
// initialized flag, and an init function holding the field initializers.
template <class T>
SIMSE_PACK_PUSH
struct ns1_Cache {
    Int count;
    Str label;
};
SIMSE_PACK_POP

template <class T> inline ns1_Cache<T> ns1_Cache_instance;
template <class T> inline bool ns1_Cache_initialized = false;

template <class T> void ns1_Cache_init();

template <class T>
ns1_Cache<T>& ns1_Cache_access() {
    if (!ns1_Cache_initialized<T>) {
        ns1_Cache_initialized<T> = true;      // before the initializers, so one that
        ns1_Cache_init<T>();                  // reads the object sees one state
    }
    return ns1_Cache_instance<T>;
}

template <class T>
void ns1_Cache_init() {
    initRuns = initRuns + 1;
    ns1_Cache_access<T>().count = 0;
    ns1_Cache_access<T>().label = "c";
}

// The residual path: a generic body mentioning an object behind its type
// parameter (`Cache<Bool>` is deliberately not collected below).
template <class T>
Int& cacheCount() {
    return ns1_Cache_access<T>().count;
}

// ---- the generated pass, called before the body of main ---------------------
void simse_initStatics();

void simse_initStatics() {
    ns1_requests = 0;
    ns1_origin = "boot";
    ns1_Defaults_instance.retries = 3;
    ns1_Defaults_instance.label = "simse";
    ns1_Defaults_instance.bias = 7;
    // Collected at reification: the concrete instantiations the program reaches.
    ns1_Cache_initialized<Int> = true;
    ns1_Cache_init<Int>();
    ns1_Cache_initialized<Str> = true;
    ns1_Cache_init<Str>();
}

int main() {
    // Before the pass: storage is empty, not indeterminate. This is the
    // guarantee `specs/statics.md` makes observable.
    std::printf("before the pass:  requests=%d origin=<%s> retries=%d bias=%d\n",
                ns1_requests, ns1_origin.c_str(), ns1_Defaults_instance.retries,
                ns1_Defaults_instance.bias);

    simse_initStatics();
    std::printf("after the pass:   requests=%d origin=<%s> retries=%d label=<%s> bias=%d\n",
                ns1_requests, ns1_origin.c_str(), ns1_Defaults_instance.retries,
                ns1_Defaults_instance.label.c_str(), ns1_Defaults_instance.bias);

    // Collected instantiations were initialized by the pass: reading their fields
    // runs no initializer. Two instantiations, two runs - and reading twice more
    // adds nothing.
    Int runsAfterPass = initRuns;
    std::printf("cache collected:  Int.count=%d Int.label=<%s> Str.count=%d\n",
                ns1_Cache_access<Int>().count, ns1_Cache_access<Int>().label.c_str(),
                ns1_Cache_access<Str>().count);
    std::printf("runs after pass:  %d\n", initRuns);
    (void) ns1_Cache_access<Int>().count;
    (void) ns1_Cache_access<Str>().label;
    std::printf("extra reads:      %d runs\n", initRuns - runsAfterPass);

    // An instantiation the collection did not see runs its initializers on first
    // use - once, no matter how often it is read afterwards.
    std::printf("cache residual:   Bool.count=%d\n", cacheCount<Bool>());
    std::printf("again:            Bool.count=%d\n", cacheCount<Bool>());
    std::printf("runs total:       %d\n", initRuns);

    // Instances are separate storage, and mutable.
    ns1_Cache_access<Int>().count = 5;
    ns1_Cache_access<Str>().count = 6;
    std::printf("cache separate:   Int.count=%d Str.count=%d\n",
                ns1_Cache_access<Int>().count, ns1_Cache_access<Str>().count);

    // `arrayEmpty<T>()` as a per-type static over `Array<T>(0)`: the same shape,
    // and the empty array is the shim's shared zero-length block.
    std::printf("arrayEmpty:       count=%d\n", arrayEmpty<Int>().count());
    return 0;
}
