package app

// A `native` declaration may take a pointer to a class. The thunk spells it `void*` and the
// argument crosses as the address - which is what a library's own struct needs: the platform fills
// it *in place*, so the caller and the callee must agree on the layout.
//
// `SYSTEMTIME` is the proof that the ABI is right and not just the emission: eight 16-bit fields
// written by `GetSystemTime` in place, read back here. Before this case the declaration was
// refused ("has a parameter type the generator cannot map"), because a pointer mapped only when
// its pointee did - and the case that made the difference matter is Skia's `sk_rect_t` in the
// Minesweeper example (`*SkRect` on `sk_canvas_draw_rect`).

native class SystemTime(
    var year: Int16, var month: Int16, var weekday: Int16, var day: Int16,
    var hour: Int16, var minute: Int16, var second: Int16,
    var milliseconds: Int16
)

@SmGen("native", "kernel32.dll", "GetSystemTime")
fun GetSystemTime(time: *SystemTime): Unit

fun main(): Int {
    var time: SystemTime = SystemTime(0, 0, 0, 0, 0, 0, 0, 0)
    GetSystemTime(*time)
    // Ranges, not values: the clock is the one thing a test must not pin.
    println("year after 2000: " + (time.year > 2000).toString())
    println("month in range: " + (time.month >= 1 && time.month <= 12).toString())
    println("day in range: " + (time.day >= 1 && time.day <= 31).toString())
    println("weekday in range: " + (time.weekday <= 6).toString())
    println("hour in range: " + (time.hour <= 23).toString())
    return 0
}
