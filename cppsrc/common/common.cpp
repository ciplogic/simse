//
// Created by cipri on 9/4/2026.
//

#include "common.h"
#include <filesystem>
#include <cstdio>
#include <algorithm>

namespace common {
    Str readFile(const Str &filePath) {
        Str result;
        FILE* file = fopen(filePath.c_str(), "rb");
        AutoDefer a([file]() {
            fclose(file);
        });
        fseek(file, 0, SEEK_END);
        int fileSize = ftell(file);
        fseek(file, 0, SEEK_SET);
        result.resize(fileSize);
        fread(result.data(), 1, fileSize, file);
        return result;
    }

    List<Str> filesInDir(const Str &dirPath, Str ext) {
        List<Str> matchingFiles;

        // The standard filesystem API works in std::string; the language works in
        // Str (`simse_toStdString` is a no-op copy when Str is std::string).
        const std::string dir = simse_toStdString(dirPath);
        const std::string wantedExt = simse_toStdString(ext);

        // Ensure the directory exists and is actually a directory
        if (!std::filesystem::exists(dir) || !std::filesystem::is_directory(dir)) {
            return matchingFiles;
        }

        // Iterate through the files in the directory tree
        for (const auto& entry : std::filesystem::recursive_directory_iterator(dir)) {
            // Check if it's a regular file and has the matching extension
            if (entry.is_regular_file() && entry.path().extension() == wantedExt) {
                // Generic separators so a scanned path matches the same path given
                // explicitly (e.g. `--root cppsrc` vs an explicit input file).
                matchingFiles.push_back(simse_fromStdString(entry.path().generic_string()));
            }
        }

        // Sort so multi-file consumers (and golden tests) see a deterministic order.
        std::sort(matchingFiles.begin(), matchingFiles.end());

        return matchingFiles;

    }
}
