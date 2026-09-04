# Workflow Repair

`scripts/workflow_repair.py` is a standalone, bounded repair tool for the v4
durable workflow authority. It depends only on `workflow_inspector.py` and the
`workflow_cas.py` publication leaf; it never imports the lifecycle
implementation, invokes workflow commands, evaluates policy, or writes worktree
projections. See the canonical [architecture and status
map](agent-workflow.md#architecture) for how this independently callable tool
relates to the active legacy lifecycle.

## Commands and trust boundary

```bash
python3 scripts/workflow_repair.py prepare ISSUE --root ROOT \
  --checkpoint CHECKPOINT --request REQUEST > bundle.json
python3 scripts/workflow_repair.py dry-run --root ROOT --bundle bundle.json
python3 scripts/workflow_repair.py apply --root ROOT --bundle bundle.json
python3 scripts/workflow_repair.py recover ISSUE --root ROOT
```

`prepare` and `dry-run` are read-only. They contain no filesystem persistence
calls; standard output is their only output. `apply` and `recover` are the only
mutating commands. They write only fixed locations below the Git common-dir
store:

```text
chess-echo-agent-workflow/
  issues/<issue>/index-integrity.json
  issues/<issue>/repair.lock
  issues/<issue>/repair-journal.json
  objects/sha256/<first-two>/<remaining>
  repair-audits/sha256/<first-two>/<remaining>
```

They never write `.agent-workflow/**` or delete an immutable object.

## Canonical documents

All documents use #128
`utf8-json-sort-keys-compact-ascii-v1`: exact integers, sorted keys, compact
ASCII JSON, UTF-8, and one newline for files/standard output. A digest is
SHA-256 of canonical bytes before its digest field is added, without a trailing
newline. Bundles and results contain no generated timestamps or filesystem
paths.

The public input checkpoint is the complete
`chess-echo-workflow-checkpoint-v1` document emitted by #128. Its digest,
format, canonicalization, executing inspector identity, and exact nested schema
must verify. Missing, extra, or mistyped fields are rejected. The schema admits
the optional authority `correction` field and both #128 validation observation
forms: `{"status":"not-recorded"}` or a recorded status with checks and an
optional attempt ID.

### Request

`chess-echo-workflow-repair-request-v1` has exactly:

```json
{
  "confirmation": "REPAIR ISSUE 42 POINTER BINDING",
  "format": "chess-echo-workflow-repair-request-v1",
  "issue": 42,
  "objects": [],
  "operation": {"binding": {}, "type": "pointer-binding"},
  "operator": "operator identity",
  "reason": "nonempty reason",
  "selector": {"current": true}
}
```

The selector is exactly one of `{"current":true}`, `{"run_id":"<32 hex>"}`
or `{"correction":N}`. `objects` contains complete immutable object records:

```json
{"bytes_base64":"...", "kind":"run-envelope", "sha256":"<64 hex>", "size":123}
```

Bytes, size, kind, canonical structured encoding, and hash are verified.
Duplicate, conflicting, unsupported, or target-unreachable objects are denied.

Supported operations are:

* **`pointer-binding`** — supplies the exact existing `run_update` binding for
  the selected run and requires `REPAIR ISSUE <issue> POINTER BINDING`. The
  source must currently resolve to the supplied checkpoint. The tool finds and
  verifies that binding in the SOURCE index chain without consulting bundled
  objects, then requires byte-for-byte-equivalent structured content, including
  its envelope and all identity/tip counters. It constructs, rather than
  accepts, the pointer and issue index. It adds exactly one index generation
  whose `previous_index` is the source index, preserves every table except
  `run_update`, republishes only that existing binding, and leaves the pointer
  selection unchanged. A supplied binding cannot introduce a new envelope,
  state, history, event, or other authority object.
* **`integrity-reseal`** — supplies a complete target pointer byte record and
  requires `RESEAL ISSUE <issue> INTEGRITY`. It may additionally declare the
  exact current `{"status":"missing|corrupt","code":"..."}` inspection failure.
  A declared failure must match inspection; otherwise the checkpoint must
  exactly match the resolved source. The target graph must validate in the
  in-memory overlay. Issue/run/family/correction identity, lifecycle state,
  generation, sequence, event tip, repository facts, and observations must
  remain unchanged. The v1 reseal is deliberately limited to reconstructing the
  selected latest envelope and its index/pointer references. State, history,
  and event references cannot change. The new envelope must equal the original
  checkpoint-addressed envelope after removing this exact provenance member:

  ```json
  {
    "integrity_reseal": {
      "checkpoint_sha256": "<source checkpoint digest>",
      "source_envelope_sha256": "<source envelope digest>"
    }
  }
  ```

  The source index chain must remain readable, and normalizing the new envelope
  reference back to the checkpoint reference must reproduce the source index
  and pointer exactly. When the source envelope is declared missing or corrupt,
  the tool removes that exact provenance member from the supplied replacement,
  hashes the recovered original bytes against the source-index reference, and
  inspects the original source pointer through an in-memory overlay. The
  reconstructed checkpoint must equal the supplied checkpoint exactly; its
  authority fields are never trusted as reconstruction inputs. Deeper
  reconstruction is unsupported rather than guessed. This operation is not a
  lifecycle or evidence-policy change. A missing source pointer itself is
  unsupported because there are no exact source pointer bytes to compare.

### Bundle

`chess-echo-workflow-repair-bundle-v1` contains:

* issue, selector, allowlisted operation type, and nonempty authorization;
* the complete validated public #128 checkpoint;
* an exact source pointer byte record and source checkpoint digest;
* source index generation/reference, run ID/generation, sequence, event tip,
  and all repository facts;
* an optional exact source inspection failure;
* the exact target pointer byte record;
* every new immutable object's kind, bytes, size, and hash;
* the expected complete target #128 checkpoint and authority;
* a deterministic mutation plan; and
* `bundle_sha256`.

There are no destination paths, JSON patches, commands, or free-form mutation
instructions. `dry-run` and `apply` return byte-for-byte equal `plan` values.
`dry-run` reads and byte-classifies the live pointer without taking the lock or
writing anything. A source pointer validates source and target preconditions and
reports `source-applicable`; an exact target validates postcommit authority and
reports `target-already-applied`. Other readable pointers are stale, while
missing/unreadable or unsafe pointers retain their normal stale/conflict type.
The confirmation phrase is only an accident-prevention guard. Authority comes
from the canonical, hash-verified bundle plus exact source and target
preconditions; the phrase is not an approval, signature, or identity proof.

`chess-echo-workflow-repair-result-v1` reports a typed outcome, bundle digest,
plan, expected target checkpoint/authority, and audit reference. It also has a
deterministic `result_sha256`.

A canonical, hash-valid bundle presented to `apply` is an attempted repair.
Successful attempts and typed stale, denied, invalid, or conflict outcomes
publish deterministic immutable audit receipts linked to that bundle and its
source and target pointer hashes. Input that cannot first validate as a
canonical bundle is not accepted as an attempted repair and cannot provide a
trustworthy audit identity. An interrupted transaction is represented by its
journal until recovery completes it.

## Atomic application

Application takes the per-issue `repair.lock` advisory lock and first finishes
any valid existing journal. Every trusted #129 writer must take this same lock.
The old lifecycle writer and any other non-cooperating writer are prohibited
during repair and must be quiescent. Stale source pointer, checkpoint, authority
counters, repository HEAD/tree/base/status facts, or target graph fails before
journal or object publication. With no journal, the live pointer is classified
before root-dependent bundle validation. An exact already-committed target is
validated as a postcommit state, and any authority/root failure is an audited
conflict.

Journal loading is a fixed-path descriptor read using
`O_RDONLY|O_NONBLOCK|O_NOFOLLOW` where available. The descriptor must be regular, all
bytes are read through it, and a final `lstat` must still identify the same
regular device/inode. Symlinks, replacements, nonregular files, and unreadable
journals fail closed.

The transaction is:

1. Publish one canonical `chess-echo-workflow-repair-journal-v1` embedding the
   complete bundle and fsync its directory.
2. Publish bundled immutable objects. Each object is written and fsynced to a
   same-directory hidden temporary, then hard-linked to its final name without
   overwrite, followed by directory fsync. An existing destination is opened
   read-only with no-follow protection where available, required by `fstat` to
   be regular, read and hashed through that descriptor, then accepted only if a
   final `lstat` still names the same device/inode. Symlinks, replacements,
   nonregular files, and collisions fail closed.
3. Recheck the source checkpoint/failure and target immediately before commit.
   For a declared missing-object failure, publication may make the old graph
   inspectable; it must then equal the supplied source checkpoint.
4. Run the `before-pointer-publication` seam, write and fsync a same-directory
   pointer temporary, then perform the final byte-exact source-pointer read and
   comparison immediately followed by `os.replace`, with no callback,
   validation, or filesystem operation between them. The replacement is the
   sole authority commit point; its directory is then fsynced.
5. Run #128 inspection from the published pointer and require the exact
   expected target checkpoint.
6. Publish a deterministic immutable
   `chess-echo-workflow-repair-audit-v1` receipt by content hash.
7. Remove the journal and fsync its directory.

Old pointers remain represented by their immutable index predecessors and no
old immutable object is removed. Interrupted object temporaries are never final
object names and therefore cannot expose partial objects.

## Recovery and interruptions

Recovery validates the journal and its embedded bundle before acting. A
malformed journal remains in place and fails closed. Pointer classification is
byte-exact:

| Current pointer | Recovery |
|---|---|
| exact source | Resume object publication, recheck, and commit |
| exact target | Verify target, publish/verify receipt, remove journal |
| missing, unreadable, or anything else | Pointer conflict; retain journal and make no change |

Recognized test seam phases surround journal publication, every object
publication, pointer publication, receipt publication, and journal removal:

| Boundary | Durable state and recovery |
|---|---|
| before journal | No transaction; `recover` reports `clean` |
| after journal | Source; resume |
| before/after object | Source; exact objects are idempotently reused |
| before pointer | Source; prepare temporary, final exact recheck, then replace |
| after pointer | Target; verify and finalize |
| before/after receipt | Target; publish or accept exact receipt |
| before journal removal | Target; verify receipt and remove |
| after journal removal | Complete; `recover` reports `clean` |

Reapplying an already-target bundle produces the same deterministic applied
result and receipt. Cooperating repair callers serialize on `repair.lock`. The
immediate byte-exact source-pointer recheck before `os.replace` and
postpublication verification detect drift, but portable POSIX replacement is
not a lock-free compare-and-swap against an actor that bypasses the lock.

## Outcomes and non-goals

Successful outcomes are `dry-run`, `applied`, and recovery `clean`. Failures
are typed as `stale`, `denied`, `invalid`, or `conflict` and use distinct exit
codes. A stale validated attempt leaves the authority pointer, immutable
authority objects, and projections unchanged. Once pointer publication commits,
any postpublication inspection or checkpoint mismatch is a `conflict`, not
`stale`, and the journal is retained for diagnosis/recovery.

The tool does not synthesize approvals, transition lifecycle state, run hooks,
repair projections, migrate legacy data, accept arbitrary setters/patches,
overwrite objects, resolve conflicts heuristically, or treat a confirmation
phrase as authorization.

## Frozen issue #115 boundary

Issue #115 is permanently frozen historical evidence and is not a repair target.
The repair CLI's generic issue selector is not authorization to prepare, apply,
recover, or otherwise mutate #115. Historical source and postmortem material may
inform architecture documentation, but no operational workflow should depend on
or resume its runtime state.

Existing optional integration coverage, when the historical durable pointer is
available, is limited to asserting read-only byte preservation. It does not
authorize an operator to construct or apply a repair.
