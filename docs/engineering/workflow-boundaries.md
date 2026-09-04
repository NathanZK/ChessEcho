# Workflow Module Boundaries

Issue #130 establishes a first explicit dependency boundary without redesigning
the workflow lifecycle or changing its stored formats.

## Current responsibility map

| Area | Owner after #130 |
|---|---|
| Legacy v4 projection paths and canonical serialization | `workflow_kernel.py` |
| Legacy v4 envelope and transaction-snapshot integrity checks | `workflow_kernel.py` |
| Per-run locking and atomic projection-file replacement | `workflow_kernel.py` |
| Bounded external process execution and process-group cleanup | `workflow_supervisor.py` |
| Immutable durable-CAS object publication | `workflow_cas.py` |
| Canonical evidence manifests, provenance, bindings, and derived views | `workflow_evidence.py` |
| Deterministic legacy/durable compatibility planning and immutable publication | `workflow_migration.py` |
| Dependency invalidation and convergence policy evaluation | `workflow_policy.py` |
| Inactive work-type intake, route, advisory targeted-check, and structural completion policy | `workflow_work_type_policy.py` |
| Inactive incremental reviewed-plan revision policy | `workflow_plan_revision_policy.py` |
| Lifecycle, approvals, reviews, corrections, validation, migration, and recovery policy | `agent_workflow.py` |
| Git, GitHub, process execution, command parsing, and human-facing output | `agent_workflow.py` |
| Durable-store inspection and checkpoints | `workflow_inspector.py` |
| Durable-store repair bundles and recovery | `workflow_repair.py` |

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

`workflow_cas.py` is a standard-library-only leaf extracted from the reviewed
#129 immutable publication path. It owns create-exclusive temporary writes,
fsync ordering, hard-link publication, immutable collision verification, and
concurrent idempotence. It does not own pointers, transactions, evidence
schemas, lifecycle policy, or projections.

`workflow_inspector.py` remains an independent read-only trusted component.
`workflow_repair.py` depends on the inspector and CAS leaf.
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

`workflow_policy.py` is the inactive #134 policy evaluator. It verifies #132
bindings and #133 migration plans, computes a fixed dependency closure, and
applies bounded convergence rules to a self-contained canonical state. It
cannot publish or apply the result, read `.agent-workflow/**`, execute a
process, or import the legacy CLI, kernel, repair tool, supervisor, or CAS
publisher.

`workflow_work_type_policy.py` is the inactive #116 policy surface. It verifies
explicit #132-bound intake, baseline, diff, artifact, review, and acceptance
documents; returns deterministic work-type routes and scope assessments; and
may invoke only policy-selected targeted checks directly through
`workflow_supervisor` without shell parsing.
Those process results are advisory because #131 explicitly cannot observe
escaped descendants. The module does not import the legacy lifecycle or #134,
publish evidence, acquire trust anchors, execute comprehensive validation,
authenticate actors, or transition authority. Future #144 orchestration owns
activation and composition of the separate #116 and #134 policy results.

`workflow_plan_revision_policy.py` is the inactive #125 read-only evaluator.
It validates native evidence-backed plan snapshots, exact diffs, dispositions,
and technical-review coverage, then derives an incremental or full review
requirement. It cannot publish evidence, mutate lifecycle state, authenticate
actors, preserve approval, or establish freshness. Future #144 is the sole
owner of composing #116, #125, and #134 results and activating their effects.

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
evaluation. It does not activate that policy, mutate workflow authority, or
change legacy reopen/correction behavior.

#116 adds complete but inactive work-type policy contracts. It does not alter
legacy initialization or completion, treat #134's invalidation DAG as a
lifecycle, make targeted checks authoritative, or claim latest-tip,
revocation, replay prevention, temporal freshness, or authenticated approval.
