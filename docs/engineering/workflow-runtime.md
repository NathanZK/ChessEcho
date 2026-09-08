# Workflow runtime

`scripts/workflow_runtime.py` is the external boundary for the replacement
orchestrator. It supplies fixed Git and GitHub observations, including trusted
remote-head observations, baseline-pinned command resolution, bounded execution,
cancellation pass-through, uncertain GitHub-write reconciliation, and one
supervised validated-source publication operation. It does not select lifecycle
state, publish evidence, read or write an authority pointer, migrate or repair a
run, or define retry policy.

The runtime imports exactly `workflow_inspector` and `workflow_supervisor`.
Every external process goes through `workflow_supervisor.supervise` on the
calling thread. The runtime uses only inspector canonicalization, hashing, and
Git common-directory resolution. It intentionally does not use
`AuthorityReader`: request and authority bindings are opaque caller-supplied
#132 references, and dereferencing either would incorrectly give this boundary
authority-selection responsibility.

## Provider configuration

`.github/agent-workflow.json` contains one exact-key `orchestrator` object with
format `chess-echo-orchestrator-config-v1`. It declares:

- `mode`, activated for the reviewed trusted-local host;
- sorted unique `frozen_issues`;
- exactly `implementer`, `planner`, and `reviewer` rows in lexical order;
- each role's fixed command prefix, repository-relative cwd, #131 stdout and
  optional independent stderr limits,
  execution-boundary kind, and exact provider/version/source and agent
  executable hashes;
- exact `git` and `gh` command names and limits;
- an explicit absolute-directory `validation_path`; and
- an exact structurally validated `supervision` object whose four-gate semantics
  are owned by `workflow_supervision_policy.py`; and
- human accounts sorted by numeric account ID plus lexically sorted allowed
  author associations.

Unknown, missing, duplicate, noncanonical, shell, dispatch-wrapper, traversal,
PATH-separator injection, or out-of-range values fail closed. No `runtime-test-*` provider or fixture source hash may appear in the committed
config. The trusted-local host verifies the installed executable and provider
against this base-pinned configuration before execution.

With `mode=inactive`, bootstrap and read-only observations remain callable.
Validation, agent execution, and GitHub writes return
`unsupported/runtime-inactive` before starting a process.

## Bootstrap

```python
adapter = workflow_runtime.bootstrap(
    root,
    "owner/repository",
    pathlib.Path("/absolute/path/to/git"),
    pathlib.Path("/absolute/path/to/gh"),
    github_token,
)
```

The executable paths must resolve once to regular executable files. Their
resolved paths and SHA-256 values are recorded. The token is retained only as
private in-memory transport state: it is never included in `repr`, canonical
documents, command arguments, logs, or non-GitHub environments.

Bootstrap uses fixed 30-second, 1-second-grace, 8-MiB process limits except
that the config bytes are capped at 1 MiB. It reads GitHub's repository default
branch and that branch's remote tip, then reads:

1. clean initial `HEAD`;
2. `refs/remotes/origin/<default>^{commit}`;
3. that remote-tracking commit's tree; and
4. that exact commit's `.github/agent-workflow.json` blob and bytes.

The rule is deliberately unambiguous: **both** initial `HEAD` and the local
remote-tracking tip must byte-equal the successfully observed GitHub remote
tip. The config `target_base` must equal the observed default branch. A dirty
worktree or any mismatch is `stale`; the operator must fetch or reconcile
outside the read-only runtime. A local ref alone is never described as latest.
Worktree config bytes cannot override the base-pinned blob.

Bootstrap compares two complete reads of every bound remote, local, trust,
status, Git/GitHub executable, validation executable, and config fact. The
returned runtime uses the selected stable executable records, not values read
before the comparison. If the reads differ, bootstrap repeats that complete
comparison once; movement in the second comparison is `stale`.

The bootstrap document also pins the runtime module version and source hash.
The Phase 1 activation and operational procedure is documented in
[`workflow-local-provider.md`](workflow-local-provider.md). After
classification, the orchestrator publishes a
`chess-echo-runtime-reconstruction-pin-v1` document bound to the selected
baseline and triage. That pin contains the exact bootstrap document but no
credential.

## Reconstruction after intake

