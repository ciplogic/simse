package fixtures

// The out-parameter shape the C API wants and the `native` generator serves: a `native
// class` whose fields are the out slots, each handed to the library as its own address -
// `GetProcessAffinityMask`'s two `DWORD_PTR*` are two arguments, not one struct pointer
// (one pointer covers one slot; the register after the last argument is whatever was
// there, and a library that writes through it faults). A `DWORD_PTR` is a pointer-sized
// integer, so the fields are `Int64` - a pointer-typed field would make `*field` the load
// through the pointer, not the field's address. The process handle itself is a `RawPtr`
// return. The kernel32 pair runs wherever Windows does.
//
// The values are the machine's own, so the checks are ranges, not values: both masks
// are written, and the process mask at least names the one processor this test runs on.

native class AffinityMask(var process: Int64, var system: Int64)

@SmGen("native", "kernel32.dll", "GetCurrentProcess")
fun getCurrentProcess(): RawPtr

@SmGen("native", "kernel32.dll", "GetProcessAffinityMask")
fun getProcessAffinityMask(handle: RawPtr, process: *Int64, system: *Int64): Bool

fun main(): Int {
    val mask: AffinityMask = AffinityMask(0, 0)
    val answered: Bool = getProcessAffinityMask(getCurrentProcess(), *mask.process, *mask.system)
    println("answered: " + answered.toString())
    println("process mask written: " + (mask.process != 0).toString())
    println("system mask written: " + (mask.system != 0).toString())
    return 0
}
