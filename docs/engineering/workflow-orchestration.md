# Workflow orchestration

`scripts/workflow_orchestrator.py` is the inactive-by-default composition layer
for issue #144. It selects one next action for one issue; the authority,
runtime, evidence, work-type, plan-revision, and workflow-policy modules retain
their respective ownership boundaries.

The orchestrator never writes CAS or pointers, invokes Git/GitHub/processes
directly, imports the legacy lifecycle, or computes policy state itself. It
publishes through `workflow_evidence.publish`, selects through
`workflow_authority.prepare`/`commit`, and delegates all external work to
`workflow_runtime`.

## Activation blockers

The implementation path is complete, but production activation remains blocked
only by:

1. a reviewed `external-sandbox-v1` provider;
2. credential isolation that denies agents both GitHub credentials and the
   authority store;
3. a trusted pre-genesis owner that publishes the runtime's exact raw GitHub
   issue response into CAS (the current evidence API requires its subject to
   pre-exist, and the orchestrator is prohibited from writing CAS directly);
4. Slice 4 routing/cutover;
5. a separately reviewed repair contract for arbitrary corruption of the new
   authority pointer; and
6. migration and deferred replacement policy work.

`RUNTIME_PROVIDER` and `SANDBOX_PROVIDER` are deliberately unset in production.
The integration tests inject deterministic seams to exercise the complete
composition without claiming that those fixtures provide production containment.

## Commands

```text
python3 scripts/workflow_orchestrator.py status ISSUE --root ROOT
python3 scripts/workflow_orchestrator.py plan-next ISSUE --root ROOT
python3 scripts/workflow_orchestrator.py init ISSUE --root ROOT --request REQUEST
python3 scripts/workflow_orchestrator.py step ISSUE --root ROOT --expected-tip SHA256 [--request HANDOFF]
python3 scripts/workflow_orchestrator.py approve ISSUE --root ROOT --expected-tip SHA256 --authorization AUTHORIZATION
python3 scripts/workflow_orchestrator.py cancel ISSUE --root ROOT --expected-tip SHA256 --reason REASON
python3 scripts/workflow_orchestrator.py recover ISSUE --root ROOT --expected-tip SHA256 [--authorization AUTHORIZATION]
```

`status` and `plan-next` are read-only. Each mutating command performs at most
one authority commit. The first `step` atomically claims and executes exactly
one bounded runtime operation, publishes its result without changing authority,
and returns an immutable evidence handoff. While that request remains pending,
every caller is `busy` except a later `step` presenting that exact verified
handoff, which performs the sole result transition without executing another
process. A crash requires explicit recovery rather than another execution.
There is no run-until-done loop or automatic retry.

## Fresh implementation path

1. `init` re-observes the issue source, validates its externally seeded raw
   object, derives the family identity, publishes issue/baseline/triage
   evidence, creates `implementation-a`, initializes policy, and commits the
   genesis state.
2. The planner's stdout is parsed as a strict candidate, converted to a
   plan snapshot, and passed to `workflow_plan_revision_policy`.
3. A Reviewer produces a separately validated technical review. A
   `technical-review-accepted` result is not approval: it opens an exact
   GitHub plan challenge.
4. Verified human approval activates `plan-approval`. A reviewer-requested
   revision before approval is evaluated through the revision policy; a
   subsequent unsupported revision pauses. A revision after approval is never
   carried forward and pauses with `unsupported-policy-transition`.
5. The test author runs before the implementation author. Its clean,
   test-path-only repository observation is wrapped as `test-manifest`;
   technical test review then opens the independent human test challenge.
6. Verified test approval activates `test-approval`. The implementation report
   and its clean, one-commit in-scope observation are re-evaluated by the public
   #116 completion policy before activating `implementation-submission`.
7. Every configured comprehensive-validation check has its own claim/execution
   and verified-finalization steps. The final validation record contains the
   requests/results in profile order and activates `validation`. A failed check
   pauses with `unsupported-policy-transition`; it cannot silently replace
   implementation evidence.
8. A validated final technical review activates `final-review`. Its strict PR
   metadata must use exactly `## What`, `## Why`, and `## Testing`.
9. Draft-PR creation is claimed and run once. A failed/uncertain write is
   reconciled by a later exact PR observation, never by another create request.
   The runtime independently observes the already-published remote head twice
   around complete local observations and requires it to equal the validated
   local commit before mutation; the orchestrator accepts the result only when
   it embeds that exact trusted remote-head observation.
   `pr-metadata` requires an `OPEN`, draft PR matching base, head, title, and
   body hashes.
10. The final GitHub challenge is bound to the final review, validation, PR
    observation, and local observation. Its exact human authorization activates
    `pr-approval`; after authorization, both the PR and local repository are
    freshly re-observed for the same clean head/base/open-draft metadata, then
    the exact authorization source is re-observed before the single successor
    pointer state is `COMPLETED`.

The active #134 bindings are evaluated and published in this exact order:
`plan-approval`, `test-manifest`, `test-approval`,
`implementation-submission`, `validation`, `final-review`, `pr-metadata`, and
`pr-approval`. Node wrappers use the policy's direct dependencies and carry
the exact evidence, repository observation, and authorization appropriate to
each node.

Completion is an authority-pointer transition, not a merge. The orchestrator
never marks a draft ready, merges it, or deploys it.

## Human authority and recovery

Every human command resolves one live GitHub comment or review with the exact
challenge confirmation, numeric account identity, configured association, and
unedited body. Technical review output cannot satisfy a human gate.

Cancellation changes a pending executable attempt to `cancel-requested` without
clearing it. Because successor states must bind pending requests to their
immediate authority predecessor, the cancellation transition re-attests the
same immutable request bytes under that predecessor while retaining the
original attempt ID. The original request remains in the immutable ancestry;
the re-attestation is not executable and cannot create a second attempt.

Before runtime entry, the orchestrator synchronously rechecks authority and
pre-cancels execution if the claim is no longer current. During execution, a
bounded watcher observes authority. Any pointer change signals the runtime
cancellation event; a matching `cancel-requested` state records the intended
cancellation. A result arriving after any pointer change is stale and cannot be
selected.

Recovery is two steps: it first commits a `recovery` human challenge bound to
the recorded pending request, then consumes an exact GitHub authorization.
It reconstructs only the safe phase encoded by that request's fixed operation
name. It never accepts `{acknowledge: "recover"}`, resets blindly to planning,
or repairs a damaged pointer. Failed validation replacement remains paused.
Any historical draft-PR write claim remains discoverable across later failed or
cancelled reconciliation reads, so recovery can only reconcile that possible
mutation and can never schedule a second create.

Restart reconstruction follows only the selected pointer and immutable
evidence chain; no PID file, sidecar state, or hidden process table is used.

## Evidence and trust boundaries

Agent stdout is untrusted. The runtime binds it to a bounded process result;
the orchestrator verifies its hash, size, duplicate-free JSON, strict candidate
shape, and phase-specific fields before any candidate is used. Plan snapshots
and reviews are additionally verified by the public plan-revision policy.

All new orchestration documents are canonical evidence publications. The
orchestrator uses public APIs only and leaves evidence graph validation,
authority pointer mechanics, policy DAG evaluation, GitHub authentication, and
process containment to the lower owners.

Issue #115 is denied before any lookup. Fresh initialization rejects an existing
legacy owner, and every status read rejects dual legacy/replacement authority.
Non-implementation routes are rejected until their own route/cutover work is
activated. Migration remains an explicit later boundary and never inherits an
approval.
