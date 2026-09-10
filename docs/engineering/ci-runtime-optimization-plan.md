# CI runtime optimization plan

## Scope and baseline

This plan addresses CI wall-clock time only. It does not implement issue #174,
change workflow runtime behavior, modify issue workflow state, or reduce any
security, freshness, authority, provenance, or human-authorization guarantee.

The branch started from `origin/main` commit
`a098d229aa13f2af7c930d7e559ed33c88378cc0`.

| Measurement | Result |
| --- | ---: |
| Latest comparable successful CI run | `34461080086` |
| CI wall-clock time | 2,361 seconds (39m 21s) |
| `agent-workflow` test step | 2,332.296 seconds |
| CI tests | 695 run, 4 skipped, 0 failed |
| Clean local workflow suite | 2,555.516 seconds |
| Local tests | 695 run, 0 skipped, 0 failed |

The four CI skips are read-only integration checks for locally installed frozen
issue #115 evidence. Their fixtures are intentionally absent on ephemeral GitHub
Actions runners. The local run found that evidence and ran all four; this is an
environmental difference, not a CI coverage regression.

The exact environment-dependent tests are:

* `scripts.tests.test_workflow_inspector.FrozenIssue115IntegrationTest.test_frozen_issue_115_resolves_without_mutation_when_available`
  skips when the Git common-dir store or frozen #115 authority is unavailable.
* `scripts.tests.test_workflow_evidence.FrozenIssue115EvidenceAdapterTest.test_frozen_issue_115_adapter_is_read_only_when_available`
  skips when the frozen #115 durable pointer is unavailable.
* `scripts.tests.test_workflow_migration.FrozenIssue115MigrationTest.test_plan_and_dry_run_leave_available_frozen_authority_unchanged`
  skips when frozen #115 authority or reachable raw evidence is unavailable.
* `scripts.tests.test_workflow_repair.WorkflowRepairTest.test_real_frozen_115_integration_is_read_only_when_available`
  skips when the frozen #115 durable pointer is unavailable.

The current workflow uses one serial `agent-workflow` job to run
`python3 -m unittest discover -s scripts/tests -p 'test_*.py'`. Backend and
frontend jobs run independently behind path filters. In the comparable run they
were skipped, so Python workflow tests account for more than 98% of wall time.
Historical runs where all jobs executed show backend and frontend completing
well before the workflow suite.

## History and regression

| Point in history | Tests | Test time | Meaning |
| --- | ---: | ---: | --- |
| Before PR #172 (`34031390369`) | 580 | 616.369s | Pre-reconstruction baseline |
| PR #172 final (`34068240775`) | 594 | 1,899.432s | Pinned reconstruction introduced |
| PR #187 (`34230874815`) | 650 | 1,689.317s | Later lower-variance point |
| Current (`34461080086`) | 695 | 2,332.296s | Current comparable baseline |

PR #172 removed an accidental same-command historical-replay amplification. Its
issue #174 follow-up recorded a representative lifecycle reduction from 169 to
99 reconstructions, 10,669 to 7,309 supervised subprocesses, and 156.172s to
109.777s. The remaining regression is still reconstruction/subprocess dominated,
but subsequent trusted-local and transport contracts added 101 tests after the
PR #172 final run and roughly 433 seconds to comparable CI.

## Ranked runtime contributors

1. **Orchestrator lifecycle integration tests.** The 63 tests in
   `test_workflow_orchestrator.py` dominate the suite. A representative complete
   lifecycle test took 205.601s locally and performed 98 runtime reconstructions
   and 7,183 supervised subprocess launches. Other measured distinct lifecycle
   cases took 48-162 seconds each.
2. **Other workflow test modules.** The other 632 tests complete in approximately
   57 seconds when measured module by module in isolated checkouts. The largest
   were `test_workflow_local_provider.py` (14.40s),
   `test_agent_workflow.py` (11.65s), and `test_workflow_policy.py` (9.93s).
3. **CI and language setup.** Checkout and Python setup take approximately 11
   seconds. Gradle configuration, Kotlin compilation, Spring/Flyway/database
   setup, and frontend compilation are not on the critical path of comparable
   workflow-only runs and are already isolated into independently filtered jobs.

The dominant cost exists because orchestrator integration tests create real
temporary Git repositories and cross the same fresh runtime boundaries as a real
command. Each reconstruction verifies the repository, configuration,
executables, authority, and phase facts through supervised Git and fake-GitHub
subprocesses. The work is intentionally process-heavy rather than caused by
sleeps, polling, database reset, provider startup, or external network access.

## Test-suite audit

