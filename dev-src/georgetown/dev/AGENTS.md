# Full-tick benchmark context

Last verified: 2026-10-01

## Purpose
The `tick_bench*.clj` suite measures complete simulation ticks against reproducible
starting inputs without starting the application or using developer data. These
contracts apply to the benchmark suite, not other development helpers here.
See [the benchmark guide](../../../docs/TICK_BENCH.md) for commands and limitations.

## Contracts
- **Exposes:** `georgetown.dev.tick-bench/run!` and its CLI for sequential cases;
  `georgetown.dev.tick-bench-report/generate!` for reports from saved results.
- **Expects:** Supported selectors (baseline, populations 50/500, four shifts).
  Validate run options before creating directories or opening databases.
- **Guarantees:** Each warmup/sample restores and fingerprint-validates the full
  fixture before timing exactly one `georgetown.sim.tick/tick!`; epoch verification
  follows timing. Restoration, validation and reporting are outside the timestamps.
- Normal runs require both at least four warmups and ten seconds of tick execution,
  plus at least ten samples (default 30). The warmup call budget is not a timeout.
  Smoke uses one warmup/two samples and produces no statistical reports.

## Dependencies and boundaries
- Uses production tick/schema/blueprints, explicit Datalevin handles, public
  Criterium statistics and fastester reporting; fastester is a dev-profile dependency.
- Keep benchmark entry points independent of application/dev startup, authentication,
  email, configured database access and the server database singleton. Disable RCF
  before loading simulation namespaces so inline application tests are not enabled.
- Fixture databases use newly created explicit paths and ownership-marked handles.
  Restoration retracts all entities only in owned databases; connections close in
  `finally`, and benchmark database files remain available for inspection.

## Invariants and decisions
- Determinism covers starting fixtures, not tick outcomes. Preserve production
  randomness in measurements; controlled random choices belong in correctness tests.
- All cases share the single-invocation timing path. Raw durations are nanoseconds;
  Criterium time estimates are seconds, variance seconds squared, execution-count 1.
  No reset-cost or timing-overhead subtraction and no hardware pass/fail thresholds.
- Runs have unique directories under `target/tick-bench`; raw/status/fixture artifacts
  stay separate from fastester's completed-result ingestion tree. Failures preserve
  partial artifacts and failed status; incomplete case results are quarantined.
- Persist only explicitly selected safe provenance. Exclude configuration,
  environment, credentials and JVM arguments; sanitize Criterium runtime details.
- Report regeneration executes no ticks and generates its own fastester options;
  callers supply a suite-owned run directory, not executable options.
- Extend scenarios through explicit fixture dispatch/selectors, not a second timing
  path. Version changed fixture/artifact contracts and extend restoration/activity
  tests; construct later-shift inputs directly rather than through random ticks.
