# Results: Simse vs C++ over the Goose benchmark suite

Process wall time, median of 3 runs, same cl.exe and flags for both
(/O2 /Ob3 /GL /MD). Sizes: small, medium. Checksums verified across rows.

## sum

Build a flat i32 array, then scan it eight times.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| simse | 39 ms | 172 ms |
| cpp variant 0 | 42 ms | 120 ms |
| cpp variant 1 | 38 ms | 94 ms |

## push

Push N records while keeping a mark on every 64th.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| simse | 38 ms | 153 ms |
| cpp variant 0 | 38 ms | 117 ms |
| cpp variant 1 | 32 ms | 94 ms |
| cpp variant 2 | 49 ms | 395 ms |

## strlist

Split a text into N words, keep them, scan four times.

| implementation | small (N=200000) | medium (N=2000000) |
| --- | --- | --- |
| simse | 55 ms | 282 ms |
| cpp variant 0 | 40 ms | 226 ms |

## records

Build a log of N variant records, aggregate four times.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| simse | 91 ms | 377 ms |
| cpp variant 0 | 77 ms | 350 ms |
| cpp variant 1 | 77 ms | 339 ms |
| cpp variant 2 | 49 ms | 223 ms |

## tree

Build a complete binary tree of the given depth, sum eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| simse | 28 ms | 85 ms |
| cpp variant 0 | 33 ms | 171 ms |
| cpp variant 2 | 38 ms | 85 ms |

## interp

Build an expression tree of the given depth, evaluate eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| simse | 27 ms | 50 ms |
| cpp variant 0 | 40 ms | 40 ms |
| cpp variant 2 | 43 ms | 41 ms |

## graph

Adjacency for V vertices, 8V edges, BFS from four sources.

| implementation | small (V=100000) | medium (V=500000) |
| --- | --- | --- |
| simse | 98 ms | 1564 ms |
| cpp variant 0 | 138 ms | 1129 ms |
| cpp variant 1 | 43 ms | 218 ms |
| cpp variant 2 | 77 ms | 1321 ms |

## words

Count word frequencies over a text of N words.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| simse | 101 ms | 1331 ms |
| cpp variant 0 | 77 ms | 1191 ms |
| cpp variant 2 | 69 ms | 534 ms |

## particles

Integrate N particles for 200 steps of f32 math.

| implementation | small (N=100000) | medium (N=1000000) |
| --- | --- | --- |
| simse | 59 ms | 351 ms |
| cpp variant 0 | 58 ms | 331 ms |
| cpp variant 2 | 56 ms | 351 ms |

## sexp

Generate N s-expression forms, parse, walk four times.

| implementation | small (N=20000) | medium (N=100000) |
| --- | --- | --- |
| simse | 219 ms | 980 ms |
| cpp variant 0 | 240 ms | 1114 ms |
| cpp variant 1 | 160 ms | 727 ms |

## lru

An LRU cache of capacity N/8 under N skewed lookups.

| implementation | small (N=2000000) | medium (N=8000000) |
| --- | --- | --- |
| simse | 133 ms | 949 ms |
| cpp variant 0 | 490 ms | 2683 ms |
| cpp variant 1 | 93 ms | 674 ms |

## scene

A scene graph of the given depth, animated for 16 frames.

| implementation | small (depth=12) | medium (depth=14) |
| --- | --- | --- |
| simse | 35 ms | 155 ms |
| cpp variant 0 | 45 ms | 224 ms |
| cpp variant 1 | 35 ms | 137 ms |

## calc

Parse and evaluate N arithmetic expressions, a quarter malformed.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| simse | 104 ms | 328 ms |
| cpp variant 0 | 284 ms | 1041 ms |
| cpp variant 1 | 76 ms | 228 ms |

## bintrees

The Benchmarks Game binary-trees up to the given depth.

| implementation | small (depth=15) | medium (depth=17) |
| --- | --- | --- |
| simse | 67 ms | 191 ms |
| cpp variant 0 | 270 ms | 985 ms |
| cpp variant 1 | 50 ms | 166 ms |

## respond

Build and render N JSON responses.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| simse | 182 ms | 648 ms |
| cpp variant 0 | 122 ms | 459 ms |
| cpp variant 1 | 91 ms | 265 ms |

## blur

16 passes of a 3x3 blur over a WxW 8-bit image.

| implementation | small (W=1024) | medium (W=2048) |
| --- | --- | --- |
| simse | 81 ms | 265 ms |
| cpp variant 0 | 63 ms | 179 ms |
| cpp variant 1 | 38 ms | 74 ms |

## Simse / C++ (idiomatic variant) ratio

| benchmark | small | medium |
| --- | --- | --- |
| sum | 0.92x | 1.43x |
| push | 1.00x | 1.31x |
| strlist | 1.39x | 1.25x |
| records | 1.17x | 1.08x |
| tree | 0.86x | 0.50x |
| interp | 0.68x | 1.26x |
| graph | 0.71x | 1.39x |
| words | 1.30x | 1.12x |
| particles | 1.03x | 1.06x |
| sexp | 0.91x | 0.88x |
| lru | 0.27x | 0.35x |
| scene | 0.78x | 0.69x |
| calc | 0.37x | 0.32x |
| bintrees | 0.25x | 0.19x |
| respond | 1.49x | 1.41x |
| blur | 1.29x | 1.48x |

