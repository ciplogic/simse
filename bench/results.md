# Results: Simse vs C++ over the Goose benchmark suite

Ratios of process wall time (median of 3 interleaved runs, same cl.exe and
flags for every row): the idiomatic C++ row (VARIANT 0) is the 1.00 baseline, and each
other column is that row's time divided by it - so the reading is machine-independent
to first order, a slower or busier core slowing both sides together. Sizes:
medium. Checksums verified across rows; a row that disagrees is a
mismatch, never timed.

| benchmark | Simse / std | hand-tuned / std |
| --- | --- | --- |
| sum | 1.01 | 0.93 |
| push | 1.13 | 0.97 |
| strlist | 1.08 | - |
| records | 1.15 | 0.74 |
| tree | 0.52 | 0.70 |
| interp | 0.62 | 0.60 |
| graph | 1.34 | 0.24 |
| words | 0.93 | 0.42 |
| particles | 0.96 | 1.05 |
| sexp | 0.87 | 0.66 |
| lru | 0.30 | 0.23 |
| scene | 0.70 | 0.75 |
| calc | 0.36 | 0.30 |
| bintrees | 0.29 | 0.28 |
| respond | 1.36 | 0.67 |
| blur | 1.22 | 0.76 |

