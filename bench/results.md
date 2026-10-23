# Results: Simse vs C++ over the Goose benchmark suite

Process wall time, median of 3 runs, same cl.exe and flags for both
(/O2 /Ob3 /GL /MD). Sizes: small, medium. Checksums verified across rows.

## sum

Build a flat i32 array, then scan it eight times.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 33 ms | 130 ms |
| cpp variant 1 | 64 ms | 114 ms |
| simse | 63 ms | 204 ms |

## push

Push N records while keeping a mark on every 64th.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 30 ms | 107 ms |
| cpp variant 1 | 42 ms | 75 ms |
| cpp variant 2 | 66 ms | 225 ms |
| simse | 46 ms | 116 ms |

## strlist

Split a text into N words, keep them, scan four times.

| implementation | small (N=200000) | medium (N=2000000) |
| --- | --- | --- |
| cpp variant 0 | 38 ms | 147 ms |
| simse | 53 ms | 188 ms |

## records

Build a log of N variant records, aggregate four times.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 51 ms | 253 ms |
| cpp variant 1 | 61 ms | 266 ms |
| cpp variant 2 | 42 ms | 188 ms |
| simse | 72 ms | 286 ms |

## tree

Build a complete binary tree of the given depth, sum eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 22 ms | 104 ms |
| cpp variant 2 | 30 ms | 67 ms |
| simse | 31 ms | 62 ms |

## interp

Build an expression tree of the given depth, evaluate eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 18 ms | 42 ms |
| cpp variant 2 | 31 ms | 23 ms |
| simse | 26 ms | 42 ms |

## graph

Adjacency for V vertices, 8V edges, BFS from four sources.

| implementation | small (V=100000) | medium (V=500000) |
| --- | --- | --- |
| cpp variant 0 | 78 ms | 707 ms |
| cpp variant 1 | 60 ms | 135 ms |
| cpp variant 2 | 57 ms | 820 ms |
| simse | 66 ms | 980 ms |

## words

Count word frequencies over a text of N words.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 71 ms | 1005 ms |
| cpp variant 2 | 58 ms | 505 ms |
| simse | 99 ms | 1064 ms |

## particles

Integrate N particles for 200 steps of f32 math.

| implementation | small (N=100000) | medium (N=1000000) |
| --- | --- | --- |
| cpp variant 0 | 59 ms | 326 ms |
| cpp variant 2 | 71 ms | 371 ms |
| simse | 64 ms | 372 ms |

## sexp

Generate N s-expression forms, parse, walk four times.

| implementation | small (N=20000) | medium (N=100000) |
| --- | --- | --- |
| cpp variant 0 | 258 ms | 1138 ms |
| cpp variant 1 | 171 ms | 768 ms |
| simse | 238 ms | 1009 ms |

## lru

An LRU cache of capacity N/8 under N skewed lookups.

| implementation | small (N=2000000) | medium (N=8000000) |
| --- | --- | --- |
| cpp variant 0 | 445 ms | 2798 ms |
| cpp variant 1 | 98 ms | 753 ms |
| simse | 123 ms | 1032 ms |

## scene

A scene graph of the given depth, animated for 16 frames.

| implementation | small (depth=12) | medium (depth=14) |
| --- | --- | --- |
| cpp variant 0 | 53 ms | 225 ms |
| cpp variant 1 | 44 ms | 147 ms |
| simse | 47 ms | 177 ms |

## calc

Parse and evaluate N arithmetic expressions, a quarter malformed.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 289 ms | 1122 ms |
| cpp variant 1 | 76 ms | 231 ms |
| simse | 116 ms | 352 ms |

## bintrees

The Benchmarks Game binary-trees up to the given depth.

| implementation | small (depth=15) | medium (depth=17) |
| --- | --- | --- |
| cpp variant 0 | 244 ms | 1056 ms |
| cpp variant 1 | 53 ms | 163 ms |
| simse | 71 ms | 193 ms |

## respond

Build and render N JSON responses.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 148 ms | 476 ms |
| cpp variant 1 | 83 ms | 271 ms |
| simse | 188 ms | 648 ms |

## blur

16 passes of a 3x3 blur over a WxW 8-bit image.

| implementation | small (W=1024) | medium (W=2048) |
| --- | --- | --- |
| cpp variant 0 | 64 ms | 194 ms |
| cpp variant 1 | 36 ms | 83 ms |
| simse | 84 ms | 267 ms |

## Simse / C++ (idiomatic variant) ratio

| benchmark | small | medium |
| --- | --- | --- |
| sum | 1.91x | 1.58x |
| push | 1.52x | 1.08x |
| strlist | 1.41x | 1.27x |
| records | 1.40x | 1.13x |
| tree | 1.39x | 0.60x |
| interp | 1.48x | 1.00x |
| graph | 0.84x | 1.39x |
| words | 1.39x | 1.06x |
| particles | 1.08x | 1.14x |
| sexp | 0.92x | 0.89x |
| lru | 0.28x | 0.37x |
| scene | 0.90x | 0.79x |
| calc | 0.40x | 0.31x |
| bintrees | 0.29x | 0.18x |
| respond | 1.27x | 1.36x |
| blur | 1.30x | 1.38x |

