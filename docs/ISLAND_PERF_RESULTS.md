# Local island performance verification

Measured 2026-09-30. Both default cases completed successfully. Required focused
implementation review passed after fixes and follow-up review.

## Complete default comparison

Command: `lein run -m georgetown.dev.island-perf`.
The [complete archived evidence bundle](artifacts/island-perf/README.md) includes
raw records, reports, initial fixtures, logs and both harness-owned databases,
with a SHA-256 checksum. Original local artifacts:
`target/island-perf/run-16268097977057157945`:

- 50: `case-50-16533502543553858093`
- 500: `case-500-1078460358748897298`

Each case completed 20 warm-up and 200 measured ticks in a distinct verified raw
JVM PID and fresh owned DB. Singleton remained uninitialized; server/scheduler/
push/developer-core namespaces were absent in the workers. Run comparison exit 0.

Environment: Linux x86_64, Intel Core i7-12700H, 20 logical cores, Java 26.0.1;
JVM max heap 8,371,830,784 bytes, both project `--add-opens` options preserved.
Metadata includes revision/worktree identity, effective classpath and SHA-256
identities for resolved database resources and harness/simulation source.
The effective `dat.api` resolved from the existing `checkouts/dat` source; it was
read for runtime provenance/behavior verification only, never modified.

| Initial citizens | Measured wall | Process CPU | CPU utilization | Tick p50 | Tick p95 | Tick max | Sampled RSS peak | Sampled used-heap peak |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 9.951 s | 27.940 s | 280.77% | 47.222 ms | 68.942 ms | 203.171 ms | 2,394,800,128 bytes (2.23 GiB) | 1,018,701,904 bytes (0.95 GiB) |
| 500 | 2161.484 s (36.02 min) | 28972.140 s | 1340.38% | 3515.539 ms | 26811.585 ms | 30027.340 ms | 7,566,725,120 bytes (7.05 GiB) | 1,022,118,056 bytes (0.95 GiB) |

Sum of measured tick durations: 8.456 s / 2153.627 s. Successful measured
RSS/heap sample counts: 101 / 21441. CPU 100% means one core, so 1340% is about
13.4 core-equivalents of sustained process CPU, not 1340% of the entire machine.
Other applications and short harness tests ran on this developer machine during
part of the comparison; these results are not controlled dedicated-host numbers.

Database logical file bytes (not allocated disk space or RAM):

| Initial citizens | Setup end | Warm-up end | Measured end |
|---:|---:|---:|---:|
| 50 | 233605 | 479365 | 479365 |
| 500 | 1089669 | 2691205 | 3543173 |

## Viability and drift

Both cases selected warm-up epochs 16–19 and measured epochs 216–219 from the
pre-tick public-stat epochs, with complete morning-to-night shifts.

| Initial citizens | Final measured-cycle housing | Paid wages | Gross food produced | Food sold |
|---:|---:|---:|---:|---:|
| 50 | 50 | 610 | 388.720 | 132 |
| 500 | 469 | 5750 | 3708.094 | 1093 |

Warm-up cycles also had positive activity: housing 50/500, wages 610/5750,
production 390.002/3750.049, sales 200/2000. No required activity was missing.

State snapshots, in initial → warm-up-end → measured-end order:

| Initial citizens | Population | Citizen savings total | Owner balance | Government balance | Owner food stock |
|---:|---|---|---|---|---|
| 50 | 50 → 50 → 50 | 5000 → 977981.84 → 2391204.98 | 5000000 → 4031435 → 2644644 | 50000 → 45562 → 18927 | 400 → 1350.046 → 12944.304 |
| 500 | 500 → 500 → 469 | 50000 → 9778809.57 → 22697096.75 | 50000000 → 40315646 → 26174958 | 500000 → 455523 → 188509 | 4000 → 12750.402 → 135835.34 |