The expensive orchestrator tests repeat lifecycle setup, but their assertions
cover distinct contracts: fresh reconstruction, command isolation, authorization
re-observation, fail-closed repository drift, cancellation, pending-result
reconciliation, evidence binding, policy selection, and one-time draft
publication. Consolidating them would hide which trust boundary regressed, while
mocking reconstruction would stop testing the property responsible for the cost.

No expensive test is proposed for removal or consolidation. The complete
happy-path test and the reconstruction-count tests overlap in setup but assert
different invariants. Non-orchestrator tests are not material enough to justify
coverage changes.

No mutation-testing framework is configured. The workflow suite already contains
targeted mutation/tamper tables for canonical documents, bindings, authority,
history, transport, policy, and repository observations. Those tests detect
meaningful security regressions and remain unchanged. The planned shard
partition will have focused tests proving that every discovered test appears
exactly once across all shards, so mutations that create an overlap or omission
fail.

Targeted mutation probes against the implemented partition tests killed three
relevant mutants: omitting position zero, duplicating the first selected
position, and treating a failed child process as successful. This provides
direct confidence in the coverage-preservation and fail-closed properties without
adding an unrelated mutation framework.

## Proposed optimization

Run the unchanged unittest suite as four test-case-level shards on isolated
GitHub Actions runners:

1. Add `scripts/run_workflow_test_shard.py`. It performs the same `test_*.py`
   discovery as the serial command with the repository as the explicit
   `top_level_dir`, recursively flattens the returned suite in its native order,
   and selects test instances by list position modulo the shard count. Each
   selected canonical `scripts.tests.*` ID runs in a separate Python process,
   preventing omitted earlier methods or other imported test modules from
   providing accidental module-global aliases. A parity test compares the exact
   file/class/method multiset after removing only that package prefix. The runner
   does not sort or deduplicate IDs. Discovery-generated failure tests execute
   directly and fail closed because they cannot be reconstructed by ID.
2. Add focused partition tests proving complete, disjoint selection and rejecting
   invalid shard coordinates.
3. Add the runner path explicitly to the `agents` path filter, so a runner-only
   change cannot bypass the suite.
4. Expand the workflow test execution into a fixed four-entry matrix with
   indices `0..3`, `fail-fast: false`, and no `continue-on-error`. Every shard
   receives a fresh checkout and Python environment, so no mutable repository,
   process, module, credential, or authority state is shared.
5. Preserve `agent-workflow` as the aggregate job/check name. It depends on both
   `changes` and the matrix job, uses `always()` while gating on
   `agents == 'true'`, and explicitly fails unless both change detection and the
   matrix result are exactly `success`. It also runs and fails when change
   detection itself fails before producing outputs. It is skipped only when a
   successful path-filter result says the agent suite is irrelevant.
6. Keep `make agent-workflow-test` as the serial local/validation command and add
   a separate shard target used only by CI.

The completed per-test profile used eight isolated clones on one oversubscribed
local host. Pairing those measurements into the proposed four modulo partitions
predicts shard loads of 817s, 1,375s, 1,190s, and 1,157s. Even this
resource-contended estimate makes the longest shard 46% shorter than the
2,555.516s serial local run. Independent hosted runners should avoid that local
CPU and filesystem contention; runner setup adds only about 11 seconds per shard
and does not affect the critical-path conclusion.

Expected CI wall-clock time is 10-23 minutes, a 42-75% reduction. Total executed
pre-existing tests, reconstructions, subprocesses, and environment-dependent
skips should remain unchanged; only independent runner occupancy overlaps. The
focused partition tests will raise the final discovered test count above 695.

## Rejected alternatives and risk review

### Cache or reuse a verified fixture

Rejected. A runtime object, repository snapshot, executable digest, or
reconstruction reused across commands could cross the exact freshness and
authority boundary that PR #172 established. Tests explicitly require fresh
adapter identity and separate reconstruction for claim, finalization, history,
and approval. A test-only cache could also make these tests pass without
exercising production behavior. Reducing semantically unnecessary reconstruction
belongs to issue #174, which this task must not implement.

### Remove duplicate lifecycle tests

Rejected. Timing is concentrated in tests with distinct fail-closed mutations and
gate transitions. No measured expensive test was found to assert only a
superficial input variant. Removing tests would reduce operation counts and make
the speedup non-comparable.

### Shard only by module

Rejected. More than 97% of measured local time is concentrated in one module, so
module-level sharding would leave the critical path nearly unchanged.

### Parallelize inside one checkout or process

Rejected. The tests patch module globals, create repositories beneath the source
checkout, inspect Git cleanliness, exercise signals and process groups, and read
Git common-dir state. Threads or subprocesses sharing one checkout would
introduce races and weaken isolation. Separate Actions runners preserve current
serial semantics within each shard.

