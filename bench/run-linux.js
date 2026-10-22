// run-linux.js - the Goose-benchmarks comparison harness, Linux edition.
//
// The Windows harness (run.js) builds every row with the same cl.exe; this one
// builds every row twice - once with g++, once with clang++ - so the suite
// answers two questions at once: Simse against C++ on Linux, and the two
// system compilers against each other on both the hand-written references and
// the Simse-generated C++. Rows: cpp<N> g++ / cpp<N> clang++ / simse g++ /
// simse clang++. Every row of a benchmark must print the same checksum or the
// size is failed, never timed.
//
//   bun bench/run-linux.js                          # small+medium, 3 reps
//   bun bench/run-linux.js --sizes small,medium,large
//   bun bench/run-linux.js --filter tree --cxx clang++-19
//   bun bench/run-linux.js --simse simse-clang++    # transpile with the other build
//
// Results land in bench/results-linux-i3.md (rename with --out).

import { readdirSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from "node:fs";
import * as path from "node:path";

const HERE = path.resolve(import.meta.dir);
const REPO = path.resolve(HERE, "..");
const WORK = path.join(HERE, ".work-linux");

// Same suite as run.js: aardappel/goose's bench rows; `cpp` lists the reference
// variants, the first being the idiomatic one the ratio table uses.
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

function runProcess(command, options = {}) {
    const started = performance.now();
    const proc = Bun.spawnSync(command, {
        cwd: options.cwd ?? REPO,
        stdout: "pipe",
        stderr: "pipe",
    });
    return {
        exit: proc.exitCode ?? 1,
        stdout: proc.stdout.toString(),
        stderr: proc.stderr.toString(),
        ms: performance.now() - started,
    };
}

function whichOne(name) {
    const probe = runProcess(["which", name]);
    return probe.exit === 0 && probe.stdout.trim().length > 0 ? name : null;
}

function findClang() {
    for (const name of ["clang++", "clang++-19", "clang++-18", "clang++-17"]) {
        if (whichOne(name)) return name;
    }
    return null;
}

function parseArgs(argv) {
    const opts = {
        filters: [], sizes: ["small", "medium"], reps: 3,
        simse: path.join(REPO, "simse-g++"),
        gxx: "g++", cxx: findClang(), out: "results-linux-i3.md",
    };
    const value = (i) => {
        if (i + 1 >= argv.length) fail(`missing value for ${argv[i]}`);
        return argv[i + 1];
    };
    for (let i = 0; i < argv.length; i++) {
        switch (argv[i]) {
            case "--filter": opts.filters.push(value(i)); i++; break;
            case "--sizes": opts.sizes = value(i).split(",").filter((s) => s.length > 0); i++; break;
            case "--reps": opts.reps = Number(value(i)); i++; break;
            case "--simse": opts.simse = path.resolve(REPO, value(i)); i++; break;
            case "--gxx": opts.gxx = value(i); i++; break;
            case "--cxx": opts.cxx = value(i); i++; break;
            case "--out": opts.out = value(i); i++; break;
            default: fail(`unknown option '${argv[i]}'`);
        }
    }
    for (const s of opts.sizes) {
        if (!SIZE_NAMES.includes(s)) fail(`unknown size '${s}' (want ${SIZE_NAMES.join(", ")})`);
    }
    if (!Number.isFinite(opts.reps) || opts.reps < 1) fail("--reps wants a count");
    if (!opts.cxx) fail("no clang++ found (pass --cxx clang++-N)");
    return opts;
}

function fail(message) {
    console.error(`bench: ${message}`);
    process.exit(1);
}

// The size travels differently into each language: C++ takes -DBENCH_N, a Simse
// program has no preprocessor, so the one `// BENCH_N` line is rewritten in a
// copy of the source (the goose suite's own arrangement).
function writeSized(src, dst, n) {
    const lines = readFileSync(src, "utf8").split("\n");
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

// Both compilers get the same flag set; the references are standalone TUs, the
// Simse-generated C++ additionally includes runtime headers from the repo root.
function compile(compiler, flags, source, exe, extraIncludes = []) {
    const argv = [compiler, "-std=c++20", "-O2", "-DNDEBUG", ...extraIncludes, ...flags, "-o", exe, source];
    const compiled = runProcess(argv);
    if (compiled.exit !== 0) {
        throw new Error(`${compiler} failed: ${(compiled.stderr || compiled.stdout).split("\n").filter((l) => /error/i.test(l)).slice(0, 2).join(" | ")}`);
    }
    return exe;
}

function transpile(simse, srcRoot, outCpp) {
    const transpiled = runProcess([simse, "--root", srcRoot, "--root", path.join(HERE, "simse", "kit"), "-o", outCpp]);
    if (transpiled.exit !== 0) {
        throw new Error(`transpile failed: ${(transpiled.stderr || transpiled.stdout).split("\n").slice(0, 2).join(" | ")}`);
    }
}

// Times every exe of a benchmark at one size with the runs interleaved
// round-robin, so turbo/thermal history lands evenly on all the rows instead
// of piling boost on the first and heat on the last.
function runInterleaved(exes, reps) {
    const outputs = exes.map(() => null);
    const times = exes.map(() => []);
    for (let rep = 0; rep < reps; rep++) {
        for (let e = 0; e < exes.length; e++) {
            const run = runProcess([exes[e]]);
            if (run.exit !== 0) {
                return { error: `exit ${run.exit} on rep ${rep}: ${run.stderr.split("\n")[0]}` };
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

function toolVersion(command) {
    return runProcess(command).stdout.split("\n")[0].trim();
}

function cpuModel() {
    for (const line of readFileSync("/proc/cpuinfo", "utf8").split("\n")) {
        if (line.startsWith("model name")) return line.split(":")[1].trim();
    }
    return "unknown CPU";
}

async function main() {
    const opts = parseArgs(Bun.argv.slice(2));
    if (!existsSync(opts.simse)) fail(`compiler not found: ${opts.simse}`);
    const compilers = [
        { tag: "g++", bin: opts.gxx },
        { tag: "clang++", bin: opts.cxx },
    ];
    for (const c of compilers) {
        if (!whichOne(c.bin)) fail(`compiler not found: ${c.bin}`);
    }
    const versions = {
        gxx: toolVersion([opts.gxx, "-dumpfullversion"]),
        cxx: toolVersion([opts.cxx, "--version"]),
    };

    const selected = BENCHMARKS.filter((b) => opts.filters.length === 0 || opts.filters.some((f) => b.name.includes(f)));
    rmSync(WORK, { recursive: true, force: true });
    mkdirSync(WORK, { recursive: true });

    console.log(`simse vs cpp over the goose bench suite - ${opts.gxx} ${versions.gxx} and ${opts.cxx}`);
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

            // Build every row first, then measure the interleaved runs.
            let buildError = null;
            for (const variant of bench.cpp) {
                const src = path.join(HERE, "cpp", `${bench.name}.cpp`);
                for (const c of compilers) {
                    try {
                        exes.push(compile(c.bin, [`-DBENCH_N=${n}`, `-DVARIANT=${variant}`], src, path.join(work, `cpp${variant}-${c.tag}`)));
                        labels.push(`cpp${variant} ${c.tag}`);
                    } catch (e) {
                        buildError = `cpp variant ${variant} [${c.tag}]: ${e.message}`;
                        break;
                    }
                }
                if (buildError) break;
            }
            if (!buildError) {
                const outCpp = path.join(work, "out.cpp");
                try {
                    writeSized(path.join(HERE, "simse", bench.name, "src", "main.kt"), path.join(work, "src", "main.kt"), n);
                    transpile(opts.simse, path.join(work, "src"), outCpp);
                    for (const c of compilers) {
                        exes.push(compile(c.bin, [], outCpp, path.join(work, `simse-${c.tag}`), ["-I", REPO]));
                        labels.push(`simse ${c.tag}`);
                    }
                } catch (e) {
                    buildError = `simse: ${e.message}`;
                }
            }
            if (buildError) {
                console.log(`  ${sizeName} (${bench.param}=${n}): BUILD FAILED - ${buildError.split("\n")[0]}`);
                results[bench.name].sizes[sizeName] = { simse: { error: buildError.split("\n")[0] } };
                results[bench.name].checks[sizeName] = "build failed";
                continue;
            }

            const out = runInterleaved(exes, opts.reps);
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
                    line.push(`${labels[e]} ${out.ms[e].toFixed(0)}`);
                }
            }
            results[bench.name].sizes[sizeName] = rows;
            console.log(`  ${sizeName} (${bench.param}=${n}): ${line.join("  |  ")}`);
        }
        console.log("");
    }

    writeResults(opts, versions, selected, results);
    console.log(`results written to ${path.join(HERE, opts.out)}`);
}

function writeResults(opts, versions, selected, results) {
    let md = `# Results: Simse vs C++ over the Goose benchmark suite - Linux\n\n`;
    md += `Machine: Linux on ${cpuModel()}, 8 cores/8 threads. Same flags for every row\n`;
    md += `(\`-std=c++20 -O2 -DNDEBUG\`; the Simse-generated C++ adds \`-I<repo>\` for the runtime\n`;
    md += `headers). Compilers: g++ ${versions.gxx}, clang++ ${versions.cxx.replace(/^Ubuntu clang version /, "")}.\n`;
    md += `Process wall time, median of ${opts.reps} runs, interleaved round-robin over all rows.\n`;
    md += `Sizes: ${opts.sizes.join(", ")}. Every row of a benchmark must print the same checksum\n`;
    md += `or the size is failed, never timed. Small sizes sit near the process-startup floor -\n`;
    md += `read them for parity, not ratios.\n\n`;
    const ratios = [];
    for (const bench of selected) {
        const r = results[bench.name];
        md += `## ${bench.name}\n\n${r.what}\n\n`;
        md += `| implementation | ${opts.sizes.map((s) => `${s} (${r.param}=${bench.sizes[SIZE_NAMES.indexOf(s)]})`).join(" | ")} |\n`;
        md += `| --- | ${opts.sizes.map(() => "---").join(" | ")} |\n`;
        const rowNames = [];
        for (const sizeName of opts.sizes) {
            for (const row of Object.keys(r.sizes[sizeName] ?? {})) {
                if (!rowNames.includes(row)) rowNames.push(row);
            }
        }
        for (const row of rowNames) {
            const cells = opts.sizes.map((s) => {
                const cell = (r.sizes[s] ?? {})[row];
                if (!cell) return "-";
                if (cell.error) return `failed`;
                return `${cell.ms.toFixed(0)} ms`;
            });
            md += `| ${row} | ${cells.join(" | ")} |\n`;
        }
        const idiomatic = "cpp" + bench.cpp[0];
        if (opts.sizes.some((s) => r.sizes[s]?.[`simse g++`]?.ms || r.sizes[s]?.[`simse clang++`]?.ms)) {
            ratios.push({ bench: bench.name, r, idiomatic });
        }
        const problems = Object.entries(r.checks);
        if (problems.length > 0) {
            md += `\nChecksum problems: ${problems.map(([s, why]) => `${s}: ${why}`).join("; ")}\n`;
        }
        md += "\n";
    }
    if (ratios.length > 0) {
        md += `## Ratios (median over sizes with both rows timed)\n\n`;
        md += `| benchmark | simse g++ / cpp${""} idiomatic g++ | simse clang++ / idiomatic clang++ | cpp clang++ / g++ | simse clang++ / g++ |\n`;
        md += `| --- | --- | --- | --- | --- |\n`;
        for (const { bench, r, idiomatic } of ratios) {
            const ratio = (num, den) => {
                const nums = opts.sizes.map((s) => (r.sizes[s] ?? {})[num]).filter((c) => c?.ms);
                const dens = opts.sizes.map((s) => (r.sizes[s] ?? {})[den]).filter((c) => c?.ms);
                if (nums.length === 0 || nums.length !== dens.length) return "-";
                return (nums.reduce((a, c) => a + c.ms, 0) / nums.length / (dens.reduce((a, c) => a + c.ms, 0) / dens.length)).toFixed(2) + "x";
            };
            md += `| ${bench} | ${ratio(`simse g++`, `${idiomatic} g++`)} | ${ratio(`simse clang++`, `${idiomatic} clang++`)} | ${ratio(`${idiomatic} clang++`, `${idiomatic} g++`)} | ${ratio(`simse clang++`, `simse g++`)} |\n`;
        }
        md += "\n";
    }
    writeFileSync(path.join(HERE, opts.out), md);
}

await main();
