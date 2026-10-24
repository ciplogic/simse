// Iterate.cs - the one-command iteration loop, the C# twin of tools/iterate.js.
//
//   dotnet run --project tools/iterate -- [--full] [--filter <text>] [--jobs <n>|--serial] [--quiet]
//
// or, published once as a native binary (NativeAOT - no runtime to spin up):
//
//   dotnet publish tools/iterate -c Release -r win-arm64 -p:PublishAot=true
//   tools/iterate/bin/Release/net8.0/win-arm64/publish/simse-iterate.exe [--full]
//
// Why a C# twin: the corpus step runs every case through a pool of Tasks, 16 at a time by
// default (`--serial` is the old one-at-a-time). The JS pool overlapped spawns through
// bun's libuv, which fails at random on some machines (`uv_spawn EUNKNOWN`, a different
// process each run); .NET's Process under Tasks has no such bug. The corpus itself is
// tools/stress.js's semantics, ported case for case (same `expected.*` contract, same
// `.work` layout, same output lines), and the fast loop's bootstrap check is native too -
// the JS one copied its refresh through a `copyFileSync` that hits the same libuv flake.
// The build, refresh and full fixed-point steps still spawn the same bun tools (build.js,
// tools/bootstrap.js) one at a time, sequential and single - the shape that always worked -
// so all the build logic stays in one place.
//
// Fast (the default) is the loop for a change whose effect you want to see:
//
//   1. build      the fast compiler (bun build.js --fast - cl /O1)
//   2. stress     the whole corpus, 16 cases at a time
//   3. bootstrap  one transpile (native): is the published file still what the tree emits?
//                 when the emission moved, the file is refreshed and the line says so
//
// `--full` is the commit loop: the release build, the corpus, the bootstrap refresh
// (bun build.js --bootstrap) and the two-way fixed point (bun tools/bootstrap.js).
//
// Every spawned step's output is captured: a passing run prints one line per step and the
// total, and a failing step's whole output. Corpus options are stress.js's: --simse,
// --release, --define, --arch, --update, --keep-going, --list.

using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace SimseIterate;

public static class Program
{
    static readonly string Repo = FindRepo();
    static readonly string Stress = Path.Combine(Repo, "stress");

    // cl.exe builds the compiler and compiles every case on Windows; on Linux the same
    // shape runs with g++ (or clang++) and the sibling shim include. The bun build steps
    // stay Windows-only: build.js sources vcvars.
    static readonly bool OnWindows = RuntimeInformation.IsOSPlatform(OSPlatform.Windows);

    // tools/roots.mjs's list, spelled as the command line: what the compiler is built from.
    static readonly string[] CompilerRootArgs =
    {
        "--root", "src",
        "--root", "modules/compiler",
        "--root", "modules/gzip",
        "--root", "modules/http",
        "--root", "modules/io",
        "--root", "modules/json",
        "--root", "modules/jsonnode",
        "--root", "modules/linq",
        "--root", "modules/process",
        "--root", "modules/streams",
        "--root", "modules/unsafe",
        "--root", "modules/xml",
        "--root", "modules/zip",
    };

    static int Fail(string message)
    {
        Console.Error.WriteLine($"iterate: {message}");
        return 1;
    }

