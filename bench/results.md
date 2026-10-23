# Results: Simse vs C++ over the Goose benchmark suite

Process wall time, median of 3 runs, same cl.exe and flags for both
(/O2 /Ob3 /GL /MD). Sizes: small, medium. Checksums verified across rows.

## sum

Build a flat i32 array, then scan it eight times.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 83 ms | 104 ms |
| cpp variant 1 | 77 ms | 99 ms |
| simse | 62 ms | 146 ms |

## push

Push N records while keeping a mark on every 64th.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 62 ms | 122 ms |
| cpp variant 1 | 61 ms | 119 ms |
| cpp variant 2 | 70 ms | 248 ms |
| simse | 58 ms | 131 ms |

## strlist

Split a text into N words, keep them, scan four times.

| implementation | small (N=200000) | medium (N=2000000) |
| --- | --- | --- |
| cpp variant 0 | 74 ms | 188 ms |
| simse | 88 ms | 333 ms |

## records

Build a log of N variant records, aggregate four times.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 85 ms | 256 ms |
| cpp variant 1 | 100 ms | 273 ms |
| cpp variant 2 | 82 ms | 217 ms |
| simse | 97 ms | 305 ms |

## tree

Build a complete binary tree of the given depth, sum eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 58 ms | 141 ms |
| cpp variant 2 | 71 ms | 92 ms |
| simse | 81 ms | 99 ms |

## interp

Build an expression tree of the given depth, evaluate eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 51 ms | 76 ms |
| cpp variant 2 | 52 ms | 71 ms |
| simse | 77 ms | 91 ms |

## words

Count word frequencies over a text of N words.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 87 ms | 741 ms |
| cpp variant 2 | 77 ms | 351 ms |
| simse | 115 ms | 765 ms |

## particles

Integrate N particles for 200 steps of f32 math.

| implementation | small (N=100000) | medium (N=1000000) |
| --- | --- | --- |
| cpp variant 0 | 68 ms | 234 ms |
| cpp variant 2 | 80 ms | 248 ms |
| simse | 80 ms | 247 ms |

## sexp

Generate N s-expression forms, parse, walk four times.

| implementation | small (N=20000) | medium (N=100000) |
| --- | --- | --- |
| cpp variant 0 | 191 ms | 861 ms |
| cpp variant 1 | 175 ms | 538 ms |
| simse | 197 ms | 766 ms |

## lru

An LRU cache of capacity N/8 under N skewed lookups.

| implementation | small (N=2000000) | medium (N=8000000) |
| --- | --- | --- |
| cpp variant 0 | 335 ms | 2006 ms |
| cpp variant 1 | 116 ms | 472 ms |
| simse | 112 ms | 617 ms |

## scene

A scene graph of the given depth, animated for 16 frames.

| implementation | small (depth=12) | medium (depth=14) |
| --- | --- | --- |
| cpp variant 0 | 62 ms | 193 ms |
| cpp variant 1 | 63 ms | 149 ms |
| simse | 67 ms | 151 ms |

## calc

Parse and evaluate N arithmetic expressions, a quarter malformed.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 231 ms | 689 ms |
| cpp variant 1 | 111 ms | 200 ms |
| simse | failed | failed |

Checksum problems: small: simse line 1: expected 4149826, got 4153873; medium: simse line 1: expected 16589727, got 16599342

## bintrees

The Benchmarks Game binary-trees up to the given depth.

| implementation | small (depth=15) | medium (depth=17) |
| --- | --- | --- |
| cpp variant 0 | 165 ms | 569 ms |
| cpp variant 1 | 79 ms | 173 ms |
| simse | 98 ms | 181 ms |

## respond

Build and render N JSON responses.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 135 ms | 334 ms |
| cpp variant 1 | 90 ms | 210 ms |
| simse | failed | failed |

Checksum problems: small: simse line 2: expected 22983649, got 22983584; medium: simse line 2: expected 91800236, got 91800917

## blur

16 passes of a 3x3 blur over a WxW 8-bit image.

| implementation | small (W=1024) | medium (W=2048) |
| --- | --- | --- |
| cpp variant 0 | 76 ms | 130 ms |
| cpp variant 1 | 70 ms | 101 ms |
| simse | 87 ms | 187 ms |

## Simse / C++ (idiomatic variant) ratio

| benchmark | small | medium |
| --- | --- | --- |
| sum | 0.75x | 1.41x |
| push | 0.94x | 1.07x |
| strlist | 1.18x | 1.78x |
| records | 1.13x | 1.19x |
| tree | 1.38x | 0.70x |
| interp | 1.50x | 1.20x |
| words | 1.32x | 1.03x |
| particles | 1.17x | 1.06x |
| sexp | 1.03x | 0.89x |
| lru | 0.33x | 0.31x |
| scene | 1.08x | 0.78x |
| bintrees | 0.60x | 0.32x |
| blur | 1.15x | 1.44x |

