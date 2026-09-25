package asyncsample

// The program the async work is for: two suspended file operations and a chain that reaches
// them. `readFileTextAsync` answers `Async<Res<Str>>` because the file may not exist, and the
// `Res` is what `!!` propagates - so a failure needs no exception and no callback, it is a
// value the caller returns.
//
// Only the two leaves name `Async`; everything above them is inferred. `copyFile` is async
// because it calls something async, and `main` is async because `copyFile` is - while
// `describe` cannot suspend, so it keeps its plain signature and its direct call. That is the
// point of the inference: coloring stops where the suspensions do, and a helper is never
// dragged into a state machine for the convenience of its callers.
//
// Until the machine lowering lands this program does not compile; `--showAsync` is how the
// inference is checked (cppsrc/sema/Async.kt):
//
//     ./simse.exe --root docs/examples/async/src --showAsync

// The leaves: a body-less declaration is where the runtime's suspension lives (the `@SmGen`
// form the RTL uses for everything whose body is C++). The section is not written yet - the
// generator that reads it is the next step after the machine lowering.
@SmGen("res", "asyncfs", "simse_async_readFileText")
fun readFileTextAsync(path: Str): Async<Res<Str>>

@SmGen("res", "asyncfs", "simse_async_writeFile")
fun writeFileAsync(path: Str, text: Str): Async<Res<Int>>

// Async by inference: both of its suspensions are calls, and both failures leave through `!!`.
// The signature is an ordinary `Res<Int>` - the `Async` wrapper is the compiler's, which is
// what keeps a call to this function a suspension and nothing else.
fun copyFile(from: Str, to: Str): Res<Int> {
    val text: Res<Str> = readFileTextAsync(from)
    val content: Str = text!!
    val written: Res<Int> = writeFileAsync(to, content)
    val size: Int = written!!
    return Res<Int>.ok(size)
}

// Synchronous: no suspension reaches it, so it stays a plain function with a direct call.
fun describe(size: Int): Str {
    return "copied " + size.toString() + " bytes"
}

fun main(): Int {
    val size: Res<Int> = copyFile("input.txt", "output.txt")
    if (!size.isOk()) {
        eprintln("copy failed: " + size.error)
        return 1
    }
    println(describe(size.value))
    return 0
}
