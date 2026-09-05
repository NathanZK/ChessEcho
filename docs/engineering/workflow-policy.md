# Workflow Invalidation and Convergence Policy

Issue #134 adds deterministic policy construction and evaluation outside the
workflow lifecycle. `scripts/workflow_policy.py` verifies canonical evidence,
constructs the restricted policy genesis, computes forward binding and minimal
invalidation, and enforces convergence limits. It is read-only: it returns
candidate state bytes but never publishes an object, selects authority, changes
a pointer, writes a projection, executes a command, or reads
`.agent-workflow/**`.

## Command

```text
python3 scripts/workflow_policy.py initialize --root ROOT \
  --request INITIALIZE.json --implementation-a-binding SHA256

python3 scripts/workflow_policy.py evaluate --root ROOT --request REQUEST.json \
  --trusted-state-binding SHA256
```

The equivalent package command is:

```text
python3 -m scripts.workflow_policy initialize --root ROOT \
  --request INITIALIZE.json --implementation-a-binding SHA256

python3 -m scripts.workflow_policy evaluate --root ROOT --request REQUEST.json \
  --trusted-state-binding SHA256
```

Both forms emit one inspector-canonical JSON document. Failure outcomes and exit
codes are `missing` (3), `unsupported` (4), `corrupt` (5), `ambiguous` (6), and
`stale` (7). A resolved evaluation, including an explicit escalation decision,
exits zero.
`--root` is untrusted input: invalid path values and roots that cannot be
resolved or read return typed policy failures rather than filesystem
exceptions.

## Fixed dependency DAG

The v1 graph is code-defined and cannot be supplied or weakened by a caller:

| Node | Exact direct dependencies |
|---|---|
| `plan-approval` | none |
| `implementation-a` | none |
| `test-manifest` | `plan-approval`, `implementation-a` |
| `test-approval` | `test-manifest` |
| `implementation-submission` | `plan-approval`, `implementation-a`, `test-approval` |
| `validation` | `implementation-submission` |
| `final-review` | `validation` |
| `pr-metadata` | `final-review` |
| `pr-approval` | `final-review`, `pr-metadata` |

Every active and historical node names an exact #132 `evidence-binding`.
Evidence decision type must equal the node name. Every active node additionally
records the exact binding for every direct dependency. Preservation requires
byte-identical references. Semantic similarity, matching prose, or an agent
classification cannot preserve a node.

Generation zero is policy-owned and contains exactly `implementation-a`.
`plan-approval` and every other node must be added later through `bind`; a
self-consistent authority chain with an empty, duplicate, or expanded genesis
is invalid. `implementation-a` cannot be added through `bind`.

When a root changes, the evaluator invalidates that root and every reachable
active descendant. Ancestors and unrelated siblings remain active. Invalidated
bindings stay in immutable CAS and in the historical table; only the derived
next active table clears them.

## Initialization

`initialize(root, issue, family_run_id, implementation_a_binding_record)`
verifies the complete #132 evidence graph, exact issue and family, migration
metadata, and decision type `implementation-a`. It returns the canonical
generation-zero state with only that binding active and historical, zero
budgets, a null transition and tip, and convergence episode 0 in `UNKNOWN` with
no evidence.

The CLI request has exactly:

```json
{
  "format": "chess-echo-workflow-policy-initialize-request-v1",
  "issue": 134,
  "family_run_id": "0123456789abcdef0123456789abcdef",
  "implementation_a": {
    "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
    "migration_plan": null
  },
  "request_sha256": "<64 lowercase hex>"
}
```

`request_sha256` is the inspector-canonical SHA-256 of the request before that
field is added. `--implementation-a-binding` is an independently designated
digest and must equal `implementation_a.binding.sha256`. Initialization returns
only the candidate state document; it does not publish or activate it.

## Evaluation request and state

`chess-echo-workflow-policy-request-v1` has exactly:

```json
{
  "format": "chess-echo-workflow-policy-request-v1",
  "issue": 134,
  "family_run_id": "0123456789abcdef0123456789abcdef",
  "state": {},
  "expected_state_sha256": "<64 lowercase hex>",
  "bindings": [],
  "authority_chain": [],
  "operation": {}
}
```

`bindings` must contain exactly one input for every active, historical,
convergence, parent, bound-node, bound-dependency, replacement,
authority-chain, or operation evidence reference. Recorded transition inputs,
including historical binds and correction authorizations, remain required for
deterministic replay:

```json
{
  "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
  "migration_plan": null
}
```

The evaluator verifies every complete #132 graph. A binding tagged with the
#133 migration adapter requires its complete canonical migration plan in place
of null. The plan is rebuilt and verified, and its resulting binding must equal
the supplied reference. A plan on a non-migrated binding is ambiguous.

