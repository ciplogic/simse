// Profiling.kt
//
// The instrumented profiler (`--profile`, impl_specs/profiling.md): one RAII timer per
// emitted body, and the CSV written when the program leaves. The runtime is emitted into
// the program as text (the prologue below), and it is a *flag*: with `--profile` off
// everything here returns "", so a program built without it is unchanged.
//
// A measured body is a *column*, not a name: the emitter hands each one a dense `Int`
// constant while it writes the bodies (`Emitter.profIndexOf`), and the names are emitted as
// one table of constants beside them (`profNameTableText`). The runtime keeps one
// `List<FunctionData>` indexed by that constant - no dictionary, no string compare and no
// per-entry allocation, and a measurement is one array index.
//
// The instant stays `Int64` (a nanosecond count is ~1000x a microsecond one, and the
// `--profile-nanos` unit is a display choice, not a range one), and the report's names are
// the packages the compiler knows (`ns1_emitFunction` is `codegen::emitFunction`).
//
// The runtime is a backtick string (specs/built-in-types.md): raw and multi-line, so the C++
// is written as it is read, and only the computed pieces - the clock, the unit, the file
// path - are concatenated in.
//
// State machines' `advance`/`value` are not measured: a body's own timer covers them.

package profiling

// `--profile`: emit the runtime and a `measure(...)` preamble in every body.
var profEnabledFlag: Bool = false

// `--profile-file <path>`: where the table is written when the program leaves. The default
// is `simse_profile.txt`; `-` (or an empty path) keeps it on stderr.
var profFileFlag: Str = "simse_profile.txt"

fun profEnabled(): Bool {
    return profEnabledFlag
}

fun profSetEnabled(value: Bool): Unit {
    profEnabledFlag = value
}

fun profSetFile(path: *Str): Unit {
    profFileFlag = *path
}

// `--profile-nanos`: report nanoseconds instead of microseconds. Both totals are `Int64`
// (`FunctionData.total`, `ProfileScope::start_`), so the finer unit is a display choice and
// not a range one - a nanosecond count is ~1000x a microsecond count.
var profNanosFlag: Bool = false

fun profSetNanos(value: Bool): Unit {
    profNanosFlag = value
}

// The clock the emitted timer reads, and the CSV column it feeds.
fun profClock(): Str {
    if (profNanosFlag) {
        return "simse_nowNanos"
    }
    return "simse_nowMicros"
}

fun profUnit(): Str {
    if (profNanosFlag) {
        return "total_ns"
    }
    return "total_us"
}

// A C++ string literal's body: `\` and `"` escaped. A path needs it, a mangled symbol does not.
fun profEscape(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text.charAt(i)
        if (ch == '\\' || ch == '"') {
            out.append('\\')
        }
        out.append(ch)
        i = i + 1
    }
    return out
}

// The prologue's addition: the `<cstdio>` the report prints through, then the runtime
// itself. Empty when the flag is off. The C++ sits at column 0 in the source, because that
// is how it is emitted: a backtick string takes its lines as written.
fun profPreludeText(): Str {
    if (!profEnabledFlag) {
        return ""
    }
    var text: Str = Str()
    text.appendStr(`#include <cstdio>

// ---- profiling (--profile) ------------------------------------------------
// Every emitted body starts with a profileApp.measure(<index>) whose constructor counts the
// entry and whose destructor banks the elapsed time; the table prints when the program
// leaves. The index is a compile-time constant and the names are the table of constants
// below, so a measurement is one array index, no lookup.
namespace simse_profiling {
    struct FunctionData {
        Int64 total;
        Int64 calls;
        FunctionData() : total(0), calls(0) {}
    };

    // Index -> name, by the same constant measure receives; defined with the bodies.
    extern const char *const kMethodNames[];

    // Where the table goes when the program leaves (--profile-file, default
    // simse_profile.txt; a lone '-' keeps it on stderr).
    static const char *const kProfileFile = "`)
    text.appendStr(profEscape(profFileFlag))
    text.appendStr(`";

    class ProfileScope {
    public:
        ProfileScope(Int index, List<FunctionData> *functions)
            : index_(index), functions_(functions), start_(`)
    text.appendStr(profClock())
    text.appendStr(`()) {
            FunctionData &row = (*functions_)[index_];
            row.calls = row.calls + 1;
        }
        ProfileScope(const ProfileScope &) = delete;
        ProfileScope &operator=(const ProfileScope &) = delete;
        ~ProfileScope() {
            FunctionData &row = (*functions_)[index_];
            row.total = row.total + (`)
    text.appendStr(profClock())
    text.appendStr(`() - start_);
        }

    private:
        Int index_;
        List<FunctionData> *functions_;
        Int64 start_;
    };

    class ProfileApp {
    public:
        ProfileScope measure(Int index) {
            if (functions.size() <= index) {
                functions.resize(index + 1);
            }
            return ProfileScope(index, &functions);
        }

        // Biggest total first, as CSV, to the profile file: the timings measure the
        // program, they are not output of it, and a tool reads them, not an eye.
        void report() {
            FILE *out = stderr;
            if (kProfileFile[0] != 0 && kProfileFile[0] != '-') {
                FILE *opened = fopen(kProfileFile, "w");
                if (opened != NULL) out = opened;
            }
            List<Int> order;
            for (Int i = 0; i < functions.size(); i++) {
                if (functions[i].calls > 0) order.push_back(i);
            }
            for (Int i = 0; i < order.size(); i++) {
                Int best = i;
                for (Int j = i + 1; j < order.size(); j++) {
                    if (functions[order[j]].total > functions[order[best]].total) best = j;
                }
                if (best != i) {
                    Int swap = order[i];
                    order[i] = order[best];
                    order[best] = swap;
                }
            }
            fprintf(out, "name,`)
    text.appendStr(profUnit())
    text.appendStr(`,calls\n");
            for (Int i = 0; i < order.size(); i++) {
                FunctionData &row = functions[order[i]];
                fprintf(out, "%s,%lld,%lld\n", kMethodNames[order[i]],
                    (long long) row.total, (long long) row.calls);
            }
            if (out != stderr) fclose(out);
        }

    private:
        List<FunctionData> functions;
    };
}

namespace {
    simse_profiling::ProfileApp profileApp;

    // The table, when the program leaves: a static outlives every automatic scope,
    // so this runs after main returned - whichever return it took.
    struct ProfileReport {
        ~ProfileReport() { profileApp.report(); }
    } profileReport;
}

`)
    return text
}

// The name table, emitted beside the bodies (`--profile`): the constant a `measure` call
// indexes with is the index here. One C++ string constant per measured body, so the report
// names a row without building a Simse `Str`. Empty when the flag is off.
fun profNameTableText(names: *List<Str>): Str {
    if (!profEnabledFlag) {
        return ""
    }
    var text: Str = Str()
    text.appendStr(`namespace simse_profiling {
    const char *const kMethodNames[] = {
`)
    if (names.size() == 0) {
        text.appendStr(`        ""
`)
    }
    for (*name in names) {
        text.appendStr(`        "`)
        text.appendStr(profEscape(name))
        text.appendStr(`",
`)
    }
    text.appendStr(`    };
}
`)
    return text
}

// The first statement of an emitted body: the timer, named by the body's constant index
// (`Emitter.profIndexOf`). Empty when the flag is off.
fun profPreamble(index: Int): Str {
    if (!profEnabledFlag) {
        return ""
    }
    return fmtStr("auto __smProfile = profileApp.measure(|);", index.toString())
}