`bootstrap()` remains the only new-intake constructor and retains its clean
`HEAD == tracking ref == live default tip` rule. Every later command obtains a
new adapter from the reviewed host by passing a canonical
`chess-echo-runtime-reconstruction-request-v1` to `reconstruct()`. The request
binds:

- the selected runtime pin, baseline, triage, and current authority references;
- the exact canonical baseline and triage documents; and
- either the exact phase-selected repository observation, the clean initial
  base, or a trusted-current observation for a recovery state that has no safe
  selected `HEAD`.

Reconstruction decodes the base-pinned config bytes and revalidates their Git
blob and SHA-256 identities, projected profiles and limits, configured mode,
runtime source, Git/GitHub and validation executable paths and hashes, GitHub
repository and default-branch identity, the unchanged live default tip, the
local target-base commit/tree, and worktree trust controls. It then observes
the issue repository twice with a stable timestamp and requires the selected
repository expectation. A later issue commit is accepted only when exact
selected evidence names it; a moved or rewritten `HEAD`, dirty worktree,
replacement ref, graft, alternate object directory, changed executable,
changed config, changed authority, or newer default tip fails closed.

The request and reconstruction document are credential-free. GitHub and source
publication credentials are supplied only to `reconstruct()` by the reviewed
host and remain private runtime memory under the same environment restrictions
as bootstrap.

The direct and package CLIs expose `bootstrap` and `execute`. They require
absolute Git/GitHub executable paths and read the explicitly selected GitHub
token from standard input. The CLI has no sandbox-provider option or provider
discovery mechanism.

## Fixed process boundary

Commands are derived from the bootstrap bytes, not caller argv:

- Git and GitHub always reuse the exact bootstrap executables;
- validation chooses an existing profile and check name, resolves only `npm`,
  `npx`, or `make`-style configured names through `validation_path`, or resolves
  a configured `./gradlew` under its validated cwd; bootstrap records the
  resolved path and SHA-256, and execution rejects replacement;
- trusted-local agent execution delegates the exact base-pinned role and
  executable to the reviewed provider, which constructs one deterministic
  prompt bound to the canonical request and request binding;
- source publication derives one exact non-force push from a canonical
  observation-bound request and the bootstrapped repository identity; and
- the only write operation is `create-draft-pr`, constructed from an exact
  typed payload whose refs and title/body hashes match its reconciliation
  expectation; it requires a validated repository observation and a stable
  trusted remote-head observation before the mutation starts. The orchestrator
  supplies a pre-write check that runs after the initial remote-head preflight
  to revalidate live final/publication authorization and unchanged authority.
  The runtime then repeats the complete local/remote-head observation
  immediately before process launch.

Each call receives a newly isolated `HOME`. Common environment keys are exactly
`PATH`, `HOME`, `LC_ALL=C.UTF-8`, `LANG=C.UTF-8`, and `TZ=UTC`. Git additionally
sets `GIT_OPTIONAL_LOCKS=0`, `GIT_NO_LAZY_FETCH=1`,
`GIT_NO_REPLACE_OBJECTS=1`, `GIT_CONFIG_NOSYSTEM=1`,
`GIT_TERMINAL_PROMPT=0`, and empty `GIT_ASKPASS`. GitHub additionally receives
only `GH_HOST=github.com`, `GH_PROMPT_DISABLED=1`, and the designated
`GH_TOKEN`. Caller `GH_*`, `SSH_*`, `GNUPGHOME`, askpass, Git redirection,
alternate-object, and other environment entries are never inherited.
Source publication uses a separate host-supplied credential retained only in
private runtime memory. It is supplied only to the fixed publication process as
a host-scoped HTTP header through runtime-owned Git configuration; it is absent
from argv, all agent/validation/read environments, canonical requests/results,
and errors. The fixed command disables hooks, submodules, redirects,
system/global config, prompting, and credential helpers.
GitHub write refs, title, and body are rejected before preflight if they contain
the designated token, and process output is rejected if it discloses that token.
Execution and security-sensitive read results are checked against the exact
command hash, configured limits, containment shape, outcome/reason pairing, and
stream schema before their state can authorize a decision. Only a complete
canonical `cancelled-before-start` result proves that a write did not begin.
Process-result v2 records the stdout and stderr limits independently; callers
that omit the additive stderr field retain equal per-stream behavior.

