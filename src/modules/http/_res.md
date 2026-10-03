Generated C++
====
The `http` module's C++ - the declarations are src/modules/http/api.kt. The WinINet calls
are `@SmGen("native", "wininet.dll", ...)`; what is C++ here is the constants, the buffer
the reads land in, the two block copies at the boundary and the error text. None of it
calls WinINet, so nothing has to be linked (`examples/http/sockets`' `_res.md` is the same
split, and `specs/resources.md`/`impl_specs/generators.md` own the file format). The
section is `emit: reached`, so a program that never fetches carries none of it.

!httpglue
====
emit: reached
includes:
```cpp
// windows.h pulls in nothing WinINet needs beyond the basics, and the `native`
// generator's own include block (windows.h + WIN32_LEAN_AND_MEAN) renders before this
// one; including it again is a guarded no-op. wininet.h is for the INTERNET_* constants
// only - the functions themselves are reached through the generator's thunks.
#include <windows.h>
#include <wininet.h>
#include <cstring>
#include <string>
```
forward:
```cpp
Int32 simse_http_flags();
Int32 simse_http_connectTimeout();
Int32 simse_http_receiveTimeout();
Str simse_http_errorText(const Str& step);
void simse_http_bufferInit(List<Char>* buffer, Int32 size);
void simse_http_append(List<Char>* out, const Char* bytes, Int32 count);
Str simse_http_charsToStr(const List<Char>* chars);
```
bodies:
```cpp
// The server's own answer every time (no cache), and no dialog on a certificate or
// authentication failure: the call fails and the error text says why.
Int32 simse_http_flags() {
    return (Int32) (INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_NO_UI);
}

Int32 simse_http_connectTimeout() {
    return (Int32) INTERNET_OPTION_CONNECT_TIMEOUT;
}

Int32 simse_http_receiveTimeout() {
    return (Int32) INTERNET_OPTION_RECEIVE_TIMEOUT;
}

// The step that failed, the platform's code, and the platform's own message. Read
// immediately after the failing call: `GetLastError` is the error the WinINet call left,
// and nothing between the two calls is a Win32 call of its own.
Str simse_http_errorText(const Str& step) {
    const unsigned long code = (unsigned long) ::GetLastError();
    Str text = step;
    text.append(" failed");
    if (code == 0) {
        // A missing wininet.dll or symbol answers a default rather than setting an error.
        text.append(": the library or its symbol could not be resolved");
        return text;
    }
    text.append(" (");
    text += std::to_string(code);
    text.append(")");
    char message[512];
    const unsigned long length = ::FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, code, 0, message, (unsigned long) sizeof(message), nullptr);
    if (length > 0) {
        // FormatMessage's text ends with a CR/LF; the message is one line.
        unsigned long trimmed = length;
        while (trimmed > 0 && (message[trimmed - 1] == '\r' || message[trimmed - 1] == '\n')) {
            trimmed--;
        }
        text.append(": ");
        text.append(message, (Int) trimmed);
    }
    return text;
}

// The buffer one request's reads land in; the caller owns it.
void simse_http_bufferInit(List<Char>* buffer, Int32 size) {
    buffer->resize(size);
}

// One block copy per chunk: appending a `Char` at a time would grow the response once per
// byte.
void simse_http_append(List<Char>* out, const Char* bytes, Int32 count) {
    if (count <= 0) return;
    const Int base = out->size();
    out->resize(base + count);
    std::memcpy(out->data() + base, bytes, (std::size_t) count);
}

// The response as an owned `Str` - the one copy when a caller wants text.
Str simse_http_charsToStr(const List<Char>* chars) {
    Str text;
    const Int count = chars->size();
    if (count <= 0) return text;
    text.resize(count);
    std::memcpy(text.data(), chars->data(), (std::size_t) count);
    return text;
}
```
