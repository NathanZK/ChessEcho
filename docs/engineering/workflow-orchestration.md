# Workflow orchestration

`scripts/workflow_orchestrator.py` is the provider-neutral composition layer
delivered under issue #144. That tracker remains open with unreconciled
acceptance items; current source and tests, not checkbox state, establish the
behavior documented here. The orchestrator selects one next action for one
issue; the authority, runtime, evidence, work-type, plan-revision, and
workflow-policy modules retain their respective ownership boundaries.

The orchestrator never writes CAS or pointers, invokes Git/GitHub/processes
directly, imports the legacy lifecycle, or computes policy state itself. It
publishes through `workflow_evidence.publish`, selects through
`workflow_authority.prepare`/`commit`, and delegates all external work to
`workflow_runtime`.

## Phase 1 local activation

Phase 1 is activated through the reviewed
[`workflow_local_host.py`](workflow-local-provider.md) entry point.
`RUNTIME_PROVIDER`, `SANDBOX_PROVIDER`, and `PENDING_RESULT_PROVIDER` remain
unset when this module is invoked directly, so an action that needs runtime,
provider, or pending-result capability fails closed. Core-only actions that do
not need those seams can still change authority; direct invocation is therefore
not a universal no-mutation boundary. The host installs fixed, base-pinned
providers without plugin discovery.

Phase 1 deliberately does not claim hostile-process isolation. The trusted
local operator accepts same-UID filesystem, credential, authority-store, and
network exposure while agent output remains untrusted and all existing
evidence, validation, authority, reconstruction, and human gates remain in
force. Hardened isolation remains issue #160.

## Commands

```text
python3 scripts/workflow_orchestrator.py status ISSUE --root ROOT
python3 scripts/workflow_orchestrator.py plan-next ISSUE --root ROOT
python3 scripts/workflow_orchestrator.py init ISSUE --root ROOT --request REQUEST
python3 scripts/workflow_orchestrator.py step ISSUE --root ROOT --expected-tip SHA256 [--request HANDOFF]
python3 scripts/workflow_orchestrator.py approve ISSUE --root ROOT --expected-tip SHA256 --authorization AUTHORIZATION
python3 scripts/workflow_orchestrator.py reject ISSUE --root ROOT --expected-tip SHA256 [--reason REASON | --authorization AUTHORIZATION]
python3 scripts/workflow_orchestrator.py set-supervision ISSUE --root ROOT --expected-tip SHA256 --supervision SUPERVISION
python3 scripts/workflow_orchestrator.py cancel ISSUE --root ROOT --expected-tip SHA256 --reason REASON
python3 scripts/workflow_orchestrator.py recover ISSUE --root ROOT --expected-tip SHA256 [--authorization AUTHORIZATION]
```

`status` and `plan-next` are read-only. Each mutating command performs at most
one authority commit. The first `step` atomically claims exactly one bounded
runtime operation and then executes it externally; the claim exists before the
process starts. Execution publishes its result without changing authority and
returns an immutable evidence handoff. While that request remains pending, a
competing execution/finalization cannot take ownership; read-only status and
planning remain available, cancellation may mark an executable claim, and a
later `step` presenting the exact verified handoff performs the sole result
transition without executing another process. A crash requires explicit
recovery rather than another execution. The orchestrator itself has no
run-until-done loop or automatic retry; `workflow_driver.py` provides a
separate bounded continuation loop over fresh `plan-next` results.

`reject` is deliberately narrower than approval. At the current supervised
`tests` gate, the first call supplies a nonempty reason and replaces the pending
approval action with an immutable rejection challenge bound to the exact issue,
family, waiting phase, generation, pointer, authority, approval challenge,
`test-manifest`, technical review, and repository observation. A second call
supplies the exact GitHub authorization source for that challenge. It publishes
standalone rejection and authorization evidence, advances authority by one more
generation, and returns to `TEST_IMPLEMENTATION`. The rejected manifest,
review, approval challenge, transport, and provenance remain in immutable
history. Submitting reworked tests uses the policy's bounded `tests` reopen to
invalidate the old manifest and descendants, activates a new manifest, and
requires a new technical review and human approval challenge.

