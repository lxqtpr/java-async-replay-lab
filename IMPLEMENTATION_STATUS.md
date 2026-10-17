# Implementation and verification status

Updated: 17 October 2026. The original corpus sections below describe the
15 October snapshot; the new reducer section is explicitly separate.
Research prototype prepared for public release. On 17 October 2026, the user
confirmed the right to publish the new code and retained legacy modules.
No software-registration or patent application has been submitted.

## Completed

- Exported 42 allowlisted Maven/Java/script files from the existing
  `deterministic-async-junit5-extension` project. Original files were read only;
  `.git`, build outputs, IDE metadata, artifacts and environment files were not
  copied. All 42 exported source files still matched the original after work.
- Added `async-replay-core`: creating-thread-confined cooperative runtime,
  virtual `Clock`, explicit `Executor`, labelled delayed callbacks, cancellation,
  step budget, task leak detection, seeded tie ordering, portable trace files,
  and strict decision replay.
- Added `async-replay-junit5`: per-test injection, class/method configuration,
  trace-file replay, and teardown failure for leaked tasks. A real Jupiter
  EngineTestKit regression verifies that a passing test body with pending work
  fails during teardown.
- Added a timeout/late-effect bug and guarded logical counterpart, standalone
  record/replay runner, two example traces, README and evaluation plan.
- Added four logical bug/fixed scenarios, bounded seed campaigns, CSV/JSON output, persisted traces, fresh strict replay and source fingerprints. The runner distinguishes expected invariant violations from infrastructure errors and rejects nonempty output directories.
- Preserved the three legacy modules and their original Maven coordinates.
  The only modified exported file is the root POM, adding the new modules and
  updating its display name/description. Legacy implementation sources were
  not silently rewritten or claimed as newly authored.

## Verification actually run

```sh
sh scripts/verify.sh -o
```

Passed on OpenJDK 25.0.2 and Amazon Corretto 21.0.10 with Java `--release 17`:

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| async-replay-core | 26 | 0 | 0 | 0 |
| async-replay-junit5 | 4 | 0 | 0 | 0 |
| legacy junit5-deterministic-async-extension | 2 | 0 | 0 | 0 |
| legacy experiment-runner | 2 | 0 | 0 | 0 |
| Total | 34 | 0 | 0 | 0 |

Core checks cover clock advancement and nested submissions, explicit
CompletableFuture chains, both tie orders in a bounded seed set, trace
roundtrip/replay, changed eligible set/label/outcome, callback exception replay,
cancellation, leak detection, incomplete replay, bounded self-rescheduling,
cross-thread/nested-drive rejection, duration overflow, malformed traces, and
the late-effect bug/fixed example. Nine additional tests cover the four-scenario corpus and persisted experiment data, fingerprint exclusions, nonempty-directory protection, and failed acceptance gates. This is a small defined test suite, not
exhaustive interleaving coverage.

Standalone core compilation also passed with
`javac --release 17 -Xlint:all -Werror`. The following commands were run:

```sh
sh scripts/demo.sh record 2 examples/timeout-bug.trace
sh scripts/demo.sh replay examples/timeout-bug.trace
sh scripts/demo.sh record 2 examples/timeout-guarded.trace guarded
sh scripts/demo.sh replay examples/timeout-guarded.trace guarded
```

The bug run and its replay reported `result=timeout, effects=1, steps=3`
and `BUG REPRODUCED`. The guarded run and its replay reported
`result=timeout, effects=0, steps=3` and `invariant satisfied`. These are
logical scenario counters, not latency/throughput measurements. Runtime JDK 17
was not available, so execution on JDK 17 itself is not claimed.

## Recorded corpus experiment

```sh
sh scripts/run_corpus.sh research-results/jdk25 32
# Select Java/Javac 21 with JAVA_BIN/JAVAC_BIN for this second command.
sh scripts/run_corpus.sh research-results/jdk21 32
```

