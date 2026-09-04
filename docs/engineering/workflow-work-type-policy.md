# Inactive Work-Type Policy

Issue #116 adds `scripts/workflow_work_type_policy.py`, an inactive policy
surface above the independent evidence reader and process supervisor. It
classifies explicit intake, describes the required route, runs bounded advisory
targeted checks, verifies a designated final diff structurally, and assesses
non-implementation artifact/review/acceptance evidence.

It does not integrate with `agent_workflow.py`, persist or publish authority,
acquire trust anchors, authenticate actors, run comprehensive validation, call
#134, or transition a lifecycle. #144 owns those activation concerns.

## Commands

Both direct and package forms emit one inspector-canonical JSON document:

```text
workflow_work_type_policy.py classify
  [--root ROOT] --request REQUEST
  --trusted-issue-snapshot-binding SHA256
  --trusted-baseline-binding SHA256

workflow_work_type_policy.py run-targeted
  [--root ROOT] --request REQUEST
  --trusted-issue-snapshot-binding SHA256
  --trusted-baseline-binding SHA256
  --trusted-triage-binding SHA256

workflow_work_type_policy.py assess-completion
  [--root ROOT] --request REQUEST
  --trusted-issue-snapshot-binding SHA256
  --trusted-baseline-binding SHA256
  --trusted-triage-binding SHA256
  --designated-observation-binding SHA256
  [--designated-review-binding SHA256]
  [--designated-acceptance-binding SHA256]
```

`--root` defaults to `.` and is untrusted. The module resolves the repository
and existing durable store without creating either. `--request` is required.
Missing, unsupported, corrupt, ambiguous, stale, and denied outcomes exit 3
through 8 respectively. Invalid CLI is `unsupported`.

## Canonical contract

Every request and result has an exact key set and a final digest. Request
digests are `request_sha256`; result digests are `result_sha256`. Evidence
documents use their schema-specific digest. A digest covers inspector-canonical
bytes after removing only that digest field: exact integers, sorted object keys,
compact separators, ASCII escapes, and UTF-8 with no trailing newline.

Structured documents are limited to 8 MiB. Paths are normalized
repository-relative POSIX paths of at most 4,096 bytes. SHA-256 references use
64 lowercase hex; Git object IDs consistently use either 40 or 64 lowercase
hex per observation. Unknown keys, floats, duplicate semantic entries, and
noncanonical schema-defined array ordering fail closed.

Every evidence input is exactly:

```json
{
  "binding": {"kind": "evidence-binding", "sha256": "...", "size": 123},
  "document": {}
}
```

The module calls `workflow_evidence.project` against `--root`, requires the
binding's exact issue/family, decision, subject, original lineage, and null
migration, then reads the projected document payload through
`workflow_inspector.AuthorityReader`. The stored payload must byte-equal the
inline canonical document. V1 does not accept migrated work-type evidence.

The fixed evidence identities are:

| Document | Decision type | Subject |
|---|---|---|
| Issue snapshot | `work-type-issue-snapshot` | its immutable `issue-snapshot` source |
| Trusted baseline | `work-type-baseline` | issue-snapshot binding |
| Triage result | `work-type-triage` | baseline binding |
| Diff observation | `work-type-diff-observation` | triage binding |
| Targeted result | `work-type-targeted-validation` | triage binding |
| Artifact | `work-type-artifact` | triage binding |
| Independent review | `work-type-independent-review` | artifact binding |
| Human acceptance | `work-type-human-acceptance` | review binding |
| Documentation content check | `work-type-documentation-content-check` | artifact binding |
| Documentation diff check | `work-type-documentation-diff-check` | observation binding |

Decision IDs contain the document digest, for example
`baseline-<baseline_sha256>`, `triage-<result_sha256>`, and
`artifact-<artifact_sha256>`. Issue snapshots additionally include the issue
number. All V1 lineage is exactly original with a null parent, and migration is
null.

## Trusted issue and baseline

`chess-echo-work-type-issue-snapshot-v1` records repository, issue, exact URL,
title, body, sorted labels, immutable source reference, attribution timestamp,
and `snapshot_sha256`. The binding digest must equal the CLI-designated issue
digest.

`chess-echo-work-type-baseline-v1` records:

- the issue-snapshot binding and family run ID;
- target-base name, remote-tracking ref, commit, and tree;
- exact base-commit `.github/agent-workflow.json` bytes, SHA-256, size, and Git
  blob object ID;