Plan, final, publication, automatic, already-approved, published, historical,
and non-gate states have no rejection transition. They fail closed rather than
falling through recovery, retry, or a generic force path. Stale expected tips,
edited or replayed authorization, changed repository state, and any mismatched
issue/family/generation/pointer/authority/challenge/artifact binding produce no
successor.

`set-supervision` is a core orchestrator API but is not exposed by the current
reviewed Phase 1 local host. The documented host path can operate configured
gates but cannot change their mode. Treat runtime gate-mode revision as an
activation gap, not as an available local-host operator procedure.

## Fresh implementation path

1. `init` requires the typed publication from
   [`workflow-issue-source.md`](workflow-issue-source.md), repeats its exact
   base-pinned bootstrap and issue observation, validates unchanged
   bootstrap/config/tool/source identity and exact CAS bytes, derives the family
   identity, publishes issue/baseline/triage plus a credential-free runtime
   reconstruction pin, creates `implementation-a`,
   initializes policy, and commits the genesis state. An edit after intake requires
   a fresh explicit intake; no source is silently substituted.
2. The planner's stdout is parsed as a strict candidate, converted to a
   plan snapshot, and passed to `workflow_plan_revision_policy`.
3. A Reviewer produces a separately validated technical review. A
   `technical-review-accepted` result is not gate satisfaction: it opens an
   exact policy-bound plan challenge.
4. Supervised human authorization or deterministic automatic satisfaction,
   according to the selected policy revision, activates `plan-approval`. A reviewer-requested
   revision before approval is evaluated through the revision policy; a
   subsequent unsupported revision pauses. A revision after approval is never
   carried forward and pauses with `unsupported-policy-transition`.
5. The test author runs before the implementation author. Its clean,
   test-path-only repository observation is wrapped as `test-manifest`;
   technical test review then opens the independent test challenge. An explicit
   human rejection opens its own exact challenge and, after authorization,
   creates a successor generation at `TEST_IMPLEMENTATION`; the reworked
   manifest replaces the rejected policy root only after another successful
   test-author result.
6. Policy-governed test satisfaction activates `test-approval`. The implementation report
   and its clean, one-commit in-scope observation are re-evaluated by the public
   #116 completion policy before activating `implementation-submission`.
7. Every configured comprehensive-validation check has its own claim/execution
   and verified-finalization steps. The final validation record contains the
   requests/results in profile order and activates `validation`. A failed check
   pauses with `unsupported-policy-transition`; it cannot silently replace
   implementation evidence.
8. A validated final technical review activates `final-review` and opens the
   pre-publication `final` gate. Its strict PR metadata must use exactly
   `## What`, `## Why`, and `## Testing`. Final satisfaction binds final-review,
   validation, and a fresh clean local observation; it never depends on
   `pr-metadata`.
9. PR preparation obtains a fresh clean local observation and opens the distinct
   `pr-publication` gate. Its selected standalone satisfaction is mandatory
   before a GitHub-write request can be claimed. Both selected gate
   satisfactions and their supervised authorization sources are re-observed
   before that claim and again after the initial trusted remote-head preflight;
   both gates must retain one common final-approved repository/config snapshot.
   The runtime repeats the complete local/remote-head observation after those
   authorization reads and immediately before the mutation process starts.
10. Draft-PR creation is claimed and run once. A failed/uncertain write is
   reconciled by a later exact PR observation, never by another create request.
   The runtime independently observes the already-published remote head twice
   around complete local observations and requires it to equal the validated
   local commit before mutation; the orchestrator accepts the result only when
   it embeds that exact trusted remote-head observation.
   `pr-metadata` requires an `OPEN`, draft PR matching base, head, title, and
   body hashes.
11. After `pr-metadata` is selected, local state is observed before and after a
    fresh PR observation for the same clean head/base/open-draft metadata. A deterministic
    follow-up binds final satisfaction, publication satisfaction, final review,
    and the PR observation into existing `pr-approval`, then moves the single
    successor pointer to `COMPLETED`.

