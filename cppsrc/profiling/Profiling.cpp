#include "Profiling.h"

// The instrumented profiler (`--profile`, impl_specs/profiling.md): the C++ ring of
// cppsrc/profiling/Profiling.kt, which is the Simse ring of the same thing. The two emit
// the same C++ - the strings below are the mirror of that file's, statement for
// statement - and both read a flag their own driver sets.
namespace profiling {
    namespace {
        // The flag, as a function-local static: no global to initialize before `main`,
        // the same shape `linear::showIl` uses.
        bool& enabledFlag() {
            static bool value = false;
            return value;
        }
    }

    bool enabled() {
        return enabledFlag();
    }

    void setEnabled(bool value) {
        enabledFlag() = value;
    }

    Str preludeText() {
        if (!enabled()) return "";
        Str out;
        out += "#include <cstdio>\n";
        out += "\n";
        out += "// ---- profiling (--profile) ------------------------------------------------\n";
        out += "// Every emitted body starts with a `profileApp.measure(...)` whose destructor\n";
        out += "// banks the elapsed microseconds, and the table prints when the program leaves.\n";
        out += "namespace simse_profiling {\n";
        out += "    // One method's row: the table is one dictionary, so an entry costs one lookup.\n";
        out += "    struct ProfileRow {\n";
        out += "        Int64 total;\n";
        out += "        Int64 calls;\n";
        out += "        Int64 order;  // 1-based; 0 means this row has not been seen yet\n";
        out += "        ProfileRow() : total(0), calls(0), order(0) {}\n";
        out += "    };\n";
        out += "\n";
        out += "    class ProfileScope {\n";
        out += "    public:\n";
        out += "        ProfileScope(const Str &name, List<Str> *order, Dictionary<Str, ProfileRow> *rows)\n";
        out += "            : name_(name), order_(order), rows_(rows), start_(simse_nowMicros()) {}\n";
        out += "        ProfileScope(const ProfileScope &) = delete;\n";
        out += "        ProfileScope &operator=(const ProfileScope &) = delete;\n";
        out += "        ~ProfileScope() {\n";
        out += "            ProfileRow &row = (*rows_)[name_];\n";
        out += "            if (row.order == 0) {\n";
        out += "                row.order = order_->size() + 1;\n";
        out += "                order_->push_back(name_);\n";
        out += "            }\n";
        out += "            row.calls = row.calls + 1;\n";
        out += "            row.total = row.total + (simse_nowMicros() - start_);\n";
        out += "        }\n";
        out += "\n";
        out += "    private:\n";
        out += "        Str name_;\n";
        out += "        List<Str> *order_;\n";
        out += "        Dictionary<Str, ProfileRow> *rows_;\n";
        out += "        Int64 start_;\n";
        out += "    };\n";
        out += "\n";
        out += "    class ProfileApp {\n";
        out += "    public:\n";
        out += "        ProfileScope measure(const Str &name) {\n";
        out += "            return ProfileScope(name, &order, &rows);\n";
        out += "        }\n";
        out += "\n";
        out += "        // Biggest total first, on stderr: the timings measure the program, they\n";
        out += "        // are not output of it.\n";
        out += "        void report() {\n";
        out += "            for (Int i = 0; i < order.size(); i++) {\n";
        out += "                Int best = i;\n";
        out += "                for (Int j = i + 1; j < order.size(); j++) {\n";
        out += "                    if (rows[order[j]].total > rows[order[best]].total) best = j;\n";
        out += "                }\n";
        out += "                if (best != i) {\n";
        out += "                    Str swap = order[i];\n";
        out += "                    order[i] = order[best];\n";
        out += "                    order[best] = swap;\n";
        out += "                }\n";
        out += "            }\n";
        out += R"(            fprintf(stderr, "profile (microseconds, inclusive, %lld method(s)):\n",)";
        out += "\n";
        out += "                    (long long) order.size());\n";
        out += "            for (Int i = 0; i < order.size(); i++) {\n";
        out += "                ProfileRow &row = rows[order[i]];\n";
        out += R"(                fprintf(stderr, "  %12lld us  %10lld calls  %s\n",)";
        out += "\n";
        out += "                        (long long) row.total, (long long) row.calls,\n";
        out += "                        order[i].c_str());\n";
        out += "            }\n";
        out += "        }\n";
        out += "\n";
        out += "    private:\n";
        out += "        List<Str> order;\n";
        out += "        Dictionary<Str, ProfileRow> rows;\n";
        out += "    };\n";
        out += "}\n";
        out += "\n";
        out += "namespace {\n";
        out += "    simse_profiling::ProfileApp profileApp;\n";
        out += "\n";
        out += "    // The table, when the program leaves: a static outlives every automatic scope,\n";
        out += "    // so this runs after main returned - whichever return it took.\n";
        out += "    struct ProfileReport {\n";
        out += "        ~ProfileReport() { profileApp.report(); }\n";
        out += "    } profileReport;\n";
        out += "}\n";
        out += "\n";
        return out;
    }

    Str preamble(const Str& name) {
        if (!enabled()) return "";
        return Str("auto __smProfile = profileApp.measure(\"") + name + "\");";
    }
}
