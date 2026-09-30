# Development Performance Harness

Last verified: 2026-09-30

## Purpose

The synthetic island performance harness provides repeatable tick measurements without using a developer's configured database or starting application services.

## Contracts and invariants

- The supervisor creates an owned case directory and fresh database directory for every case, then runs the worker in a separate JVM. Keep database creation, ticking, and observations inside that child.
- The harness does not load application configuration or initialize the application database singleton. The worker denies `georgetown.server.db/db`, `connect!`, and `georgetown.server.config/get`; keep this boundary and its tests intact.
- Fixtures are deterministic transaction data, not a database. Persist complete fixture EDN when replay is needed, and replay its transactions only into a fresh owned database.
- Tick durations cover `tick/tick!`, including assignment-witness instrumentation. Keep post-tick observation and report I/O outside that interval. Periodic resource sampling can overlap ticks. CPU/resource summaries use measured-phase start and end boundaries; do not present them as whole-process or tick-only measurements.
- Preserve child identity verification, bounded timeout/termination and reaping, and partial EDN evidence on failures. Do not record config, environment-variable values, arbitrary JVM properties, or dependency source contents in run metadata. Resolved classpath paths/resource URLs and source hashes are provenance. Inherited JVM flags are limited to `--add-opens`, `-Xms`, and `-Xmx`.

## Dependencies and tests

- The harness uses the simulation tick/schema and explicit database APIs; it must not start server, scheduler, or push services.
- Keep isolation, fixture restore, failure, and timing-boundary coverage in `island_perf_test.clj`, `island_perf_worker_test.clj`, `island_perf_fixture_test.clj`, and `island_perf_metrics_test.clj`.
- Developer entry point: [`docs/DEV.md`](../../../../docs/DEV.md), “Local performance measurements.” The benchmark guide is [`docs/ISLAND_PERF.md`](../../../../docs/ISLAND_PERF.md).
