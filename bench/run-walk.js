// run.js - the Goose-benchmarks comparison harness (bench/README.md).
//
// Builds every benchmark twice from the same algorithms - the C++ references in
// bench/cpp (aardappel/goose's own sources, taken verbatim) and the Simse
// implementations in bench/simse/<name>/src - at the goose suite's data sizes,
// checks that every row of a benchmark prints the same checksum, and times the
// processes. C++ and the Simse-generated C++ go through the same cl.exe and the
// same flags, so the comparison is the data structure and memory strategy, not
// the backend.
//
//   bun bench/run.js                       # medium size, 3 reps, ratio report
//   bun bench/run.js --sizes small,medium,large
//   bun bench/run.js --filter blur --reps 5
//
// Results land in bench/results.md; a checksum mismatch fails the benchmark's
// size (all rows must agree) and is reported, not timed.

import { readdirSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, copyFileSync } from "node:fs";
import * as path from "node:path";
import { developerEnv, fail as failTool, hostArch, whichCl } from "../tools/msvc.mjs";

const TOOL = "bench";
const fail = (message) => failTool(TOOL, message);
const REPO = path.resolve(import.meta.dir, "..");
const HERE = path.resolve(import.meta.dir);
const WORK = path.join(HERE, ".work");

// The suite: aardappel/goose's bench/run_bench.py rows, C++ reference variants
// and all. `sizes` is the goose suite's small/medium/large schedule; `param`
// names it in the report. `cpp` rows compile bench/cpp/<file> with
// -DVARIANT=<v>; the first idiomatic variant is the ratio's baseline.
const BENCHMARKS = [
    { name: "sum", what: "Build a flat i32 array, then scan it eight times.", param: "N", sizes: [1000000, 16000000, 128000000], cpp: [0, 1] },
    { name: "push", what: "Push N records while keeping a mark on every 64th.", param: "N", sizes: [1000000, 16000000, 64000000], cpp: [0, 1, 2] },
    { name: "strlist", what: "Split a text into N words, keep them, scan four times.", param: "N", sizes: [200000, 2000000, 8000000], cpp: [0] },
    { name: "records", what: "Build a log of N variant records, aggregate four times.", param: "N", sizes: [500000, 4000000, 16000000], cpp: [0, 1, 2] },
    { name: "tree", what: "Build a complete binary tree of the given depth, sum eight times.", param: "depth", sizes: [16, 20, 24], cpp: [0, 2] },
    { name: "interp", what: "Build an expression tree of the given depth, evaluate eight times.", param: "depth", sizes: [16, 20, 24], cpp: [0, 2] },
    { name: "graph", what: "Adjacency for V vertices, 8V edges, BFS from four sources.", param: "V", sizes: [100000, 500000, 2000000], cpp: [0, 1, 2] },
    { name: "words", what: "Count word frequencies over a text of N words.", param: "N", sizes: [500000, 4000000, 16000000], cpp: [0, 2] },
    { name: "particles", what: "Integrate N particles for 200 steps of f32 math.", param: "N", sizes: [100000, 1000000, 4000000], cpp: [0, 2] },
    { name: "sexp", what: "Generate N s-expression forms, parse, walk four times.", param: "N", sizes: [20000, 100000, 300000], cpp: [0, 1] },
    { name: "lru", what: "An LRU cache of capacity N/8 under N skewed lookups.", param: "N", sizes: [2000000, 8000000, 32000000], cpp: [0, 1] },
    { name: "scene", what: "A scene graph of the given depth, animated for 16 frames.", param: "depth", sizes: [12, 14, 16], cpp: [0, 1] },
    { name: "calc", what: "Parse and evaluate N arithmetic expressions, a quarter malformed.", param: "N", sizes: [100000, 400000, 1600000], cpp: [0, 1] },
    { name: "bintrees", what: "The Benchmarks Game binary-trees up to the given depth.", param: "depth", sizes: [15, 17, 19], cpp: [0, 1] },
    { name: "respond", what: "Build and render N JSON responses.", param: "N", sizes: [100000, 400000, 1600000], cpp: [0, 1] },
    { name: "blur", what: "16 passes of a 3x3 blur over a WxW 8-bit image.", param: "W", sizes: [1024, 2048, 8192], cpp: [0, 1] },
];

const SIZE_NAMES = ["small", "medium", "large"];

// --- harness -----------------------------------------------------------------

const BLOCKED = "was blocked by";

