//
// Created by cipri on 9/4/2026.
//

#include "common.h"
#include <filesystem>
#include <cstdio>

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

        // Ensure the directory exists and is actually a directory
        if (!std::filesystem::exists(dirPath) || !std::filesystem::is_directory(dirPath)) {
            return matchingFiles;
        }

        // Iterate through the files in the directory
        for (const auto& entry : std::filesystem::directory_iterator(dirPath)) {
            // Check if it's a regular file and has the matching extension
            if (entry.is_regular_file() && entry.path().extension() == ext) {
                matchingFiles.push_back(entry.path().string());
            }
        }

        return matchingFiles;

    }
}