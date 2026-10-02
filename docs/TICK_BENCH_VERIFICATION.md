# tick-bench implementation verification

Verification date: 2026-10-01. Source baseline:
`ead8fb130f03d51aea70800b649ba1ebbcc21e5a` plus the uncommitted development-only
benchmark implementation. Production sources and `checkouts/` were not modified.

## Executed gates

- `lein deps`: resolved fastester 1 / Criterium 0.4.6 without changing production
  Clojure. Report tests explicitly verified Clojure 1.11.0.
- `lein run -m georgetown.dev.tick-bench-test-runner`: initial three-namespace
  suite passed 13 tests / 364 assertions. Final expanded suite passed **18 tests /
  434 assertions, zero failures/errors, exit 0**, including CLI isolation, injected
  failure exits, and normal-runner failing-assertion verification.
- `lein run -m georgetown.dev.tick-bench --smoke`: all eight real Datalevin/full-tick
  cases completed, exit 0. Run:
  `target/tick-bench/1790861622092-5d8a50de-fe6b-4f5e-a540-3e1d9bacd3e5`.
  Each case has one warmup and two measured calls; no statistical claim.
- `lein run -m georgetown.dev.tick-bench --population 50 --shift morning`:
  exit 0; 34 warmups accumulated 10,010,646,235 ns tick time and
  34,844,071,078 ns warmup wall time; 30 measured calls; genuine Criterium
  statistics and fastester HTML/Markdown. Run:
  `target/tick-bench/1790861386049-7d9c1933-c6f0-4b70-9d60-4e81fc0a238e`.
- `lein run -m georgetown.dev.tick-bench --regenerate
  target/tick-bench/1790861386049-7d9c1933-c6f0-4b70-9d60-4e81fc0a238e`:
  exit 0. Report tests separately verify eight synthetic normal cases, local
  data/SVG links, absence of synthetic secret JVM arguments, and regeneration
  from a subprocess with `/tmp` working directory.

These real artifact runs preceded the final requested-settings/quarantine fix;
that fix is covered by focused failure/artifact tests and the final suite.

## Implementation review

Independent read-only Standards and Spec reviewers inspected all new source/tests,
dependency wiring and documentation against the approved plan (not only its
planning review). Cross-model independence was unavailable; both used the default
configured model.

Initial findings and disposition:

1. **Important, Spec:** CLI tests omitted from unattended runner. Fixed by requiring
   and running the supplementary namespace in ordinary dispatch.
2. **Important, Spec:** original requested settings absent. Fixed by persisting
   `:requested-settings` separately; smoke-override artifact regression added.
3. **Important, Spec:** a close/status-write failure after publishing result could
   leave a failed case in report ingestion. Fixed by quarantining active result
   outside ingestion and removing completed-case status; both failure paths tested.
4. **Minor, Standards:** unnecessary special test-runner failure mode. Removed;
   subprocess test now injects an actual failing assertion into normal dispatch.

Spec re-review: **Ready, no findings**, prior Important findings fixed and no new
Critical/Important issues. Standards: no hard standards violations; the sole Minor
finding was removed. No unresolved findings or rebuttals.

## Failed attempts and limits

- Initial runner loading enabled existing inline RCF tests and revealed three
  unrelated park-capacity expectation failures. Benchmark loading now disables RCF
  before simulation loading; dedicated clojure.test suite runs independently.
- One normal attempt read source during an edit and failed to compile; subsequent
  compile/test and real normal CLI runs passed.
- First smoke exceeded its tool's 200-second deadline. Second smoke exited 137
  while heavy native verification processes overlapped; cause was not proved.
  A later serial smoke completed successfully with a 30-minute tool budget.
- An expanded suite initially caught an incorrect failure-exit test override that
  reran expensive fixture tests in a child and timed out. The child now removes
  existing test metadata and injects one assertion into normal dispatch.
- JVM 26 emits existing native-access/Unsafe deprecation warnings. No hard native
  timeout or deterministic outcomes are promised. Dat's init wrapper does not
  expose an inaccessible partially constructed connection if its internal init
  throws; all successfully returned owned handles have tested closure paths.
- No hardware performance thresholds. Warmup loop limits cannot interrupt a stuck
  native call. No issue-tracker workflow was found or changed.