The active #134 bindings are evaluated and published in this exact order:
`plan-approval`, `test-manifest`, `test-approval`,
`implementation-submission`, `validation`, `final-review`, `pr-metadata`, and
`pr-approval`. Node wrappers use the policy's direct dependencies and carry
the exact evidence, repository observation, and authorization appropriate to
each node.

Completion is an authority-pointer transition, not a merge. The orchestrator
never marks a draft ready, merges it, or deploys it.

The four configurable gates and policy-revision protocol are specified in
[`workflow-supervision-policy.md`](workflow-supervision-policy.md). Automatic
satisfaction is deterministic policy evidence, not human authorization and not
agent or reviewer output. Mandatory-human recovery, activation, cutover,
irreversible repair/authority transfer, and production credential/provider
authorization remain outside that configuration.

Every mutating public command reconstructs the selected supervision-policy
history. Genesis must equal the base-pinned configuration, and each replacement
must be the exact human-authorized successor selected by authority.
The same selected-history revalidation validates lifecycle phase origins, every configurable-gate
successor, and both sides of mandatory-human recovery. Structural authority
selection alone cannot skip an approval or resume a paused/cancelled attempt.

## Candidate contract

The provider is allowed to speak its own external protocol, but it does not get
to define what the workflow considers valid work.
`workflow_local_provider.py` validates Copilot JSONL, identifies the terminal
candidate boundary, extracts exact candidate bytes, and preserves the raw
transport. `workflow_orchestrator_resume.py` then verifies that those bytes
match the execution-result digest, decodes duplicate-free UTF-8 JSON, and owns
the strict phase-specific contract:

- plan candidates contain exact plan text, line-bounded units, dependencies,
  review classes, and revision metadata;
- review candidates use only `accepted`, `needs-revision`, or
  `full-review-required`, with exact finding fields;
- implementer candidates contain one nonempty report; and
- final-review PR metadata has exact `head_ref`, `title`, and `body` fields,
  with nonempty `## What`, `## Why`, and `## Testing` sections.

Provider 1.5.6 carries operation-specific JSON Schema copies to communicate the
plan candidate contract in `write-plan` and the review candidate contract in
`review-plan`, `review-tests`, and `review-final`. The plan schema has distinct
initial and revision variants selected from the exact projected input roles
and describes the existing unit-map and deterministic-diff constraints.
Because JSON Schema cannot express array ordering, the planner prompt also
states directly that each dependency array is a canonical set representation
sorted by ascending UTF-8 byte order, not an execution-order signal.
Focused tests keep these prompt schemas aligned with the unchanged core
validators. Implementer prompts do not embed a JSON Schema copy. The provider
cannot normalize an unsupported shape, verdict, extra field, or prose-prefixed
object into acceptance. #198 demonstrated both an invalid reviewer candidate
and, in a later isolated run, a planner candidate containing prose before JSON;
the prompt corrections leave strict decoding unchanged. Its only
`session.info` compatibility rule accepts an ephemeral `file_created` event
bound to one active `create` start and its exact path; the event does not alter
tool completion accounting.

## Human authority and recovery

Every human command resolves one live GitHub issue comment with the exact
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
Only initialization may use strict live bootstrap. Later commands give the
reviewed runtime host the exact selected runtime pin, baseline, triage, current
authority, and phase repository evidence. The returned adapter must attest to
that byte-identical reconstruction request, and authority is checked again
after reconstruction.

`status` and `plan-next` expose a
`chess-echo-pending-result-query-v1` when an executable request is pending. A
reviewed result-discovery host may answer only that exact query with bounded
published evidence-binding candidates. Zero candidates is `missing`, multiple
candidates is `ambiguous`, and a malformed, wrong-kind, wrong-subject,
wrong-attempt, wrong-family, or stale-authority candidate fails closed. The
orchestrator verifies the selected object through the existing evidence
boundary and can deterministically republish its embedded repository-after
observation before finalization. Discovery never scans CAS, runs the external
operation, retries a GitHub mutation, or assumes that a child process or escaped
descendant stopped after caller death.

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
