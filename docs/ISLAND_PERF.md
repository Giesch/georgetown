# Local island performance measurements

This developer-only harness runs the real `georgetown.sim.tick/tick!`, including
world extraction, simulation rules, native allocation solver and database writes.
It runs cases sequentially, each in a fresh raw Java child process and fresh
harness-owned database. It never starts the server, scheduler or clients and
never opens the configured developer database. Child-local guards reject singleton
DB and config access. It does not modify `checkouts/`.

## Commands

From the repository root with Java and Leiningen installed:

```sh
# Manual acceptance: 50 then 500 citizens, each 20 warm-up + 200 measured ticks
lein run -m georgetown.dev.island-perf

# Small real smoke: two full four-shift cycles
lein run -m georgetown.dev.island-perf --population 50 --warmup 4 --measured 4 --timeout-seconds 180

# Overrides; no sleeps between ticks
lein run -m georgetown.dev.island-perf --population 8 --warmup 8 --measured 12 --timeout-seconds 180

# Rerun preserved initial fixture in a new DB (use the actual artifact path)
lein run -m georgetown.dev.island-perf --population 50 --fixture target/island-perf/RUN/CASE/initial-fixture.edn --warmup 4 --measured 4 --timeout-seconds 180

# Targeted unattended clojure.test suite, not inline RCF
lein run -m georgetown.dev.island-perf-test-runner
# Deliberate assertion failure: must exit nonzero
lein run -m georgetown.dev.island-perf-test-runner --deliberate-failure
```

Whole-case timeout defaults to 7200 seconds. The initial 1800-second limit was
calibrated upward because the local 500-citizen case completed only about half
of its measured ticks after 27 minutes; tick counts remain 20/200. It starts before child launch and
covers loading/setup, warm-up and measurement without resetting. Termination has
separate finite bounds: 2 seconds graceful termination, then 2 seconds forced
reap. Catchable parent shutdown also terminates its owned worker. SIGKILL or
machine failure cannot guarantee cleanup/finalization. No lock files are deleted.

Every invocation creates a unique directory below `target/island-perf` (override
with `--report-dir`). It retains databases, fixture EDN, per-case `records.edn`
(one incremental EDN record per line), `report.edn`, `supervisor.edn`, child stdout
and stderr, plus run `metadata.edn` and `comparison.edn`. Do not point output at
`checkouts/` or developer data. No automatic deletion is performed. To remove
artifacts, remove only a known harness-created run directory after its process
has exited. Errors/timeouts produce non-success comparison status without
erasing other cases. A timeout may have records but no final child report.

## Fixture and viability

Recipe v1 starts exactly N citizens at epoch 0, with stable population-specific
UUIDs and explicit schema transaction data. It uses ceiling rounding for
apartments N/25, farms N/4 and food markets N/20. Capacities are respectively
25 housing, 2 farm jobs per active shift and 25 food sales plus 1 market job per
active shift. Farm work occurs morning/afternoon; market work occurs three daytime
shifts; shelter is sold at night. All buildings connect through lots/deeds to one
funded owner. Citizens start with 100 savings, skills/talents/preferences 0.75;
owner funding is 100000*N, government funding 1000*N, owner food stock 8*N,
initial market labour stock 25 per market. Rent and food cost 1; offered wage 10.
`fixture-manifest` records rounding, actual building/capacity totals and funding.
There is no replenishment or reset during ticks.

The complete initial transaction fixture is saved before warm-up. Restoring it
into a fresh DB reproduces initial identity/state, not a random trajectory.
Observations after every completed tick retain overwritten public stats and offer
utilization, citizen/player balances, stocks and holder sets. Initial, warm-up-end
and measured-end snapshots show drift. Public stats record deaths/emigrations;
holder changes expose removals. The latest complete morning–afternoon–evening–night
sequence wholly within **each** phase is selected using public-stats pre-tick
epoch/shift, excluding trailing incomplete sequences. A valid cycle requires
positive night housing occupancy, actual paid wages, gross food production and
food sales. Gross production/wages are captured at real assignment effects using
pre-effect productivity, before applying the original function unchanged; stock
growth alone is not a production witness. Missing activity is fixture-invalid and
non-success. A custom phase with no complete cycle is unverified, permitted to
exit zero when otherwise successful; it is not economy acceptance evidence.

## Measurement definitions

* Tick latency: monotonic duration of the full `tick!` call for completed measured
  ticks. p50/p95 use nearest rank: sorted values at rank ceil(p*N). Empty series
  is unavailable. Failed/interrupted ticks do not count as completed.
* CPU: child OperatingSystemMXBean cumulative process CPU at measured boundaries.
  CPU percent = 100 * CPU delta / measured elapsed wall. 100% means one logical
  core; values above 100% are valid. Includes JVM/GC/native threads and harness
  instrumentation, excludes launcher CPU. Missing boundaries are unavailable, not
  zero. Cooperative partial exits use the child's actual partial boundaries.
* Measured elapsed wall is separate from sum of tick durations. Post-tick
  observations/persistence and sampling are included in phase CPU/wall but outside
  tick duration (assignment witness instrumentation is necessarily inside tick).
* RSS: Linux `/proc/self/status` VmRSS, KiB converted to bytes. JVM heap:
  MemoryMXBean used/committed/max bytes, read inside the child. Samples carry
  phase, actual timestamps, reasons and PID. Default periodic cadence is 100 ms,
  plus attempted measured entry/exit reads. Measured peaks are **observed sampled**
  RSS/used-heap peaks within boundaries, not lifetime high-water or absolute peaks.
* Database size: sum of regular file logical lengths in bytes at setup-end,
  warm-up-end and measured-end. This is not allocated disk space or resident
  memory. Boundary directory sizing is excluded from tick and measured interval.
* Metadata records selected OS/CPU/JVM information, effective settings, revision,
  tracked worktree status, resolved classpath and dependency resource provenance.
  It does not dump config or environment variables. Review JVM options before
  sharing artifacts if custom flags contain sensitive values.

The effective `dat.api` uses its explicit wrapper's underlying connection for
transactions; `init!` passes the supplied directory to Datalevin `get-conn`, and
`close!` closes that connection. Resolved source identity is recorded, rather
than assuming a declared version describes a checkout. Datalevin's underlying
LMDB store uses mapped database files; resident pages, JVM native memory and solver
allocations contribute to RSS. RSS minus heap cannot attribute these categories.

## Interpretation and scope

This is sustained consecutive-tick load, **not** idle-inclusive production CPU
utilization or a promise to reproduce a production failure. Random allocation and
birth/death/migration persist; the population and economy can drift. The workload
is one synthetic island with meaningful housing, farming/sales and funded work,
not an import of a production island. Client work, scheduler idle time, multiple
islands, contention and browser/network costs are excluded. Memory growth alone
does not prove a leak. No hardware-dependent pass/fail threshold is imposed.

Full defaults are manual acceptance scenarios; the targeted tests are harness
checks, and their test doubles never supply benchmark tick measurements.

See [ISLAND_PERF_RESULTS.md](ISLAND_PERF_RESULTS.md) for measured local results and
verification/review outcomes.
