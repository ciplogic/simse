// Profiling.kt
//
// The instrumented profiler (`--profile`, impl_specs/profiling.md): one RAII timer per
// emitted body, and the table printed when the program leaves. The runtime is emitted into
// the program as text (the prologue below), and it is a *flag*: with `--profile` off
// everything here returns "", so a program built without it is unchanged.
//
// State machines' `advance`/`value` are not measured: a body's own timer covers them.

package profiling

// `--profile`: emit the runtime and a `measure(...)` preamble in every body.
var profEnabledFlag: Bool = false

fun profEnabled(): Bool {
    return profEnabledFlag
}

fun profSetEnabled(value: Bool): Unit {
    profEnabledFlag = value
}

// The prologue's addition: the `<cstdio>` the report prints through, then the runtime
// itself. Empty when the flag is off.
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

// The first statement of an emitted body: the timer, named by the symbol the body is emitted
// under. Empty when the flag is off.
fun profPreamble(name: *Str): Str {
    if (!profEnabledFlag) {
        return ""
    }
    return fmtStr("auto __smProfile = profileApp.measure(\"|\");", name)
}
