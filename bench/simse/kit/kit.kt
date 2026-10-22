package kit

// Shared support for the Simse benchmark implementations (bench/README.md). The
// benchmarks are ports of aardappel/goose's bench suite, whose generators and
// checksums are written against C++ `uint64_t` (bench/cpp/bench.h). Simse's
// scalars are signed, so the u64 values travel as `Int64` bit patterns and this
// kit recovers the three operations the signed surface lacks. Addition,
// subtraction and multiplication need no helper: the generated C++ multiplies
// and adds in two's-complement, which is the u64 wrap bit for bit (verified by
// the runner, which fails the bench when a checksum disagrees with the C++ row).
//
// A byte read out of a `Str` needs one more mask: `Char` is a *signed* 8-bit
// type, so `byteAt` answers 0..255 the way `uint8_t` reads.

// The xorshift64 the C++ references use: x ^= x << 13; x ^= x >> 7 (logical);
// x ^= x << 17. The left shifts are the modulo-2^64 ones C++20 defines; only
// the right shift needs the logical-shift helper.
fun xsNext(x: Int64): Int64 {
    var v: Int64 = x
    v = v ^ (v << 13)
    v = v ^ uShr(v, 7)
    v = v ^ (v << 17)
    return v
}

// The C++ references' `xs_mod`: the bit pattern modulo k, k > 0. A negative
// `x` names the pattern x + 2^64, and the wrap changes the value by 2^64,
// which is 0 only modulo a power of two - so the general case adds 2^64 mod k
// (built from 2^62 mod k, staying in positive territory throughout) to the
// normalized signed remainder.
fun xsMod(x: Int64, k: Int64): Int64 {
    if (x >= Int64(0)) {
        return x % k
    }
    val h: Int64 = 4294967296 % k
    val m64: Int64 = (h * h) % k
    return (((x % k) + k) % k + m64) % k
}

// Logical shift right of the 64-bit pattern: an arithmetic shift carries the
// sign bit in, so the high bits are masked off afterwards.
fun uShr(x: Int64, n: Int): Int64 {
    if (n == 0) {
        return x
    }
    return (x >> n) & ((Int64(1) << (64 - n)) - Int64(1))
}

// The unsigned decimal spelling of the bit pattern - what the C++ rows print
// with `printf("%llu")`. One binary long-division pass per digit: the running
// remainder stays below 10, so every step is small-integer work, and the
// quotient's bits are set with `1 << i`, whose bit pattern is the mask wanted.
fun uToString(x: Int64): Str {
    if (x == Int64(0)) {
        return "0"
    }
    var digits: Str = ""
    var v: Int64 = x
    while (v != Int64(0)) {
        var q: Int64 = 0
        var rem: Int64 = 0
        var i: Int = 63
        while (i >= 0) {
            rem = rem * 2 + (uShr(v, i) & Int64(1))
            if (rem >= Int64(10)) {
                rem -= Int64(10)
                q = q | (Int64(1) << i)
            }
            i--
        }
        digits.append(Char(48 + Int(rem)))
        v = q
    }
    var out: Str = ""
    var j: Int = digits.size() - 1
    while (j >= 0) {
        out.append(digits[j])
        j--
    }
    return out
}

// One byte of a `Str` or a view of one, 0..255: the unsigned read every C++
// row gets from `uint8_t`/`unsigned char`.
fun byteAt(s: *Str, i: Int): Int {
    return Int(s[i]) & 255
}