The optional caller-owned `cancel_event` is passed to #131 unchanged while
preflight remains eligible. If it is already set or becomes set during a
cancelled preflight, runtime latches that decision in a private always-set event
before invoking the primary supervisor; clearing the caller's event cannot then
start an unverified child. A pre-cancelled request skips external pre/post
observations, obtains the supervisor's canonical cancelled result, and never
starts the child. A write cancelled before process start is not reconciled
because no mutation was attempted. Once a write may have started, its single
bounded reconciliation and local postflight run without that cancellation
event so a completed mutation cannot be mislabeled cancelled and retried.
They also run before propagating an interruption or malformed supervisor result
reported after invocation of the write command.
Runtime does not watch or mutate authority. Agent, validation, mutation,
authorization, migration, and repair calls are never automatically retried.
If the caller dies after a child starts, the runtime makes no claim that the
child or escaped descendants stopped. A later process must not execute the
pending request again; it may only finalize a separately discovered, exact
published result or enter the existing cancellation and human-recovery path.

## Observation documents

The runtime constructs these canonical, exact-key documents without importing
#116:

- `chess-echo-work-type-issue-snapshot-v1`, returned with the exact bounded raw
  GitHub bytes referenced by its `source` field; the runtime rejects mismatched API
  or HTML identity, pull-request records, malformed labels, and duplicate labels;
- `chess-echo-work-type-baseline-v1`; and
- `chess-echo-work-type-diff-observation-v1`.

The baseline requires the caller's previously published issue-snapshot binding
and family ID. The diff requires the caller's published triage binding. Tests
publish the runtime bytes through #132 and pass them through #116's public
`classify` and `assess_completion` APIs.

`observe_issue(issue)` returns `(snapshot_document, raw_source_bytes)`. The trusted
pre-genesis owner in [`workflow-issue-source.md`](workflow-issue-source.md) is the
only production entry point that publishes those raw bytes. The orchestrator later
publishes the snapshot document and passes that binding to `build_baseline(...)`.

Repository observation records base/HEAD commits and trees, ancestry and commit
count, normalized changes, staged/unstaged/untracked and index flags,
replacement/graft controls, and the unchanged HEAD config. The runtime compares
guard observations before and after the complete read and repeats the full
read-only observation at most once. Movement during the second observation is
`stale`.

Pull-request observations use
`chess-echo-github-pr-observation-v1` and bind exact repository, number, URL,
state, draft flag, base/head refs and SHAs, title/body hashes, source request
binding, and observation time. The caller supplies the originating workflow
issue separately so frozen-issue denial occurs before any PR lookup.

Remote-head observations use
`chess-echo-github-remote-head-observation-v1` and bind the bootstrapped
repository, exact `refs/heads/<branch>` ref, resolved commit SHA, source
repository-observation digest, and observation time. `observe_remote_head(...)`
accepts no expected-SHA argument: it derives that value only from the validated
`repository_before.head.commit`. The runtime performs a complete local
observation, reads the remote ref, completely revalidates the local repository,
and reads the remote ref again immediately before mutation. It retries that
sequence at most once and fails closed on malformed, missing, divergent, or
moving refs. Branch names are validated before any external call. An
unpublished branch remains rejected by this read-only operation.

## Validated source publication

`build_source_publication_request(...)` accepts only the selected clean
repository observation, its exact local commit and tree, the bootstrapped
repository identity, and one exact safe `refs/heads/<branch>` target. The
canonical request contains no credential or authority mutation. It rejects
dirty worktrees, hidden index flags, replacement refs, grafts, alternates,
environment redirection, non-descendant heads, parallel caller-selected SHAs,
tags, deletes, force controls, wildcard/refspec input, URLs, configuration,
hooks, shells, and unknown fields.

`publish_validated_branch(...)` first revalidates the base-pinned config, full
local observation, and exact remote ref. An existing exact ref returns
`confirmed/already-published` without starting a mutation. A different ref
returns `conflict`; updates require a separate future authorization contract.
Only a stable missing ref can start the single fixed `git push`. The source SHA
is always taken from the trusted observation, the destination URL is derived
from the bootstrapped repository, and the destination is its exact requested
branch.