- a deterministic projection of all four repository profiles;
- one policy-owned execution-limit row for every projected check; and
- externally reviewed targeted templates.

The decoded base config supplies `target_base` and profiles. Omitted check
`cwd` normalizes to `.`, while configured check order, command order, and
`test_paths` order are preserved. Check names and test paths must be unique.
`test_paths` is deliberately not sorted.

Each configured check has exactly one limit row, sorted and unique by
`(profile,check)`, with:

```json
{
  "timeout_ms": 3600000,
  "grace_ms": 2000,
  "output_limit_bytes": 524288
}
```

Missing, extra/duplicate, invalid, or policy-mismatched rows are typed failures.
Callers cannot override them. Templates retain their own frozen timeout, grace,
and output limits, but cannot invoke a shell or accept option-like selectors.

The words “trusted” and “designated” mean only that the CLI caller supplied an
expected digest. The module does not establish who selected it or whether it is
current.

## Classification and routes

`chess-echo-work-type-triage-request-v1` contains issue and baseline evidence
envelopes plus:

```json
{
  "work_type": "implementation|design|research|documentation",
  "basis": "nonempty issue-aligned reason",
  "deliverable": {
    "storage": "git|evidence-cas",
    "kind": "implementation-change|design-document|research-report|documentation-change",
    "locations": ["normalized/path"]
  },
  "executable_change_expected": true,
  "expected_scope": [{"kind": "path|subtree", "path": "normalized/path"}],
  "validation_profile": "backend|frontend|full-stack|workflow-tooling|design-artifact|research-artifact|documentation-content-diff",
  "unresolved_ambiguities": []
}
```

Scope entries are sorted, unique, nonoverlapping, and cannot select repository
root. Any unresolved ambiguity returns `ambiguous`; the policy never guesses a
work type.

Implementation requires Git storage, an implementation deliverable,
`executable_change_expected: true`, nonempty repository scope, and one
repository profile. Design, research, and documentation require false and their
matching profile. CAS-only design/research has empty Git scope. Documentation
is Git-backed.

The result is `chess-echo-work-type-triage-result-v1`. It freezes the normalized
classification and returns fixed inactive requirements:

- **implementation:** source-aligned plan, independent plan review, explicit
  plan approval, tests before production, independent test review, explicit
  test approval, implementation, the legacy workflow's own fresh comprehensive
  final validation, independent final review, and explicit PR approval;
- **design/research:** durable artifact, independent artifact review, clean
  final scope verification, and explicit artifact acceptance;
- **documentation:** durable content, content and diff checks, independent
  review, clean final scope verification, and explicit acceptance.

The result always states `operationally_active: false` and lists initialization
integration, authoritative recording, latest-tip/revocation, temporal final
validation, and lifecycle completion as unsatisfied.

## Advisory targeted checks

`run-targeted` accepts 1–4 profile checks or frozen templates. A profile check
must belong to the selected base profile, has no selectors, and uses its exact
policy-owned limit row. A template must allow the profile and takes 1–32
selectors within its frozen limits. Selectors cannot begin with `-`, traverse,
be absolute, contain NUL/backslash, or fall outside their path/test-ID grammar.
Declared targeted scope must be within intake scope.

The three non-repository profiles have no executable targeted checks and return
`denied/non-repository-profile-has-no-targeted-checks`.

Each command runs directly through `workflow_supervisor.supervise` with
`shell=False`. Template prefixes reject direct shells and common command
dispatch wrappers such as `env` and `busybox`; relative-path selectors must
resolve through symlinks inside the repository, command cwd, and declared
scope. V1 test-ID templates must invoke Python unittest directly; every dotted
ID must resolve to an actual repository `.py` module inside the command cwd and
declared scope, so standard-library or external callables cannot be selected.
The module does not claim that a reviewed target program cannot itself spawn
descendants. The result embeds the exact #131
process result and is permanently marked:

```text
advisory-only
not-authoritative
not-final-validation
escaped-descendants-not-observable
freshness-not-verified
replay-not-prevented
```

Even a successful process result has `descendant_cleanup_verified: false`.
Targeted results are never accepted by completion assessment. Attempt labels
are attribution, not uniqueness or freshness.

## Designated final observation

`chess-echo-work-type-diff-observation-v1` is externally produced and
designated. It records exact base and HEAD commits/trees, object format,
ancestry, sorted no-renames A/D/M/T records, complete workspace facts, unchanged
HEAD config identity, normalized change-list digest, observer/source
attribution, and:

