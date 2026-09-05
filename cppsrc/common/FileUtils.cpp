//
// Created by cipri on 9/4/2026.
//

#include "FileUtils.h"
#include <filesystem>

namespace FileUtils {
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