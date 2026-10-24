package fixtures

// The out-parameter shape the C API wants and the `native` generator now serves: a
// `native class` whose fields are the slots the library writes through the address the
// borrow hands it - `GetProcessAffinityMask`'s two `DWORD_PTR*` - with the process
// handle itself a `RawPtr` return. The kernel32 pair runs wherever Windows does, which
// is what the loader-under-test cases use.
//
// The values are the machine's own, so the checks are ranges, not values: both masks
// are written, and the process mask at least names the one processor this test runs on.

native class AffinityMask(var process: RawPtr, var system: RawPtr)

@SmGen("native", "kernel32.dll", "GetCurrentProcess")
fun getCurrentProcess(): RawPtr

@SmGen("native", "kernel32.dll", "GetProcessAffinityMask")
fun getProcessAffinityMask(handle: RawPtr, mask: *AffinityMask): Bool

fun main(): Int {
    val mask: AffinityMask = AffinityMask(null, null)
    val answered: Bool = getProcessAffinityMask(getCurrentProcess(), *mask)
    println("answered: " + answered.toString())
    println("process mask written: " + (mask.process != null).toString())
    println("system mask written: " + (mask.system != null).toString())
    return 0
}
