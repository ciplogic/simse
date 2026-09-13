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
        return common::toPath(path).filename().string();
    }

    Str readFileText(const Str &path) {
        return common::readFile(path);
    }

    bool writeFileText(const Str &path, const Str &text) {
        std::filesystem::path p(common::toPath(path));
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
        return (common::toPath(goldenDir) / common::toPath(name + "." + category + ".expected")).string();
    }

    // A separator-normalized key for a path, so paths built with mixed `/` and `\`
    // compare equal on Windows.
    Str pathKey(const Str &path) {
        std::error_code ec;
        std::filesystem::path canonical =
            std::filesystem::weakly_canonical(common::toPath(path), ec);
        return ec ? path : canonical.string();
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

    // The `src` directory of one stress project (`stress/<study>/src`), which is
    // where the end-to-end programs live since the stress harness took over the
    // round trip (stress/README.md). A few assertions below still pin what those
    // programs compile to; what they print is the harness's job.
    Str stressSource(const Str &fixturesDir, const Str &study) {
        return (common::toPath(fixturesDir).parent_path().parent_path() / "stress" / common::toPath(study) / "src").string();
    }

    // Analyzes one module with no prelude and no imports (used by the negative
    // fixture checks, which only assert on the fixture's own diagnostics).
    List<Str> analyzeOne(const ast::Module &module, const Str &name) {
        List<sema::Input> inputs;
        sema::Input input;
        input.fileName = name;
        input.module = &module;
        inputs.push_back(input);
        return sema::analyze(inputs);
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
        if (!std::filesystem::exists(common::toPath(goldenPath))) {
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

    Str goldenDir = (common::toPath(fixturesDir).parent_path() / "golden").string();

    List<TokenMatcher> *rules = getTokenRules();
    Scanner scanner(rules);

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
        Str path = (common::toPath(fixturesDir) / "parse_error.simse").string();
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
        Str path = (common::toPath(fixturesDir) / "sema_unknown_type.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "sema_unknown_type.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = analyzeOne(parsed.Value, "sema_unknown_type.simse");
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
        Str path = (common::toPath(fixturesDir) / "ctor_arity.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "ctor_arity.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = analyzeOne(parsed.Value, "ctor_arity.simse");
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
        Str path = (common::toPath(fixturesDir) / "sema_switch_label.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "sema_switch_label.simse")
                                      : resError<ast::Module>("scan failed");
        bool reported = false;
        if (parsed.isOk()) {
            List<Str> diagnostics = analyzeOne(parsed.Value, "sema_switch_label.simse");
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
        Str path = (common::toPath(fixturesDir) / "hoisting.simse").string();
        ScanResult scan = scanFile(&scanner, path);
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = scan.ok
                                      ? parser::parseModule(tokens, "hoisting.simse")
                                      : resError<ast::Module>("scan failed");
        bool clean = false;
        if (parsed.isOk()) {
            clean = analyzeOne(parsed.Value, "hoisting.simse").empty();
        }
        if (clean) {
            passed++;
            printf("PASS hoisting.simse (use before declaration is clean)\n");
        } else {
            failed++;
            printf("FAIL hoisting.simse: expected parse ok and zero sema diagnostics\n");
        }
    }

    // Modules and packages: two files that declare the same package and define
    // the same top-level name are a duplicate-definition error, and an import of
    // a package no participating file declares is an error. The per-file fixture
    // harness cannot exercise cross-file behavior, so this builds the two modules
    // directly.
    {
        ast::Module first;
        first.pos = SourcePos{0, 1, 1};
        first.package.push_back("shared");
        auto widget = std::make_shared<ast::Decl>();
        widget->kind = ast::DeclKind::DataClass;
        widget->name = "Widget";
        widget->pos = SourcePos{0, 1, 1};
        first.declarations.push_back(widget);

        ast::Module second;
        second.pos = SourcePos{0, 1, 1};
        second.package.push_back("shared");
        auto widgetAgain = std::make_shared<ast::Decl>();
        widgetAgain->kind = ast::DeclKind::DataClass;
        widgetAgain->name = "Widget";
        widgetAgain->pos = SourcePos{0, 3, 1};
        second.declarations.push_back(widgetAgain);
        ast::Import missing;
        missing.pos = SourcePos{0, 2, 1};
        missing.path.push_back("missing");
        second.imports.push_back(missing);

        List<sema::Input> inputs;
        sema::Input firstInput;
        firstInput.fileName = "first.simse";
        firstInput.module = &first;
        inputs.push_back(firstInput);
        sema::Input secondInput;
        secondInput.fileName = "second.simse";
        secondInput.module = &second;
        inputs.push_back(secondInput);

        List<Str> diagnostics = sema::analyze(inputs);
        bool duplicate = false;
        bool unresolved = false;
        for (const Str &diagnostic: diagnostics) {
            if (diagnostic.find("duplicate declaration 'Widget'") != Str::npos) duplicate = true;
            if (diagnostic.find("cannot resolve import 'missing'") != Str::npos) unresolved = true;
        }
        if (duplicate && unresolved) {
            passed++;
            printf("PASS packages (cross-file duplicate and unresolved import reported)\n");
        } else {
            failed++;
            printf("FAIL packages: duplicate=%d unresolved=%d\n",
                   duplicate ? 1 : 0, unresolved ? 1 : 0);
        }
    }

    // T9: the generic program must emit both distinct instantiations and no
    // unused one, and lower generic functions and built-in containers.
    {
        Str cpp = emitFixture(&scanner, stressSource(fixturesDir, "generics"), "main.simse");
        // The generic definition and its `_make_` factory emit the parameterized
        // form `Pair<A, B>`; exclude it so only actual instantiations remain.
        List<Str> pairs;
        for (const Str &form: distinctForms(cpp, "Pair<")) {
            if (form != "Pair<A, B>") pairs.push_back(form);
        }
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
            printf("PASS stress/generics (templates: two instantiations, no unused)\n");
        } else {
            failed++;
            printf("FAIL stress/generics: generic instantiation assertions failed\n");
        }
    }

    // T10: the native program must declare the symbol once, emit no body, and
    // call the symbol directly.
    {
        Str cpp = emitFixture(&scanner, stressSource(fixturesDir, "native-read-file"), "main.simse");
        bool ok = !cpp.empty()
                  && cpp.find("Str simse_native_readFile(const Str& path);") != Str::npos
                  && cpp.find("simse_native_readFile(\"stress/native-read-file/native_data.txt\")") != Str::npos;
        if (ok) {
            passed++;
            printf("PASS stress/native-read-file (native symbol declared and called)\n");
        } else {
            failed++;
            printf("FAIL stress/native-read-file: native emission assertions failed\n");
        }
    }

    // T12: container methods lower to the native extension symbols, receiver
    // first, and are not emitted as written.
    {
        Str cpp = emitFixture(&scanner, stressSource(fixturesDir, "containers"), "main.simse");
        bool ok = !cpp.empty()
                  && cpp.find("simse_list_append(") != Str::npos
                  && cpp.find("simse_list_removeAt(") != Str::npos
                  && cpp.find("simse_list_removeRange(") != Str::npos
                  && cpp.find(".append(") == Str::npos
                  && cpp.find(".removeAt(") == Str::npos
                  && cpp.find(".removeRange(") == Str::npos;
        if (ok) {
            passed++;
            printf("PASS stress/containers (List methods lower to simse_list_*)\n");
        } else {
            failed++;
            printf("FAIL stress/containers: container method emission assertions failed\n");
        }
    }

    // Real sources: every mirror under cppsrc, plus a root main.simse when one
    // is present, must parse and analyze cleanly. The analysis is the real
    // compilation-wide one: the prelude plus every source file, so cross-package
    // imports resolve by package and duplicate definitions across files of one
    // package are caught.
    List<Str> sources = filesInDir(Str(SIMSE_SOURCE_ROOT) + "/cppsrc", ".simse");
    Str rootMain = Str(SIMSE_SOURCE_ROOT) + "/main.simse";
    if (std::filesystem::exists(common::toPath(rootMain))) {
        sources.push_back(rootMain);
    }
    List<Str> preludeFiles = filesInDir(Str(SIMSE_DEFAULT_PRELUDE), ".simse");
    Str preludeDirKey = pathKey(Str(SIMSE_DEFAULT_PRELUDE));

    List<Str> sourceNames;
    List<Str> compiledNames;
    List<ast::Module> sourceModules;
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
        sourceNames.push_back(name);

        // Prelude files are already part of the compilation as prelude inputs (so
        // their declared package `rtl` is implicit); do not add them again, or
        // every prelude declaration would look like a duplicate.
        bool isPreludeFile = false;
        for (const Str &preludeFile: preludeFiles) {
            if (pathKey(preludeFile) == pathKey(source)) {
                isPreludeFile = true;
            }
        }
        if (!isPreludeFile
            && pathKey(common::toPath(source).parent_path().string()) == preludeDirKey) {
            isPreludeFile = true;
        }
        if (!isPreludeFile) {
            compiledNames.push_back(name);
            sourceModules.push_back(parsed.Value);
        }
    }

    List<sema::Input> compilation = preludeInputs();
    for (int i = 0; i < (int) sourceModules.size(); i++) {
        sema::Input input;
        input.fileName = compiledNames[i];
        input.module = &sourceModules[i];
        compilation.push_back(input);
    }
    List<Str> sourceDiagnostics = sema::analyze(compilation);
    if (!sourceDiagnostics.empty()) {
        failed += (int) sourceNames.size();
        printf("FAIL source compilation: sema reported %d diagnostic(s)\n",
               (int) sourceDiagnostics.size());
        for (const Str &diagnostic: sourceDiagnostics) {
            printf("    %s\n", diagnostic.c_str());
        }
    } else {
        for (const Str &name: sourceNames) {
            passed++;
            printf("PASS source %s\n", name.c_str());
        }
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