function runProcess(command, options = {}) {
    // Two platform realities shape this: Bun's direct spawn of freshly linked
    // exes fails with uv EUNKNOWN, and Smart App Control intermittently blocks
    // an unsigned exe until (or unless) its cloud verdict allows it - the same
    // rough edges tools/stress.js works around. Every child therefore goes
    // through `cmd /d /c` (none of the paths carry spaces), and a spawn or a
    // block is retried with backoff; a genuine run failure is not.
    const started = performance.now();
    let proc = null;
    for (let attempt = 0; attempt < 8; attempt++) {
        Bun.sleepSync(attempt === 0 ? 0 : 1000 * attempt);
        try {
            proc = Bun.spawnSync(["cmd.exe", "/d", "/c", ...command], {
                cwd: options.cwd ?? REPO,
                env: options.env,
                stdout: "pipe",
                stderr: "pipe",
            });
        } catch (error) {
            if (attempt === 7) fail(`spawn failed repeatedly: ${command.join(" ")}\n${error.message}`);
            continue;
        }
        const text = proc.stdout.toString() + proc.stderr.toString();
        const blocked = text.includes(BLOCKED)
            || ((proc.exitCode === 199 || proc.exitCode === 5 || proc.exitCode === 125 || proc.exitCode === 126)
                && proc.stdout.toString().length === 0 && proc.stderr.toString().length === 0);
        if (blocked) {
            if (attempt === 7) fail(`blocked by policy repeatedly: ${command.join(" ")}\n${text}`);
            continue;
        }
        break;
    }
    const ms = performance.now() - started;
    return {
        exit: proc.exitCode ?? 1,
        stdout: proc.stdout.toString(),
        stderr: proc.stderr.toString(),
        ms,
    };
}

function parseArgs(argv) {
    // Medium only by default: the small sizes sit on the process-startup floor and move
    // with core scheduling, so their ratios are noise; ask for them with --sizes.
    const opts = { filters: [], sizes: ["medium"], reps: 3, simse: null };
    const value = (i) => {
        if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
        return argv[i + 1];
    };
    for (let i = 0; i < argv.length; i++) {
        switch (argv[i]) {
            case "--filter": opts.filters.push(value(i)); i++; break;
            case "--sizes": opts.sizes = value(i).split(",").filter((s) => s.length > 0); i++; break;
            case "--reps": opts.reps = Number(value(i)); i++; break;
            case "--simse": opts.simse = value(i); i++; break;
            default: fail(`unknown option '${argv[i]}'`);
        }
    }
    for (const s of opts.sizes) {
        if (!SIZE_NAMES.includes(s)) fail(`unknown size '${s}' (want ${SIZE_NAMES.join(", ")})`);
    }
    if (!Number.isFinite(opts.reps) || opts.reps < 1) fail("--reps wants a count");
    return opts;
}

// The size travels differently into each language: C++ takes -DBENCH_N, a Simse
// program has no preprocessor, so the one `// BENCH_N` line is rewritten in a
// copy of the source (the goose suite's own arrangement).
function writeSized(src, dst, n) {
    // CRLF-tolerant: a fresh Windows checkout materializes the sources with \r\n
    // endings, and `.` in a JS regex does not match \r - the anchored tail below would
    // miss every line. The sized copy joins back with plain \n.
    const lines = readFileSync(src, "utf8").split(/\r?\n/);
    let hit = false;
    const out = lines.map((line) => {
        const match = line.match(/^(\s*val\s+\w+:\s*Int\s*=\s*)(\d+)(\s*\/\/ BENCH_N.*)$/);
        if (!match) return line;
        hit = true;
        return `${match[1]}${n}${match[3]}`;
    });
    if (!hit) fail(`no '// BENCH_N' line in ${src}`);
    mkdirSync(path.dirname(dst), { recursive: true });
    writeFileSync(dst, out.join("\n"));
}

function median(times) {
    const sorted = [...times].sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length / 2)];
}

// --- one benchmark at one size ------------------------------------------------

function buildCpp(build, bench, variant, n, work) {
    const exe = path.join(work, `cpp${variant}.exe`);
    const flags = ["/nologo", "/std:c++20", "/EHsc", "/W3", "/bigobj", "/MD", "/O2", "/Ob3", "/DNDEBUG", "/GL",
        `/DBENCH_N=${n}`, `/DVARIANT=${variant}`, `/Fe${exe}`, path.join(HERE, "cpp", `${bench.name}.cpp`)];
    const compiled = runProcess([build.cl, ...flags], { env: build.env });
    if (compiled.exit !== 0) {
        throw new Error(`cl.exe failed on cpp variant ${variant}: ${(compiled.stderr || compiled.stdout).split("\n").filter((l) => l.includes("error")).slice(0, 2).join(" | ")}`);
    }
    return exe;
}

