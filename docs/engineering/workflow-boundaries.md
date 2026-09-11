# Workflow Module Boundaries

Issue #130 established the first explicit dependency boundary without
redesigning the workflow lifecycle or changing its stored formats. This current
map extends that original boundary through the later replacement-orchestrator,
runtime, provider, and driver work.

## Current responsibility map

| Area | Current owner |
|---|---|
| Legacy v4 projection paths and canonical serialization | `workflow_kernel.py` |
| Legacy v4 envelope and transaction-snapshot integrity checks | `workflow_kernel.py` |
| Per-run locking and atomic projection-file replacement | `workflow_kernel.py` |
| Bounded external process execution and process-group cleanup | `workflow_supervisor.py` |
| Immutable durable content-addressed storage (CAS) publication | `workflow_cas.py` |
| Canonical evidence manifests, provenance, bindings, and derived views | `workflow_evidence.py` |
| Deterministic legacy/durable compatibility planning and immutable publication | `workflow_migration.py` |
| Dependency invalidation and convergence policy evaluation | `workflow_policy.py` |
| Work-type intake, route, advisory targeted-check, and structural completion policy | `workflow_work_type_policy.py` |
| Incremental reviewed-plan revision policy | `workflow_plan_revision_policy.py` |
| Four-gate supervision policy | `workflow_supervision_policy.py` |
| Replacement orchestration authority pointer | `workflow_authority.py` |
| Authorized replacement-pointer restoration | `workflow_authority_repair.py` |
| Active trusted Git/GitHub/process and validated-source publication adapter | `workflow_runtime.py` |
| Pure runtime reconstruction schemas and comparison logic | `workflow_runtime_reconstruction.py` |
| Reviewed Phase 1 host and deterministic workspace/result bootstrap | `workflow_local_host.py` |
| Trusted-local agent execution and execution-fact attestation | `workflow_local_provider.py` |
| Trusted pre-genesis issue-source publication | `workflow_issue_source.py` |
| Active replacement lifecycle composition | `workflow_orchestrator.py` and its gate-focused `workflow_orchestrator_gates.py` mixin |
| Exact candidate decoding, phase-specific candidate schemas, and pending-result resume helpers | `workflow_orchestrator_resume.py` |
| Bounded automatic continuation over fresh host plans | `workflow_driver.py` |
| Legacy lifecycle, approvals, reviews, corrections, validation, adoption/migration, and projection-recovery policy | `agent_workflow.py` |
| Git, GitHub, process execution, command parsing, and human-facing output | `agent_workflow.py` |
| Durable-store inspection and checkpoints | `workflow_inspector.py` |
| Durable-store repair bundles and recovery | `workflow_repair.py` |

