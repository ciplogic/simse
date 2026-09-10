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

        // Ensure the directory exists and is actually a directory
        if (!std::filesystem::exists(dirPath) || !std::filesystem::is_directory(dirPath)) {
            return matchingFiles;
        }

        // Iterate through the files in the directory tree
        for (const auto& entry : std::filesystem::recursive_directory_iterator(dirPath)) {
            // Check if it's a regular file and has the matching extension
            if (entry.is_regular_file() && entry.path().extension() == ext) {
                matchingFiles.push_back(entry.path().string());
            }
        }

        // Sort so multi-file consumers (and golden tests) see a deterministic order.
        std::sort(matchingFiles.begin(), matchingFiles.end());

        return matchingFiles;

    }

    char StrView::at(int index) {
        return str->at(start + index);
    }

    bool StrView::startsWith(const Str &str) {
        if (str.length() > len) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (at(i) != str.at(i)) {
                return false;
            }
        }
        return true;
    }

    StrView StrView::slice(int matchLength) {
        return {str, start, matchLength};
    }

    Str StrView::toString() {
        Str result;
        result.resize(len);
        for (int i = 0; i < len; i++) {
            result.at(i) = at(i);
        }
        return result;
    }

    StrView viewOf(Str *str) {
        return {str, 0, (int) str->length()};
    }

    StrView viewOfAtPos(Str *str, int pos) {
        return {str, pos, (int) str->length() - pos};
    }
}