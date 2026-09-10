//
// Golden test runner. For every fixture in the fixtures directory it runs the
// scan -> parse -> sema pipeline and compares deterministic token, AST, and sema
// dumps against checked-in goldens. It also parses and analyzes every real source
// mirror and asserts they are clean. See tests/README.md.
//

#include "test_support.h"
#include "../cppsrc/codegen/Codegen.h"
#include "../cppsrc/common/common.h"
#include "../cppsrc/parser/Parser.h"
#include "../cppsrc/sema/Sema.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>

#ifndef SIMSE_FIXTURES_DIR
#define SIMSE_FIXTURES_DIR "tests/fixtures"
#endif
#ifndef SIMSE_SOURCE_ROOT
#define SIMSE_SOURCE_ROOT "."
#endif

using namespace common;
using namespace lex;
using namespace tests;

namespace {
    Str baseName(const Str &path) {
        return std::filesystem::path(path).filename().string();
    }

    Str readFileText(const Str &path) {
        return common::readFile(path);
    }

    bool writeFileText(const Str &path, const Str &text) {
        std::filesystem::path p(path);
        std::filesystem::create_directories(p.parent_path());
        FILE *file = fopen(path.c_str(), "wb");
        if (file == nullptr) {
            return false;
        }
        fwrite(text.data(), 1, text.length(), file);
        fclose(file);
        return true;
    }

    List<Str> splitLines(const Str &text) {
        List<Str> lines;
        Str current;
        for (char ch: text) {
            if (ch == '\n') {
                lines.push_back(current);
                current.clear();
            } else {
                current += ch;
            }
        }
        if (!current.empty()) {
            lines.push_back(current);
        }
        return lines;
    }

    // Prints the differing lines of two dumps, capped so a large mismatch does
    // not flood the terminal.
    void printDiff(const Str &expected, const Str &actual) {
        List<Str> expectedLines = splitLines(expected);
        List<Str> actualLines = splitLines(actual);
        int maxLines = (int) std::max(expectedLines.size(), actualLines.size());
        int shown = 0;
        for (int i = 0; i < maxLines; i++) {
            Str expectedLine = i < (int) expectedLines.size() ? expectedLines[i] : Str("<missing>");
            Str actualLine = i < (int) actualLines.size() ? actualLines[i] : Str("<missing>");
            if (expectedLine == actualLine) {
                continue;
            }
            printf("    line %d:\n      expected: %s\n      actual:   %s\n",
                   i + 1, expectedLine.c_str(), actualLine.c_str());
            shown++;
            if (shown >= 20) {
                printf("    ...\n");
                break;
            }
        }
    }

    Str goldenPathFor(const Str &goldenDir, const Str &name, const Str &category) {
        return (std::filesystem::path(goldenDir) / (name + "." + category + ".expected")).string();
    }

    // Distinct `Prefix<...>` forms occurring in `text` (in first-seen order).
    List<Str> distinctForms(const Str &text, const Str &prefix) {
        List<Str> forms;
        size_t pos = text.find(prefix, 0);
        while (pos != Str::npos) {
            size_t end = text.find('>', pos);
            if (end == Str::npos) break;
            Str form = text.substr(pos, end - pos + 1);
            bool seen = false;
            for (const Str &existing: forms) {
                if (existing == form) {
                    seen = true;
                    break;
                }
            }
            if (!seen) forms.push_back(form);
            pos = text.find(prefix, end + 1);
        }
        return forms;
    }

    // Parses and emits a single fixture in-process (with the RTL prelude),
    // returning the C++ or an empty string on failure.
    Str emitFixture(Scanner *scanner, const Str &fixturesDir, const Str &name) {
        return emitFixtureCpp(scanner, fixturesDir, name);
    }

    // Compares (or, in update mode, rewrites) one golden. Returns false and
    // prints a diff or an explanatory message on mismatch. Does not count.
    bool compareGolden(const Str &label, const Str &actual, const Str &goldenPath, bool update) {
        if (update) {
            if (!writeFileText(goldenPath, actual)) {
                printf("FAIL %s: cannot write golden %s\n", label.c_str(), goldenPath.c_str());
                return false;
            }
            return true;
        }
        if (!std::filesystem::exists(goldenPath)) {
            printf("FAIL %s: missing golden %s (run with --update)\n",
                   label.c_str(), goldenPath.c_str());
            return false;
        }
        Str expected = readFileText(goldenPath);
        if (expected == actual) {
            return true;
        }
        printf("FAIL %s\n", label.c_str());
        printDiff(expected, actual);
        return false;
    }
}

