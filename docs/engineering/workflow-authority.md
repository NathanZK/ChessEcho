# Workflow Authority

`scripts/workflow_authority.py` is the inactive expected-tip selector for the
future orchestration path tracked by issue #144. It selects one verified
`orchestration-state` evidence binding as current for an issue. It does not
interpret lifecycle policy, construct policy state, invoke agents or processes,
access Git or GitHub, publish evidence, migrate or repair legacy state, retry an
operation, or perform convergence.

The legacy workflow remains active. Publishing an evidence binding does not make
it current; only a successful authority pointer commit selects it. Migrated-v4
cutover is not activated by this slice.

## Commands

```bash
python3 scripts/workflow_authority.py status ISSUE --root REPOSITORY
python3 scripts/workflow_authority.py checkpoint ISSUE --root REPOSITORY
python3 scripts/workflow_authority.py prepare ISSUE --root REPOSITORY \
  --candidate-binding BINDING_SHA256 > bundle.json
python3 scripts/workflow_authority.py commit --root REPOSITORY \
  --bundle bundle.json
```

The same commands support package execution with
`python3 -m scripts.workflow_authority`. Output is canonical JSON. `status`,
`checkpoint`, and `prepare` are read-only; `commit` is the only mutating API.

## Pointer and lock

Each issue has exactly one mutable pointer:

```text
<git-common-dir>/chess-echo-agent-workflow/orchestration/issues/<issue>/pointer.json
```

Its schema is exact:

```json
{
  "format": "chess-echo-orchestration-pointer-v1",
  "issue": 144,
  "generation": 0,
  "authority": {
    "kind": "evidence-binding",
    "sha256": "64-lowercase-hex",
    "size": 1
  }
}
```

The pointer is compact, ASCII JSON with sorted keys, no trailing newline, and a
4 KiB maximum. Its SHA-256 digest is the expected tip. It contains no timestamp,
PID, host, path, or environment value.

Cooperating writers serialize on the sibling `authority.lock`. The lock is
advisory and per issue. It does not claim distributed locking or protect against
an actor that ignores the protocol.

## Selected evidence

The selected binding has decision
`orchestration-state:generation-<N>`, exactly one regular `100644`
`workflow-orchestration/state.json` manifest entry, and no migration adapter.
The canonical state has a 2 MiB maximum and exact schema:

```json
{
  "format": "chess-echo-orchestration-state-v1",
  "issue": 144,
  "family_run_id": "32-lowercase-hex",
  "generation": 0,
  "previous_authority": null,
  "previous_pointer_sha256": null,
  "route": "implementation",
  "phase": "PLANNING",
  "triage_binding": {
    "kind": "evidence-binding",
    "sha256": "64-lowercase-hex",
    "size": 1
  },
  "policy_state_binding": {
    "kind": "evidence-binding",
    "sha256": "64-lowercase-hex",
    "size": 1
  },
  "candidates": [],
  "pending": null,
  "cutover": {
    "mode": "new-run",
    "legacy_checkpoint_sha256": null,
    "migration_binding": null
  },
  "transition": {
    "type": "initialize",
    "request_binding": null,
    "result_binding": null,
    "authorization_binding": null,
    "repository_observation_binding": null
  },
  "state_sha256": "64-lowercase-hex"
}
```

`state_sha256` covers the canonical state without that field. The binding
identity uses the state's issue and family, `run_generation=generation`,
`sequence=generation+1`, and `correction=null`. Its run ID is the first 32
characters of
`SHA256("orchestration-state-v1\0" || canonical-state-bytes)`, and its event tip
is `SHA256("orchestration-tip-v1\0" || canonical-state-bytes)`.

Only `route=implementation` is accepted in this slice. Any other route returns
`unsupported-route-not-activated`. Authority verifies the state, state-binding,
and policy-state-binding issue and family. The policy binding decision must be
`policy-state`.

At genesis, the state binding subject equals `policy_state_binding`, its lineage
is original, and both previous fields are null. Later generations require:

