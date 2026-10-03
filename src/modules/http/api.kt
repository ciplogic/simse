package http

// The `http` module: a minimal blocking HTTP/HTTPS client over WinINet (`wininet.dll` -
// present on every Windows install, ARM64 included), built on the `native` generator
// (impl_specs/native-interop.md). Nothing is linked and no import library is needed; the
// DLL is resolved on first call, exactly as `examples/http/sockets` does for `ws2_32.dll`.
//
// `httpGet` is the whole surface: the response body as bytes-as-`Char`s, or a `Res` error
// naming the step that failed plus the platform's code and message. A response with a
// non-2xx status is *not* an error - WinINet hands the body over either way (the raw
// natives below are there when a caller wants headers or the status code).
//
// The spellings are WinINet's ANSI ones: a `Str` is the byte string the `A`-suffixed APIs
// take, so a URL with non-ASCII characters must arrive percent-encoded (as it must in a
// request line anyway), and the response bytes come back exactly as they arrived - no
// charset translation.

// ---- the WinINet exports, resolved from wininet.dll ----
//
// The Simse names carry an `internet` prefix so they cannot collide with a program's own
// words; the second attribute argument is the exported symbol.

@SmGen("native", "wininet.dll", "InternetOpenA")
fun internetOpen(agent: Str, accessType: Int, proxy: *Int8, proxyBypass: *Int8, flags: Int): RawPtr

@SmGen("native", "wininet.dll", "InternetOpenUrlA")
fun internetOpenUrl(session: RawPtr, url: Str, headers: *Int8, headersLength: Int, flags: Int, context: RawPtr): RawPtr

@SmGen("native", "wininet.dll", "InternetReadFile")
fun internetReadFile(request: RawPtr, buffer: *Char, toRead: Int, read: *Int): Int

@SmGen("native", "wininet.dll", "InternetCloseHandle")
fun internetCloseHandle(handle: RawPtr): Int

@SmGen("native", "wininet.dll", "InternetSetOptionA")
fun internetSetOption(handle: RawPtr, option: Int, value: *Int, length: Int): Int

// ---- the ABI glue (the module's `_res.md`): constants, buffers, the boundary copies ----
//
// The WinINet constants live in C++ (`wininet.h`): the flag word does not fit a signed
// `Int` literal, and the header's names are the documentation.

@SmGen("res", "httpglue", "simse_http_flags")
fun httpFlags(): Int

@SmGen("res", "httpglue", "simse_http_connectTimeout")
fun httpConnectOption(): Int

@SmGen("res", "httpglue", "simse_http_receiveTimeout")
fun httpReceiveOption(): Int

// The step's failure, as text: the platform's code and message for the last WinINet
// failure, read immediately after the failing call (the glue calls `GetLastError` /
// `FormatMessageA`; see `_res.md`).
@SmGen("res", "httpglue", "simse_http_errorText")
fun httpErrorText(step: Str): Str

// Sizes the caller's `buffer` for one request's reads: the buffer is a plain local that
// outlives the loop, not a value returned across a move (which is also why this is an
// `init` and not a `buffer(size): List<Char>` factory).
@SmGen("res", "httpglue", "simse_http_bufferInit")
fun httpBufferInit(buffer: *List<Char>, size: Int): Unit

// Appends the `count` bytes at `bytes` to the response being accumulated: one block copy,
// not a `Char` at a time.
@SmGen("res", "httpglue", "simse_http_append")
fun httpAppend(out: *List<Char>, bytes: *Char, count: Int): Unit

// The same bytes as an owned `Str`, for a caller that wants text.
@SmGen("res", "httpglue", "simse_http_charsToStr")
fun httpCharsToStr(chars: *List<Char>): Str

// ---- the surface ----

// The read size one `InternetReadFile` asks for.
fun httpChunk(): Int {
    return 16384
}

// The connect/receive bound, in milliseconds: WinINet's own defaults can hold a caller for
// a minute against a host that answers nothing. A caller that needs another bound can
// reach `internetSetOption` itself.
fun httpTimeout(): Int {
    return 15000
}

// The whole body of `url`, or a `Res` error. `out` accumulates one chunk at a time, and
// the session and the request are closed on every path.
fun httpGet(url: Str): Res<List<Char>> {
    val session: RawPtr = internetOpen("Simse/1.0", 0, null, null, 0)
    if (session == null) {
        return Res<List<Char>>.err(httpErrorText("InternetOpen"))
    }
    var timeout: Int = httpTimeout()
    internetSetOption(session, httpConnectOption(), *timeout, 4)
    internetSetOption(session, httpReceiveOption(), *timeout, 4)
    val request: RawPtr = internetOpenUrl(session, url, null, 0, httpFlags(), null)
    if (request == null) {
        val message: Str = httpErrorText("InternetOpenUrl")
        internetCloseHandle(session)
        return Res<List<Char>>.err(message)
    }
    val buffer: List<Char> = List<Char>()
    httpBufferInit(*buffer, httpChunk())
    var out: List<Char> = List<Char>()
    var error: Str = ""
    while (true) {
        var read: Int = 0
        if (internetReadFile(request, spanOf(buffer).atPtr(0), httpChunk(), *read) == 0) {
            error = httpErrorText("InternetReadFile")
            break
        }
        if (read <= 0) {
            break
        }
        httpAppend(*out, spanOf(buffer).atPtr(0), read)
    }
    internetCloseHandle(request)
    internetCloseHandle(session)
    if (error.size() > 0) {
        return Res<List<Char>>.err(error)
    }
    return Res<List<Char>>.ok(out)
}

// `httpGet`'s bytes as an owned `Str`: the convenience for a text response, with the same
// error text.
fun httpGetText(url: Str): Res<Str> {
    val body: Res<List<Char>> = httpGet(url)
    if (!body.isOk()) {
        return Res<Str>.err(body.error)
    }
    return Res<Str>.ok(httpCharsToStr(*body.value))
}
