# Workflow supervision policy

`scripts/workflow_supervision_policy.py` is the inactive, deterministic policy
owner for configurable approval gates in the replacement workflow. It has no
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

Before any orchestration action, the selected authority history is replayed from
genesis. Revision zero must equal the trusted baseline configuration. Every
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

Consumers also reconstruct authority history and require the named challenge to
have been the selected pending request and the satisfaction to have been selected
by its immediate gate successor. A self-consistent but orphaned challenge,
decision, or satisfaction cannot authorize later work. Every selected transition
out of a waiting gate is replayed against that exact satisfaction, including live
human-source re-observation; a structurally valid successor cannot skip a gate.

The orchestrator retains plan and test satisfaction in the existing
`plan-approval` and `test-approval` nodes. `final` and `pr-publication`
satisfaction remain standalone until deterministic post-write completion binds
them into the existing `pr-approval` node. The #134 node graph and evidence
formats are unchanged. History replay requires each approval wrapper to select
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
substituted challenge, edited authorization, and replay fail closed.

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