int main(int argc, char **argv) {
    Str fixturesDir = SIMSE_FIXTURES_DIR;
    bool update = false;
    for (int i = 1; i < argc; i++) {
        Str arg = argv[i];
        if (arg == "--update") {
            update = true;
        } else {
            fixturesDir = arg;
        }
    }
    if (const char *env = std::getenv("SIMSE_UPDATE_GOLDENS")) {
        if (Str(env) == "1" || Str(env) == "true") {
            update = true;
        }
    }

    Str goldenDir = (std::filesystem::path(fixturesDir).parent_path() / "golden").string();

    List<TokenMatcher> rules = getTokenRules();
    Scanner scanner(&rules);

    int passed = 0;
    int failed = 0;

    // Fixtures: tokens + AST + sema goldens.
    List<Str> fixtures = filesInDir(fixturesDir, ".simse");
    for (const Str &fixture: fixtures) {
        Str name = baseName(fixture);

        ScanResult scan = scanFile(&scanner, fixture);
        Str tokensText = dump(scan);
        AstSemaResult astSema = runAstSema(scan, name);

        Str wrapperIssue = checkWrappers(&scanner, fixture, scan);
        if (!wrapperIssue.empty()) {
            failed++;
            printf("FAIL %s: %s\n", name.c_str(), wrapperIssue.c_str());
            continue;
        }

        bool ok = true;
        ok &= compareGolden(name, tokensText, goldenPathFor(goldenDir, name, "tokens"), update);
        ok &= compareGolden(name, astSema.ast, goldenPathFor(goldenDir, name, "ast"), update);
        if (astSema.parsed) {
            ok &= compareGolden(name, astSema.astXml, goldenPathFor(goldenDir, name, "astxml"), update);
        }
        ok &= compareGolden(name, astSema.sema, goldenPathFor(goldenDir, name, "sema"), update);
        if (astSema.hasCpp) {
            ok &= compareGolden(name, astSema.cpp, goldenPathFor(goldenDir, name, "cpp"), update);
        }

        if (ok) {
            passed++;
            printf(update ? "UPDATED %s\n" : "PASS %s\n", name.c_str());
        } else {
            failed++;
        }
    }

    // Negative fixtures: explicit assertions beyond the stored goldens.
    {
        Str path = (std::filesystem::path(fixturesDir) / "parse_error.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "parse_error.simse")
                                      : resError<ast::Module>("scan failed");
        if (scan.ok && !parsed.isOk()) {
            passed++;
            printf("PASS negative parse_error.simse (parse rejected)\n");
        } else {
            failed++;
            printf("FAIL negative parse_error.simse: expected a parse error\n");
        }
    }
    {
        Str path = (std::filesystem::path(fixturesDir) / "sema_unknown_type.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "sema_unknown_type.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = sema::analyze(parsed.Value, "sema_unknown_type.simse");
            for (const Str &diagnostic: diagnostics) {
                if (diagnostic.find("Nope") != Str::npos) {
                    reported = true;
                }
            }
        }
        if (reported) {
            passed++;
            printf("PASS negative sema_unknown_type.simse (diagnostic reported)\n");
        } else {
            failed++;
            printf("FAIL negative sema_unknown_type.simse: expected an unknown-type diagnostic\n");
        }
    }

    // Negative fixture: a data-class constructor called with the wrong number of
    // arguments must be diagnosed with a clear positioned message.
    {
        Str path = (std::filesystem::path(fixturesDir) / "ctor_arity.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "ctor_arity.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = sema::analyze(parsed.Value, "ctor_arity.simse");
            for (const Str &diagnostic: diagnostics) {
                if (diagnostic.find("data class 'Widget' expects 2 field(s) but got 1")
                    != Str::npos) {
                    reported = true;
                }
            }
        }
        if (reported) {
            passed++;
            printf("PASS negative ctor_arity.simse (constructor arity diagnostic reported)\n");
        } else {
            failed++;
            printf("FAIL negative ctor_arity.simse: expected a constructor arity diagnostic\n");
        }
    }

    // Negative fixture: a `case` label that is not a constant expression must be
    // diagnosed.
    {
        Str path = (std::filesystem::path(fixturesDir) / "sema_switch_label.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "sema_switch_label.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = sema::analyze(parsed.Value, "sema_switch_label.simse");
            for (const Str &diagnostic: diagnostics) {
                if (diagnostic.find("case label must be a constant expression") != Str::npos) {
                    reported = true;
                }
            }
        }
        if (reported) {
            passed++;
            printf("PASS negative sema_switch_label.simse (case-label diagnostic reported)\n");
        } else {
            failed++;
            printf("FAIL negative sema_switch_label.simse: expected a case-label diagnostic\n");
        }
    }

    // Positive fixture: hoisted declarations are usable before their textual
    // definition, and resolution stays clean.
    {
        Str path = (std::filesystem::path(fixturesDir) / "hoisting.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "hoisting.simse")
                                      : resError<ast::Module>("scan failed");
        bool clean = false;
        if (parsed.isOk()) {
            clean = sema::analyze(parsed.Value, "hoisting.simse").empty();
        }
        if (clean) {
            passed++;
            printf("PASS hoisting.simse (use before declaration is clean)\n");
        } else {
            failed++;
            printf("FAIL hoisting.simse: expected parse ok and zero sema diagnostics\n");
        }
    }

    // T9: the generic fixture must emit both distinct instantiations and no
    // unused one, and lower generic functions and built-in containers.
    {
        Str cpp = emitFixture(&scanner, fixturesDir, "emit_generics.simse");
        List<Str> pairs = distinctForms(cpp, "Pair<");
        bool ok = !cpp.empty()
                  && pairs.size() == 2
                  && pairs[0] != pairs[1]
                  && cpp.find("Pair<Int, Bool>") != Str::npos
                  && cpp.find("Pair<Str, Int>") != Str::npos
                  && cpp.find("identity<Int>") != Str::npos
                  && cpp.find("List<Int>") != Str::npos
                  && cpp.find("SmallVector<Int, 4>") != Str::npos;
        if (ok) {
            passed++;
            printf("PASS emit_generics.simse (templates: two instantiations, no unused)\n");
        } else {
            failed++;
            printf("FAIL emit_generics.simse: generic instantiation assertions failed\n");
        }
    }

    // T10: the native fixture must declare the symbol once, emit no body, and
    // call the symbol directly.
    {
        Str cpp = emitFixture(&scanner, fixturesDir, "native_readfile.simse");
        bool ok = !cpp.empty()
                  && cpp.find("Str simse_native_readFile(const Str& path);") != Str::npos
                  && cpp.find("simse_native_readFile(\"tests/fixtures/native_data.txt\")") != Str::npos;
        if (ok) {
            passed++;
            printf("PASS native_readfile.simse (native symbol declared and called)\n");
        } else {
            failed++;
            printf("FAIL native_readfile.simse: native emission assertions failed\n");
        }
    }

    // T12: container methods lower to the native extension symbols, receiver
    // first, and are not emitted as written.
    {
        Str cpp = emitFixture(&scanner, fixturesDir, "emit_containers.simse");
        bool ok = !cpp.empty()
                  && cpp.find("simse_list_append(") != Str::npos
                  && cpp.find("simse_list_removeAt(") != Str::npos
                  && cpp.find("simse_list_removeRange(") != Str::npos
                  && cpp.find(".append(") == Str::npos
                  && cpp.find(".removeAt(") == Str::npos
                  && cpp.find(".removeRange(") == Str::npos;
        if (ok) {
            passed++;
            printf("PASS emit_containers.simse (List methods lower to simse_list_*)\n");
        } else {
            failed++;
            printf("FAIL emit_containers.simse: container method emission assertions failed\n");
        }
    }

    // Real sources: every mirror under cppsrc, plus a root main.simse when one
    // is present, must parse and analyze cleanly.
    List<Str> sources = filesInDir(Str(SIMSE_SOURCE_ROOT) + "/cppsrc", ".simse");
    Str rootMain = Str(SIMSE_SOURCE_ROOT) + "/main.simse";
    if (std::filesystem::exists(rootMain)) {
        sources.push_back(rootMain);
    }
    for (const Str &source: sources) {
        Str name = baseName(source);
        ScanResult scan = scanFile(&scanner, source);
        if (!scan.ok) {
            failed++;
            printf("FAIL source %s: scan error: %s\n", name.c_str(), scan.errorMessage.c_str());
            continue;
        }
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = parser::parseModule(tokens, name);
        if (!parsed.isOk()) {
            failed++;
            printf("FAIL source %s: %s\n", name.c_str(), parsed.Error.c_str());
            continue;
        }
        List<Str> diagnostics = sema::analyze(parsed.Value, name);
        if (!diagnostics.empty()) {
            failed++;
            printf("FAIL source %s: sema reported %d diagnostic(s)\n",
                   name.c_str(), (int) diagnostics.size());
            for (const Str &diagnostic: diagnostics) {
                printf("    %s\n", diagnostic.c_str());
            }
            continue;
        }
        passed++;
        printf("PASS source %s\n", name.c_str());
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