The heavily funded synthetic economy exhibits substantial money redistribution,
food accumulation and population drift. It is viable by the activity contract,
not calibrated to production balance. The large case is **initially** 500 citizens,
not fixed at 500 throughout. Per-tick public stats retain deaths/emigration and
holder/stock snapshots for further diagnosis. No stocks or funds were replenished.

## Verification and calibration

- Final full targeted suite: 20 tests / 303 assertions, zero failures/errors, exit 0.
- Final deliberate failing runner: 21 tests / 304 assertions, exactly one intended
  assertion failure, zero errors, exit 1.
- Real 50-citizen 4+4 smoke: exit 0, both cycles viable and real CPU/RSS/heap.
  Artifacts: `target/island-perf/run-1672189063612711887`.
- Override 8 citizens, 8+12 ticks, 180-second cap: exit 0.
  Artifacts: `target/island-perf/run-13740755076592606270`.
- Restore 50-citizen smoke fixture into a fresh DB: exit 0.
  Artifacts: `target/island-perf/run-18276863345153267011`.
- Restore canonical initial state, persisted epochs, configured-access guards,
  setup/warm-up/measured partial errors, CPU boundaries, resource filtering,
  sentinel isolation, deadlines/escalation and parent shutdown are tested.

The first 1800-second trial retained a successful 50 case and 500 partial records
(20 warm-up / 109 measured). Its outer shell timeout fired before supervisor
finalization; both parent and child PIDs were verified absent. There is no valid
end CPU boundary, so no CPU summary is claimed for that partial case. Artifacts:
`target/island-perf/run-4830582341075453191`. The finite default deadline was raised
to 7200 seconds without changing 20/200 tick counts or simulation. The complete
rerun used a 15000-second outer bound, longer than both case limits plus grace.

A subsequent attempt had a 500-child startup compilation failure from reading
source during an edit (`Sys` truncation), not workload failure. Evidence retained:
`target/island-perf/run-11974174199083150782`. Source was frozen and compilation
rechecked before the complete comparison. Early test source/assertion errors were
fixed. Fake-child startup deadlines were increased from 10 to 30 seconds (stall
4 to 15 seconds) to avoid load-sensitive false failures; bounds remain tested.

An exploratory `lein trampoline run` compile loaded developer RCF hooks and
printed three existing allocation assertions (stroll capacity expected 10,
actual 50). These are not harness tests. The targeted runner disables RCF before
loading its explicit clojure.test namespaces; measured children do not load
developer core. No production tests or simulation code were changed.

## Implementation review

A general-purpose reviewer inspected implementation, database isolation/lifecycle,
metric math, viability, tests and documentation after complete default acceptance.
Initial verdict: one Important shutdown race and two Minor findings.

- Important: shutdown could miss a worker created before registration. Fixed with
  one lifecycle lock and shutdown-started flag shared by launch/registration and
  the hook. Bounded termination stays outside the lock. Deterministic tests cover
  shutdown before launch and between process creation and registration; the two
  new tests also passed ten repeated runs (20 executions / 110 assertions).
- Minor: metadata invariant wording contradicted permitted classpath provenance.
  Corrected to distinguish paths/resource URLs/hashes from source contents and
  document allowlisted inherited JVM flags.
- Minor: missing worker-level invalid/unverified exit coverage. Added real
  stock-only zero-production and short-phase scenarios, checking diagnostics,
  comparison status and exit codes.

Follow-up review: **PASS**, no unresolved Critical/Important findings; all prior
findings resolved. Final full suite and intentional-failure checks above ran
again after fixes. Benchmark rerun was not needed: fixes changed supervisor
lifecycle/tests/documentation, not the measured worker or fixture workload.

These are local sustained-load observations, not idle production utilization,
leak evidence, or a production-crash reproduction. RSS growth cannot be attributed
to a particular native/database category by subtracting heap. See
[ISLAND_PERF.md](ISLAND_PERF.md) for definitions and limitations.
