# Independent Workflow Inspector

`scripts/workflow_inspector.py` is a read-only bootstrap tool for inspecting the
current v4 durable workflow store without importing or invoking
`scripts/agent_workflow.py`. It does not implement workflow policy, lifecycle
transitions, migration, recovery, or repair.

## Commands

```bash
python3 scripts/workflow_inspector.py inspect ISSUE \
  --root REPOSITORY \
  [--run-id RUN_ID | --correction NUMBER]

python3 scripts/workflow_inspector.py checkpoint ISSUE \
  --root REPOSITORY \
  [--run-id RUN_ID | --correction NUMBER]
```

With no selector, the issue pointer's current normal run is inspected. A run ID
is 32 lowercase hexadecimal characters. `--run-id` and `--correction` are
mutually exclusive.

Both commands emit one canonical JSON document to standard output. They never
create an output file. `inspect` adds a typed `outcome`; `checkpoint` succeeds
only after complete supported inspection and adds a content digest.

| Outcome | Exit | Meaning |
|---|---:|---|
| `resolved` | 0 | The supported authority and repository facts verified |
| `missing` | 3 | A required pointer, object, run, Git object, or path is absent |
| `unsupported` | 4 | The input uses a format or construct outside this bounded reader |
| `corrupt` | 5 | Hash, size, schema, identity, sequence, or linkage is inconsistent |
| `ambiguous` | 6 | Valid-looking records resolve to conflicting authority |

Legacy worktree projections without a durable pointer are reported as
`unsupported`; the inspector never adopts or migrates them.

## Read-only boundary

The implementation contains no filesystem write, replace, rename, unlink,
mkdir, locking, or persistence path. It reads the Git common directory directly
to locate:

```text
chess-echo-agent-workflow/
  issues/<issue>/index-integrity.json
  objects/sha256/<first-two-hex>/<remaining-hex>
```

It does not import lifecycle or persistence helpers. Repository observations
use only these Git commands:

```text
git rev-parse --verify HEAD^{commit}
git rev-parse --verify HEAD^{tree}
git rev-parse --verify <base-ref>^{commit}
git rev-parse --verify <base-ref>^{tree}
git merge-base HEAD <base-ref>
git merge-base --is-ancestor <base-commit> HEAD
git rev-list --count <base-commit>..HEAD
git status --porcelain=v1 -z --untracked-files=all -- . \
  :(exclude).agent-workflow/runs/**
```

Every invocation uses `git -c core.fsmonitor=false`, sets
`GIT_OPTIONAL_LOCKS=0` and `GIT_NO_LAZY_FETCH=1`, and captures output directly
without a shell. Mutable HEAD, base, and status observations are repeated after
the immutable commit queries and inspection fails if they moved. There is no
GitHub, network, agent, workflow-command, hook, or shell execution.

Repository inspection is deliberately limited to facts needed to identify the
inspected workspace and establish a future expected-tip precondition: HEAD
commit/tree, recorded and currently resolved base commit/tree, merge base,
ancestry, commit count, cleanliness, and the SHA-256 of Git's canonical
porcelain status bytes. It does not reproduce a full repository snapshot or
copy file contents into the result.

## Supported authority verification

The inspector independently implements only the current durable v4 read
contract:

- pointer format, issue, generation, index reference, and selection;
- canonical, contiguous issue-index predecessors through generation zero;
- unique run IDs and correction numbers and an unambiguous current run;
- selected binding, envelope, state, history, and event identity;
- state generation, sequence, lifecycle name, and event tip;
- contiguous history and the complete durable event predecessor/self-hash
  chain through sequence one or a legacy-import anchor verified against the
  immutable imported `history.jsonl`;
- SHA-256 and byte-size verification of supported immutable objects;
- canonical JSON for structured objects;
- typed and base64 evidence wrapper decoding plus raw evidence hashes; and
- direct evidence references reachable from the selected state and history.

The current generation is authoritative. Direct predecessor objects are
hash-verified, while the inspector does not reproduce lifecycle-aware replay,
compatibility policy, migration interpretation, or historical policy
certification. A required kind or format outside this contract is
`unsupported`, not guessed.

## Checkpoint schema

The exact top-level `chess-echo-workflow-checkpoint-v1` object is:

```json
{
  "authority": {
    "envelope": {"kind": "run-envelope", "sha256": "...", "size": 0},
    "event": {"kind": "run-event", "sha256": "...", "size": 0},
    "event_tip": "...",
    "family_run_id": "...",
    "history": {"kind": "run-history", "sha256": "...", "size": 0},
    "index": {"kind": "issue-index", "sha256": "...", "size": 0},
    "index_generation": 0,
    "issue": 0,
    "pointer_sha256": "...",
    "run_generation": 0,
    "run_id": "...",
    "sequence": 0,
    "state": {"kind": "run-state", "sha256": "...", "size": 0},
    "state_name": "...",
    "verified_objects": [
      {"kinds": ["..."], "sha256": "...", "size": 0}
    ]
  },
  "canonicalization": "utf8-json-sort-keys-compact-ascii-v1",
  "checkpoint_sha256": "...",
  "format": "chess-echo-workflow-checkpoint-v1",
  "inspector": {
    "source_sha256": "...",
    "version": "1.0.0"
  },
  "observations": {
    "latest_validation": {
      "attempt_id": "...",
      "checks": [],
      "status": "..."
    }
  },
  "repository": {
    "base": {
      "matches_recorded": true,
      "recorded_commit": "...",
      "ref": "...",
      "resolved_commit": "...",
      "tree": "..."
    },
    "commit_count_from_resolved_base": 0,
    "head": {"commit": "...", "tree": "..."},
    "head_descends_from_resolved_base": true,
    "merge_base": "...",
    "worktree": {"clean": true, "status_sha256": "..."}
  }
}
```

`authority.correction` is present only for a correction run. A validation with
no attempts is represented as `{"status":"not-recorded"}`. Optional fields are
omitted rather than filled with an ambiguous null.

Object references contain hashes and sizes only; evidence payloads are never
copied into a checkpoint.

## Canonicalization and digest

`utf8-json-sort-keys-compact-ascii-v1` means:

1. The value contains JSON objects, arrays, strings, exact integers, booleans,
   and nulls, but no floating-point numbers.
2. Object keys are sorted lexicographically.
3. Separators are exactly `,` and `:` with no insignificant whitespace.
4. Non-ASCII characters use JSON ASCII escapes.
5. The encoded form is UTF-8 and the emitted document has one trailing newline.
6. `verified_objects` is sorted by SHA-256 and duplicate hashes are removed.
7. No generation timestamp, absolute path, environment value, or evidence
   payload is included.

`checkpoint_sha256` is SHA-256 over the canonical JSON bytes of the complete
checkpoint object before that field is added, without a trailing newline.
Consequently, unchanged authority, repository facts, and inspector source
produce byte-identical checkpoints.

## Relationship to recovery

A future recovery tool may consume the pointer hash, index hash, run ID,
generation, sequence, event tip, selected object hashes, and repository facts
as exact compare-and-swap preconditions. This inspector does not define repair
bundles, authorize changes, publish objects, recover transactions, reseal
integrity, or mutate projections.
