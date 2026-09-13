// brc_naive.cpp - the 1 Billion Row Challenge in plain C++ with the STL, written
// the way a first attempt is written: `std::ifstream` + `std::getline`, a
// `std::unordered_map<std::string, Stats>`, `std::stod` on each temperature, and a
// sorted report at the end. No mmap, no chunked reads, no custom number parsing, no
// per-station arrays, no threads.
//
// It is the yardstick for the Simse implementation in benchmarks/onebrc (the same
// algorithm, same naive shape), so it deliberately keeps the two allocations per row
// that a naive version has: one `substr` for the name, one for the value.
//
//   tools\_probe.bat brc_naive /O2 /Ob3 /DNDEBUG
//   tools\brc_naive.exe <data-file>
//
// The report goes to stdout in the challenge's format `{name=min/mean/max}`, sorted
// by name, rounded to one decimal by `printf("%.1f")`; timing and throughput go to
// stderr, so stdout can be diffed byte for byte against another implementation.
// (Note: `%.1f` rounds the binary double half-to-even, while the challenge's Java
// reference uses `Math.round(mean * 10) / 10`; use tools/gen_measurements.mjs
// `check` - which aggregates in exact integer tenths - as the output reference.)

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

struct Stats {
    double min = 1e300;
    double max = -1e300;
    double sum = 0.0;
    long long count = 0;
};

static double round1(double value) {
    return value; // printf's %.1f does the rounding
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: brc_naive <data-file>\n");
        return 2;
    }
#ifdef _WIN32
    // Text mode would turn every '\n' into CRLF, and the report is meant to be
    // diffed byte for byte against another implementation's.
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) {
        std::fprintf(stderr, "brc_naive: cannot open %s\n", argv[1]);
        return 2;
    }

    std::unordered_map<std::string, Stats> byStation;
    byStation.reserve(1024);

    std::string line;
    long long rows = 0;
    const auto began = std::chrono::steady_clock::now();
    while (std::getline(in, line)) {
        const std::size_t semi = line.find(';');
        if (semi == std::string::npos || semi == 0) {
            continue;
        }
        const double value = std::stod(line.substr(semi + 1));
        Stats& stats = byStation[line.substr(0, semi)];
        if (value < stats.min) stats.min = value;
        if (value > stats.max) stats.max = value;
        stats.sum += value;
        stats.count++;
        rows++;
    }
    const auto ended = std::chrono::steady_clock::now();

    std::vector<const std::string*> names;
    names.reserve(byStation.size());
    for (const auto& entry: byStation) {
        names.push_back(&entry.first);
    }
    std::sort(names.begin(), names.end(),
              [](const std::string* left, const std::string* right) { return *left < *right; });

    for (const std::string* name: names) {
        const Stats& stats = byStation[*name];
        const double mean = stats.sum / (double) stats.count;
        std::printf("{%s=%.1f/%.1f/%.1f}\n", name->c_str(), round1(stats.min), round1(mean),
                    round1(stats.max));
    }

    const double seconds = std::chrono::duration<double>(ended - began).count();
    std::fprintf(stderr, "%lld rows, %.2f s, %d stations\n", rows, seconds, (int) names.size());
    return 0;
}