function buildSimse(build, bench, n, work) {
    const src = path.join(work, "src");
    mkdirSync(src, { recursive: true });
    writeSized(path.join(HERE, "simse", bench.name, "src", "main.kt"), path.join(src, "main.kt"), n);
    const outCpp = path.join(work, "out.cpp");
    // The kit carries the shared u64 scaffolding over `unsafe.UInt64`, so every row
    // names both roots and the module.
    const transpiled = runProcess([build.simse, "--root", src, "--root", path.join(HERE, "simse", "kit"), "--module", path.join(REPO, "modules", "unsafe"), "-o", outCpp, "--for-walk"], { env: build.env });
    if (transpiled.exit !== 0) {
        throw new Error(`transpile failed: ${(transpiled.stderr || transpiled.stdout).split("\n").slice(0, 2).join(" | ")}`);
    }
    const exe = path.join(work, "simse.exe");
    const flags = ["/nologo", "/std:c++20", "/EHsc", "/W3", "/bigobj", "/MD", "/O2", "/Ob3", "/DNDEBUG", "/GL",
        `/I${REPO}`, `/Fo${work}${path.sep}`, `/Fe${exe}`, outCpp];
    const compiled = runProcess([build.cl, ...flags], { env: build.env });
    if (compiled.exit !== 0) {
        throw new Error(`cl.exe failed on the Simse-generated C++: ${(compiled.stderr || compiled.stdout).split("\n").filter((l) => l.includes("error")).slice(0, 2).join(" | ")}`);
    }
    return exe;
}

function startRun(build, exe) {
    const run = runProcess([exe], { env: build.env });
    if (run.exit !== 0 && run.stdout.length === 0 && run.stderr.length === 0) {
        // A policy block occasionally surfaces as a bare nonzero exit with no
        // output; pause once and retry before believing it.
        Bun.sleepSync(2000);
        return runProcess([exe], { env: build.env });
    }
    return run;
}

// Times every exe of a benchmark at one size with the runs interleaved
// round-robin, so on a laptop the turbo/thermal history lands evenly on all
// the rows instead of piling boost on the first and heat on the last.
function runInterleaved(build, exes, reps) {
    const outputs = exes.map(() => null);
    const times = exes.map(() => []);
    // The row order rotates with the rep: whichever position in the round the machine
    // favors (the first runner meets a ramping clock, the last a warm one), every row
    // takes every position exactly once when reps divides the row count - and never
    // keeps one.
    for (let rep = 0; rep < reps; rep++) {
        for (let i = 0; i < exes.length; i++) {
            const e = (rep + i) % exes.length;
            const run = startRun(build, exes[e]);
            if (run.exit !== 0) {
                return { error: `exit ${run.exit} on rep ${rep}` };
            }
            if (outputs[e] === null) {
                outputs[e] = run.stdout;
            } else if (run.stdout !== outputs[e]) {
                return { error: "output differs between reps" };
            }
            times[e].push(run.ms);
            Bun.sleepSync(250);
        }
    }
    return {
        outputs: outputs.map((o) => o.replace(/\r\n/g, "\n").trim()),
        ms: times.map((t) => median(t)),
    };
}

