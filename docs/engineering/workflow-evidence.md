# Canonical Workflow Evidence

Issue #132 defines immutable semantic evidence independently of the legacy
workflow orchestrator. `scripts/workflow_evidence.py` uses the existing durable
content-addressed storage (CAS) below the Git common directory and never writes
`.agent-workflow/**`. See
the canonical [architecture and status map](agent-workflow.md#architecture) for
the distinction between independently callable evidence mechanisms, inactive
policy, and the active legacy lifecycle.

## Commands

```text
workflow_evidence.py publish --root ROOT --request PUBLICATION.json
workflow_evidence.py verify --root ROOT --binding SHA256 [--expect EXPECTED.json]
workflow_evidence.py project --root ROOT --binding SHA256
workflow_evidence.py adapt-v4 ISSUE --root ROOT [--run-id ID | --correction N]
```

Commands emit one canonical JSON document. Outcomes are `resolved`, `missing`,
`corrupt`, `unsupported`, `stale`, or `ambiguous`; every non-resolved outcome
fails closed. `adapt-v4` is read-only and never converts or publishes evidence.

## Limits

The v1 limits are:

- 64 MiB per raw payload;
- 8 MiB per structured CAS object;
- 10,000 entries per manifest; and
- 512 MiB of unique payload bytes per publication.

Limits are verified before publication and again where applicable during reads.

## Canonicalization and storage

Structured objects use #128
`utf8-json-sort-keys-compact-ascii-v1`: exact JSON integers, sorted object keys,
compact separators, ASCII escapes, and UTF-8. CAS objects have no trailing
newline. Command output has one trailing newline. SHA-256 always covers the
exact stored bytes.

Raw payloads are stored directly as `evidence-payload` objects:

```json
{"kind":"evidence-payload","sha256":"<sha256>","size":123}
```

The path remains:

```text
chess-echo-agent-workflow/objects/sha256/<first-two>/<remaining>
```

Identical bytes therefore occupy one CAS object. Publication uses the extracted
`workflow_cas.py` create-exclusive, fsync, hard-link, collision-checking
primitive from #129. Dependencies are published first and the binding is
published last. An interruption can leave only unreachable immutable objects;
it cannot produce a success result or a binding with unpublished dependencies.
Concurrent publication of identical bytes is idempotent.

"Immutable" here describes the supported publication protocol: writers never
overwrite a conflicting destination, and readers verify exact hash, size, kind,
and graph references. It is not a filesystem permission, signature, retention
policy, or defense against an actor with direct write access to the store.
Binding-last is the evidence-graph commit point, not a multi-object filesystem
transaction or a guarantee that an interrupted publication leaves no orphaned
objects.

## Semantic manifest

`chess-echo-evidence-manifest-v1` contains entries sorted by the UTF-8 bytes of
their normalized repository-relative POSIX paths:

```json
{
  "entries": [
    {
      "content_sha256": "<sha256>",
      "kind": "regular",
      "mode": "100644",
      "path": "src/example.txt",
      "payload": {
        "kind": "evidence-payload",
        "sha256": "<sha256>",
        "size": 123
      },
      "size": 123
    }
  ],
  "format": "chess-echo-evidence-manifest-v1",
  "kind": "evidence-manifest"
}
```

Supported entries are regular files (`100644` or `100755`), symlinks
(`120000`, with link-target bytes as content), and deleted paths. Deleted entry
`mode`, `content_sha256`, `size`, and `payload` fields are explicitly null.
Sparse-checkout state is provenance, not a semantic kind: committed and
workspace captures of the same bytes, mode, kind, and path have the same
semantic manifest.

Duplicate normalized paths are ambiguous and rejected. Directories, special
files, absolute paths, parent traversal, platform separators, and unknown modes
are unsupported.

## Provenance

`chess-echo-evidence-provenance-v1` separately binds every manifest entry hash
to one or more canonical captures. A capture records:

- capture method;
- RFC 3339 capture time;
- tool name and version; and
- a typed `workspace`, `git`, `v4`, or `external` source.

Git commit/blob OIDs, sparse source details, source locations, timestamps, and
tool identity exist only in provenance. Changing them changes the provenance
and binding hashes but never the semantic manifest hash. Absolute source paths
are prohibited.

## Binding

`chess-echo-evidence-binding-v1` binds one manifest and provenance object to:

- exact issue, run ID, family run ID, correction number or null;
- exact run generation, sequence, and event tip;
- a typed decision ID;
- the exact supported subject object reference;
- correction lineage; and
- explicit migration metadata or null.

Lineage is one of:

- `original`, with a null parent;
- `inherited`, with an immutable parent binding and byte-identical manifest and
  subject; or
- `replacement`, with an immutable parent binding and a new manifest permitted.

Parent and child must have the same issue and family run, and different run
IDs. The evidence layer verifies these facts but does not decide which workflow
correction class may inherit evidence. Invalidated evidence has no child
binding. Lifecycle invalidation remains outside #132.

Binding identity is caller-asserted and structurally validated. Migration
adapter tags select graph validation rules; they are not signatures or producer
attestations. #133 derives legacy and durable identities by rebuilding its
deterministic migration plan. Consumers that require that derivation must
verify through the migration plan or supply its expected identity and subject
to `verify`.

An expectation supplied to `verify` must exactly match the binding identity and
subject. A mismatch is `stale`, not silently accepted.

## Authority and projections

Raw payloads, manifests, provenance records, and bindings are canonical
content-addressed records whose stored bytes are authoritative for their own
identity after verification. A binding becomes workflow authority only when an
external authority mechanism selects that exact binding under its current
preconditions. Publication alone does not authenticate a producer, establish a
latest tip, prove freshness or non-revocation, record human approval, or
activate a lifecycle.

`project` expands a verified binding into a deterministic human-readable view
without payload bytes. The projection contains its exact source references and
is never persisted as authority.

## v4 compatibility

`adapt-v4` uses the independent #128 reader to verify current v4 pointer,
index, run, state, history, event, and reachable evidence objects. It reports
typed references without copying or converting payloads. It distinguishes:

- absent migration metadata as `not-recorded`;
- explicit `migration: null` as `none`; and
- a verified migration object as `recorded`.

Malformed or unsupported legacy evidence fails closed. Conversion belongs to
#133.

## Non-goals

#132 does not migrate evidence, compact or delete legacy objects, define
dependency invalidation, revise lifecycle/review policy, implement incremental
review, or integrate with `agent_workflow.py`. Frozen #115 authority and
projections remain read-only.
