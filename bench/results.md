# Results: Simse vs C++ over the Goose benchmark suite

Ratios of process wall time (median of 3 interleaved runs, same cl.exe and
flags for every row): the idiomatic C++ row (VARIANT 0) is the 1.00 baseline, and each
other column is that row's time divided by it - so the reading is machine-independent
to first order, a slower or busier core slowing both sides together. Sizes:
medium. Checksums verified across rows; a row that disagrees is a
mismatch, never timed.

| benchmark | Simse / std | hand-tuned / std |
| --- | --- | --- |
| sum | 1.13 | 1.08 |
| push | 0.86 | 0.81 |
| strlist | 0.97 | - |
| records | 1.00 | 0.84 |
| tree | 0.49 | 0.42 |
| interp | 0.68 | 1.00 |
| graph | 1.18 | 0.17 |
| words | 0.95 | 0.37 |
| particles | 1.02 | 1.07 |
| sexp | 0.84 | 0.68 |
| lru | 0.32 | 0.23 |
| scene | 0.58 | 0.60 |
| calc | 0.31 | 0.29 |
| bintrees | 0.24 | 0.23 |
| respond | 1.45 | 0.57 |
| blur | 1.23 | 0.38 |

