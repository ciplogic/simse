// Scratch: run a command and report its exact peak working set / private bytes.
// PROCESS_MEMORY_COUNTERS keeps the peaks as long as the process handle is open,
// so no sampling is needed. Not part of the build.
#include <windows.h>
#include <psapi.h>

#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("usage: memrun <exe> [args...]\n");
        return 2;
    }
    std::string command;
    for (int i = 1; i < argc; i++) {
        if (i > 1) command += " ";
        command += argv[i];
    }

    STARTUPINFOA startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};
    if (!CreateProcessA(nullptr, command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr,
                        &startup, &process)) {
        std::printf("memrun: cannot start '%s'\n", command.c_str());
        return 2;
    }
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(process.hProcess, &code);

    PROCESS_MEMORY_COUNTERS counters{};
    if (GetProcessMemoryInfo(process.hProcess, &counters, sizeof(counters))) {
        std::printf("peak working set %7.1f MB | peak private %7.1f MB | exit %lu\n",
                    counters.PeakWorkingSetSize / 1048576.0,
                    counters.PeakPagefileUsage / 1048576.0, code);
    } else {
        std::printf("memrun: no counters (exit %lu)\n", code);
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return code == 0 ? 0 : 1;
}
