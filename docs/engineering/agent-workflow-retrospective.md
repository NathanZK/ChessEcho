# ChessEcho governed-agent workflow retrospective

## Executive summary

ChessEcho's workflow did not become trustworthy by adding more approval
prompts. It became trustworthy by progressively moving consequential facts out
of the agent's authority and into independently checkable evidence.

The original #99 workflow established a useful state machine: planner,
reviewer, test implementer, implementation implementer, validation, and
human gates. It still had a dangerous implicit model, however: once a
producer supplied the expected report or phase-completion object, the
orchestrator could treat that report as the explanation of what happened.
The #198 saga exposed the limits of that approach. Provider versions,
transport framing, topology declarations, candidate-output JSON, acceptance
coverage, and phase-completion metadata repeatedly failed in different ways.
Issue #237 captured the durable lesson: producer compatibility is a
consumption problem; governance is a trust problem. The workflow may be
tolerant of representation, but it must be intolerant of unproven Git state,
scope, ancestry, authorization, and evidence.

The later sequence (#262/#266, #309, #311/#313, #315/#317, #318/#319,
#316/#320) made stale state and recovery first-class. A target is now an
identity, not a branch name; a candidate is a content-and-path identity, not
an agent assertion; approval is bound to evidence and target state, not a
boolean phase flag; and recovery is a governed transition that preserves
valid evidence while explicitly invalidating stale evidence. Journals are
crash-consistency evidence, not a substitute for authorization.

The #109/#1091 sequence is the end-to-end proof. A completed run was replayed
against an advanced `main`, first misclassified because scratch validation
 lacked dependency setup, then made recoverable after authoritative
configuration and narrowly scoped finalized-run recovery were added. The
reanchored revision exposed a second bug: comparing against the historical
parent implementation commit confused already-merged unrelated work with the
revision candidate. The fix made the run's own `test_commit` the boundary.
Later target movement, implementation-target recovery, revalidation, fresh
approval, PR publication, and a post-push GitHub race all completed through
deterministic recovery rather than manual state mutation.

The architecture is substantially safer than #99, but it is not automatically
optimal. Some mechanisms protect distinct trust boundaries and should remain.
Other mechanisms repeat the same invariant in phase-specific code, expose a
large failure-code surface, or mix producer compatibility with governance.
The right simplification criterion is invariant ownership: merge gates only
when they establish the same independently observable property, and do not
remove a check merely because it is inconvenient.

## Research basis and method

This retrospective was reconstructed from:

* the current `scripts/agent_workflow.py`, `scripts/tests/test_agent_workflow.py`,
  `.github/agent-workflow.json`, and `docs/engineering/agent-workflow*.md`;
* Git history and diffs for the workflow milestones, including `3bdfde8`
  (#99), `c9fedba` (the later simplification), `a0bc542`, `94d7d75`,
  `d3069ae`, `d795899`, `38f894e`, `5258a9a`, `b995ee8`, `b222c5f`,
  `b65eda5`, `de71db6`, `b918aa0`, and `fb10694`;
* GitHub issues #99, #198, #237, #252, #262, #266, #309, #311, #313,
  #315, #316, #317, #318, #319, #320, and #109, together with the merged
  PRs that implement them; and
* the current regression suite, especially the reconciliation, recovery,
  revision-boundary, validation-setup, PR-race, and target-authenticity
  tests.

`#1091` is a workflow-local revision identifier, not a GitHub issue. The
associated GitHub publication is PR #308. The final implementation commit is
`b0d7956ee7bdf7ca8ed56a2b846ca7deaf8c53b1`, on branch
`nathanzk-issue-109-opening-context`, published to PR #308 against `main`.

## Architectural timeline

| Milestone | Before | Trigger and change | Trust property established | Character |
| --- | --- | --- | --- | --- |
| #99 / PR #99 (`3bdfde8`) | Ad hoc agent execution and human convention | Introduced planner/reviewer/implementer roles, explicit states, three approval gates, frozen scope/test/Git evidence, validation, and draft-PR checks | A run cannot advance merely because an agent says it is ready; transitions have required artifacts and human gates | Foundational architecture |
| #198 era / PRs #199, #202, #204, #209, #210, #235, #243 | Provider output was consumed through increasingly strict prompt and event contracts | Repeated failures in provider/source identity, phase metadata, candidate JSON, acceptance coverage, file-created events, and transport framing led to explicit producer contracts and compatibility handling | The producer's representation is not the authority for repository facts | Compatibility hardening and architectural discovery |
| #237 / PRs #240 and #242 | Governance failures and producer presentation failures were entangled | Added a compatibility path and separated process outcome from transport framing; accepted reviewed representation variants without weakening Git/evidence gates | Tolerant input framing can coexist with strict trusted-local verification | Architectural boundary |
| #252 | Product identity and ownership were not reliably bound to an authenticated caller | Added owner-scoped identity/session behavior and concurrency tests; relevant to the workflow's broader authorization lesson | Names, caller-supplied selectors, and local acknowledgments are not authentication or ownership | Adjacent security hardening |
| #262 / #268 (`d3069ae`) | Every task was forced through a committed test change | Allowed an approved `NOT_APPLICABLE` test boundary without inventing a test artifact | Applicability is an explicit reviewed fact, not a synthetic commit | Corrective compatibility |
| #266 / PR #267 (`d795899`) | An in-progress run became unusable when authorized `main` advanced | Added fail-closed, descendant-only `reanchor-target` before implementation artifacts exist | Target identity can move only through a recorded, bounded transition; prior evidence is preserved | Recovery architecture |
| Candidate and Gate 3 hardening (`a0bc542`, `5258a9a`, #282-era tests) | A path list or diff serialization could be mistaken for candidate identity | Added canonical candidate tree/content/mode checks, target authenticity, direct-child topology, atomic journals, and crash recovery | Candidate bytes, paths, modes, ancestry, and topology are independently observable | Integrity hardening |
| #309 / PR #310 (`b995ee8`) | A completed run had to be manually re-created after target advancement; classifier used an historical baseline | Added completed-run reconciliation in an isolated scratch worktree, real three-way replay, validation, scope/equivalence checks, lease publication, and deterministic revision escalation | A completed run can be replayed only when its approved delta remains provably the same | Recovery machinery |
| #311 / PR #312 (`b222c5f`) | Scratch worktrees lacked ignored/generated dependencies, producing false test failures | Added configured setup commands, caller-supplied root support, persisted setup evidence, and retryable setup failure | Validation result is meaningful only with its environment and setup provenance | Test infrastructure |
| #313 / PR #314 (`b65eda5`) | Reconciliation could use stale caller configuration | Load and verify workflow code/config from the authoritative current target; never silently fall back | Current validation authority belongs to the target being reconciled, while historical run evidence remains immutable | Configuration provenance |
| #315/#317 / PR #317 (`de71db6`) | A finalized journal permanently preserved a false tooling-driven test revision | Added narrow recovery for only the eligible, auto-created, not-yet-progressed revision caused by immutable unavailable-tool evidence | A terminal journal is immutable by default, but a proven tooling misclassification has a separate governed correction | Recovery of recovery |
| #318/#319 / PR #319 (`b918aa0`) | Genuine overlap after target advancement made automatic candidate reconciliation unsafe, while reanchor was too early-only | Added in-flight `recover-implementation-target`, invalidating stale downstream evidence and requiring fresh submission/approval | Operators authorize a governed restart of stale work; they do not resolve conflicts manually | Recovery machinery |
| #316/#320 / PR #320 (`fb10694`) | Revision classifier compared against historical parent implementation after reanchor | Derive candidate paths from the current run's `test_commit`; add unrelated-change and genuine-out-of-scope regressions | Revision classification is owned by current-run evidence, not historical association | Provenance correction |
| #109/#1091 / PR #308 (`b0d7956`) | A normal product change crossed target movement, environment failure, reanchor, revision, and publication races | The incident exercised completed-run reconciliation, setup/config provenance, recovery, revision boundary, fresh approval, and PR recovery end-to-end | Recovery can preserve byte-identical approved work without transferring stale authority | Architectural validation |

## The #198 turning point: producer compatibility is not governance

Issue #198 was nominally a small product change, but its workflow runs became
an architectural experiment. The provider contract accumulated requirements
for a pinned source, provider/version metadata, phase-completion records,
planner acceptance coverage, reviewer candidate structure, implementation
commit boundaries, topology, and exact output framing. Fixes appeared to work,
then the next run failed on a different strict contract: a valid result was
wrapped in prose, a successful process emitted an unexpected event shape, or
acceptance coverage referred to the wrong projected issue snapshot.

Those failures were real, but they were not all governance failures. A
producer can fail to communicate a valid result without changing the
repository. Conversely, a producer can communicate a plausible result while
the worktree contains the wrong paths, a candidate commit has the wrong parent,
or approval refers to stale target state. Making prompts stricter treats both
cases as if they were the same problem and creates an unbounded compatibility
surface.

The durable split is:

* **Producer compatibility:** tolerate or normalize the representation needed
  to consume plans, reports, event streams, and phase results. A reviewed
  `model.call_failure` can be an ephemeral transport event; a successful
  process and its diagnostic framing are separate facts. Compatibility errors
  should be actionable producer/tooling failures.
* **Trusted workflow governance:** independently establish target identity,
  source integrity, ancestry, scope, candidate content and modes, test
  boundary, validation evidence, approval binding, topology, and live PR
  identity. No prose or JSON field can make these facts true.

Issue #237 states the principle directly: **be tolerant about how the agent
communicates and intolerant about what the workflow ultimately trusts**. The
resulting controller still consumes producer output, but the output is a
claim or input to a check, not proof of consequential work.

The source identity distinction is important. A cryptographic source SHA or
executable digest identifies the code that ran and is authoritative for
integrity. A human-readable provider version is useful audit labeling, but it
is not a production identity: it can be omitted, mislabeled, reused, or
describe a distribution rather than the executable actually invoked. The
same rule applies to local approval: the workflow records a self-attested
acknowledgment and its text, but that record does not authenticate a person.

## From state as truth to evidence as authority

The current implementation has several deliberately non-interchangeable
sources:

* `state.json` answers where a run believes it is in the state machine.
* Gate and reconciliation journals preserve transition evidence and crash
  boundaries.
* Git establishes commit identity, tree/content/mode identity, ancestry,
  changed paths, and topology.
* Validation records checks, setup, profile, target, and results.
* GitHub is re-read for live PR identity and head after publication.

The controller continuously reconstructs facts rather than trusting a copied
state field. The important evolution is:

| Concept | Failure mode | Current independent proof |
| --- | --- | --- |
| Target/base/head identity | `origin/main` moves or `origin` points at a different repository | Resolve and verify the configured authoritative remote, record the target OID, require strict descendant movement where recovery permits it |
| Test boundary | Production work changes an approved test or a test commit is reused under a different target | `test_commit`, approved paths/content, applicability, and byte-for-byte rechecks |
| Candidate identity | Agent report or path list describes different bytes than the worktree | Canonical candidate tree/content/mode identity, sorted paths, diff digest/length, clean index, and exact re-computation |
| Immutable evidence | A report, approval, or journal is edited after it was used | Digests, append-only transition entries, journal binding, atomic persistence, and refusal on mismatch |
| Ancestry/topology | A valid-looking commit is based on the wrong parent or contains extra commits | Direct-child and one-commit checks against the target; ancestry checks before and after replay |
| Scope | Unrelated target changes are mistaken for candidate work, or candidate paths escape approved scope | Concrete changed-path sets, approved plan/test union, tree equivalence, and current-run baselines |
| Approval binding | A phase flag survives changed candidate/target/evidence | Approval is revalidated against the exact target, evidence, candidate, acknowledgment, and journal-bound transition |
| Staleness | Old validation remains after target or candidate changes | Clear downstream evidence, require revalidation/fresh approval, or use a narrowly eligible replay |
| Reanchor/reconciliation | State is copied forward without proving the same work survived | Real Git replay, three-way conflict detection, target-change analysis, and persisted provenance |
| Supersession | Invalidated work is silently reused in a clean run | Explicit retirement with a reason and no inherited evidence |

The workflow now knows independently that candidate bytes, paths, modes,
parentage, target ancestry, PR identity, and validation configuration match
the evidence. It previously learned many of these facts from the agent's
phase-completion claim or mutable state.

## Recovery as a first-class architectural concept

### Completed-run reconciliation (#309)

Rerunning an old completed run against a newer `main` is unsafe because the
same branch name does not establish the same base, scope, or validation
environment. `reconcile-completed-run` therefore:

1. requires a strict descendant target and a live, matching draft PR;
2. reconstructs the approved candidate from the completed run, never from an
   agent-supplied patch;
3. creates an isolated scratch worktree at the new target;
4. applies the approved change as a real Git three-way replay;
5. rejects conflicts, target overlap, scope drift, topology drift, and
   content/mode differences;
6. runs the authoritative validation profile and records setup/check evidence;
7. creates exactly one direct-child reconciled commit;
8. publishes with `--force-with-lease`; and
9. verifies the live PR head before finalizing its journal and state.

The operation is not a convenience rebase. It is a proof that the approved
delta survives a new target.

### Validation setup (#311) and authoritative configuration (#313)

The first #109 replay failed because a tracked-files-only scratch worktree did
not contain ignored frontend dependencies. `eslint`, `tsc`, `vitest`, and
`next` were unavailable. The replay itself was clean; the test classification
was false. Setup commands, a caller-supplied root, persisted setup evidence,
and setup-failure semantics made environment failure distinct from candidate
failure.

That exposed a second provenance problem: the historical caller worktree could
load a pre-#311 configuration. Reconciliation now obtains workflow code and
validation configuration from the authoritative current target after target
identity and descendant checks. Historical state is input evidence and is not
rewritten with current configuration; current configuration controls how the
replay is validated.

### Finalized-run recovery (#315/#317)

Finalized journals are normally terminal evidence. Re-running them blindly
could replace an immutable classification with a later, unexplained result.
The recovery path is intentionally narrow: it accepts only a finalized
unavailable-tool failure that created an automatic test revision, where the
child is still at its initial entry state and has no genuine governed
progress. It records a separate immutable recovery journal and repeats the
replay under authoritative setup/configuration. A legitimate later test,
implementation, or plan revision is not resurrected or overwritten.

### In-flight implementation-target recovery (#318/#319)

If an uncommitted candidate overlaps a newly advanced target,
`reconcile-candidate` must fail closed. `reanchor-target` is deliberately
too early once implementation artifacts exist. `recover-implementation-target`
fills that gap by recording authorization, old/new targets, overlap analysis,
candidate and test-boundary identities, and every invalidated downstream
field. It never merges or rebases the stale candidate. It clears candidate,
validation, review, and implementation approval; if the test boundary is
affected it also clears the test commit and returns to test implementation.

This is operator authorization of a governed transition, not permission to
manually resolve Git conflicts. The workflow decides what is invalidated and
what must be re-established.

## The #316/#320 revision-boundary bug

The original revision classifier used the parent run's historical
`parent_implementation_commit` as the candidate-content baseline. That is
reasonable only while the revision target is still adjacent to that commit.
After `reanchor-target`, the revision's current target includes unrelated
already-merged changes. Comparing the candidate with the historical parent
therefore makes those unrelated files look like new revision content and can
escalate a legitimate test revision to a plan revision.

The correct baseline is the revision run's own `test_commit`: it is the
approved test boundary against which `submit-implementation`,
`reconcile-candidate`, and implementation-target reconciliation already
reason. Commit `fb10694` (PR #320) changed `_derive_revision_boundary` to use
that run-owned evidence. The regression suite covers both:

* unrelated changes already present after reanchor are ignored; and
* a genuine out-of-scope candidate still escalates.

This is a compact statement of provenance: a classifier must compare against
evidence belonging to the current run, not whichever historical commit is
conveniently associated with its parent.

## #109/#1091 end-to-end case study

### Original run and the first reconciliation

Issue #109 added optional Chess.com opening context. Its product intent was
narrow: use stored ECO/ECOUrl metadata for presentation, while normalized FEN
remained canonical and URL slugs were not treated as an authoritative opening
API. The run completed and its implementation was later published as PR #308.
The implementation commit was `b0d7956...`, with the run's approved target
and candidate evidence recorded before later `main` advancement.

Before PR #308 merged, `main` advanced because #107 merged. A completed-run
reconciliation was attempted as workflow-local revision #1091. The replay
was correctly recognized as needing proof rather than being blindly reused.
The initial #309 classifier also revealed a defect: comparing against the
historical #109 implementation made unrelated #107 frontend changes appear
to belong to #109. This was not a reason to weaken fail-closed behavior; it
motivated the run-owned baseline fixed by #320.

The first real scratch replay then failed with missing frontend executables.
It was an environment/setup failure, not a candidate or Docker/Testcontainers
failure: the scratch worktree had tracked files but no ignored/generated
dependencies. The old workflow could only turn that into a misleading test
revision. #311 added setup; #313 made the current target's configuration
authoritative.

Because the old finalized journal had already recorded the tooling-driven
classification, #315/#317 added the narrow recovery path. That recovery was
eligible only while the auto-created child revision had made no genuine
progress. Once #1091 had governed work of its own, finalized #109 was no
longer eligible for resurrection. Continuing #1091 was the correct path.

### Reanchor, byte identity, and validation

The approved candidate remained byte-identical while #1091 was reanchored.
The run's `test_commit` made the revision boundary stable even though the
historical parent implementation was behind the new target. Full-stack
validation then exposed environment-specific behavior. Docker/Testcontainers
failures were not evidence that the candidate was defective: the relevant
distinction was whether the candidate changed the tested code or the
environment could provide its dependencies and services. The frontend
process-group failure was a supervisor/environment artifact, not candidate
failure.

Using #109's original authoritative `frontend` profile was legitimate when
the governed evidence said that was the approved validation contract. A
current target's configuration governs reconciliation setup and execution,
but it does not silently rewrite the historical run's approved profile.

### Target advancement and governed implementation recovery

After another `origin/main` advancement, implementation approval correctly
failed. The approved implementation evidence was now stale; continuing to
approve it would transfer authorization across a changed target. The correct
command was `recover-implementation-target`, which preserved the audit
history, invalidated stale candidate/validation/review/approval fields,
reapplied the candidate through the governed path, and required fresh
validation and fresh human approval.

The GitHub post-push propagation race did not justify editing local state by
hand. The pending PR-revision journal and `recover-pr-revision` re-read the
live PR, checked its exact repository/number/base/branch/head/draft identity,
and finalized only when the observed remote state matched the journal. The
result was a completed governed revision with the expected implementation
identity, ancestry, validation, and PR publication.

### Could #99 have handled this safely?

Not end-to-end. #99 could gate the initial plan, tests, implementation, and
validation, but it had no safe answer for several later facts:

* target movement before publication required deleting/restarting or manually
  rebasing a run, losing or transferring evidence;
* completed-run replay had no isolated three-way reconciliation and exact
  equivalence proof;
* missing scratch dependencies looked like test failure;
* stale caller configuration could control reconciliation;
* a finalized false classification was terminal with no narrow recovery;
* reanchor could make historical-parent revision classification wrong;
* overlapping in-flight candidates had no governed invalidation path; and
* PR push propagation had no journal-bound recovery.

The original workflow would either stop safely and require a new run, or
invite unsafe manual intervention. The current workflow preserves more valid
work while refusing to make stale work valid by assertion.

## Failure taxonomy

| Category | Evidence and current handling | Fail closed? | Recovery/retry | Current gap |
| --- | --- | --- | --- | --- |
| Candidate/software defect | Candidate-specific validation failure, content/scope evidence, reproducible check output | Yes | Return to implementation; fresh validation/approval | A product failure and environment failure can still require operator interpretation |
| Test defect | Reviewed test boundary or explicitly reopened test evidence; test revision path | Yes | `reopen-tests` or governed test revision | Classification depends on approved human/test evidence |
| Workflow/tooling defect | Workflow tests, malformed journals, persistence failures, unsupported command/config | Yes | Crash-safe recovery or code fix; do not reuse ambiguous state | Some codes are implementation-level rather than operator-level categories |
| Stale evidence | Target/candidate/evidence digest or journal binding mismatch | Yes | Reanchor, reconciliation, or invalidate and restart | Many stale variants surface as distinct codes |
| Target advancement | Strict descendant target with unchanged approved delta, or overlap | Yes | Reconcile if provably safe; otherwise recover/reopen | Branch movement and target authenticity remain operationally expensive |
| Target overlap | Changed target paths intersect approved test/candidate paths or replay conflicts | Yes | In-flight recovery or a governed revision | No safe automatic conflict resolution by design |
| Environment/dependency failure | Setup evidence and unavailable-tool diagnostics distinguish missing tools from check failure | Yes | Retry after authoritative setup/config correction | Service availability versus candidate defect can remain hard to classify |
| Provider/producer compatibility | Transport framing, phase metadata, candidate representation, provider event shape | Yes for consumption, not as repository proof | Normalize/retry or fix producer contract | Historical compatibility logic still leaks into some orchestration paths |
| External API/race | Live PR identity/head differs during or after push; lease failure | Yes | Pending journal plus `recover-pr-revision` or retry | Not every GitHub race has a dedicated recovery command |
| Ambiguous ancestry/evidence | Non-descendant target, wrong parent, dirty worktree, conflicting journal, missing digest | Yes | No automatic recovery until evidence is unambiguous | Correctly conservative, but operator diagnostics are dense |
| Governance/authorization violation | Wrong role, acknowledgment, confirmation, or phase transition | Yes | Obtain fresh explicit authorization; never infer it | Local acknowledgment is intentionally not caller authentication |
| Security/integrity violation | Substituted remote, repository mismatch, candidate tampering, PR identity mismatch | Yes | Stop; supersede or investigate, never transfer evidence | Cryptographic executable identity and human label are not always presented together |

The taxonomy is useful, but the implementation does not yet expose all
categories as a small normalized public model. The typed error surface is
precise for fail-closed behavior but difficult to aggregate operationally.

## Current architecture by trust boundary

| Fact | Current authority | Agent claim trusted? | Independent verification? |
| --- | --- | --- | --- |
| Source executable identity | Cryptographic/pinned source identity and verified executable/integrity metadata | No | Yes, where the provider path exposes it |
| Provider version | Human-readable audit label | No | Not a production identity; label is recorded only |
| Target HEAD | Authoritative remote resolution and Git OID | No | Yes; remote identity, ancestry, and OID are checked |
| Ancestry | Git commit graph | No | Yes |
| Candidate bytes/modes | Git tree/content and canonical candidate identity | No | Yes |
| Test boundary | Approved `test_commit`, paths/content, applicability | Only as proposal | Yes at submit, validation, approval, and recovery |
| Validation results | Configured profile, setup evidence, check output, target/candidate binding | No | Yes, by executing checks and persisting evidence |
| Approval | Human acknowledgment bound to the exact transition evidence | Only as a request | Binding is checked; caller authentication is not claimed |
| PR identity | Live GitHub repository/number/base/branch/head/draft state | No | Yes before and after publication |
| Merge status | GitHub live state and Git ancestry | No | Yes when queried; merge remains human-controlled |

## Over-engineering assessment

### Keep

* Independent Git verification of candidate bytes, modes, scope, ancestry, and
  one-commit topology. These protect different ways a plausible report can
  diverge from actual work.
* Target authenticity and descendant checks. A branch name is not an
  authority.
* Immutable evidence plus atomic, journal-first transitions. Crash recovery
  without durable intent risks duplicate commits or unbound state.
* Fresh validation and approval after target movement. This is the core stale
  authorization guarantee.
* Isolated real replay for completed-run reconciliation. A textual patch or
  agent-supplied revision ID is insufficient.
* Explicit recovery commands that invalidate only stale downstream evidence.
  They preserve work without making conflicts or authorization disappear.
* Live PR identity, lease publication, and post-push recovery. External
  systems are part of the trust boundary.

### Simplify

The code repeats candidate, test-boundary, topology, journal-binding, and
target checks across normal approval, candidate reconciliation,
implementation-target reconciliation, completed-run reconciliation, and
revision publication. These should share named invariant projections and
produce a common evidence record, while retaining phase-specific policy
around what may be invalidated.

The failure-code surface should be grouped by invariant ownership (identity,
ancestry, content, scope, authorization, environment, external race) with
phase/context fields, rather than making every phase-specific symptom a
separate conceptual category. This is an observability simplification, not a
request to remove fail-closed checks.

### Reclassify

Transport framing, provider event variants, human-readable version labels,
and producer report formatting belong to compatibility/diagnostics unless
they prevent the workflow from obtaining independently verifiable evidence.
They should not be described as authorization failures. Conversely, a
candidate tree mismatch or PR identity mismatch should remain governance and
integrity failures even if a producer caused it.

### Remove only with evidence

No current evidence supports removing target authenticity, exact content
equivalence, three-way conflict detection, fresh approval, setup provenance,
journal durability, or live PR re-verification. They each correspond to an
observed failure or a concrete ambiguity in #109/#1091 and the recovery
tests. A mechanism should be retired only after a replacement owns the same
invariant and regression evidence demonstrates equivalent protection.

## Principles to preserve

1. **Agents produce claims; the workflow establishes facts.** #198/#237
   showed that better prompts cannot be the authority.
2. **Approval authorizes a specific evidence state.** #99 established gates;
   #266 and #318 showed why approval cannot float across target movement.
3. **Immutable evidence remains immutable.** #315/#317 preserve finalized
   journals and use a separate, narrow recovery record.
4. **Stale evidence never transfers silently.** #262/#266 and #1091 require
   reanchor, reconciliation, or invalidation.
5. **Target advancement is normal, governed input.** #266 and #309 turn branch
   movement into explicit replay/recovery rather than an excuse to bypass.
6. **Recovery preserves valid evidence and invalidates only stale evidence.**
   #319 and #317 are deliberately narrower than a clean restart.
7. **Operators authorize transitions, not Git conflict semantics.** #318/#319
   refuse manual conflict resolution as a governance shortcut.
8. **Run-owned provenance beats historical association.** #316/#320 make
   `test_commit` the revision boundary after reanchor.
9. **Validation is contextual evidence.** #311/#313 show that setup,
   profile, target, and configuration authority are part of the result.
10. **Fail closed when authority or provenance is ambiguous.** This is the
    consistent response to substituted remotes, dirty state, overlap,
    journal mismatch, and live PR races.

## Remaining risks

### Observed

* The workflow has duplicated state representations and repeated invariant
  checks. This increases maintenance and makes it harder to explain which
  projection is authoritative.
* The typed failure surface is difficult to aggregate into operational
  metrics or a concise operator runbook.
* #109/#1091 demonstrated that environment failures can be misclassified until
  setup and configuration provenance are explicitly implemented.
* External GitHub propagation can leave a pending journal even after the
  remote operation succeeded; recovery is safe but adds operator cognitive
  load.
* Documentation and implementation are both large and must evolve together;
  the historical simplification commit (`c9fedba`) demonstrates that
  architecture can accumulate faster than its explanatory model.

### Strongly evidenced concerns

* Producer compatibility remains adjacent to governance in some command paths,
  so a future provider change could again create pressure for governance
  exceptions.
* Recovery commands have different eligibility rules that are individually
  justified but easy to confuse: reanchor, candidate reconciliation,
  implementation-target recovery, completed-run reconciliation, completed-run
  recovery, and PR-revision recovery.
* Local self-attested acknowledgment is a process gate, not authenticated
  human identity. The documentation states this correctly; operators must not
  infer stronger assurance.

### Not established by this investigation

No evidence here proves that the workflow has a GitHub authorization bypass,
that a current recovery command silently accepts a conflicting candidate, or
that PR #308's final commit is wrong. Such claims would require a separate
security or incident investigation.

## Recommended next architectural work

### P0 — preserve current trust guarantees

* Keep all target authenticity, ancestry, exact-content, setup-provenance,
  fresh-approval, journal-durability, and live-PR checks.
* Treat any proposed recovery simplification as a proof obligation: name the
  invariant, its authority, and the regression cases that replace the old
  check.

### P1 — simplify and clarify architecture

* Extract shared invariant projections for target identity, candidate identity,
  test boundary, topology, and approval binding. Keep policy-specific
  invalidation separate from proof computation.
* Publish a small recovery decision table that maps observed state to exactly
  one legal command, including why the other commands are refused.
* Separate producer compatibility diagnostics from governance failure codes in
  the operator-facing model.

### P2 — improve observability and auditability

* Add structured summaries of authority, evidence digests, target transitions,
  setup outcomes, and invalidated fields to journal inspection output.
* Aggregate failures by invariant owner and environment/provider cause without
  collapsing the detailed code needed for fail-closed diagnosis.
* Add a documented incident template for external-service races and
  environment failures.

### P3 — future experiments

* Explore a read-only “proof report” that explains which authority established
  each current fact before an operator authorizes recovery.
* Measure recovery frequency and time-to-resolution before changing gate
  boundaries. Convenience alone is not evidence that a gate is unnecessary.

## Conclusion

The central evolution is from a workflow that asks an agent to complete phases
to a workflow that independently proves the consequences of those phases. The
agent remains valuable for planning, implementation, review, and diagnosis,
but its output is no longer the source of truth for repository state.

The resulting system is more conservative, more verbose, and more complex than
#99. That cost is justified where it protects distinct trust boundaries and
allows valid work to survive target movement without silently transferring
authorization. It is not justified when the same invariant is restated in
multiple producer-specific or phase-specific forms. The next architectural
step is therefore not weakening governance; it is making authority and
invariant ownership explicit enough that the system can be simpler without
becoming less trustworthy.
