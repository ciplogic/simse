#pragma once
#include "../rtl/simse.hpp"

namespace FileUtils {
    Str readFile(const Str& filePath);
    List<Str> filesInDir(const Str& dirPath, Str ext = ".simse");

}