`authority_chain` is a nonempty, genesis-to-tip list of exact
`{"binding": ..., "state": ...}` pairs. Each binding has decision
`policy-state:generation-N`, contains exactly one regular
`workflow-policy/state.json` manifest entry whose payload is the canonical
state, and links to the prior state binding through #132 replacement lineage.
The evaluator replays every recorded transition from the immutable genesis and
requires the supplied current state to equal the chain tip. A newly calculated
state is not authoritative until an external, separately authorized publisher
binds it and extends this chain.

The complete supplied current state must already be canonical policy output;
the evaluator never normalizes it and continues. Its `state_sha256` and the
request's `expected_state_sha256` must both digest that exact canonical state.
Transitions are derived only from this canonical, authority-chain-bound input.

`--trusted-state-binding` is the independently obtained expected current tip.
The request cannot select or replace that trust anchor. A self-consistent
counter reset, a republished reset child, or an entirely new forged genesis
fails because its chain tip does not match the trusted binding.

`chess-echo-workflow-policy-state-v1` has exactly:

```json
{
  "format": "chess-echo-workflow-policy-state-v1",
  "issue": 134,
  "family_run_id": "0123456789abcdef0123456789abcdef",
  "generation": 0,
  "transition_tip": null,
  "transition": null,
  "active": [
    {
      "node": "implementation-a",
      "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
      "dependencies": []
    }
  ],
  "history": [
    {
      "node": "implementation-a",
      "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
      "status": "active",
      "transition_id": null
    }
  ],
  "budgets": {
    "reopens": 0,
    "retries": {
      "UNKNOWN": 0,
      "CAUSE_ESTABLISHED": 0,
      "FIX_IDENTIFIED": 0,
      "FIX_APPLIED": 0,
      "TARGETED_VERIFIED": 0,
      "CLOSED": 0
    }
  },
  "convergence": {"episode": 0, "state": "UNKNOWN", "evidence": []},
  "state_sha256": "<digest>"
}
```

`state_sha256` covers inspector-canonical bytes before that field is added.
`expected_state_sha256`, the embedded digest, active/history agreement, all
dependency references, and all binding graphs must verify before evaluation.
Active nodes use fixed DAG order, and history uses node then binding-hash order;
noncanonical array ordering is rejected rather than normalized during a
no-change escalation.

Generation zero has a null transition and zero budgets. Every later state
records the exact normalized operation that produced it. Its binding must use
replacement lineage to the prior state binding, its transition hash must
recompute from the prior state, and replay must reproduce the complete next
state. Budget counters are therefore derived from an immutable transition
chain; changing and rehashing a counter, or publishing a reset child state,
fails closed.

## Operations

### Bind

```json
{
  "type": "bind",
  "node": "test-manifest",
  "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
  "dependencies": [
    {
      "node": "plan-approval",
      "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123}
    },
    {
      "node": "implementation-a",
      "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123}
    }
  ],
  "reason": "Activate reviewed test manifest"
}
```

`bind` activates exactly one inactive fixed-DAG node other than
`implementation-a`. Its dependency list must name every direct dependency in
the code-defined order, each dependency must already be active, and each
reference must byte-equal that active binding. The new binding's decision type
must equal the node and the same reference cannot already occur for that node
in history.

A successful bind increments generation, changes the transition tip and active
table digest, and adds one active/history row with the transition ID. It returns
`changed_roots` containing only the node, no invalidation, and the complete
prior active table in `preserved`. Existing active/history rows, budgets, and
the complete convergence object are unchanged. Bind never replaces an active
node and never escalates. Any convergence evidence prepared against the prior
tip or active table must be re-issued.

### Reopen

```json
{
  "type": "reopen",
  "target": "plan",
  "reason": "Material design change",
  "replacements": [
    {
      "node": "plan-approval",
      "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123}
    }
  ]
}
```

`plan` replaces `plan-approval`; `tests` replaces `test-manifest`. The exact
replacement must differ from the active binding and carry the matching
evidence decision type.

### Correction

```json
{
  "type": "correction",
  "classification": "implementation-only",
  "reason": "Correct the submitted implementation",
  "parent_binding": {
    "kind": "evidence-binding",
    "sha256": "...",
    "size": 123
  },
  "child_identity": {
    "issue": 134,
    "run_id": "fedcba9876543210fedcba9876543210",
    "family_run_id": "0123456789abcdef0123456789abcdef",
    "correction": 1,
    "run_generation": 0,
    "sequence": 1,
    "event_tip": "<64 lowercase hex>"
  },
  "authorization": {
    "binding": {
      "kind": "evidence-binding",
      "sha256": "...",
      "size": 123
    },
    "document": {
      "format": "chess-echo-correction-authorization-v1",
      "issue": 134,
      "family_run_id": "0123456789abcdef0123456789abcdef",
      "parent_binding": {},
      "child_identity": {},
      "classification": "implementation-only",
      "roots": ["implementation-submission"]
    }
  },
  "replacements": []
}
```

