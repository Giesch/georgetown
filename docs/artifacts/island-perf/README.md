# Complete successful benchmark evidence

`run-16268097977057157945.tar.gz` contains the complete successful sequential
50/500-citizen benchmark run from 2026-09-30 (approximately 191 MB uncompressed,
25 MB compressed). See [the results summary](../../ISLAND_PERF_RESULTS.md).

Included: run comparison and metadata; both child/supervisor reports; incremental
raw tick, resource and activity observations; complete initial fixture EDN;
child stdout/stderr; and both **harness-owned synthetic** final databases.
No configured developer database, configuration file, environment dump or
`checkouts/` source is included. Metadata contains original local paths, effective
classpath/resource provenance, revision and allowlisted JVM flags.

Verify from the repository root:

```sh
sha256sum -c docs/artifacts/island-perf/SHA256SUMS
```

Extract into a new disposable directory, not onto configured developer data:

```sh
mkdir -p /tmp/georgetown-island-perf-evidence
tar -xzf docs/artifacts/island-perf/run-16268097977057157945.tar.gz -C /tmp/georgetown-island-perf-evidence
```

The reports' absolute paths describe the original run; extraction does not rewrite
them. Prefer saved `initial-fixture.edn` with the harness `--fixture` option for a
rerun into a fresh owned database. Archived DB files are supporting evidence, not
an instruction to replace any running database. The archived `lock.mdb` files
are included as originally preserved; no live locks were deleted.

The run used Raf's `dan-diverse-citizens` commit `ead8fb1` as its simulation
baseline. Later implementation-review fixes affect supervisor shutdown and tests,
not the measured tick workload. Failed exploratory runs remain local and are
summarized in the results document; this bundle contains only complete acceptance.