async function main() {
    const opts = parseArgs(Bun.argv.slice(2));
    const env = developerEnv(hostArch(), TOOL);
    const cl = whichCl(env);
    if (!cl) fail("cl.exe not found on the Visual Studio PATH");
    const build = { env, cl, simse: opts.simse ? path.resolve(REPO, opts.simse) : path.join(REPO, "simse.exe") };
    if (!existsSync(build.simse)) fail(`compiler not found: ${build.simse}`);

    const selected = BENCHMARKS.filter((b) => opts.filters.length === 0 || opts.filters.some((f) => b.name.includes(f)));
    rmSync(WORK, { recursive: true, force: true });
    mkdirSync(WORK, { recursive: true });

    console.log(`simse --for-walk vs cpp over the goose bench suite - cl.exe ${path.basename(cl)}`);
    console.log(`sizes: ${opts.sizes.join(", ")}   reps: ${opts.reps}\n`);

    const results = {};
    for (const bench of selected) {
        results[bench.name] = { what: bench.what, param: bench.param, sizes: {}, checks: {} };
        console.log(`== ${bench.name}: ${bench.what}`);
        for (const sizeName of opts.sizes) {
            const si = SIZE_NAMES.indexOf(sizeName);
            const n = bench.sizes[si];
            const work = path.join(WORK, bench.name, sizeName);
            mkdirSync(work, { recursive: true });
            const rows = {};
            const line = [];
            const labels = [];
            const exes = [];

            // Build every row first, then measure the interleaved runs. The Simse row is
            // first: the interleave spreads the turbo/thermal history across the rows of
            // a rep, but the row order inside the rep still leans on the machine - the
            // first runner gets the boost and the last the heat, and the row under test
            // should not be the one paying for the references.
            let buildError = null;
            try {
                exes.push(buildSimse(build, bench, n, work));
                labels.push("simse");
            } catch (e) {
                buildError = `simse: ${e.message}`;
            }
            if (!buildError) {
                for (const variant of bench.cpp) {
                    try {
                        exes.push(buildCpp(build, bench, variant, n, work));
                        labels.push(`cpp variant ${variant}`);
                    } catch (e) {
                        buildError = `cpp variant ${variant}: ${e.message}`;
                        break;
                    }
                }
            }
            if (buildError) {
                // cl or the transpiler failed: no row of this size is timed.
                console.log(`  ${sizeName} (${bench.param}=${n}): BUILD FAILED - ${buildError.split("\n")[0]}`);
                results[bench.name].sizes[sizeName] = { simse: { error: buildError.split("\n")[0] } };
                results[bench.name].checks[sizeName] = "build failed";
                continue;
            }

            const out = runInterleaved(build, exes, opts.reps);
            if (out.error) {
                console.log(`  ${sizeName} (${bench.param}=${n}): RUN FAILED - ${out.error}`);
                results[bench.name].sizes[sizeName] = { simse: { error: out.error } };
                results[bench.name].checks[sizeName] = out.error;
                continue;
            }

            // Checksum agreement across the rows; the first output is baseline.
            const baseline = out.outputs[0];
            for (let e = 0; e < exes.length; e++) {
                if (out.outputs[e] !== baseline) {
                    const got = out.outputs[e].split("\n");
                    const want = baseline.split("\n");
                    const at = got.findIndex((v, i) => v !== want[i]);
                    const why = `${labels[e]} line ${at + 1}: expected ${want[at]}, got ${got[at]}`;
                    line.push(`${labels[e]}: CHECKSUM MISMATCH`);
                    rows[labels[e]] = { error: "checksum mismatch" };
                    results[bench.name].checks[sizeName] = why;
                } else {
                    rows[labels[e]] = { ms: out.ms[e] };
                    line.push(`${labels[e].replace("cpp variant ", "cpp")} ${out.ms[e].toFixed(0)}`);
                }
            }
            results[bench.name].sizes[sizeName] = rows;
            console.log(`  ${sizeName} (${bench.param}=${n}): ${line.join("  |  ")}`);
        }
        console.log("");
    }

    writeResults(opts, selected, results);
    console.log(`results written to ${path.relative(REPO, path.join(HERE, "results.md"))}`);
}

function writeResults(opts, selected, results) {
    let md = `# Results: Simse vs C++ over the Goose benchmark suite\n\n`;
    md += `Ratios of process wall time (median of ${opts.reps} interleaved runs, same cl.exe and\n`;
    md += `flags for every row): the idiomatic C++ row (VARIANT 0) is the 1.00 baseline, and each\n`;
    md += `other column is that row's time divided by it - so the reading is machine-independent\n`;
    md += `to first order, a slower or busier core slowing both sides together. Sizes:\n`;
    md += `${opts.sizes.join(", ")}. Checksums verified across rows; a row that disagrees is a\n`;
    md += `mismatch, never timed.\n\n`;
    md += `| benchmark | Simse / std | hand-tuned / std |\n`;
    md += `| --- | --- | --- |\n`;
    const problemsByBench = [];
    for (const bench of selected) {
        const r = results[bench.name];
        const stdLabel = "cpp variant " + bench.cpp[0];
        const cells = opts.sizes.map((sizeName) => {
            const at = r.sizes[sizeName] ?? {};
            const std = at[stdLabel];
            if (!std?.ms) return { simse: "-", expert: "-" };
            let simse = "-";
            if (at.simse?.ms) simse = (at.simse.ms / std.ms).toFixed(2);
            else if (at.simse?.error) simse = "mismatch";
            let expert = "-";
            let best = null;
            for (const variant of bench.cpp) {
                if (variant === bench.cpp[0]) continue;
                const row = at["cpp variant " + variant];
                if (row?.ms && (best === null || row.ms < best)) best = row.ms;
            }
            if (best !== null) expert = (best / std.ms).toFixed(2);
            return { simse, expert };
        });
        const simseCell = cells.map((c) => c.simse).join(", ");
        const expertCell = cells.map((c) => c.expert).join(", ");
        md += `| ${bench.name} | ${simseCell} | ${expertCell} |\n`;
        const problems = Object.entries(r.checks);
        if (problems.length > 0) {
            problemsByBench.push(`- **${bench.name}** - ${problems.map(([s, why]) => `${s}: ${why}`).join("; ")}`);
        }
    }
    if (problemsByBench.length > 0) {
        md += `\nChecksum problems:\n\n${problemsByBench.join("\n")}\n`;
    }
    md += "\n";
    writeFileSync(path.join(HERE, "results.md"), md);
}

await main();
