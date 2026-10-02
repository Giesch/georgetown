# Full-tick benchmarks

This development-only suite times `georgetown.sim.tick/tick!`, including database
extraction, all rules/native allocation, and the transaction. It never starts the
application, authenticates users, sends mail, or uses the configured database.

## Commands

Run from the repository root with the existing JVM add-opens options:

```sh
lein run -m georgetown.dev.tick-bench-test-runner
lein run -m georgetown.dev.tick-bench --smoke
lein run -m georgetown.dev.tick-bench --population 50 --shift morning
lein run -m georgetown.dev.tick-bench
lein run -m georgetown.dev.tick-bench --population 500 --scenario baseline --shift night --samples 30 --warmup-calls 4 --warmup-seconds 10 --warmup-limit 1000
lein run -m georgetown.dev.tick-bench --regenerate target/tick-bench/RUN-ID
```

REPL (without loading dev core or starting the server):

```clojure
(require '[georgetown.dev.tick-bench :as bench])
(bench/run! {:smoke true})
(bench/run! {:populations [50] :shifts [:morning] :scenarios [:baseline]})
(require '[georgetown.dev.tick-bench-report :as report])
(report/generate! (clojure.java.io/file "target/tick-bench/RUN-ID"))
```

Selectors accept only populations 50/500, scenario baseline, and shifts morning,
afternoon, evening, night. Unknown selectors fail before opening a database.
REPL selection vectors can select several cases; CLI selectors select one value.
Cases run sequentially. Normal runs default to 30 measured calls per case and
require **both** four warmup calls and ten seconds of accumulated tick execution.
Restoration does not count toward the ten seconds. Larger minima are configurable;
the warmup loop fails at its call limit (at most 1000). This is not a native-call
interruption or hard timeout. Normal runs require at least ten samples.

Smoke runs use one warmup and two measured calls, bypass normal warmup minima,
and are explicitly non-statistical. Smoke verifies integration, not performance.
There are no hardware pass/fail thresholds.

## Input and measurement boundary

The eight baseline cases are two players with 50 or 500 citizens, at each of four
starting shifts (epochs 0–3). Both owners have funded housing, farms, markets and
food stocks. The fixture recipe and logical transaction data are saved with each
case. Every warmup and measured call restores and validates the same fixture
**outside** the timestamps. The timed region contains exactly one complete tick.
The resulting epoch is checked after timing. A failed tick is not a sample.

Deterministic fixtures mean reproducible **starting input**, not reproducible
outcomes: production tick randomness remains unchanged. Births, deaths and other
stochastic changes are permitted. Correctness tests use controlled random choices;
benchmark measurements do not.

This is a project-owned single-invocation timing loop, public Criterium statistics,
and fastester reporting—not stock Criterium/fastester timing. Raw durations are
nanoseconds; Criterium time estimates are seconds (variance is seconds squared).
Each sample has execution-count 1. Restoration, validation, statistics, rendering
and connection closure are excluded. No reset-cost or timing-overhead subtraction
is applied. Single-call noise differs from amortized Criterium batches. Database
restoration affects caches and allocation/GC pressure; warmups do not eliminate
these effects. Compare equivalent environments and recipe versions, not isolated
numbers from unrelated machines.

## Artifacts and failures

Each run creates a unique directory under `target/tick-bench`. It preserves status,
requested/achieved settings, explicitly selected safe runtime/dependency/source
versions, fixture data/manifests/fingerprints, and raw samples. Report ingestion is
kept separate from raw and status artifacts. Normal runs additionally save compatible
Criterium result EDN and generate fastester HTML/Markdown. Regeneration reads saved
results and does not execute ticks. It accepts a suite-owned run directory, not an
arbitrary executable options file.

The suite does not dump configuration, environment variables, credentials or JVM
arguments. Criterium runtime-details/input-arguments are removed before persistence.
Failure preserves partial artifacts with failed status and propagates phase/case
context in the REPL; the CLI returns nonzero. Connections close on success and
catchable failure. Benchmark-owned databases remain in their unique case folders;
no unrelated data is deleted.

## Extending scenarios

Only baseline is implemented. Add a named fixture dispatch and explicit selector
entry when adding a scenario; reuse the same populations/shifts and runner rather
than creating a different timing path. Change recipe/format versions when changing
logical fixtures or artifacts. Record new capacities/funding in the manifest and
extend fingerprint, restoration and activity tests. Do not derive later-shift
fixtures by running random ticks.
