// Profiling.kt
//
// The instrumented profiler (`--profile`, impl_specs/profiling.md): one RAII timer per
// emitted body, and the call *tree* written when the program leaves. The runtime is emitted
// into the program as text (the prologue below), and it is a *flag*: with `--profile` off
// everything here returns "", so a program built without it is unchanged.
//
// A measured body is a *column*, not a name: the emitter hands each one a dense `Int`
// constant while it writes the bodies (`Emitter.profIndexOf`), and the names are emitted as
// one table of constants beside them (`profNameTableText`), so the runtime never compares or
// stores a name while measuring - an entry pushes a node and the node's one body id.
//
// Every entry pushes a *node* on a stack and banks its elapsed time there: a node is one
// exact call stack - the pair (the stack it was reached through, the body) - kept in one
// `unordered_map` keyed by the parent node's ordinal and the body id packed into an
// `unsigned long long` (0 is the root node, the frame outside `main`). The report reads those
// nodes back as a tree - one line per node, indented, biggest child first - so a line says
// *who called whom* along that path, and a child's total is inside its own line's, because
// two call sites of one body are two nodes and not one shared row.
//
// The instant stays `Int64` (a nanosecond count is ~1000x a microsecond one, and the
// `--profile-nanos` unit is a display choice, not a range one), and the report's names are
// the packages the compiler knows (`ns1_emitFunction` is `codegen.emitFunction`). A name is
// Simse-spelled, so the namespace separator is `.` (`profDots`), not C++'s `::`.
//
// The runtime is a backtick string (specs/built-in-types.md): raw and multi-line, so the C++
// is written as it is read, and only the computed pieces - the clock, the unit, the file
// path - are concatenated in.
//
// State machines' `advance`/`value` are not measured: a body's own timer covers them.

package profiling

// `--profile`: emit the runtime and a `measure(...)` preamble in every body.
var profEnabledFlag: Bool = false

// `--profile-file <path>`: where the tree is written when the program leaves. The default
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
// (`CallNode.total`, `ProfileScope::start_`), so the finer unit is a display choice and
// not a range one - a nanosecond count is ~1000x a microsecond count.
var profNanosFlag: Bool = false

fun profSetNanos(value: Bool): Unit {
    profNanosFlag = value
}

// The clock the emitted timer reads, and the unit its rows are written in.
fun profClock(): Str {
    if (profNanosFlag) {
        return "simse_nowNanos"
    }
    return "simse_nowMicros"
}

fun profUnit(): Str {
    if (profNanosFlag) {
        return "ns"
    }
    return "us"
}