Each JDK produced 256 recorded runs (four scenarios, two variants, seeds 0–31),
each followed by a fresh replay of its disk trace. There were 182 passing runs
and 74 expected invariant failures, all in buggy variants. All 128 fixed runs
passed. Replay matched the exact trace, failure signature and final state in
256/256 cases on each JDK; unexpected errors: zero.

Buggy violations: timeout/late effect 21/32, unknown-result retry 21/32,
stale cancellation/retry 11/32, callback after resource close 21/32. Each of
the eight scenario/variant groups had two observed dispatch paths after
excluding the seed header. These are handcrafted logical examples and repeated
paths, not 256 independent defects or a measurement of general detection rate.

Both JDK runs have the same source fingerprint:
`6efa674b1321c9e6155a39806e8466833b198ce666b2170b3939969124dc1d9a`.
All 256 trace files are byte-identical between these two JDK runs. This result
applies to this source snapshot and seed range, not arbitrary future JDKs.
Raw data, environment and independently checked aggregates are preserved
under `research-results/`; see its `analysis.json`.

## Remaining work

### Workload reducer added on 16–17 October

`async-replay-reducer` now provides a complement-based ddmin-style baseline with
plain and dependency-closed deletions, exact target-signature preservation,
structural INVALID outcomes, bounded evaluations and a local cache. It does not
infer causality or minimize arbitrary strict runtime scheduling traces.

The complete local verification now has **46 tests**, all passing on JDK 21.0.10
and 25.0.2: the original 34 plus 12 reducer tests. The new tests include other-bug
rejection, budget exhaustion, setup-error propagation, invalid DAGs, a nonmonotonic
counterexample to global minimality, immutable API boundaries, fresh replay and
fixed controls.

`research-results/reducer-2026-10-16/` contains nine constructed workloads in two
modes (18 rows per JDK), with raw CSV, source fingerprints, inputs and both
original/minimized trace files. All reduce to the six manually declared target
events. Each trace passes fresh strict replay; fixed controls pass. All 36
corresponding trace files and both CSVs are byte-identical across the two JDKs.

Plain mode used 277 candidate evaluations / 126 oracle calls / 151 structural
rejects; dependency-closed mode used 145 / 145 / 0. It used more oracle calls in
seven workloads and fewer in two. General execution-cost improvement, wall-time
speedup and novel patentable rules are **not** established. See
`docs/reducer-baseline-ru.md` and the independently checked `analysis.json`.

The old four-scenario corpus still refers to source fingerprint `6efa674b…`;
the byte-verified `research-results/corpus-source-2026-10-15.zip` preserves that
entire recorded source manifest before parent-POM/script additions. The new
reducer comparison has its own explicit source hash scope. Do not silently
associate the old experiment fingerprint with the expanded repository.

### Still unimplemented or unverified

The PostgreSQL/Testcontainers demo and its old experiment script were not run.
A GitHub Actions workflow is configured for JDK 17/21 using the same local
commands; its first hosted run is pending. There are no new real-service or
process-crash experiments, comparisons with other libraries, performance data,
distributed faults, general runtime-trace minimizer, automatic failing-test
attachment for arbitrary JUnit tests, or independent external reproduction. Corpus-specific CSV and
trace artifact export are implemented. The optional evaluation roadmap remains
unimplemented.

The runtime does not control arbitrary Java threads, memory-model interleavings,
default/common-pool executors, real I/O or wall-clock calls. A blocking callback
can block the test. Replay checks scheduling decisions and exception classes;
the scenario must assert application correctness. Seed compatibility across
arbitrary JDK versions and trace compatibility across code revisions are not
guaranteed.

Publication authorization was confirmed on 17 October 2026, including the
retained legacy modules. No open-source license has been selected, and this
release grants no new open-source license; see `LICENSE-NOTICE.md`. No worldwide
novelty or patentability is asserted.
