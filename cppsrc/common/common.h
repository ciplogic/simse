#pragma once
#include "../rtl/simse.hpp"

namespace common {
    Str readFile(const Str& filePath);
    List<Str> filesInDir(const Str& dirPath, Str ext = ".simse");

}
