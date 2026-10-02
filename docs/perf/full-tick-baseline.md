# Full-tick baseline performance report

Measured on 2026-10-01 against source revision `ead8fb130f03d51aea70800b649ba1ebbcc21e5a` plus the development-only benchmark implementation. This establishes a baseline; it is not a before/after comparison or a claim of improved production performance.

## Statistical run

Command: `lein run -m georgetown.dev.tick-bench --population 50 --shift morning`

| Item | Result |
| --- | --- |
| Scenario | baseline, morning, population selector 50, two players |
| Measured full ticks | 30 |
| Criterium mean estimate | 273.4 ms |
| Mean bootstrap interval (saved result) | 174.2–483.2 ms |
| Standard deviation (square root of Criterium variance estimate) | 395.3 ms |
| Observed sample range | 64.5–1793.9 ms |
| Outliers | 1 high-mild, 5 high-severe |
| Warmup | 34 calls; 10.011 s accumulated tick execution; 34.844 s wall time |

The wide spread and outliers make this a noisy baseline. Do not treat the mean as a stable performance target. The generated chart shows mean ± standard deviation, not a confidence interval. Bootstrap settings are 1000 resamples and tail quantile 0.025.

Environment: Linux amd64, kernel `7.1.5-76070105-generic`, 20 available processors; Eclipse Adoptium Java 26.0.1; Clojure 1.11.0; Datalevin 1.0.2; Criterium 0.4.6; fastester 1. CPU model and memory capacity were not captured.

## Measurement boundary

Each sample times exactly one complete `georgetown.sim.tick/tick!`, including database extraction, rules/native allocation and transaction. Fixture restoration and fingerprint validation occur before timing; epoch verification occurs after timing. Statistics, rendering and connection closure are excluded. No timing-overhead or reset-cost subtraction is applied.

Starting inputs are reproducible; production random outcomes are not. Restoration affects caches, allocation and GC. These single-call measurements differ from amortized Criterium batches. Compare only equivalent environments and fixture versions.

## Integration and verification

The saved smoke run completed all eight cases: populations 50/500 across morning, afternoon, evening and night. Each case used one warmup and two measured calls. Smoke results are non-statistical and are not performance estimates.

On 2026-10-02, `lein run -m georgetown.dev.tick-bench-test-runner` passed **18 tests / 434 assertions**, with zero failures or errors. Existing native-access and Unsafe deprecation warnings remain.

The saved real runs preceded the final requested-settings and failed-result quarantine fixes. The final suite covers those changes; these archived measurements were not rerun after them. See [implementation verification](../TICK_BENCH_VERIFICATION.md) for earlier verification and limits.

## Supporting artifacts

Download [tick-bench-artifacts.tar.gz](tick-bench-artifacts.tar.gz) and extract it locally:

```sh
tar -xzf tick-bench-artifacts.tar.gz
```

The archive preserves two successful run directories:

- `1790861386049-7d9c1933-c6f0-4b70-9d60-4e81fc0a238e`: statistical run; open `reports/index.html` for the chart/table. Includes generated Markdown, SVG, Criterium result EDN, raw samples, logical fixture data, report manifest and status/provenance.
- `1790861622092-5d8a50de-fe6b-4f5e-a540-3e1d9bacd3e5`: eight-case smoke run; includes raw samples, logical fixture data and status/provenance.

Database binaries/locks, failed and synthetic test runs, and the local dependency checkout are excluded. Original generated fastester options retain historical absolute paths; use `--regenerate` with the extracted statistical run directory to generate options for its new location rather than executing that historical options file.
