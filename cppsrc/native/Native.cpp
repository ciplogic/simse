#include "Native.h"

#include "../common/common.h"

Str simse_native_readFile(const Str& path) {
    return common::readFile(path);
}