The mutation is never retried. After any process state that may have started,
including timeout, cancellation, signal, malformed supervisor output, or an
exception, the runtime performs one trusted exact remote-head reconciliation
without the caller cancellation event. A match returns `confirmed`; otherwise
the canonical result is `uncertain`, `ambiguous`, `stale`, `denied`, or
`conflict`. A concurrent different ref can never become confirmed. Publication
does not select or consume authority, create a pull request, merge, mark ready,
or deploy; a future orchestrator must separately consume a confirmed result
under its expected-tip rules.

The CLI exposes `publish-branch`. Its standard input contains the general
GitHub observation token on the first line and the distinct publication
credential on the second; neither credential is accepted in argv.

Authorization observations require that same originating workflow issue and use
`chess-echo-github-authorization-observation-v1`. Numeric account ID is primary;
the configured login must match that account row, association must be allowed,
the source must belong to the exact requested issue or pull request, and the
complete current UTF-8 body must byte-equal the expected confirmation. No
trimming, substring matching, or Markdown normalization occurs. Issue comments
must have equal creation/update timestamps and exact API-target and HTML-anchor
identities. Pull-request reviews are
cross-checked between REST and GraphQL and require `lastEditedAt=null`; their
creation and submission timestamps are recorded. This is an
observation, not consumed authority or replay protection.

Frozen issue #115 is denied immediately after config validation and before any
issue-specific GitHub or artifact lookup.

## Two-phase execution transport

`Runtime.build_request(...)` returns
`chess-echo-execution-request-v1`. The caller publishes those exact bytes
through #132 and then calls:

```python
adapter.execute(
    request_document,
    request_binding,
    reconciliation_expectation=None,
    cancel_event=None,
    sandbox_provider=None,
    write_payload=None,
)
```

`request_binding` is the caller-supplied #132 reference to the already
published request. Runtime embeds it byte-for-byte in
`chess-echo-execution-result-v1`; it never computes an evidence-binding hash.
The result `attempt_id` is byte-identical to the request `attempt_id`.

## Execution evidence attachments

`execute` returns only the result document. A caller that must publish the
execution evidence attachments alongside that document calls `execute_bundle`,
which returns an explicit one-use `ExecutionBundle(document, attachments)`.
Ownership is therefore explicit and cannot be cross-wired between executions;
there is no mutable last-call slot.

An attachment is generic: `path`, `sha256`, `size`, and `bytes`. Runtime
verifies that each one binds its own exact bytes, that paths are distinct and
never the execution-result path, and that no attachment exceeds
`ATTACHMENT_LIMIT_BYTES` (8 MiB). Only the trusted-local boundary produces
attachments; any other boundary that supplies them fails closed. Runtime never
interprets attachment bytes.

For a trusted-local agent result, `sandbox.transport_sha256` and
`sandbox.transport_size` must equal the supervisor's own whole-stream
`observed_sha256` and `observed_bytes`, so the provider cannot assert a
transport identity the core did not observe, and `sandbox.transport_reference`
must name exactly one supplied attachment with the identical digest and size.
`verify_result_attachments(projection, result)` then proves, against a published
binding's projection, that the reference resolves to exactly one manifest entry
of that same binding with matching path, size, content digest, and payload
reference. The orchestrator applies it after publishing an execution result that
carries attachments, and again on the recovery handoff path using the projection
it already reads, so a previously published result re-proves its sidecar link
rather than being trusted.

The 2 MiB execution-result preflight budget is unchanged. Raw transport is no
longer Base64-inlined into the document, so the budget is now a conservative
over-reservation rather than a binding constraint.

The caller supplies `attempt_id`, but it must equal:

```text
SHA256(canonical({
  authority_binding,
  operation,
  command_source,
  input_bindings,
  repository_before,
  limits,
  reconciliation_expectation
}))
```

The expectation is bound through that attempt ID but is not duplicated in the
exact request schema. Input bindings are sorted and unique by
`(role, binding.sha256)`. Normal request construction verifies the config
content hash and Git blob ID against bootstrap bytes.

The exact request keys are `format`, `issue`, `family_run_id`, `attempt_id`,
`authority_binding`, `operation`, `command_source`, `input_bindings`,
`repository_before`, `limits`, and `request_sha256`.

