// Profiling.kt
//
// The instrumented profiler (`--profile`, impl_specs/profiling.md): one RAII timer per
// emitted body, and the table the program prints when it leaves. The mirror of
// cppsrc/profiling/Profiling.cpp.
//
// It is a *flag*, not a feature: with `--profile` off, everything here returns the empty
// string, so a program built without it is byte-identical to one built before the flag
// existed. The runtime itself is *generated into* the program (the prologue below)
// rather than linked in from a header: it is a few lines, it uses only what the RTL
// already gives every program (`Str`, `List`, `Dictionary`, `simse_nowMicros`), and a
// self-contained file needs no include path of its own.
//
// What a measurement is: `profileApp.measure("ns1_emitFunction")` is constructed as the
// first statement of an emitted body and destroyed when that body leaves - through any
// `return` - so the total is that body's whole run, nested calls included. The table is
// sorted by total, and each row also carries the call count, which is what tells
// "called once, expensive" from "called a million times, cheap".
//
// What is **not** measured: a state machine's methods (`advance`, `value`). They are the
// per-element inner loop of every `for`, and `value` is a one-line accessor, so a timer
// in them costs more than they do: the flags' first reading (T78) put `advance` at 44.7M
// calls and `value` at 44.4M in the compiler's own transpile, which was ~22% of the
// measured run for the two of them. `emitMachine` passes `measure = false` for them; a
// body's *own* timer still covers their work, since they are only ever entered from a
// measured caller.

package profiling

// ---- the flag --------------------------------------------------------------

// `--profile`: emit the runtime and a `measure(...)` preamble in every body. The C++
// ring keeps the same flag (`cppsrc/profiling/Profiling.h`), so the two rings emit the
// same C++ with the flag on or off.
var profEnabledFlag: Bool = false

fun profEnabled(): Bool {
    return profEnabledFlag
}

fun profSetEnabled(value: Bool): Unit {
    profEnabledFlag = value
}

// ---- what the emitter writes -----------------------------------------------

// The prologue's addition: the `<cstdio>` the report prints through, then the runtime
// itself. Empty when the flag is off, so a program that is not being profiled carries no
// trace of any of this.
fun profPreludeText(): Str {
    if (!profEnabledFlag) {
        return ""
    }
    var text: Str = Str()
    text.appendStr("#include <cstdio>\n")
    text.appendStr("\n")
    text.appendStr("// ---- profiling (--profile) ------------------------------------------------\n")
    text.appendStr("// Every emitted body starts with a `profileApp.measure(...)` whose destructor\n")
    text.appendStr("// banks the elapsed microseconds, and the table prints when the program leaves.\n")
    text.appendStr("namespace simse_profiling {\n")
    text.appendStr("    // One method's row: the table is one dictionary, so an entry costs one lookup.\n")
    text.appendStr("    struct ProfileRow {\n")
    text.appendStr("        Int64 total;\n")
    text.appendStr("        Int64 calls;\n")
    text.appendStr("        Int64 order;  // 1-based; 0 means this row has not been seen yet\n")
    text.appendStr("        ProfileRow() : total(0), calls(0), order(0) {}\n")
    text.appendStr("    };\n")
    text.appendStr("\n")
    text.appendStr("    class ProfileScope {\n")
    text.appendStr("    public:\n")
    text.appendStr("        ProfileScope(const Str &name, List<Str> *order, Dictionary<Str, ProfileRow> *rows)\n")
    text.appendStr("            : name_(name), order_(order), rows_(rows), start_(simse_nowMicros()) {}\n")
    text.appendStr("        ProfileScope(const ProfileScope &) = delete;\n")
    text.appendStr("        ProfileScope &operator=(const ProfileScope &) = delete;\n")
    text.appendStr("        ~ProfileScope() {\n")
    text.appendStr("            ProfileRow &row = (*rows_)[name_];\n")
    text.appendStr("            if (row.order == 0) {\n")
    text.appendStr("                row.order = order_->size() + 1;\n")
    text.appendStr("                order_->push_back(name_);\n")
    text.appendStr("            }\n")
    text.appendStr("            row.calls = row.calls + 1;\n")
    text.appendStr("            row.total = row.total + (simse_nowMicros() - start_);\n")
    text.appendStr("        }\n")
    text.appendStr("\n")
    text.appendStr("    private:\n")
    text.appendStr("        Str name_;\n")
    text.appendStr("        List<Str> *order_;\n")
    text.appendStr("        Dictionary<Str, ProfileRow> *rows_;\n")
    text.appendStr("        Int64 start_;\n")
    text.appendStr("    };\n")
    text.appendStr("\n")
    text.appendStr("    class ProfileApp {\n")
    text.appendStr("    public:\n")
    text.appendStr("        ProfileScope measure(const Str &name) {\n")
    text.appendStr("            return ProfileScope(name, &order, &rows);\n")
    text.appendStr("        }\n")
    text.appendStr("\n")
    text.appendStr("        // Biggest total first, on stderr: the timings measure the program, they\n")
    text.appendStr("        // are not output of it.\n")
    text.appendStr("        void report() {\n")
    text.appendStr("            for (Int i = 0; i < order.size(); i++) {\n")
    text.appendStr("                Int best = i;\n")
    text.appendStr("                for (Int j = i + 1; j < order.size(); j++) {\n")
    text.appendStr("                    if (rows[order[j]].total > rows[order[best]].total) best = j;\n")
    text.appendStr("                }\n")
    text.appendStr("                if (best != i) {\n")
    text.appendStr("                    Str swap = order[i];\n")
    text.appendStr("                    order[i] = order[best];\n")
    text.appendStr("                    order[best] = swap;\n")
    text.appendStr("                }\n")
    text.appendStr("            }\n")
    text.appendStr("            fprintf(stderr, \"profile (microseconds, inclusive, %lld method(s)):\\n\",\n")
    text.appendStr("                    (long long) order.size());\n")
    text.appendStr("            for (Int i = 0; i < order.size(); i++) {\n")
    text.appendStr("                ProfileRow &row = rows[order[i]];\n")
    text.appendStr("                fprintf(stderr, \"  %12lld us  %10lld calls  %s\\n\",\n")
    text.appendStr("                        (long long) row.total, (long long) row.calls,\n")
    text.appendStr("                        order[i].c_str());\n")
    text.appendStr("            }\n")
    text.appendStr("        }\n")
    text.appendStr("\n")
    text.appendStr("    private:\n")
    text.appendStr("        List<Str> order;\n")
    text.appendStr("        Dictionary<Str, ProfileRow> rows;\n")
    text.appendStr("    };\n")
    text.appendStr("}\n")
    text.appendStr("\n")
    text.appendStr("namespace {\n")
    text.appendStr("    simse_profiling::ProfileApp profileApp;\n")
    text.appendStr("\n")
    text.appendStr("    // The table, when the program leaves: a static outlives every automatic scope,\n")
    text.appendStr("    // so this runs after main returned - whichever return it took.\n")
    text.appendStr("    struct ProfileReport {\n")
    text.appendStr("        ~ProfileReport() { profileApp.report(); }\n")
    text.appendStr("    } profileReport;\n")
    text.appendStr("}\n")
    text.appendStr("\n")
    return text
}

// The first statement of an emitted body: the timer. `name` is the symbol the body is
// emitted under (`ns1_emitFunction`, `..._yieldable::advance`), which is what the report
// shows. Empty when the flag is off.
fun profPreamble(name: Str): Str {
    if (!profEnabledFlag) {
        return ""
    }
    return "auto __smProfile = profileApp.measure(\"" + name + "\");"
}
