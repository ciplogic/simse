package fixtures

import http

// The `http` module's content check, hermetically: a loopback port nothing listens on fails
// through WinINet instead of hanging or crashing, and the failure is a `Res` error naming
// the step; a string that is not a URL fails before any connection. WinINet reads
// `http:`/`https:` URLs only (a `file:` URL is an invalid name), so the *success* path needs
// a live server and is not part of this corpus case - the module's download loop is still
// exercised here (session, options, request, error, handles closed).
//
// The error's text is the platform's and localized, so the checks are on its shape: the step
// prefix `httpGet` writes and a non-empty message - never the message itself.

fun main(): Int {
    val missing: Res<List<Char>> = httpGet("http://127.0.0.1:1/")
    if (!missing.isOk()) {
        println("status not-ok")
    }
    if (missing.error.startsWith("InternetOpenUrl failed")) {
        println("step InternetOpenUrl")
    }
    if (missing.error.size() > 20) {
        println("message present")
    }

    // The convenience form carries the same error text.
    val text: Res<Str> = httpGetText("http://127.0.0.1:1/")
    if (!text.isOk()) {
        println("text not-ok")
    }
    if (text.error.startsWith("InternetOpenUrl failed")) {
        println("text step InternetOpenUrl")
    }

    // A string with no URL shape fails in the same step, before a connection is attempted.
    val bad: Res<List<Char>> = httpGet("not a url")
    if (!bad.isOk()) {
        println("bad not-ok")
    }
    return 0
}
