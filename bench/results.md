# Results: Simse vs C++ over the Goose benchmark suite

Process wall time, median of 3 runs, same cl.exe and flags for both
(/O2 /Ob3 /GL /MD). Sizes: small, medium. Checksums verified across rows.

## sum

Build a flat i32 array, then scan it eight times.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 23 ms | 120 ms |
| cpp variant 1 | 49 ms | 97 ms |
| simse | 61 ms | 151 ms |

## push

Push N records while keeping a mark on every 64th.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp variant 0 | 35 ms | 128 ms |
| cpp variant 1 | 37 ms | 109 ms |
| cpp variant 2 | 75 ms | 257 ms |
| simse | 58 ms | 151 ms |

## strlist

Split a text into N words, keep them, scan four times.

| implementation | small (N=200000) | medium (N=2000000) |
| --- | --- | --- |
| cpp variant 0 | 37 ms | 174 ms |
| simse | 63 ms | 315 ms |

## records

Build a log of N variant records, aggregate four times.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 50 ms | 247 ms |
| cpp variant 1 | 83 ms | 272 ms |
| cpp variant 2 | 60 ms | 164 ms |
| simse | 79 ms | 278 ms |

## tree

Build a complete binary tree of the given depth, sum eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 25 ms | 135 ms |
| cpp variant 2 | 25 ms | 50 ms |
| simse | 37 ms | 68 ms |

## interp

Build an expression tree of the given depth, evaluate eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp variant 0 | 32 ms | 57 ms |
| cpp variant 2 | 40 ms | 64 ms |
| simse | 37 ms | 36 ms |

## graph

Adjacency for V vertices, 8V edges, BFS from four sources.

| implementation | small (V=100000) | medium (V=500000) |
| --- | --- | --- |
| cpp variant 0 | 78 ms | 639 ms |
| cpp variant 1 | 46 ms | 121 ms |
| cpp variant 2 | 90 ms | 815 ms |
| simse | 75 ms | 932 ms |

## words

Count word frequencies over a text of N words.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp variant 0 | 62 ms | 632 ms |
| cpp variant 2 | 57 ms | 302 ms |
| simse | 99 ms | 597 ms |

## particles

Integrate N particles for 200 steps of f32 math.

| implementation | small (N=100000) | medium (N=1000000) |
| --- | --- | --- |
| cpp variant 0 | 42 ms | 166 ms |
| cpp variant 2 | 66 ms | 216 ms |
| simse | 63 ms | 182 ms |

## sexp

Generate N s-expression forms, parse, walk four times.

| implementation | small (N=20000) | medium (N=100000) |
| --- | --- | --- |
| cpp variant 0 | 166 ms | 786 ms |
| cpp variant 1 | 130 ms | 504 ms |
| simse | 163 ms | 702 ms |

## lru

An LRU cache of capacity N/8 under N skewed lookups.

| implementation | small (N=2000000) | medium (N=8000000) |
| --- | --- | --- |
| cpp variant 0 | 272 ms | 1767 ms |
| cpp variant 1 | 67 ms | 391 ms |
| simse | 95 ms | 556 ms |

## scene

A scene graph of the given depth, animated for 16 frames.

| implementation | small (depth=12) | medium (depth=14) |
| --- | --- | --- |
| cpp variant 0 | 55 ms | 149 ms |
| cpp variant 1 | 36 ms | 116 ms |
| simse | 63 ms | 118 ms |

## calc

Parse and evaluate N arithmetic expressions, a quarter malformed.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 186 ms | 604 ms |
| cpp variant 1 | 75 ms | 172 ms |
| simse | 102 ms | 245 ms |

## bintrees

The Benchmarks Game binary-trees up to the given depth.

| implementation | small (depth=15) | medium (depth=17) |
| --- | --- | --- |
| cpp variant 0 | 149 ms | 492 ms |
| cpp variant 1 | 42 ms | 135 ms |
| simse | 86 ms | 155 ms |

## respond

Build and render N JSON responses.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp variant 0 | 96 ms | 294 ms |
| cpp variant 1 | 54 ms | 202 ms |
| simse | 120 ms | 457 ms |

## blur

16 passes of a 3x3 blur over a WxW 8-bit image.

| implementation | small (W=1024) | medium (W=2048) |
| --- | --- | --- |
| cpp variant 0 | 60 ms | 106 ms |
| cpp variant 1 | 33 ms | 52 ms |
| simse | 57 ms | 156 ms |

## Simse / C++ (idiomatic variant) ratio

| benchmark | small | medium |
| --- | --- | --- |
| sum | 2.65x | 1.26x |
| push | 1.67x | 1.18x |
| strlist | 1.73x | 1.81x |
| records | 1.59x | 1.12x |
| tree | 1.46x | 0.50x |
| interp | 1.13x | 0.63x |
| graph | 0.96x | 1.46x |
| words | 1.60x | 0.94x |
| particles | 1.50x | 1.10x |
| sexp | 0.98x | 0.89x |
| lru | 0.35x | 0.31x |
| scene | 1.14x | 0.79x |
| calc | 0.55x | 0.41x |
| bintrees | 0.58x | 0.32x |
| respond | 1.25x | 1.56x |
| blur | 0.96x | 1.46x |