The canonical [architecture and status
map](agent-workflow.md#current-responsibility-and-dependency-map) distinguishes selected replacement
authority, composed policy, independently callable trusted mechanisms, and the
separate legacy lifecycle. In this table, legacy adoption/migration and
projection recovery are intentionally separate from the durable evidence
migration and durable-store repair owners.

The merged baseline has no duplicate top-level production definitions or
`globals()` aliases. A structural test preserves that property. The explicit
`COMMAND_HANDLERS` registry in `agent_workflow.py` makes every public parser
command resolve to one named active handler instead of relying on a conditional
dispatch chain.

## Dependency direction

The mechanically enforced internal dependency graph is:

```text
agent_workflow   -> workflow_kernel

workflow_evidence -> workflow_inspector, workflow_cas
workflow_migration -> workflow_inspector, workflow_cas, workflow_evidence, workflow_kernel
workflow_policy -> workflow_inspector, workflow_evidence, workflow_migration
workflow_work_type_policy -> workflow_inspector, workflow_evidence, workflow_supervisor
workflow_plan_revision_policy -> workflow_inspector, workflow_evidence
workflow_supervision_policy -> workflow_inspector
workflow_authority -> workflow_inspector, workflow_evidence, workflow_cas
workflow_authority_repair -> workflow_inspector, workflow_authority, workflow_cas
workflow_runtime -> workflow_inspector, workflow_supervisor, workflow_runtime_reconstruction
workflow_runtime_reconstruction -> workflow_inspector
workflow_local_provider -> workflow_cas, workflow_evidence, workflow_inspector,
                           workflow_supervisor
workflow_local_host -> verified dynamic imports from the reviewed control checkout
workflow_issue_source -> workflow_inspector, workflow_cas, workflow_runtime
workflow_orchestrator -> workflow_inspector, workflow_evidence, workflow_authority,
                         workflow_work_type_policy, workflow_plan_revision_policy,
                         workflow_policy, workflow_supervision_policy, workflow_runtime,
                         workflow_issue_source, workflow_orchestrator_gates,
                         workflow_orchestrator_resume
workflow_orchestrator_gates -> workflow_inspector, workflow_evidence, workflow_policy,
                               workflow_runtime, workflow_supervision_policy
workflow_orchestrator_resume -> workflow_inspector
workflow_driver
workflow_repair   -> workflow_inspector, workflow_cas
workflow_cas
workflow_inspector
workflow_kernel
workflow_supervisor
```

`workflow_kernel.py` imports only the Python standard library. It must not
import lifecycle policy, CLI code, the inspector, or repair. `agent_workflow.py`
may use kernel primitives, but the kernel cannot call upward into policy.

`workflow_supervisor.py` is another standard-library-only leaf. It owns bounded
process execution but no lifecycle, retry, validation, or agent-selection
policy. #131 does not migrate legacy callers to it.

`workflow_driver.py` is also standard-library-only. It launches the reviewed
host as an external subprocess, obtains a fresh `plan-next` before every
automatic step, and enforces step/deadline/no-progress bounds. It has no Python
import edge to the host or orchestrator and cannot approve, recover, initialize,
merge, or select authority independently.

`workflow_cas.py` is a standard-library-only leaf extracted from the reviewed
#129 immutable publication path. It owns create-exclusive temporary writes,
fsync ordering, hard-link publication, immutable collision verification, and
concurrent idempotence. It does not own pointers, transactions, evidence
schemas, lifecycle policy, or projections.

`workflow_inspector.py` remains an independent read-only trusted component.
`workflow_repair.py` depends on the inspector and CAS leaf.
`workflow_authority_repair.py` verifies independently captured replacement
checkpoints through the public authority interface and shares its per-issue
lock; it does not import orchestration or legacy lifecycle policy.
`workflow_evidence.py` uses those same lower-level components for canonical
serialization, independent reads, and immutable publication. None imports the
legacy workflow CLI or the extracted legacy kernel. This keeps durable
inspection, repair, and evidence authoritative independently of lifecycle
policy.

`workflow_migration.py` is the only #133 compatibility adapter. It depends
downward on the inspector, CAS, evidence, and trusted kernel modules, but never
on the lifecycle CLI or repair. It consumes self-contained exact projection
bytes or a complete inspector checkpoint with explicit reachable selections.
It cannot mutate a pointer, projection, transaction, or lifecycle state.

`workflow_policy.py` is the read-only #134 policy evaluator. It verifies #132
bindings and #133 migration plans, computes a fixed dependency closure, and
applies bounded convergence rules to a self-contained canonical state. It
cannot publish or apply the result, read `.agent-workflow/**`, execute a
process, or import the legacy CLI, kernel, repair tool, supervisor, or CAS
publisher.

`workflow_work_type_policy.py` is the #116 policy surface. It verifies
explicit #132-bound intake, baseline, diff, artifact, review, and acceptance
documents; returns deterministic work-type routes and scope assessments; and
may invoke only policy-selected targeted checks directly through
`workflow_supervisor` without shell parsing.
Those process results are advisory because #131 explicitly cannot observe
escaped descendants. The module does not import the legacy lifecycle or #134,
publish evidence, acquire trust anchors, execute comprehensive validation,
authenticate actors, or transition authority. The replacement orchestrator owns
activation and composition of the separate #116 and #134 policy results.

`workflow_orchestrator_resume.py` owns the replacement core's strict candidate
schemas and exact pending-result reconstruction helpers. The provider may
validate Copilot transport and extract candidate bytes, but it cannot normalize
those bytes into a weaker phase contract. The orchestrator imports the resume
module; the resume module does not import the orchestrator, runtime, provider,
or host.

`workflow_orchestrator_gates.py` is a same-layer mixin that keeps exact
approval, rejection, and bounded test-rework composition out of the primary
orchestrator module. It is not independently callable and does not import the
orchestrator, authority, provider, host, CAS, or legacy lifecycle.

`workflow_plan_revision_policy.py` is the #125 read-only evaluator.
It validates native evidence-backed plan snapshots, exact diffs, dispositions,
and technical-review coverage, then derives an incremental or full review
requirement. It cannot publish evidence, mutate lifecycle state, authenticate
actors, preserve approval, or establish freshness. The replacement orchestrator
is the sole owner of composing #116, #125, and #134 results and activating their
effects.

`workflow_supervision_policy.py` is the deterministic owner of the
four configurable gates (`plan`, `tests`, `final`, and `pr-publication`). It
cannot authenticate humans, publish evidence, select authority, perform a
GitHub mutation, or configure mandatory-human activation, cutover, recovery,
repair, credential/provider, or irreversible authority-transfer operations.
`workflow_orchestrator.py` composes its decisions while
`workflow_authority.py` selects state and `workflow_runtime.py` retains the
external-operation boundary.

`workflow_issue_source.py` is the only production entry point for pre-genesis issue
bytes. It obtains them through the base-pinned runtime, validates the canonical
snapshot and GitHub identity, and delegates the one immutable raw-byte write to
`workflow_cas.py`. It imports no evidence, authority, policy, migration, repair, or
legacy lifecycle module and cannot create a generic CAS upload.

`workflow_runtime.py` also owns the only source-branch write primitive. It
accepts an exact clean repository observation and safe `refs/heads/<branch>`
target, derives the source commit from that observation, executes one fixed
non-force publication with a publication-only credential, and reconciles the
exact remote ref. It does not expose generic Git writes or mutate orchestration
authority.

`workflow_local_host.py` is the Phase 1 activation boundary. It uses isolated
Python startup, verifies its complete controller source set against a clean
base checkout before importing it, and installs the three fixed orchestrator
provider seams. `workflow_local_provider.py` executes agents through
`workflow_supervisor` in deterministic issue worktrees and records exact
execution facts. This is a trusted-local control-plane boundary, not hostile
same-UID, filesystem, credential, network, container, or VM isolation.

## Kernel boundary

The extracted kernel is intentionally small. It owns only:

- deterministic paths for the legacy worktree projection;
- canonical state/history bytes and SHA-256 helpers;
- JSON/history parsing and structural v4 envelope verification;
- encoded transaction snapshots;
- per-run advisory locking;
- atomic replacement of projection files;
- construction and publication of committed legacy v4 envelopes.

It does not own lifecycle states, allowed transitions, approvals, review
statuses, correction classes, validation rules, migration decisions, Git or
GitHub operations, subprocess execution, command registration, or output.

The module name describes its lower-level position for the existing legacy CLI;
it does not make worktree projections authoritative for #128 or #129. Durable
store authority remains defined and independently verified by those tools.

## Compatibility strategy

`agent_workflow.py` re-exports the imported kernel names that existing callers
and tests historically obtained from the monolithic module. Command names,
arguments, error type, projection paths, formats, serialized bytes, locking,
and transition behavior remain unchanged. There is no schema migration and no
second active implementation path.

The extraction order is:

1. Move pure integrity, path, locking, and atomic-write primitives intact.
2. Import those primitives into the legacy policy/orchestration module.
3. Replace conditional dispatch with one explicit named-handler registry.
4. Enforce the dependency graph, symbol ownership, and registry completeness in
   tests.

Future issues may separate policy and adapters further, but only by moving
cohesive behavior behind similarly explicit downward dependencies. They must
not duplicate the kernel or make policy authoritative for trusted repair.

## Non-goals

#130 does not change lifecycle semantics, evidence schemas, migration behavior,
recovery behavior, corrections, validation policy, or process supervision. It
does not repair or resume frozen issue #115, and it does not implement #131,
#132, #133, or #134.

#132 adds only canonical evidence objects and read-only derived/v4 views. It
does not integrate them into legacy lifecycle policy, perform migration, or
implement dependency-aware invalidation.

#133 adds only deterministic migration planning and immutable evidence
publication. It does not move migration into `agent_workflow.py`, invoke
`workflow_repair.py`, activate policy, implement #125, or modify frozen
#115 authority.

#134 adds only deterministic dependency invalidation and convergence
evaluation. The replacement orchestrator may compose its result, but the module
does not activate policy, mutate workflow authority, or change legacy
reopen/correction behavior by itself.

#116 adds complete work-type policy contracts. The replacement activates its
implementation route; the module does not alter legacy initialization or
completion, treat #134's invalidation DAG as a lifecycle, make targeted checks
authoritative, or claim latest-tip, revocation, stale-reuse prevention, temporal
freshness, or authenticated approval by itself.
