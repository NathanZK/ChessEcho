# Workflow supervision policy

`scripts/workflow_supervision_policy.py` is the deterministic policy owner for
the active replacement workflow's configurable approval gates. The orchestrator
composes its results, but the module itself has no
filesystem, process, Git, GitHub, evidence-publication, authority-selection,
migration, repair, or legacy-lifecycle capability. It imports only
`workflow_inspector` for canonical JSON, digests, and binding validation.

## Exact configuration

The `orchestrator.supervision` object in `.github/agent-workflow.json` has format
`chess-echo-supervision-config-v1` and exactly four sorted gate rows:

| Gate | Meaning |
|---|---|
| `plan` | Accept the reviewed plan before test implementation. |
| `tests` | Accept the reviewed test contract before implementation. |
| `final` | Accept implementation and final-review evidence before PR preparation. |
| `pr-publication` | Permit the trusted GitHub draft-PR mutation. |

Each row independently selects `supervised` or `automatic`; every committed
default is `supervised`. Missing, duplicate, extra, unsorted, unknown, or
non-string gates and modes fail closed.

Recovery, production activation, legacy-to-replacement authority cutover,
irreversible recovery/repair, production credential/provider authorization, and
irreversible authority transfer are mandatory-human operations. They are not
configurable gates and cannot be represented in the four-gate map.

## Policy and gate evidence

Initialization converts the base-pinned configuration into a canonical
`supervision-policy` bound to issue, family, baseline, and revision zero. A gate
challenge binds that exact policy revision, authority predecessor, gate, mode,
subjects, and repository observation.

Before any orchestration action, the replacement orchestrator revalidates the
selected authority history from genesis. Revision zero must equal the trusted
baseline configuration. Every
replacement must be the exact successor of a selected
`supervision-policy-change` challenge and its exact human authorization; a
self-consistent replacement policy cannot select itself.

A supervised challenge can be satisfied only by an exact GitHub
`human-authorization`. An automatic challenge can be satisfied only by the
deterministic rule `configured-automatic-v1`; it has no actor, reviewer, agent,
or discretionary selection field. Both paths publish a standalone
`gate-satisfaction` record that binds exactly one mechanism. Human
authorization, automatic policy satisfaction, and agent/reviewer output remain
separate evidence types and cannot substitute for one another.

The supervised `tests` gate also has an explicit two-step rejection path. A
`gate-rejection-challenge` binds the current waiting generation, pointer,
authority, approval challenge, rejected `test-manifest`, technical review,
repository observation, human reason, and fixed `TEST_IMPLEMENTATION` target.
Its byte-exact GitHub authorization has decision `reject`, not `approve`.
Selection publishes a standalone `gate-rejection` record and a successor
authority generation; it never edits the approval challenge or rejected
evidence. Reworked tests replace `test-manifest` through the existing bounded
policy reopen, which preserves the old node as invalidated history and forces a
fresh technical review and fresh approval challenge.

No other gate currently has an activated rejection transition. Plan, final, and
publication rejection would require distinct lifecycle and policy invalidation
contracts, and automatic gates have no pending human action. Those cases,
non-gate phases, approved history, and published artifacts fail closed.

Consumers also reconstruct authority history and require the named challenge to
have been the selected pending request and the satisfaction to have been selected
by its immediate gate successor. A self-consistent but orphaned challenge,
decision, or satisfaction cannot authorize later work. Every selected approval transition out of a waiting gate is recomputed against
that exact satisfaction, including live human-source re-observation. The test
rejection transition is independently recomputed against its exact challenge,
artifact, current authority, and live human-source observation. A structurally
valid successor cannot skip either gate decision.

The orchestrator retains plan and test satisfaction in the existing
`plan-approval` and `test-approval` nodes. `final` and `pr-publication`
satisfaction remain standalone until deterministic post-write completion binds
them into the existing `pr-approval` node. The #134 node graph and evidence
formats are unchanged. Selected-history revalidation requires each approval
wrapper to select
the exact challenged subject, technical review, repository observation, and
authorization mechanism; completion requires both gate satisfactions and the
reconciled `pr-metadata`/`pr-approval` evidence.

## Policy changes

`set-supervision` accepts the complete four-gate map and an expected authority
tip. It is rejected while any gate or execution is pending and in waiting,
paused, publication-preparation, or terminal phases. Publication preparation is
closed because an already satisfied publication gate may still authorize the
pending GitHub write. A valid request opens a mandatory-human
`supervision-policy-change` challenge without changing the current policy.
Only exact authorization publishes revision `N+1`.

Each challenge permanently retains the policy revision that created it.
Revisions affect future challenges only; they do not reinterpret historical
challenges or satisfaction evidence. Stale tips, changed phase, changed policy,
substituted challenge, edited authorization, and stale reuse fail closed.

## Publication ordering

An accepted final technical review opens `final`. Final satisfaction uses a
fresh clean local observation and moves to PR preparation without writing to
GitHub. PR preparation first obtains another clean local observation and opens
`pr-publication`. Only selected publication satisfaction permits a
`create-draft-pr` claim.

Immediately before that claim, the selected final and publication satisfactions,
including both supervised authorization sources, are re-observed and their current
subjects are checked against one common final-approved repository/config snapshot.
They are checked
again inside the runtime handoff after trusted remote-head preflight; the remote
head and local/config snapshot are then re-observed before process launch.
Existing uncertain-write reconciliation and post-crash behavior remain in
`workflow_runtime`. After reconciliation selects `pr-metadata`, the
orchestrator verifies local state, freshly observes the PR, then verifies local
state again so repository movement during the PR read fails closed. It binds both standalone
satisfactions into `pr-approval`, and completes. Completion does not merge,
activate, mark ready, cut over, or deploy.
