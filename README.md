# Java Async Replay Lab

[![Java verification](https://github.com/lxqtpr/java-async-replay-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/lxqtpr/java-async-replay-lab/actions/runs/39054600394)

A small Java test runtime for **explicitly injected `Clock` and `Executor`**:
advance virtual time, explore alternative orders of tasks with the same deadline,
save the execution decisions, and replay a timeout/completion defect in JUnit 5.

**Status: public research prototype.** No production reliability, performance,
worldwide novelty, patentability, or general Java thread determinism is claimed.
See [implementation status](IMPLEMENTATION_STATUS.md) for the checks actually run.

## Run a concrete counterexample

Requirements: JDK 17+ and a POSIX shell. The standalone demo has no dependencies
and contacts no external services. It compiles the core with `--release 17`.
Run from this repository directory:

```sh
sh scripts/demo.sh record 2 examples/timeout-bug.trace
sh scripts/demo.sh replay examples/timeout-bug.trace
sh scripts/demo.sh record 2 examples/timeout-guarded.trace guarded
sh scripts/demo.sh replay examples/timeout-guarded.trace guarded
```

The bug is intentional: a timeout completes the result, then a late completion
increments a logical side-effect counter anyway. The checker reports
`BUG REPRODUCED` when that order occurs. The guarded variant changes the counter
only if `CompletableFuture.complete` wins. This is an in-memory logic example,
not a recipe for cancelling real external effects or transactions.

An expected finding returns exit code zero so both record and replay can be run
as a demonstration. Malformed traces and replay divergence fail with a nonzero
exit. Record guarded and buggy runs separately: changing the program may cause
the recorded callback outcome to differ, which replay correctly rejects.

For the JUnit tests, Maven 3.9+ is needed. A first run may download dependencies;
the second command works when those dependencies are already cached:

```sh
sh scripts/verify.sh
sh scripts/verify.sh -o
```

The verification command covers the new core, adapter and workload reducer plus the exported
legacy extension and CSV summary unit tests. It does not run Docker/PostgreSQL.
Locally, 46 tests passed on JDK 21.0.10 and 25.0.2: the previous 34 plus
12 reducer checks. The compiler target is Java 17. The same test suite,
example replays, bounded corpus and reducer also passed [GitHub CI](https://github.com/lxqtpr/java-async-replay-lab/actions/runs/39054600394)
on JDK 17 and 21.

## Use in JUnit 5

```java
@AsyncReplayTest(seed = 42)
class InvoiceTest {
    @Test
    void asyncValueUsesInjectedClock(ControlledRuntime runtime) {
        var value = CompletableFuture.supplyAsync(runtime::instant, runtime);
        assertFalse(value.isDone());
        runtime.advanceBy(Duration.ZERO, 1);
        assertEquals(Instant.EPOCH, value.join());
    }
}
```

Imports: `dev.asyncreplay.ControlledRuntime`,
`dev.asyncreplay.junit5.AsyncReplayTest`, standard JDK types, and Jupiter's
`Test`/assertions. Add the local `async-replay-junit5` Maven module as a test
dependency; no package has been published to Maven Central.

For timers, call `runtime.schedule("request-timeout", Duration.ofSeconds(2),
callback)`. The returned handle cancels a pending callback. `drain(budget)` runs
all pending deadlines, including future timers. `advanceBy(duration, budget)`
runs only callbacks due by the target time. `runNext()` executes one callback.
Each test receives its own runtime. Teardown fails if tasks remain pending;
it does not silently run application work to hide leaks.

`TraceFiles.write(path, runtime.trace())` explicitly saves a trace. With
`@AsyncReplayTest(replayFile = "path/to/scenario.trace")`, the adapter reads it
before the test; its seed and origin override `seed`. The test must register
the same scenario and consume the recorded decisions. Paths are resolved from
the test process working directory. Automatic failure artifact attachment for
arbitrary JUnit tests is not implemented.

## Run the four-scenario corpus

```sh
sh scripts/run_corpus.sh target/corpus-local 32
```

Use a new or empty output directory. The runner refuses nonempty directories.
It needs only a JDK and a POSIX shell, and compiles with `--release 17` and
`-Xlint:all -Werror`. The seed range is 0 through 31; each scenario has buggy
and fixed variants, giving 256 recorded runs, each followed by a fresh replay.

| Scenario | Invariant | Fixed variant retains useful work |
| --- | --- | --- |
| Timeout and late completion | A timed-out result has no late effect | Successful completion still produces one effect |
| Retry after an unknown result | One effect per logical operation | A queued retry executes and deduplicates using the same operation key |
| Cancellation and retry | An old cancellation cannot cancel its replacement | The replacement completes successfully |
| Callback and resource closure | No write after logical resource closure | A callback that runs while open still writes |

Each trace is written to disk, read back, and replayed in a fresh runtime.
The runner checks exact scheduling decisions, the invariant failure signature,
final application state, complete replay consumption and absence of pending work.
An expected buggy invariant failure is data. Unexpected exceptions, mismatches,
fixed-variant failures or a seed range that exposes no defect in a scenario make
the command fail.

Output contains `raw.csv`, `summary.json`, `environment.json`, and one trace
per recorded run. The environment file defines the source fingerprint and
records Java/OS versions. `wall_nanos` includes recording, file I/O and replay;
it is a diagnostic and must not be reported as a performance benchmark.

These are four handcrafted logical examples, with two observed callback orders
per variant. Thirty-two seeds do not mean thirty-two distinct defects or paths.
There is no external I/O, JVM thread exploration, comparison baseline, or
general bug-discovery claim. See [the technical report](docs/research-report-ru.md)
and [draft theses](docs/theses-ru.md) for the method and recorded results.

## Reduce a declared workload while retaining the same failure

```sh
sh scripts/run_reducer.sh target/reducer-local
```

An optional dependency-free demo JAR can be built once and run without compiling
on the reviewer's machine:

```sh
sh scripts/package_demo.sh target/async-replay-reducer-demo.jar
java -jar target/async-replay-reducer-demo.jar target/reducer-from-jar
```

Run the JAR from this repository root with its sources/POMs beside it: the runner
fingerprints those inputs. Use a new output directory. JAR execution was checked
on JDK 21 and 25; the packaged JAR was not separately exercised on JDK 17.
Building a JAR does not change
the [license status](LICENSE-NOTICE.md).

The new `async-replay-reducer` module accepts an ordered list of event IDs with
explicit prerequisites and a deterministic oracle. It performs complement-based
ddmin-style reduction with an exact failure signature, invalid-result handling,
an evaluation budget and a per-run cache. Two baseline modes either delete a
chunk alone or also remove its transitive dependants. Use its Java API to supply
your own workload adapter and oracle; the command above runs the supplied example.

This reduces **workload input events**, not arbitrary saved `RunTrace` decisions.
Dependencies are declared manually. A candidate is rerun in a fresh controlled
runtime; each resulting trace is specific to that candidate. `oneMinimal` means
no supported single deletion preserves the target failure, relative to the DAG
and oracle; it does not mean a globally shortest execution.

The initial example is one constructed lost-ack defect in nine noise/placement
workloads, each tested with both modes. Inputs of 18, 54 and 198 events reduce to
the same six-event target chain in all 18 rows. Original and minimized traces
each replay exactly, and corresponding fixed variants pass, on JDK 21 and 25.
These are 18 comparisons, not 18 different bugs.

An instructive negative result: across nine workloads, plain deletion used
277 uncached candidate evaluations, of which only 126 invoked the runtime;
151 were rejected by dependency validation. Dependency-closed deletion used
145 candidates and 145 runtime calls. Fewer invalid candidates did **not** mean
fewer runtime executions. There is no speed or patent-novelty claim. See the
[method, raw evidence and limitations](docs/reducer-baseline-ru.md).

## What the runtime controls

- The creating test thread submits all callbacks. The runtime invokes one
  callback at a time on that thread. Other threads cannot submit or drive it.
- Virtual time starts at `Instant.EPOCH`, or a supplied origin. The earliest
  pending deadline is selected first. A seeded choice orders eligible callbacks
  with the same deadline. A callback may enqueue more work.
- A trace records the time, selected task ID/label, ordered eligible task IDs,
  and either `OK` or the callback's thrown exception/error class. Replay uses
  those decisions instead of random selection and fails on a mismatch.
- Task IDs follow submission order. Labels are human-readable diagnostics.
  Replay is specific to the same scenario and task-submission structure; it
  is not a portable trace across arbitrary code versions.
- Callback exceptions are recorded and rethrown. A replay checks exception
  class, not message, stack, application state, or equality of return values.
  Scenario assertions remain the correctness oracle.
- The trace records dispatched callbacks, not an event log of every submission,
  cancellation, or manual clock advancement. Those operations must be recreated
  by the scenario. An unused submit-then-cancel pair need not change the trace.
- A step budget bounds dispatched callbacks and self-rescheduling loops.
  It cannot interrupt a callback that blocks or loops internally.

The runtime does not instrument `Thread`, locks, memory accesses, the common
pool, default `CompletableFuture.*Async` executors, `Thread.sleep`,
`System.currentTimeMillis`, network/filesystem/DB I/O, or Java Memory Model
interleavings. Inject the runtime at every supported boundary. It is deliberately
not a `ScheduledExecutorService`: periodic scheduling and blocking Future APIs
would need additional contracts. This MVP is a cooperative model, not a general
concurrency checker or proof of an application's correctness.

## Existing source and module map

This package develops an existing project rather than claiming all of its code
was written for this MVP. Original Java packages and Maven coordinates are
preserved to keep the exported modules buildable.

| Module | Role | Status |
| --- | --- | --- |
| `async-replay-core` | Virtual Clock/Executor, trace/replay, four bug/fixed models and corpus runner | Implemented and unit tested |
| `async-replay-junit5` | New runtime injection, replay-file option, leak detection | Implemented and tested with Jupiter |
| `async-replay-reducer` | Signature-preserving workload reduction, two known baseline modes | Implemented; 12 unit tests and a small controlled comparison |
| `junit5-deterministic-async-extension` | Exported lifecycle/resource/polling extension | Legacy baseline, not a deterministic scheduler |
| `demo-order-service` | Exported PostgreSQL/Testcontainers async service and baseline tests | Integration tests not run for this MVP |
| `experiment-runner` | Exported CSV summary tool for legacy polling experiments | Unit tests run; no new benchmark dataset |

The legacy `Awaiter` polls using real time (`System.nanoTime`/`parkNanos`). Its
name does not mean that it controls thread scheduling. The legacy
`scripts/run_experiment.sh` targets that integration baseline only; results
cannot establish the new runtime's effectiveness. In particular, inspect
skipped test counts and do not interpret skipped Docker tests as successes.

## Prior art and experiment plan

Virtual scheduling and replay are established ideas. Relevant comparisons are
[jMock DeterministicScheduler](https://jmock.org/threading-scheduler.html),
[Awaitility](https://github.com/awaitility/awaitility/wiki/Usage),
[Lincheck](https://github.com/JetBrains/lincheck),
[jcstress](https://github.com/openjdk/jcstress), and
[Reactor virtual-time testing](https://projectreactor.io/docs/core/release/reference/testing.html).
[FoundationDB's simulation work](https://www.foundationdb.org/files/fdb-paper.pdf)
is broader prior art; [DEMi](https://github.com/NetSys/demi) also covers reducing
faulty executions. These projects have different supported boundaries; no
superiority or unique method is established by this repository.

The immediate engineering contribution here is the small inspectable contract
and a saved logical failure that can be replayed from JUnit or the standalone
runner. The [experiment plan](docs/experiment-plan.md) specifies the additional
scenarios, compatible baselines, and measurements needed before research claims.

## Next steps and publication

1. Extend the four existing bug/fixed models to a broader independently reviewed
   corpus with new defects and scheduling structures.
2. Add baseline implementations on a genuinely common supported boundary and
   record raw seed lists, oracle results, adaptation effort, and runtime.
3. Add trace diagnostics and automatic failure artifact export; extend CI as
   the API stabilizes.
4. Connect a separate real-service integration mode without transferring this
   model's replay guarantees to real I/O. Arbitrary runtime-trace minimization,
   inferred dependencies and stronger comparison corpora remain future work.

On 17 October 2026, the user confirmed the right to publish this source,
including the retained legacy modules. No open-source license has been selected
for this repository. Public source availability does not by itself make the
code open source; see the [license notice](LICENSE-NOTICE.md).

## GitHub Actions

[Run 39054600394](https://github.com/lxqtpr/java-async-replay-lab/actions/runs/39054600394) succeeded on both JDK 17 and 21 at
commit [`9d3653a2a1da3f0851bed139d41bc41c45ed0774`](https://github.com/lxqtpr/java-async-replay-lab/commit/9d3653a2a1da3f0851bed139d41bc41c45ed0774).
It ran the verification suite, replayed the buggy and guarded examples,
recorded and strictly replayed the four-scenario corpus with 32 seeds, and ran
the workload-reducer comparison. The legacy PostgreSQL/Testcontainers demo
was not included. Saved research datasets remain the separately identified
local JDK 21/25 runs; this CI result does not replace their source fingerprints.
