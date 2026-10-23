# Results: Simse vs C++ over the Goose benchmark suite

Ratios of process wall time (median of 3 interleaved runs, same cl.exe and
flags for every row): the idiomatic C++ row (VARIANT 0) is the 1.00 baseline, and each
other column is that row's time divided by it - so the reading is machine-independent
to first order, a slower or busier core slowing both sides together. Sizes:
medium. Checksums verified across rows; a row that disagrees is a
mismatch, never timed.

| benchmark | Simse / std | hand-tuned / std |
| --- | --- | --- |
| sum | 0.93 | 0.77 |
| push | 1.61 | 1.40 |
| strlist | 1.08 | - |
| records | 1.12 | 0.88 |
| tree | 0.56 | 0.69 |
| interp | 0.91 | 1.00 |
| graph | 1.37 | 0.29 |
| words | 0.98 | 0.55 |
| particles | 1.44 | 1.17 |
| sexp | 0.88 | 0.73 |
| lru | 0.35 | 0.27 |
| scene | 0.77 | 0.62 |
| calc | 0.38 | 0.35 |
| bintrees | 0.29 | 0.30 |
| respond | 1.30 | 0.66 |
| blur | 1.19 | 0.72 |