- replacement objects disabled;
- complete replacement refs;
- replacement/graft environment;
- `.git/info/grafts` presence;
- redirection variables and alternate object directories;
- staged and unstaged records;
- non-ignored untracked paths; and
- assume-unchanged/skip-worktree paths.

Assessment requires the baseline ref/commit/tree and config to match, base to be
an ancestor, and every workspace/trust-control collection to be empty. No
generated-output tolerance exists at this final boundary.

Every non-null old/new path may occur in only one no-renames change record, and
present sides cannot use the all-zero object ID. Base/HEAD commits and trees,
config blobs, replacement targets, and documentation content objects likewise
cannot be zero. Conflicting records for one path therefore fail before artifact
content can be selected.

Commit count zero is equivalent to identical base/HEAD commits; an identical
commit requires an identical tree; and tree equality is equivalent to an empty
change list. `raw_diff_sha256` must equal the inspector-canonical SHA-256 of the
normalized `changes` array. Contradictory commit/tree/count/change facts or an
unrelated diff digest are corrupt.

This is a static integrity and policy check. A #132 binding cannot prove that
the observation is live when replayed, is the latest tip, or has not been
revoked.

## Surface and scope policy

Paths classify into backend, frontend, workflow tooling, documentation, or
uncovered. Documentation is restricted to inert `.md`, `.txt`, `.rst`, and
`.adoc` files under `docs/**` or content-only README names. Other files under
`docs/**`, including executable configuration such as `docs/conf.py`, are
workflow tooling and cannot use a non-implementation route. Workflow tooling
also includes `scripts/**`, workflow agent/config/docs, workflow CI, and the
workflow-test Make target. Unknown paths fail closed.

Profile coverage is fixed:

| Profile | Exact assessment coverage |
|---|---|
| `backend` | backend with optional documentation |
| `frontend` | frontend with optional documentation |
| `full-stack` | both backend and frontend with optional documentation |
| `workflow-tooling` | workflow tooling with optional documentation |
| `design-artifact` | Git documentation or CAS evidence artifact |
| `research-artifact` | Git documentation or CAS evidence artifact |
| `documentation-content-diff` | documentation only |

Every old/new changed path must be declared and covered. Product plus workflow
tooling has no V1 profile and fails closed.

Classification applies this same coverage rule to the declared implementation
scope, so a backend intake cannot select the workflow-tooling profile and defer
that contradiction until final assessment.

Non-implementation Git changes are exact declared documentation/README
locations, regular mode `100644`, and A/M only. Deletes, type changes,
executables, symlinks, gitlinks, code, tests, schema, migrations, dependencies,
configuration, workflow tooling, and undeclared changes require
reclassification as implementation. Intake scope must equal the exact artifact
path set; subtrees and additional paths are not accepted. CAS-only work
requires an empty Git diff.

## Structural non-implementation assessment

The artifact binding contains `workflow-work-type/artifact.json` plus exactly
the declared nonempty regular `100644` payload entries. Its decision subject is
triage. The review subject is the artifact and must be `accepted` with no
blocking finding. The acceptance subject is the review and uses the exact
`artifact_accepted` confirmation.

Documentation additionally requires passing content and diff documents. Content
checks cover every artifact/observed path and bind payload SHA-256 plus the
derived SHA-1/SHA-256 Git blob ID. Diff checks bind the exact canonical change
set and declared locations.

`assess-completion` returns either:

- `implementation-route-conforms`, which means only scope/profile consistency;
  or
- `nonimplementation-structurally-satisfied`, which means only the static
  artifact/review/acceptance shape passed.

Neither means completed, approved, authenticated, fresh, or active. Human and
reviewer actor strings are attribution only.

## Freshness and activation boundary

#132 content identity and provenance do not prove current time, latest tip,
revocation absence, unique attempts, non-replay, immediate observation,
newly-executed validation, or tool-cache bypass.

#116 accepts no comprehensive-validation binding and never attests one. The
unchanged active legacy implementation path still performs its own final
validation. #144 must define trust-anchor acquisition, latest-tip and
revocation checks, unique attempt identity, temporal binding to final review
and approval, replay prevention, authoritative publication, and lifecycle
activation.

## Non-goals

- No modification or revival of frozen #115.
- No legacy workflow or #128–#134 changes.
- No lifecycle integration, authority publication, authentication, agent
  invocation, GitHub access, or new trusted-core mechanism.
- No automatic semantic classification.
- No authoritative use of supervisor output.
- No freshness, uncached, latest-tip, revocation, replay-prevention, approval,
  readiness, or completion claim.
