# Deterministic Workflow Migration

Issue #133 adds a compatibility adapter outside the workflow lifecycle. It
converts only enumerated, explicitly supplied legacy evidence into #132
immutable evidence. It never reads or writes `.agent-workflow/**`, changes a
pointer, persists a lifecycle transition, invokes repair, or activates policy.
Frozen issue #115 remains read-only.

## Commands

```bash
python3 scripts/workflow_migration.py plan --root ROOT --request request.json
python3 scripts/workflow_migration.py dry-run --root ROOT --plan plan.json
python3 scripts/workflow_migration.py apply --root ROOT --plan plan.json
python3 scripts/workflow_migration.py verify --root ROOT --plan plan.json
```

Every command emits inspector-canonical JSON. `plan` and `dry-run` are
read-only. `apply` accepts a plan, not live projection paths. `verify` requires
the planned binding to exist and verifies its complete #132 graph.

Outcomes are `resolved`, `missing`, `corrupt`, `unsupported`, `stale`, and
`ambiguous`, with exit codes 0, 3, 5, 4, 7, and 6 respectively. Unsupported or
incomplete legacy transactions are never guessed or resumed.

## Request schema

The top-level request is exact and intentionally small:

```json
{
  "format": "chess-echo-workflow-migration-request-v1",
  "source": {},
  "decision": {"type": "legacy-migration", "id": "issue-133"},
  "lineage": {
    "status": "original",
    "parent_binding": null,
    "subject": null
  }
}
```

`decision` follows the #132 decision contract. `lineage.status` is `original`,
`inherited`, or `replacement`. A correction must provide the exact parent
`evidence-binding` reference. `inherited` requires the derived semantic
manifest to equal the verified parent manifest; a null subject then reuses the
parent subject. Other null subjects use the migration source manifest.

### Projection and settled-adoption sources

The source shape for `projection-v1`, `projection-v2`, `projection-v3`,
`projection-v4`, and `settled-adoption` is:

```json
{
  "variant": "projection-v3",
  "issue": 133,
  "correction": null,
  "records": [
    {
      "logical_path": "state.json",
      "kind": "legacy-raw-evidence",
      "sha256": "<hash of exact bytes>",
      "size": 123,
      "bytes_base64": "<exact bytes>"
    }
  ],
  "selection": [
    {
      "logical_path": "artifact.bin",
      "path": "artifacts/artifact.bin",
      "entry_kind": "regular",
      "mode": "100644",
      "encoding": "raw"
    }
  ]
}
```

Records are the complete explicitly supplied source set. Logical paths are not
opened on disk. Versions 1-3 require canonical `state.json` and
`history.jsonl`. Committed v4 additionally requires a matching
`integrity.json` in `v4-committed` mode. Settled adoption requires the exact
`settled-legacy-adoption` envelope, typed reviewer/reason/timestamp/confirmation
metadata with confirmation `legacy_run_trusted`, and byte-identical embedded
state/history.
Any `*transaction.json` record is an unsupported incomplete transaction.

A legacy correction must include the complete frozen `correction` and
`parent_run` structures. Its parent-run issue, correction, state, validation
anchors, and exact state/history hashes must match the selected parent binding's
verified migration source. A same-issue binding alone is not sufficient.

Selection is explicit; unselected source bytes remain provenance only.
`entry_kind` is `regular` or `symlink`, with Git modes `100644`, `100755`, or
`120000` as appropriate. Encoding is:

- `raw`: the exact source object bytes become the evidence payload;
- `base64`: the source object remains unchanged and strict base64 decoding
  produces the payload; or
- `typed-base64`: the canonical typed wrapper remains unchanged and its
  strictly decoded `payload_base64` produces the payload.

Duplicate logical/evidence paths or conflicting selections are `ambiguous`.

### Durable-v4 source

Durable migration requires a complete #128 checkpoint and explicit reachable
objects:

```json
{
  "variant": "durable-v4",
  "checkpoint": {"format": "chess-echo-workflow-checkpoint-v1"},
  "selection": [
    {
      "logical_path": "legacy/plan.md",
      "path": "artifacts/plan.md",
      "entry_kind": "regular",
      "mode": "100644",
      "encoding": "typed-base64",
      "object": {
        "kind": "plan",
        "sha256": "<sha256>",
        "size": 123,
        "encoding": "typed-base64"
      }
    }
  ]
}
```