// A C++ string literal's body: `\` and `"` escaped. A path needs it, a mangled symbol does not.
fun profEscape(text: Str): Str {
    var out: Str
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
    var text: Str
    text.appendStr(`#include <cstdio>
#include <unordered_map>

// ---- profiling (--profile) ------------------------------------------------
// Every emitted body starts with a profileApp.measure(<index>) whose constructor counts the
// entry and whose destructor banks the elapsed time; the tree prints when the program
// leaves. The index is a compile-time constant and the names are the table of constants
// below, so a measurement is one array index and no string compare. An entry lands on a
// *node* of a call tree: a node is one exact call stack - the pair (the stack it was reached
// through, the body) - so two call sites of one body are two nodes, and a node's totals are
// exactly the calls that reached it. The report prints every node's children beneath it,
// largest total first: a child is time inside its own line, never more.
namespace simse_profiling {
    // One node of the call tree: the body that ran, the path's inclusive totals, and the
    // nodes created under it. The ordinal of a node stands for the whole stack that reaches
    // it, so a child is keyed by (parent ordinal, body).
    struct CallNode {
        Int body;
        Int64 total;
        Int64 calls;
        List<Int> children;
        CallNode() : body(0), total(0), calls(0) {}
    };

    // Index -> name, by the same constant measure receives; defined with the bodies.
    extern const char *const kMethodNames[];

    // Where the tree goes when the program leaves (--profile-file, default
    // simse_profile.txt; a lone '-' keeps it on stderr).
    static const char *const kProfileFile = "`)
    text.appendStr(profEscape(profFileFlag))
    text.appendStr(`";

    class ProfileApp;

    // One body entry: pushes the node it lands on, and banks the elapsed time there when
    // the body leaves.
    class ProfileScope {
    public:
        ProfileScope(Int index, ProfileApp *app);
        ProfileScope(const ProfileScope &) = delete;
        ProfileScope &operator=(const ProfileScope &) = delete;
        ~ProfileScope();

    private:
        Int node_;
        ProfileApp *app_;
        Int64 start_;
    };

    class ProfileApp {
    public:
        ProfileApp() {
            // Node 0 is the root: the stack outside main. It has no body and no line.
            nodes.push_back(CallNode());
        }

        ProfileScope measure(Int index) {
            return ProfileScope(index, this);
        }

        // Answers the node this entry lands on: the node of the stack as it is before this
        // body is pushed, for this body - created on first sight. Two call sites of a body
        // are two nodes, because they arrive through two stacks.
        Int enter(Int index) {
            Int parent = 0;
            if (stack.size() > 0) {
                parent = stack.back();
            }
            unsigned long long key = ((unsigned long long) parent << 32) | (unsigned long long) index;
            std::unordered_map<unsigned long long, Int>::iterator it = nodeOf.find(key);
            Int node = 0;
            if (it == nodeOf.end()) {
                node = (Int) nodes.size();
                nodes.push_back(CallNode());
                nodes[node].body = index;
                nodeOf[key] = node;
                nodes[parent].children.push_back(node);
            } else {
                node = it->second;
            }
            nodes[node].calls = nodes[node].calls + 1;
            stack.push_back(node);
            return node;
        }

        void leave(Int node, Int64 start) {
            Int64 elapsed = `)
    text.appendStr(profClock())
    text.appendStr(`() - start;
            nodes[node].total = nodes[node].total + elapsed;
            stack.pop_back();
        }

        // The tree, to the profile file: each node's children in turn, largest total first.
        // A node is one exact stack, so a child's total is time inside its line's, and the
        // file needs no sharing rules: nothing is unfolded twice.
        void report() {
            FILE *out = stderr;
            if (kProfileFile[0] != 0 && kProfileFile[0] != '-') {
                FILE *opened = fopen(kProfileFile, "w");
                if (opened != NULL) out = opened;
            }
            reportNode(out, 0, 0);
            if (out != stderr) fclose(out);
        }

    private:
        List<CallNode> nodes;
        std::unordered_map<unsigned long long, Int> nodeOf;
        List<Int> stack;

        void reportNode(FILE *out, Int node, Int depth) {
            if (depth > 256) {
                return;
            }
            List<Int> kids;
            for (Int i = 0; i < nodes[node].children.size(); i++) {
                kids.push_back(nodes[node].children[i]);
            }
            Int n = kids.size();
            for (Int i = 0; i < n; i++) {
                Int best = i;
                for (Int j = i + 1; j < n; j++) {
                    if (nodes[kids[j]].total > nodes[kids[best]].total) {
                        best = j;
                    }
                }
                if (best != i) {
                    Int tmp = kids[i];
                    kids[i] = kids[best];
                    kids[best] = tmp;
                }
            }
            for (Int i = 0; i < n; i++) {
                Int id = kids[i];
                for (Int d = 1; d < depth; d++) {
                    fputc(' ', out);
                }
                if (depth > 0) {
                    fputc('+', out);
                }
                fprintf(out, "%s():%lld `)
    text.appendStr(profUnit())
    text.appendStr(`: %lld calls\n", kMethodNames[nodes[id].body],
                        (long long) nodes[id].total, (long long) nodes[id].calls);
                reportNode(out, id, depth + 1);
            }
        }
    };

    ProfileScope::ProfileScope(Int index, ProfileApp *app) : node_(0), app_(app) {
        start_ = `)
    text.appendStr(profClock())
    text.appendStr(`();
        node_ = app_->enter(index);
    }

    ProfileScope::~ProfileScope() {
        app_->leave(node_, start_);
    }
}

namespace {
    simse_profiling::ProfileApp profileApp;

    // The tree, when the program leaves: a static outlives every automatic scope, so this
    // runs after main returned - whichever return it took.
    struct ProfileReport {
        ~ProfileReport() { profileApp.report(); }
    } profileReport;
}

`)
    return text
}

// The name table, emitted beside the bodies (`--profile`): the constant a `measure` call
// indexes with is the index here. One C++ string constant per measured body, so the report
// names a node without building a Simse `Str`. Empty when the flag is off.
// A name as Simse spells it: a namespace separates with `.`, not C++'s `::` (`ns1_emitFunction`
// is `codegen.emitFunction`). `prettySymbol` already writes the `.`, but a lambda symbol reaches
// it with a `::operator()` suffix of its own, so the last pass folds any `::` left.
fun profDots(name: *Str): Str {
    var out: Str
    var i: Int = 0
    while (i < name.size()) {
        if (name.charAt(i) == ':' && i + 1 < name.size() && name.charAt(i + 1) == ':') {
            out.append('.')
            i = i + 2
        } else {
            out.append(name.charAt(i))
            i = i + 1
        }
    }
    return out
}

fun profNameTableText(names: *List<Str>): Str {
    if (!profEnabledFlag) {
        return ""
    }
    var text: Str
    text.appendStr(`namespace simse_profiling {
    const char *const kMethodNames[] = {
`)
    if (names.size() == 0) {
        text.appendStr(`        ""
`)
    }
    for (*name in names) {
        text.appendStr(`        "`)
        text.appendStr(profEscape(profDots(name)))
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
    return `auto __smProfile = profileApp.measure(@index);`
}
