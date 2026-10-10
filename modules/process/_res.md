Generated C++
====
The `process` module's C++ - the declarations are modules/process/api.kt. Running a command is
the C runtime's `system` (`_popen` where the output is captured): the shell, one wait, and the
exit code normalized to what the program itself answered (Windows already hands the code back;
POSIX packs it in a wait status). The section is `emit: reached`, so a program that never runs
a command carries none of it.

!process
====
emit: reached
forward:
```cpp
#include <cstdio>
#include <cstdlib>
#include <string>
#if !defined(_WIN32)
#include <sys/wait.h>
#endif

Int simse_process_run(const Str& command);
Str simse_process_capture(const Str& command, Int* exitCode);
```
bodies:
```cpp
// `system`/`pclose` answer the platform's wait status; the module answers the program's own
// exit code (Windows already answers the code; POSIX packs it).
static Int simse_process_normalize(int status) {
#if defined(_WIN32)
    return (Int) status;
#else
    if (status == -1) return -1;
    if (WIFEXITED(status)) return (Int) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (Int) (128 + WTERMSIG(status));
    return (Int) status;
#endif
}

Int simse_process_run(const Str& command) {
    const std::string cmd = simse_toStdString(command);
    return simse_process_normalize(std::system(cmd.c_str()));
}

Str simse_process_capture(const Str& command, Int* exitCode) {
    std::string cmd = simse_toStdString(command);
    cmd += " 2>&1";
#if defined(_WIN32)
    FILE* pipe = _popen(cmd.c_str(), "r");
#else
    FILE* pipe = popen(cmd.c_str(), "r");
#endif
    Str text;
    if (pipe == nullptr) {
        if (exitCode != nullptr) *exitCode = -1;
        return text;
    }
    char buffer[4096];
    std::size_t got = 0;
    while ((got = std::fread(buffer, 1, sizeof(buffer), pipe)) > 0) {
        text.append(buffer, (Int) got);
    }
#if defined(_WIN32)
    const int status = _pclose(pipe);
#else
    const int status = pclose(pipe);
#endif
    if (exitCode != nullptr) *exitCode = simse_process_normalize(status);
    return text;
}
```
```