The adapter regenerates the exact checkpoint for its selected run and rejects
any difference as `stale`. Every selected reference must occur in
`authority.verified_objects`; historical objects are never inferred or chosen
by kind. The durable issue, run, family, correction, generation, sequence, and
event tip are copied exactly into the #132 identity. `apply` checks the
checkpoint before publication and again immediately before the binding.

### Already-canonical source

An already canonical request is a verification-only no-op:

```json
{
  "format": "chess-echo-workflow-migration-request-v1",
  "source": {
    "variant": "canonical-binding",
    "binding": {"kind": "evidence-binding", "sha256": "<sha256>", "size": 123}
  },
  "decision": null,
  "lineage": null
}
```

Planning verifies the complete binding graph. Applying returns the same
binding, publishes no objects, and performs no writes.

This no-op verifies #132 graph consistency; it does not attest that a local
caller used this migration tool to create the binding. The migration adapter
tag is graph-format metadata, not a signature. Consumers that require derived
legacy or durable authority must retain and verify the deterministic migration
plan, whose rebuild re-derives the exact identity and expected binding.

## Canonical plan

`plan` embeds the normalized request, exact source records, source manifest,
complete #132 publication, expected references, and (for durable v4) exact
checkpoint precondition. `plan_sha256` is SHA-256 over inspector-canonical
bytes before adding that field. No clock, environment, absolute path, or
mutable configuration contributes to a plan. Identical request and source
bytes therefore produce byte-identical plans.

The `chess-echo-migration-source-manifest-v1` object records `variant`, exact
`issue` and `correction`, explicit `migration_metadata`, and an
UTF-8-logical-path-sorted `objects` list. Each object row contains
`logical_path`, its typed CAS `object` reference, nullable selection
`encoding`, and nullable decoded `payload_sha256`/`payload_size`. Unselected
objects have all three payload fields null.
The manifest is non-empty, accepts only the six publishing variants above, and
applies the same source-object and structured-object size limits during both
publication and verification.

Legacy run identity is SHA-256-derived from the exact issue, correction,
`state.json` hash, and `history.jsonl` hash. The first 32 hex characters are
the run ID; a root run initially uses it as its family ID. The history byte
hash is the event tip and the final contiguous event is the sequence.
Corrections replace family identity with the verified exact parent's family.
Legacy lifecycle events must use their frozen resulting states. Plain root
projections begin with workflow initialization. Corrections additionally use a
frozen classification profile, a settled parent state, exhaustive inherited
and invalidated tokens, and an exact `CORRECTION_CREATED` bootstrap event.

Migration metadata is represented explicitly in the source manifest as
`not-recorded` (field absent), `none` (explicit null), or `recorded` (object).
These states are not conflated for either projections or durable v4 authority.

## Publication transaction

Before writing, `apply` validates the plan digest, rebuilds the entire plan,
checks every source hash/size and decoded payload, verifies parent and subject
references, validates the #132 publication, and checks durable preconditions.
It then:

1. publishes exact raw source objects through `workflow_cas.publish_immutable`;
2. publishes the canonical `migration-source-manifest`;
3. invokes #132 publication for payloads, semantic manifest, and deterministic
   provenance;
4. rechecks a durable checkpoint; and
5. publishes the #132 evidence binding last.

The evidence manifest remains semantic identity. Wrapper/source bytes, source
manifest, and deterministic provenance are separate. Migration captures use
`captured_at: null` only with `deterministic-migration`; no current time is
introduced.

An interruption can leave immutable objects that no binding reaches, but
cannot return success or leave a partial binding. Re-running converges, and
concurrent identical applies are idempotent. No pointer, generation, durable
index, legacy projection, transaction record, or lifecycle state is mutated.

The #132 limits remain authoritative: 64 MiB per payload, 8 MiB per structured
object, 10,000 entries, and 512 MiB total unique payload bytes.

## Conformance

`scripts/tests/fixtures/workflow-migration/` freezes v1-v4 and settled-adoption
inputs. `scripts/tests/test_workflow_migration.py` pins plan/binding hashes and
covers deterministic plans, exact source preservation, retries, concurrency,
interruption, stale checkpoints, malformed inputs, correction inheritance,
metadata states, and canonical no-op behavior.

Policy activation, incremental review (#125), dependency-aware invalidation
(#134), tiering, deletion, compaction, lifecycle recovery, and repair are
outside this tool.