The parent must be the exact active `pr-approval` binding. Every replacement
must have #132 `replacement` lineage to that exact parent, carry the declared
correction child identity, and use the decision type for its root. Multiple
replacement roots must share the complete child identity. Original-root,
sibling, wrong-parent, and mixed-child evidence fail closed. Classifications
map to fixed changed roots:

Correction evaluation additionally requires
`--trusted-correction-binding SHA256`. That out-of-band reference must identify
the operation's immutable `correction-authorization` binding. Its sole manifest
entry contains the canonical authorization document, its #132 identity is the
authorized child, and its replacement lineage names the exact parent. A caller
cannot substitute a sibling by changing both the replacement and the
self-declared child identity.

| #117 classification | Changed roots |
|---|---|
| `metadata-only` | `pr-metadata` |
| `implementation-only` | `implementation-submission` |
| `test-contract` | `test-manifest` |
| `architecture` | `plan-approval`, `implementation-a` |

This preserves #117's parent/child intent without importing or changing the
legacy correction implementation.

### Convergence

```json
{
  "type": "convergence",
  "from": "FIX_APPLIED",
  "to": "TARGETED_VERIFIED",
  "evidence": {"kind": "evidence-binding", "sha256": "...", "size": 123},
  "retry": false
}
```

Forward movement is exactly:

```text
UNKNOWN
  -> CAUSE_ESTABLISHED
  -> FIX_IDENTIFIED
  -> FIX_APPLIED
  -> TARGETED_VERIFIED
  -> CLOSED
```

Forward evidence decision types are respectively `cause-establishment`,
`fix-identification`, `fix-application`, `targeted-verification`, and `closure`.
Skipping, reversal, repeating a forward transition, or mismatched evidence
fails closed. A retry stays in the same non-closed state and records verified
evidence without advancing.

Every convergence decision ID also binds the source episode, transition tip,
and SHA-256 of the complete active dependency table:

```text
episode-<N>-tip-<TIP|genesis>-active-<SHA256>
```

Convergence history retains that context across reopen episodes. Evidence from
an earlier episode, tip, or active dependency set cannot be replayed after
invalidation.

## Limits and escalation

Root-changing reopen and correction evaluations share a maximum of two
invalidation cycles per policy lineage. Each convergence stage permits three
retries. A forward transition resets the destination stage's retry counter.

The next attempt after a limit is a resolved, no-change result:

- `decomposition-required` for invalidation-cycle exhaustion; or
- `human-recovery-required` for retry exhaustion.

Escalation does not invalidate evidence, increment generation, synthesize
approval, or weaken a gate. Invalid, stale, missing, corrupt, or ambiguous input
remains a typed failure rather than escalation.

## Result

`chess-echo-workflow-policy-result-v1` records the outcome, input state digest,
deterministic transition ID, normalized operation, changed roots, invalidated
and preserved bindings, complete next state, escalation or null, and
`result_sha256`. The result digest covers canonical bytes before its digest
field is added.

The result, like an initialized state, is derived review evidence only. It is
not an authorization, durable policy state, lifecycle transition,
compare-and-swap, approval, or mutation request.

## Trust and mutation boundary

The module depends downward only on `workflow_inspector.py`,
`workflow_evidence.py`, and `workflow_migration.py`. It does not import the
legacy workflow CLI, kernel, repair tool, supervisor, or CAS publisher.

It performs no filesystem mutation, subprocess execution, Git/GitHub access,
network access, locking, or agent invocation. Existing and frozen runs,
including #115, remain byte-identical.

All node, target, correction-classification, and convergence identifiers are
type-checked before lookup. Malformed JSON values produce canonical typed
failures rather than Python exceptions. This includes noncanonical numbers or
values discovered while comparing current or recorded authoritative state.

## Rollout and non-goals

V1 ships inactive. New canonical evidence and exact migrated evidence can be
initialized or evaluated, but no output is applied automatically. A separate
reviewed issue must define durable policy-state publication, expected-tip CAS,
authorization, recovery, and lifecycle integration.

#134 does not implement risk tiers (#127), work-type triage (#116), incremental
review (#125), evidence compaction (#135), storage redesign, automatic
approvals, policy activation, or `agent_workflow.py` integration.
