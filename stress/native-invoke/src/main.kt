package fixtures

// The `native` generator (the P/Invoke shape, impl_specs/native-interop.md): a body-less
// declaration bound to an exported symbol of a shared library at run time. `kernel32.dll` is
// used rather than a third-party library so the case runs anywhere - the loader is what is
// under test, the library is the platform's own. A scalar and a string return are both
// exercised, and the string is checked rather than printed, since a process's command line is
// not a fixed value.

@SmGen("native", "kernel32.dll", "GetCurrentProcessId")
fun currentProcessId(): Int

@SmGen("native", "kernel32.dll", "GetCommandLineA")
fun commandLine(): Str

fun main(): Int {
    println(currentProcessId() > 0)
    println(commandLine().size() > 0)
    return 0
}