    static string FindRepo()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "build.js")))
        {
            dir = dir.Parent;
        }
        if (dir is null)
        {
            throw new InvalidOperationException(
                $"cannot find the repository root (no build.js above {AppContext.BaseDirectory})");
        }
        return dir.FullName;
    }

    // --- options ------------------------------------------------------------

    sealed class Options
    {
        public bool Full;
        public readonly List<string> Filters = new();
        public int Jobs = 16;
        public string? Simse;
        public bool Release;
        public readonly List<string> Defines = new();
        public string? Arch;
        public bool Quiet;
        public bool Update;
        public bool List;
        public bool KeepGoing;
        public bool Verbose;
    }

    static Options ParseArgs(string[] argv)
    {
        var opts = new Options();
        string Value(int i)
        {
            if (i + 1 >= argv.Length) throw new InvalidOperationException($"missing value for '{argv[i]}'");
            return argv[i + 1];
        }
        for (var i = 0; i < argv.Length; i++)
        {
            switch (argv[i])
            {
                case "--full": opts.Full = true; break;
                case "--filter": opts.Filters.Add(Value(i)); i++; break;
                case "--jobs": opts.Jobs = int.Parse(Value(i)); i++; break;
                case "--serial": opts.Jobs = 1; break;
                case "--simse": opts.Simse = Value(i); i++; break;
                case "--release": opts.Release = true; break;
                case "--define": opts.Defines.Add(Value(i)); i++; break;
                case "--arch": opts.Arch = Value(i); i++; break;
                case "--quiet": opts.Quiet = true; break;
                case "--update": opts.Update = true; break;
                case "--list": opts.List = true; break;
                case "--keep-going": opts.KeepGoing = true; break;
                case "--verbose": opts.Verbose = true; break;
                case "-h": case "--help": Usage(); Environment.Exit(0); break;
                default: throw new InvalidOperationException($"unknown option '{argv[i]}' (try --help)");
            }
        }
        if (opts.Jobs < 1) throw new InvalidOperationException("--jobs wants a count of 1 or more");
        return opts;
    }

    static void Usage()
    {
        Console.WriteLine("""
            usage: dotnet run --project tools/iterate -- [options]

              --full            the commit loop: release build + corpus + bootstrap refresh + fixed point
                                (default: the fast loop - debug build + corpus + bootstrap in-sync check)
              --filter <text>   only stress cases whose path contains <text> (repeatable)
              --jobs <n>        stress cases to run at once (default: 16)
              --serial          the corpus one case at a time (same as --jobs 1)
              --simse <exe>     compiler to test (default: ./simse.exe)
              --release         compile the generated programs with /O2 /Ob3 (default: /MDd)
              --define <d[=v]>  extra preprocessor define for the compile (repeatable)
              --arch <arch>     vcvarsall target architecture (default: the host's)
              --update          rewrite expected.stdout/.stderr/.exit from this run
              --list            list the cases and their expectations, then exit
              --keep-going      report every corpus failure instead of stopping at the first
              --quiet           only the final line
              -h, --help        this text
            """);
    }

    // --- processes ----------------------------------------------------------

    sealed record ProcResult(int Exit, string Stdout, string Stderr, double Ms);

    // cl.exe is usually NOT on the ambient PATH (that is what vcvars is for), and
    // Process.Start resolves the file against the *current* PATH, not the environment the
    // child is given - so every spawned tool is resolved through the environment it runs in.
    static string Which(string name, Dictionary<string, string>? env = null)
    {
        var pathValue = env is not null && env.TryGetValue("PATH", out var fromEnv)
            ? fromEnv
            : Environment.GetEnvironmentVariable("PATH");
        if (pathValue is not null)
        {
            foreach (var dir in pathValue.Split(Path.PathSeparator))
            {
                if (dir.Length == 0) continue;
                try
                {
                    var candidate = Path.Combine(dir.Trim(), name + (OnWindows ? ".exe" : ""));
                    if (File.Exists(candidate)) return candidate;
                }
                catch (ArgumentException)
                {
                    // a malformed PATH entry is skipped, as the shell's would be
                }
            }
        }
        return name;
    }

    static async Task<ProcResult> RunProcess(
        IReadOnlyList<string> command, Dictionary<string, string>? env = null,
        string? stdin = null, string? cwd = null)
    {
        var psi = new ProcessStartInfo
        {
            FileName = command[0],
            WorkingDirectory = cwd ?? Repo,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = stdin is not null,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        for (var i = 1; i < command.Count; i++) psi.ArgumentList.Add(command[i]);
        if (env is not null)
        {
            foreach (var (name, value) in env) psi.EnvironmentVariables[name] = value;
        }
        var watch = Stopwatch.StartNew();
        using var proc = Process.Start(psi)
            ?? throw new InvalidOperationException($"could not spawn '{command[0]}'");
        if (stdin is not null)
        {
            await proc.StandardInput.WriteAsync(stdin);
            await proc.StandardInput.FlushAsync();
            proc.StandardInput.Close();
        }
        var stdoutTask = proc.StandardOutput.ReadToEndAsync();
        var stderrTask = proc.StandardError.ReadToEndAsync();
        await Task.WhenAll(stdoutTask, stderrTask);
        await proc.WaitForExitAsync();
        return new ProcResult(proc.ExitCode, stdoutTask.Result, stderrTask.Result,
            watch.Elapsed.TotalMilliseconds);
    }

    // --- the MSVC developer environment (tools/msvc.mjs's) -------------------

    static string HostArch()
    {
        return RuntimeInformation.ProcessArchitecture switch
        {
            Architecture.Arm64 => "arm64",
            Architecture.X64 => "x64",
            _ => "x86",
        };
    }

    static string NormalizeArch(string value)
    {
        var lower = value.ToLowerInvariant();
        return lower == "amd64" ? "x64" : lower;
    }

    static string? FindVcvars()
    {
        var programFilesX86 = Environment.GetEnvironmentVariable("ProgramFiles(x86)")
            ?? @"C:\Program Files (x86)";
        var vswhere = Path.Combine(programFilesX86, "Microsoft Visual Studio", "Installer", "vswhere.exe");
        if (File.Exists(vswhere))
        {
            var query = RunProcessSync(new[] { vswhere, "-latest", "-products", "*", "-property", "installationPath" });
            var vsPath = query.Stdout.Trim();
            if (query.Exit == 0 && vsPath.Length > 0)
            {
                var candidate = Path.Combine(vsPath, "VC", "Auxiliary", "Build", "vcvarsall.bat");
                if (File.Exists(candidate)) return candidate;
            }
        }
        const string fallback =
            @"C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat";
        return File.Exists(fallback) ? fallback : null;
    }

    static ProcResult RunProcessSync(IReadOnlyList<string> command)
    {
        var psi = new ProcessStartInfo
        {
            FileName = command[0],
            WorkingDirectory = Repo,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        for (var i = 1; i < command.Count; i++) psi.ArgumentList.Add(command[i]);
        using var proc = Process.Start(psi)
            ?? throw new InvalidOperationException($"could not spawn '{command[0]}'");
        var stdout = proc.StandardOutput.ReadToEnd();
        var stderr = proc.StandardError.ReadToEnd();
        proc.WaitForExit();
        return new ProcResult(proc.ExitCode, stdout, stderr, 0);
    }

    // The Visual Studio developer environment for `arch`: vcvarsall, sourced in a cmd child,
    // its variables read back with `set` (the same materialization msvc.mjs does).
    static Dictionary<string, string> DeveloperEnv(string arch)
    {
        var vcvars = FindVcvars();
        if (vcvars is null)
        {
            throw new InvalidOperationException(
                "cannot locate Visual Studio and no cl.exe on PATH (stress step)");
        }
        var script = Path.Combine(Path.GetTempPath(), $"simse-vcenv-{Environment.ProcessId}.bat");
        File.WriteAllText(script, "@echo off\r\ncall \"" + vcvars + "\" " + arch + " >nul\r\nset\r\n");
        try
        {
            var sourced = RunProcessSync(new[] { "cmd.exe", "/d", "/c", script });
            if (sourced.Exit != 0)
            {
                throw new InvalidOperationException($"vcvarsall {arch} failed:\n{sourced.Stderr}");
            }
            var env = new Dictionary<string, string>(Environment.GetEnvironmentVariables()
                .Cast<System.Collections.DictionaryEntry>()
                .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? ""),
                StringComparer.OrdinalIgnoreCase);
            foreach (var rawLine in sourced.Stdout.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None))
            {
                var eq = rawLine.IndexOf('=');
                if (eq > 0) env[rawLine[..eq]] = rawLine[(eq + 1)..];
            }
            return env;
        }
        finally
        {
            File.Delete(script);
        }
    }

    // --- the corpus (tools/stress.js's semantics) ----------------------------

    sealed record CaseStudy(string Name, string Dir);

    static List<CaseStudy> FindCases(List<string> filters)
    {
        if (!Directory.Exists(Stress)) throw new InvalidOperationException("no stress directory");
        var cases = new List<CaseStudy>();
        foreach (var dir in Directory.GetDirectories(Stress))
        {
            var name = Path.GetFileName(dir);
            if (name.StartsWith('.')) continue;
            if (!Directory.Exists(Path.Combine(dir, "src"))) continue;
            if (filters.Count > 0 && !filters.Any(filter => name.Contains(filter))) continue;
            cases.Add(new CaseStudy(name, dir));
        }
        cases.Sort((a, b) => string.CompareOrdinal(a.Name, b.Name));
        return cases;
    }

    static string? ReadIfPresent(string file)
    {
        return File.Exists(file) ? File.ReadAllText(file) : null;
    }

    // Text comparison ignores the platform's line endings: the same program prints whatever
    // its own newline convention is, and a golden must not care.
    static string Normalize(string text) => text.Replace("\r\n", "\n");

    static string Quote(string? line) =>
        "\"" + (line ?? "<no line>").Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    static string FirstDifference(string actual, string expected)
    {
        var mine = Normalize(actual).Split('\n');
        var theirs = Normalize(expected).Split('\n');
        var count = Math.Max(mine.Length, theirs.Length);
        for (var i = 0; i < count; i++)
        {
            var mineLine = i < mine.Length ? mine[i] : null;
            var theirsLine = i < theirs.Length ? theirs[i] : null;
            if (mineLine != theirsLine)
            {
                return $"line {i + 1}\n  expected: {Quote(theirsLine)}\n  actual:   {Quote(mineLine)}";
            }
        }
        return "identical";
    }

    sealed record CaseResult(string Name, bool Ok, string Detail);

    sealed class CorpusBuild
    {
        public required string Simse;
        public required Dictionary<string, string> Env;
        public required string Cl;
        public required List<string> Flags;
        public required Options Opts;
        public required string Work;
    }

    // One case: transpile, compile, run, compare - runCase in tools/stress.js.
    static async Task<CaseResult> RunCase(CorpusBuild build, CaseStudy study)
    {
        var (name, dir) = study;
        var work = Path.Combine(build.Work, name);
        try
        {
            if (Directory.Exists(work)) Directory.Delete(work, recursive: true);
            Directory.CreateDirectory(work);
        }
        catch (IOException e)
        {
            return new CaseResult(name, false, $"harness error: {e.Message}");
        }

        var expectedCpp = ReadIfPresent(Path.Combine(dir, "expected.cpp.out"));
        var expectedTranspileError = ReadIfPresent(Path.Combine(dir, "expected.transpile-error"));
        var expectedOut = ReadIfPresent(Path.Combine(dir, "expected.stdout"));
        var expectedErr = ReadIfPresent(Path.Combine(dir, "expected.stderr"));
        var expectedExit = ReadIfPresent(Path.Combine(dir, "expected.exit"));
        var args = SplitArgs(ReadIfPresent(Path.Combine(dir, "args")));
        var compilerArgs = SplitArgs(ReadIfPresent(Path.Combine(dir, "compiler-args")));
        var stdin = ReadIfPresent(Path.Combine(dir, "stdin"));

        var outCpp = Path.Combine(work, "out.cpp");
        var transpileCommand = new List<string>
        {
            build.Simse, "--root", Path.Join("stress", name, "src"), "-o", outCpp,
        };
        transpileCommand.AddRange(compilerArgs);
        var transpile = await RunProcess(transpileCommand, build.Env);
        var verbose = build.Opts.Verbose ? $"\n{transpile.Stdout}{transpile.Stderr}" : "";

        if (expectedTranspileError is not null)
        {
            var wanted = Normalize(expectedTranspileError).Trim();
            if (transpile.Exit == 0)
            {
                return new CaseResult(name, false,
                    $"expected the transpile to fail with '{wanted}', but it succeeded{verbose}");
            }
            if (!Normalize(transpile.Stderr).Contains(wanted))
            {
                return new CaseResult(name, false,
                    $"transpile diagnostic mismatch\n  expected: {Quote(wanted)}\n" +
                    $"  actual:   {Quote(Normalize(transpile.Stderr).Trim())}");
            }
            if (build.Opts.Update)
            {
                var stdoutFile = Path.Combine(dir, "expected.stdout");
                if (File.Exists(stdoutFile)) File.Delete(stdoutFile);
            }
            return new CaseResult(name, Ok: true, Detail: $"transpile rejected as expected ({transpile.Ms:F0} ms)");
        }

        if (transpile.Exit != 0)
        {
            return new CaseResult(name, false,
                $"transpile failed (exit {transpile.Exit})\n{transpile.Stderr}{verbose}");
        }
        if (expectedCpp is not null && Normalize(ReadIfPresent(outCpp) ?? "") != Normalize(expectedCpp))
        {
            return new CaseResult(name, false,
                $"the emitted C++ differs from expected.cpp.out\n{FirstDifference(ReadIfPresent(outCpp) ?? "", expectedCpp)}");
        }

        var exe = Path.Combine(work, OnWindows ? "prog.exe" : "prog");
        List<string> compileCommand;
        if (OnWindows)
        {
            compileCommand = new List<string>
            {
                build.Cl, "/nologo", "/std:c++20", "/EHsc", "/W3",
            };
            compileCommand.AddRange(build.Flags);
            compileCommand.Add($"/I{Repo}");
            compileCommand.Add($"/Fo{work}{Path.DirectorySeparatorChar}");
            compileCommand.Add($"/Fe{exe}");
            compileCommand.Add(outCpp);
        }
        else
        {
            compileCommand = new List<string> { build.Cl, "-std=c++20" };
            compileCommand.AddRange(build.Flags);
            compileCommand.Add($"-I{Repo}");
            // The sibling checkout's shim satisfies the amalgamation's unreachable Win32
            // includes (docs/building-on-linux.md); a program's own C++ rarely needs it,
            // and a missing directory is no loss.
            var shim = Path.GetFullPath(Path.Combine(Repo, "..", "simse-linux-gcc", "shim"));
            if (Directory.Exists(shim)) compileCommand.Add($"-I{shim}");
            compileCommand.Add("-o");
            compileCommand.Add(exe);
            compileCommand.Add(outCpp);
        }
        if (build.Opts.Verbose) Console.WriteLine("  " + string.Join(" ", compileCommand));
        var compiled = await RunProcess(compileCommand, build.Env);
        if (compiled.Exit != 0)
        {
            var log = string.Join("\n", (compiled.Stdout + compiled.Stderr)
                .Split('\n').Take(40));
            return new CaseResult(name, false,
                $"the C++ compile failed\n{log}");
        }

        var runCommand = new List<string> { exe };
        runCommand.AddRange(args);
        var program = await RunProcess(runCommand, build.Env, stdin);
        var actualOut = Normalize(program.Stdout);
        var actualErr = Normalize(program.Stderr);

        // A case without an expectation is not a passing case: it is an unfinished one.
        // `--update` is how a new case gets its expectations, from a run whose output has
        // been read.
        if (expectedOut is null)
        {
            if (build.Opts.Update)
            {
                WriteExpectations(dir, actualOut, actualErr, program.Exit);
                return new CaseResult(name, Ok: true,
                    Detail: $"expected.stdout written from this run ({actualOut.Length} bytes)");
            }
            return new CaseResult(name, false,
                "no expected.stdout; read this program's output, then capture it with --update");
        }

        var expectedCode = expectedExit is null ? 0 : int.Parse(Normalize(expectedExit).Trim());
        var ok = actualOut == Normalize(expectedOut)
            && (expectedErr is null || actualErr == Normalize(expectedErr))
            && program.Exit == expectedCode;
        if (ok)
        {
            return new CaseResult(name, Ok: true, Detail: $"{program.Ms:F0} ms, {actualOut.Length} bytes of stdout");
        }

        if (build.Opts.Update)
        {
            WriteExpectations(dir, actualOut, actualErr, program.Exit);
            return new CaseResult(name, Ok: true, Detail: "expected.* rewritten from this run");
        }

        var problems = new List<string>();
        if (actualOut != Normalize(expectedOut))
        {
            problems.Add($"stdout differs\n{FirstDifference(actualOut, expectedOut)}");
        }
        if (expectedErr is not null && actualErr != Normalize(expectedErr))
        {
            problems.Add($"stderr differs\n{FirstDifference(actualErr, expectedErr)}");
        }
        if (program.Exit != expectedCode)
        {
            problems.Add($"exit code {program.Exit}, expected {expectedCode}");
        }
        return new CaseResult(name, Ok: false, Detail: string.Join("\n", problems));
    }

    static List<string> SplitArgs(string? text)
    {
        if (text is null) return new List<string>();
        return text.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).ToList();
    }

    static void WriteExpectations(string dir, string stdoutText, string stderrText, int exitCode)
    {
        File.WriteAllText(Path.Combine(dir, "expected.stdout"), stdoutText);
        if (stderrText.Length > 0)
        {
            File.WriteAllText(Path.Combine(dir, "expected.stderr"), stderrText);
        }
        if (exitCode != 0)
        {
            File.WriteAllText(Path.Combine(dir, "expected.exit"), $"{exitCode}\n");
        }
    }

    // One case, steadily: a *harness* error - the case never reached a comparison - is
    // retried once, because a validation harness must not fail on its own plumbing. A
    // comparison failure is never retried: that result is the program's.
    static async Task<CaseResult> RunCaseSteady(CorpusBuild build, CaseStudy study)
    {
        for (var attempt = 0; ; attempt++)
        {
            CaseResult result;
            try
            {
                result = await RunCase(build, study);
            }
            catch (Exception e)
            {
                result = new CaseResult(study.Name, false, $"harness error: {e.Message}");
            }
            if (result.Ok || attempt > 0 || !result.Detail.StartsWith("harness error:")) return result;
        }
    }

    static string Describe(string caseDir)
    {
        var marks = new List<string>();
        if (File.Exists(Path.Combine(caseDir, "expected.transpile-error"))) marks.Add("transpile-error");
        if (File.Exists(Path.Combine(caseDir, "expected.cpp.out"))) marks.Add("cpp-golden");
        if (File.Exists(Path.Combine(caseDir, "args"))) marks.Add("args");
        if (File.Exists(Path.Combine(caseDir, "compiler-args"))) marks.Add("compiler-args");
        if (File.Exists(Path.Combine(caseDir, "stdin"))) marks.Add("stdin");
        if (File.Exists(Path.Combine(caseDir, "expected.exit"))) marks.Add("exit-code");
        if (File.Exists(Path.Combine(caseDir, "expected.stderr"))) marks.Add("stderr");
        return marks.Count > 0 ? $" [{string.Join(", ", marks)}]" : "";
    }

    // The corpus step: the pool. Tasks run `jobs` cases at once; a failure stops the
    // schedule (cases in flight finish, never-started ones report as skipped).
    static async Task<int> RunCorpus(CorpusBuild build, List<CaseStudy> cases)
    {
        var results = new CaseResult?[cases.Count];
        var stopped = false;
        using var cancel = new CancellationTokenSource();
        var atATime = build.Opts.Jobs > 1 ? $", {build.Opts.Jobs} at a time" : "";
        Console.WriteLine(
            $"stress: {Path.GetRelativePath(Repo, build.Simse)} ({(build.Opts.Release ? "release" : "debug")} programs, " +
            $"{cases.Count} cases{atATime})");
        var started = Stopwatch.StartNew();
        try
        {
            await Parallel.ForEachAsync(
                cases.Select((study, index) => (study, index)),
                new ParallelOptions
                {
                    MaxDegreeOfParallelism = build.Opts.Jobs,
                    CancellationToken = cancel.Token,
                },
                async (item, token) =>
                {
                    var result = await RunCaseSteady(build, item.study);
                    results[item.index] = result;
                    if (!result.Ok && !build.Opts.KeepGoing)
                    {
                        stopped = true;
                        cancel.Cancel();
                    }
                });
        }
        catch (OperationCanceledException) when (stopped)
        {
            // the expected stop-at-first-failure; results already in flight are kept
        }

        // Everything above ran overlapped and out of order; report in case order, at the end.
        var passed = 0;
        var failed = 0;
        var skipped = 0;
        foreach (var result in results)
        {
            if (result is null)
            {
                skipped++;
                continue;
            }
            if (!result.Ok || !build.Opts.Quiet)
            {
                Console.WriteLine($"{(result.Ok ? "PASS" : "FAIL")} {result.Name}  ({result.Detail})");
            }
            if (result.Ok) passed++;
            else failed++;
        }
        if (skipped > 0)
        {
            Console.WriteLine($"({skipped} case{(skipped == 1 ? "" : "s")} not run" +
                (stopped && !build.Opts.KeepGoing ? ": stopped at the first failure" : "") + ")");
        }
        var seconds = started.Elapsed.TotalSeconds;
        Console.WriteLine(
            $"stress: {passed} passed, {failed} failed ({cases.Count} cases, " +
            $"{(seconds < 10 ? seconds.ToString("F1") : ((int)Math.Round(seconds)).ToString())}s)");
        return failed > 0 ? 1 : 0;
    }

    // --- the fast loop's bootstrap check, native (tools/bootstrap.js --quick --write) ---

    static string BootstrapFile()
    {
        var explicitFile = Environment.GetEnvironmentVariable("SIMSE_BOOTSTRAP");
        if (explicitFile is not null) return Path.GetFullPath(explicitFile);
        var checkout = Environment.GetEnvironmentVariable("SIMSE_BOOTSTRAP_REPO");
        var root = checkout is not null
            ? Path.GetFullPath(checkout)
            : Path.GetFullPath(Path.Combine(Repo, "..", "simse_bootstrap"));
        var published = Path.Combine(root, "builds", "simse_bootstrap.cpp");
        if (File.Exists(published)) return published;
        var legacy = Path.Combine(Repo, "src", "simse_bootstrap.cpp");
        if (checkout is null && File.Exists(legacy)) return legacy;
        throw new InvalidOperationException($"no published bootstrap at {published}");
    }

    static bool SameBytes(string left, string right)
    {
        var a = new FileInfo(left);
        var b = new FileInfo(right);
        if (!a.Exists || !b.Exists || a.Length != b.Length) return false;
        using var leftStream = File.OpenRead(left);
        using var rightStream = File.OpenRead(right);
        var leftBuffer = new byte[64 * 1024];
        var rightBuffer = new byte[64 * 1024];
        while (true)
        {
            var read = leftStream.Read(leftBuffer, 0, leftBuffer.Length);
            if (read == 0) return true;
            if (rightStream.Read(rightBuffer, 0, read) != read) return false;
            for (var i = 0; i < read; i++)
            {
                if (leftBuffer[i] != rightBuffer[i]) return false;
            }
        }
    }

    static async Task<int> BootstrapQuick(string simse)
    {
        var bootstrap = BootstrapFile();
        var label = Path.GetRelativePath(Repo, bootstrap);
        if (label.Length == 0) label = bootstrap;
        var work = Path.Combine(Repo, "build", "release", "bootstrap");
        Directory.CreateDirectory(work);
        var quickOut = Path.Combine(work, "working_out.cpp");
        var command = new List<string> { simse };
        command.AddRange(CompilerRootArgs);
        command.Add("-o");
        command.Add(quickOut);
        var watch = Stopwatch.StartNew();
        var run = await RunProcess(command);
        var seconds = (watch.Elapsed.TotalMilliseconds / 1000).ToString("F1");
        if (run.Exit != 0)
        {
            Console.WriteLine($"bootstrap: FAILED - {Path.GetRelativePath(Repo, simse)} could not transpile the compiler's roots");
            foreach (var line in (run.Stderr + run.Stdout).Split('\n').Take(6))
            {
                if (line.Trim().Length > 0) Console.WriteLine(line);
            }
            return 1;
        }
        if (SameBytes(bootstrap, quickOut))
        {
            Console.WriteLine($"bootstrap: in sync ({Path.GetRelativePath(Repo, simse)} reproduces {label}, {seconds}s)");
            return 0;
        }
        File.Copy(quickOut, bootstrap, overwrite: true);
        Console.WriteLine($"bootstrap: refreshed {label} (the emission moved, {seconds}s)");
        return 0;
    }

    // --- the loop -------------------------------------------------------------

    static string Seconds(double value) =>
        value < 10 ? value.ToString("F1") + "s" : ((int)Math.Round(value)).ToString() + "s";

    static string LastLine(string text)
    {
        var lines = text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries)
            .Where(line => line.Trim().Length > 0).ToList();
        return lines.Count > 0 ? lines[^1].Trim() : "";
    }

    static async Task<int> RunBunStep(string label, IReadOnlyList<string> args, bool quiet)
    {
        var bun = Which("bun");
        var command = new List<string> { bun };
        command.AddRange(args);
        var watch = Stopwatch.StartNew();
        var step = await RunProcess(command);
        if (step.Exit != 0)
        {
            Console.WriteLine($"iterate: {label} FAILED ({Seconds(step.Ms / 1000)}, exit {step.Exit})");
            var output = (step.Stdout + step.Stderr).Trim();
            if (output.Length > 0) Console.WriteLine(output);
            Console.WriteLine($"iterate: stopped at '{label}'; nothing after it ran");
            return 1;
        }
        if (!quiet)
        {
            Console.WriteLine($"iterate: {label} - {LastLine(step.Stdout)}");
        }
        return 0;
    }

    public static async Task<int> Main(string[] argv)
    {
        Options opts;
        try
        {
            opts = ParseArgs(argv);
        }
        catch (Exception e)
        {
            return Fail(e.Message);
        }

        var cases = FindCases(opts.Filters);
        if (cases.Count == 0)
        {
            return Fail("no cases matched" +
                (opts.Filters.Count > 0 ? $" (filters: {string.Join(", ", opts.Filters)})" : ""));
        }
        if (opts.List)
        {
            foreach (var study in cases) Console.WriteLine(study.Name + Describe(study.Dir));
            Console.WriteLine($"{cases.Count} cases");
            return 0;
        }

        var simse = opts.Simse is not null
            ? Path.GetFullPath(Path.Combine(Repo, opts.Simse))
            : Path.Combine(Repo, OnWindows ? "simse.exe" : "simse");
        if (!File.Exists(simse))
        {
            return Fail("no compiler to test: build one (docs/building-on-linux.md on Linux) " +
                "or point --simse at one");
        }

        Dictionary<string, string>? env;
        string cl;
        if (OnWindows)
        {
            try
            {
                env = DeveloperEnv(NormalizeArch(opts.Arch ?? HostArch()));
                cl = Which("cl", env);
            }
            catch (Exception e)
            {
                return Fail(e.Message);
            }
        }
        else
        {
            env = null;
            cl = Which("g++") ?? Which("clang++") ?? Which("clang++-19") ?? "";
            if (cl.Length == 0)
            {
                return Fail("no g++ or clang++ on PATH (the Linux corpus step compiles with one)");
            }
        }

        var flags = new List<string>();
        if (OnWindows)
        {
            if (opts.Release)
            {
                flags.AddRange(new[] { "/MD", "/O2", "/Ob3", "/DNDEBUG" });
            }
            else
            {
                flags.Add("/MDd");
            }
            foreach (var define in opts.Defines) flags.Add($"/D{define}");
        }
        else
        {
            flags.AddRange(opts.Release
                ? new[] { "-O2", "-DNDEBUG" }
                : new[] { "-O1", "-g", "-DNDEBUG" });
            foreach (var define in opts.Defines) flags.Add($"-D{define}");
        }

        var build = new CorpusBuild
        {
            Simse = simse,
            Env = env,
            Cl = cl,
            Flags = flags,
            Opts = opts,
            Work = Path.Combine(Stress, ".work"),
        };

        var totalWatch = Stopwatch.StartNew();
        // build step: the same bun tool, sequential and single - no pool involved. It
        // sources vcvars, so it is a Windows step; on Linux the compiler arrives built
        // (docs/building-on-linux.md) and --simse points here.
        if (OnWindows)
        {
            var buildStep = opts.Full
                ? new[] { "build.js", "--release", "--no-lto", "--quiet" }
                : new[] { "build.js", "--fast", "--quiet" };
            if (await RunBunStep("build", buildStep, opts.Quiet) != 0) return 1;
        }
        else if (!opts.Quiet)
        {
            Console.WriteLine("iterate: build skipped (build.js sources vcvars; testing the compiler as built)");
        }

        // stress step: native pool.
        if (await RunCorpus(build, cases) != 0)
        {
            Console.WriteLine("iterate: stopped at 'stress'; nothing after it ran");
            return 1;
        }

        if (opts.Full && OnWindows)
        {
            if (await RunBunStep("refresh",
                    new[] { "build.js", "--release", "--no-compile", "--bootstrap", "--quiet" },
                    opts.Quiet) != 0) return 1;
            if (await RunBunStep("bootstrap", new[] { "tools/bootstrap.js", "--fast" }, opts.Quiet) != 0)
            {
                return 1;
            }
        }
        else
        {
            // The native one-direction check runs everywhere; on Linux it is the whole
            // bootstrap step (the two-way check wants cl.exe to build the published file).
            var quick = await BootstrapQuick(simse);
            if (quick != 0)
            {
                Console.WriteLine("iterate: stopped at 'bootstrap'; nothing after it ran");
                return 1;
            }
        }

        Console.WriteLine(
            $"iterate: {(opts.Full ? "full" : "fast")} loop ok " +
            $"({(OnWindows ? (opts.Full ? 4 : 3) : 2)} steps, {Seconds(totalWatch.Elapsed.TotalSeconds)})");
        return 0;
    }
}
