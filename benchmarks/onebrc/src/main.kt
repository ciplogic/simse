package onebrc

// The 1 Billion Row Challenge, written the naive way: a line at a time, a `Str` key
// per station, a `Dictionary` of aggregates, and a sorted report at the end. No
// mmap, no chunked parsing, no per-station arrays, no threads - the straight-line
// version a first attempt is written as, which is what the C++ baseline in this
// folder (`brc_naive.cpp`) does too.
//
//   onebrc <measurements.txt>
//
// Each line is parsed **in place**: `readLineView()` hands back a `StrView` into the
// stream's readahead buffer, so the line is never copied - only the station name
// (the dictionary's key type) and the temperature (`tenths` takes a `Str`) cross
// into owned strings. `benchmark.md` has the measured result.
//
// "Borrowing" the dictionary matters: `Dictionary` is a value type. A raw pointer
// (`*counts` at the call site) borrows the local without copying it; a `&counts`
// *handle* would box a copy and the aggregates would be written into the box, not
// into the local.

data class Stats(var min: Int, var max: Int, var sum: Int, var count: Int)

// "-12.3" -> -123, "12.3" -> 123, "12" -> 120. The sign is handled here rather than
// trusted to `toInt`, since the digits are concatenated around the point.
fun tenths(text: Str): Int {
    var body: Str = text
    var negative: Bool = false
    if (body.size() > 0) {
        if (body.charAt(0) == '-') {
            negative = true
            body = body.substr(1, body.size() - 1)
        }
    }

    val dot: Int = body.find(".")
    var digits: Str = body
    var scale: Int = 10
    if (dot >= 0) {
        digits = body.substr(0, dot) + body.substr(dot + 1, body.size() - dot - 1)
        scale = 1
    }

    val value: Opt<Int> = digits.toInt()
    if (!value.hasValue()) {
        return 0
    }
    var result: Int = value.value() * scale
    if (negative) {
        result = 0 - result
    }
    return result
}

// `sum / count` tenths, rounded half toward positive infinity: the challenge's
// reference rule (Java's `Math.round(mean * 10) / 10`), computed exactly.
fun meanOf(sum: Int, count: Int): Int {
    if (count <= 0) {
        return 0
    }
    if (sum >= 0) {
        return (2 * sum + count) / (2 * count)
    }
    return 0 - ((0 - 2 * sum + count) / (2 * count))
}

// 123 -> "12.3", -5 -> "-0.5"
fun formatTenths(value: Int): Str {
    var sign: Str = ""
    var magnitude: Int = value
    if (magnitude < 0) {
        sign = "-"
        magnitude = 0 - magnitude
    }
    return sign + (magnitude / 10).toString() + "." + (magnitude % 10).toString()
}

// One line of `station;temperature` folded into the running aggregates. `counts` is
// a raw pointer so the aggregates go into the caller's dictionary in place.
// `line` is a view into the stream's readahead buffer, so nothing about the line
// itself is copied. The name still becomes a `Str` - it is the dictionary's key type
// - and `tenths` still takes one; those two copies are the next thing a tuned
// version would remove.
fun tallyView(line: StrView, counts: *Dictionary<Str, Stats>): Unit {
    val semi: Int = line.find(";")
    if (semi <= 0) {
        return
    }
    fold(line.substr(0, semi), tenths(line.substr(semi + 1, line.size() - semi - 1)), counts)
}

// One station's tenths folded into `counts`.
fun fold(name: Str, value: Int, counts: *Dictionary<Str, Stats>): Unit {
    val existing: Opt<Stats> = counts.get(name)
    if (existing.hasValue()) {
        val stats: Stats = existing.value()
        if (value < stats.min) {
            stats.min = value
        }
        if (value > stats.max) {
            stats.max = value
        }
        stats.sum = stats.sum + value
        stats.count = stats.count + 1
        counts.insert(name, stats)
    } else {
        counts.insert(name, Stats(value, value, value, 1))
    }
}

fun main(args: List<Str>): Int {
    if (args.size() < 1) {
        eprintln("usage: onebrc <measurements.txt>")
        return 2
    }
    val path: Str = args[0]

    val stream: *FileStream = openFileStream(path)
    if (stream == null) {
        eprintln("onebrc: cannot open " + path)
        return 2
    }
    val size: Int64 = stream.fileSize()

    val counts: Dictionary<Str, Stats> = dictionaryOf<Str, Stats>()
    var lines: Int = 0
    val started: Int64 = nowMillis()

    // The in-place read: a view into the stream's readahead buffer per line, valid
    // until the next read - which is exactly this loop's shape.
    var line: Opt<StrView> = stream.readLineView()
    while (line.hasValue()) {
        tallyView(line.value(), *counts)
        lines = lines + 1
        line = stream.readLineView()
    }

    val elapsed: Int64 = nowMillis() - started
    stream.close()

    var names: List<Str> = counts.keys()
    names.sort((left: Str, right: Str) -> left < right)
    var i: Int = 0
    while (i < names.size()) {
        val stats: Stats = counts.get(names[i]).value()
        println("{" + names[i] + "=" + formatTenths(stats.min) + "/"
                + formatTenths(meanOf(stats.sum, stats.count)) + "/" + formatTenths(stats.max) + "}")
        i = i + 1
    }

    var rate: Int64 = 0
    if (elapsed > 0) {
        rate = (size / 1048576) * 1000 / elapsed
    }
    eprintln(lines.toString() + " rows, " + counts.size().toString() + " stations, "
             + elapsed.toString() + " ms, " + rate.toString() + " MB/s")
    return 0
}