The exact result keys are `format`, `request_binding`, `attempt_id`,
`process_result`, `candidate_output`, `repository_after`, `reconciliation`,
`sandbox`, `outcome`, and `result_sha256`. For a GitHub write,
`reconciliation.remote_head` contains the canonical trusted remote-head
observation (or null when cancellation prevented preflight); other operations
retain the existing reconciliation shape. Candidate output contains only
SHA-256 and size; stdout bytes remain solely in the #131 process result.
Canonical documents are limited to 2 MiB and digests omit only their final
digest field. Agent and validation requests require a runtime-produced
`repository_before`; execution re-observes it before starting and records a
fresh `repository_after`. GitHub process output is capped at 512 KiB so its
base64-encoded #131 result remains representable inside the 2-MiB result.
Before execution, runtime also rejects any repository-observation/output budget
whose worst-case encoded result would exceed that limit. If an unexpectedly
larger post-observation would still overflow, runtime returns a failed result
without a success-shaped repository observation.

Trusted-local execution fixes raw JSONL/stdout at 851,968 bytes, extracted
candidate output at 458,752 bytes, and stderr at 65,536 bytes. The runtime
charges their exact Base64 maxima independently: 1,135,960 + 611,672 + 87,384 =
1,835,016 bytes, identical to the earlier three-output persistence charge.
Together with the unchanged 131,074-byte prompt reservation and 65,536-byte
fixed headroom, this continues to admit a maximum 65,526-byte canonical
repository-before observation under the unchanged 2 MiB result envelope.
Repository-after remains an indivisible post-execution fact: if it makes the
finished record too large, canonicalization rejects the whole result rather
than dropping or truncating evidence.

## GitHub write uncertainty

A GitHub write carries an exact non-authoritative expectation containing
repository, base ref/SHA, head ref/SHA, title SHA-256, and body SHA-256.
The expectation's head SHA must equal the revalidated
`repository_before.head.commit`; a parallel caller-provided SHA is rejected.
Runtime freezes caller-owned expectation and payload dictionaries when
execution begins, so later mutation cannot substitute a different head after
validation.
The named branch must already exist in the bootstrapped repository, and its
trusted remote observation must resolve to that same validated local SHA before
`gh pr create` runs. Reviewer, agent, or other caller output naming a
`head_ref` cannot establish source identity by itself.

The mutation is never retried. Unless cancellation prevented process start, one
owner-qualified, URL-encoded `--paginate --slurp` read-only query follows both
successful and unsuccessful creation attempts:

- exactly one matching open draft is `succeeded/confirmed`;
- zero matches or query failure is `uncertain/unknown`; and
- multiple matches are a typed `ambiguous` failure.

Runtime compares live values only. It does not dereference evidence to derive
the expectation and does not retry a failed reconciliation query. Requiring
post-create reconciliation prevents a successful command from becoming a
success-shaped result if the PR's live head SHA no longer matches the
pre-create trusted remote-head observation. Malformed reconciliation rows are
uncertain rather than matchable, and reconciliation plus local postflight still
run after a possible mutation even when process output is malformed or exposes
the designated credential. A confirmed pull request has a positive numeric
`number` and the exact canonical repository pull-request URL for that number.

## Sandbox activation boundary

Agent execution is unavailable from the public CLI. The Python API accepts only
a caller-injected object with exact configured `name`, `version`,
`source_sha256`, and `verify(request, process_result, candidate)` members.
There is no static import, entry-point lookup, module name, environment hook, or
other dynamic provider discovery.

The configured provider executable is SHA-256 checked against the role's
provider source hash immediately before launch. The provider result is exact
`chess-echo-external-sandbox-result-v1` and binds provider identity, request and
command hashes, repository scope, candidate hash/size, credential access
`denied`, authority-store access `denied`, and containment `verified`.
Runtime requires this attestation after every attempted agent process,
including nonzero exit, timeout, signal, output overflow, and cancellation.
The attested provider name, version, and source hash must byte-equal the
injected object's validated identity rather than values chosen by its result.
`workflow_supervisor` alone cannot observe escaped descendants, so
`external-sandbox-v1` remains a genuine activation requirement. Until an
independently reviewed provider proves repository write scope, process
containment, credential denial, authority-store denial, immutable
request-binding input, and bounded canonical output, the committed runtime must
remain inactive. A test fake is not an activation substitute.