Sparse in-process execution was also rejected after validation: although all 699
instances were discovered, 15 orchestrator and migration cases depended on
aliases established by omitted earlier methods or other imported modules.
Canonical package tests passed when profiled individually, so each selected ID
runs in a fresh interpreter. This preserves the same test methods and assertions
while strengthening isolation. Exact identity-multiset parity with the serial
discovery remains a required regression test.

### Cache dependencies or remove CI jobs

Rejected. Python tests have no third-party dependency installation, and setup is
less than 1% of the critical path. Removing backend, frontend, or security checks
would weaken validation and is prohibited.

## Validation strategy

1. Run focused shard-partition tests.
2. Prove that all 695 pre-existing discovered test positions remain present in
   the final serial suite. Test-instance positions, not a deduplicated set of
   IDs, are the identity baseline.
3. Run `make agent-workflow-test` unchanged on the final tree and record its new
   total, skips, and failures as the final serial baseline.
4. Run every shard in an independent local clone and verify that concatenating
   positions from all four shards exactly reconstructs the final discovery list:
   no omitted position and no repeated position.
   The runner will print the selected test IDs, providing an explicit CI mapping;
   validation will also compare source modules from that mapping with modules in
   the serial discovery list so every discovered module appears exactly once per
   test instance.
5. Verify invalid shard coordinates fail before executing a test and any
   unsuccessful selected result returns a nonzero process exit.
6. Validate workflow syntax, the fixed `0..3` matrix cardinality, runner-only
   path-filter coverage, and aggregate-check preservation.
7. Push one commit and wait for all PR CI jobs.
8. Sum shard test, skipped, and failure counts and require equality with the
   final serial suite. The expected count is 695 plus the focused partition
   tests; expected CI skips remain four.
9. Compare PR wall-clock and longest workflow shard against run `34461080086`.

A large speedup is acceptable only if all discovered test positions run exactly
once. Any reduced pre-existing count, missing shard, repeated position, count
mismatch, additional skip, silent failure, unexpected shared-state behavior, or
aggregate success with a failed/cancelled/skipped shard is a rollback condition.

## Success and stop conditions

Success requires green CI, unchanged effective coverage, and at least a 25%
wall-clock reduction from 2,361 seconds. If the matrix is queue-limited or
imbalanced and does not meet that threshold, measured test timings will be used
to rebalance or adjust the shard count on the same PR. If runner concurrency is
the remaining constraint, further reduction would require a materially different
test architecture; no security or coverage guarantee will be traded for the
target.

## Independent review disposition

The clean review challenged count comparability, path-filter coverage, matrix
failure behavior, discovery semantics, and balance evidence. This revision
adopts all four required contract corrections:

* pre-existing and final-suite counts are tracked separately;
* `scripts/run_workflow_test_shard.py` is an explicit agent-workflow trigger;
* the fixed matrix and `always()` aggregate fail closed on non-success;
* the runner selects native discovered instances by position, preserving loader
  failures and duplicates.

Per-test timing predicted a conservative 46% reduction for the fixed four-shard
modulo assignment despite local oversubscription. This clears the 25% target
without embedding mutable timing weights or a hand-maintained test manifest in
the security-sensitive selector. Actual GitHub concurrency and queue behavior
remain an explicit PR-CI measurement and iteration gate.

## Local implementation result

The final unchanged serial command ran 700 tests in 2,591.635s with no failures
or skips. The increase from 695 is exactly the five focused shard-runner contract
tests.

Four isolated clones ran the exact staged shard implementation:

| Shard | Tests | Skipped | Failed | Duration |
| --- | ---: | ---: | ---: | ---: |
| 0 | 175 | 0 | 0 | 607.16s |
| 1 | 175 | 0 | 0 | 902.50s |
| 2 | 175 | 1 | 0 | 828.80s |
| 3 | 175 | 3 | 0 | 554.49s |

The union is 700 tests, 4 environment-dependent skips, and 0 failures. Test
position and normalized ID multisets exactly match serial discovery, with all 20
test modules represented. The local critical path is 902.50s, 65.2% below the
2,591.635s final serial duration and 64.7% below the original 2,555.516s local
baseline. This is implementation evidence only; the PR must still confirm actual
GitHub Actions wall-clock improvement before any performance claim is final.

Fresh-interpreter isolation raises total local test work to 2,892.95s across the
four shards, 11.6% above the final serial duration. That overhead is accepted
because the critical path still falls substantially and each interpreter removes
the measured order/global-alias coupling. The 20 non-orchestrator modules took
only about 57s when profiled by module; reconstruction-heavy orchestrator cases
remain the dominant cost even after interpreter startup is included.
