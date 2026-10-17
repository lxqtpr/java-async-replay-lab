# Corpus evaluation and next comparison experiment

The current implementation provides four logical bug/fixed scenarios: timeout/late effect, retry after an unknown result, cancellation/retry, and callback after resource closure. A standalone runner persists and replays their traces. Results are in `../research-results/` and the method is described in `research-report-ru.md`. It does not establish
lower flake rates, faster testing, complete bug discovery, or general novelty.

## Common experimental contract

Every scenario should state which boundaries are controlled, the defect, the
application-level oracle, bug/fixed variants, termination condition, and explicit
excluded behavior. Start with single-thread cooperative tasks and injected time.
Do not mix a logical model's result with a PostgreSQL integration test's result.

The four implemented models check each invariant and retain successful work in fixed variants. Further scenarios should introduce different scheduling structures, such as multiple overlapping retries and resource generations. There is no 10–15-scenario corpus yet.

## Baselines

- Awaitility and the existing polling extension: real asynchronous execution,
  condition waiting, explicit wall-clock budgets. Different fault model.
- jMock DeterministicScheduler: compatible injected timer/executor logic; use
  equivalent scenario and oracle, disclose differences in tie ordering.
- Lincheck: only scenarios that fit its actual supported concurrency contract;
  adaptation cost is part of the comparison.
- Reactor StepVerifier: optional Reactor rewrite, not a direct comparison of
  unchanged CompletableFuture code.
- A sleep baseline can illustrate a bad practice but cannot be the only baseline.

## Data currently collected and further measurements

The current raw CSV records scenario, variant, seed, outcome, failure signature, final state, step count, trace bytes, replay match and diagnostic wall time. Environment JSON records Java/OS, step budget, seed range, command and a defined code/build/script fingerprint. Trace write/read and fresh replay must match exact decisions, state and signature. The current 256 runs on each of JDK 21 and 25 cover two distinct dispatch paths per scenario/variant. The 74 expected buggy invariant violations are not infrastructure errors.

For a future comparison, add the baseline implementation, supported/unsupported status, adaptation effort and an equal defined budget. Report failed setup, skipped tests and unsupported scenarios separately from passes.

Compare detection rate under the same defined budget, false failures on fixed
versions, replay of the **same** failure signature, time to finding, and setup
or adaptation effort. Report uncertainty and negative results. Repeating one
trace proves reproducibility of that trace, not discovery coverage. A bounded
seed set is not exhaustive schedule exploration.

## Release acceptance

Tests and standalone commands work from a clean checkout. At least one person
other than the implementer reproduces a supplied failure and its guarded
counterpart. Only then prepare a versioned release, CI run evidence, raw data,
plots and a short technical report. License/rights and patent-disclosure
decisions must be resolved before public publication.

## Completed follow-up, 16–17 October 2026

A declared-workload reducer and two known ddmin-style baselines are now
implemented in a separate module. The constructed lost-ack workload comparison
is documented in [reducer-baseline-ru.md](reducer-baseline-ru.md). It is not a
comparison with Awaitility, jMock or Lincheck and does not complete the wider
baseline evaluation above. The dependency-closed variant reduced structural
invalidity but did not reduce total runtime calls in this small example.