- generation to increase by exactly one;
- `previous_authority`, binding subject, and replacement lineage parent to be
  byte-identical; and
- `previous_pointer_sha256` to digest the reconstructed prior pointer.

Candidates use exact `slot`/`binding` rows sorted uniquely by slot. Related
bindings are fully verified, share the issue and family, use a nonfuture
generation with its matching sequence, and have original, non-migrated lineage.
A pending request and a transition request are subject-bound to the previous
authority; transition outputs are subject-bound to their request. A human
pending request must select a `human-challenge` decision; every process pending
request must select an `execution-request` decision. Authority validates these
identity links, not challenge, command, phase-transition, or policy semantics.

Every evidence graph is checked through public `workflow_evidence.verify`, and
binding identity, decision, subject, lineage, and manifest facts come from
public `workflow_evidence.project`. Payload bytes are read through the
read-only `workflow_inspector.AuthorityReader`.

## Inspection and checkpoints

`status` verifies the current pointer and the complete predecessor chain, then
returns `chess-echo-orchestration-inspection-v1`. `checkpoint` returns the same
fields as `chess-echo-orchestration-checkpoint-v1` plus
`checkpoint_sha256`, computed over the document without that field:

```json
{
  "format": "chess-echo-orchestration-checkpoint-v1",
  "canonicalization": "utf8-json-sort-keys-compact-ascii-v1",
  "outcome": {"status": "resolved", "code": "verified"},
  "issue": 144,
  "pointer_sha256": "64-lowercase-hex",
  "pointer": {},
  "authority": {},
  "state_sha256": "64-lowercase-hex",
  "chain": [
    {
      "generation": 0,
      "binding": {},
      "state_sha256": "64-lowercase-hex"
    }
  ],
  "chain_length": 1,
  "checkpoint_sha256": "64-lowercase-hex"
}
```

The chain is genesis-to-tip ordered and limited to 10,000 generations. A
checkpoint is limited to 4 MiB and is never truncated. A missing pointer is
`missing/orchestration-pointer-missing`; there is no successful empty
checkpoint.

## Prepare and commit

`prepare` verifies the complete candidate chain against the exact current
pointer and returns a canonical
`chess-echo-orchestration-authority-bundle-v1`, limited to 16 KiB. `source` is
null at genesis; otherwise `source` and `target` are exact byte records with
`bytes_base64`, `sha256`, and `size`. The bundle also contains
`candidate_binding`, `operation_id`, and `bundle_sha256`.

`operation_id` is SHA-256 over canonical
`{issue,source_sha256,target_sha256,candidate_binding}`.
`bundle_sha256` covers the complete bundle without that field. `commit`
revalidates every field and the complete candidate evidence before taking the
lock.

Genesis publishes the pointer create-exclusively through
`workflow_cas.publish_immutable`. An exact target is an idempotent success; any
different pointer that appeared after preparation is a conflict.

Later commits:

1. classify the live pointer as exact source, exact target, or conflict;
2. write and fsync a unique same-directory
   `.pointer.json.authority-<pid>-<thread>-<operation>` temporary;
3. re-read the exact source immediately before `os.replace`;
4. atomically replace the pointer; and
5. fsync the pointer directory.

An exact target is an idempotent restart success. A stale, missing, malformed,
symlink, nonregular, or third pointer never advances authority. At commit start,
up to 1,024 stale regular authority temporaries are removed under the issue lock
and the directory is synchronized. A nonregular or excess temporary fails
closed. Normal exits remove their own temporary.

A crash before replacement leaves the source authoritative. A crash after
replacement leaves the target authoritative. Immutable candidate evidence can
remain unreachable without affecting current authority. There is no journal,
rollback, repair inference, or hidden recovery state.

Stable failures include `orchestration-pointer-missing`,
`unsupported-route-not-activated`, `pointer-not-regular`,
`pointer-source-mismatch`, `pointer-target-conflict`,
`authority-chain-limit`, `authority-binding-invalid`,
`authority-lineage-stale`, and `authority-bundle-too-large`.
