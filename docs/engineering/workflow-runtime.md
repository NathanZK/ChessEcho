# Workflow runtime

`scripts/workflow_runtime.py` is the inactive external boundary for the future
orchestrator. It supplies fixed Git and GitHub observations, baseline-pinned
command resolution, bounded execution, cancellation pass-through, and
uncertain GitHub-write reconciliation. It does not select lifecycle state,
publish evidence, read or write an authority pointer, migrate or repair a run,
or define retry policy.

The runtime imports exactly `workflow_inspector` and `workflow_supervisor`.
Every external process goes through `workflow_supervisor.supervise` on the
calling thread. The runtime uses only inspector canonicalization, hashing, and
Git common-directory resolution. It intentionally does not use
`AuthorityReader`: request and authority bindings are opaque caller-supplied
#132 references, and dereferencing either would incorrectly give this boundary
authority-selection responsibility.

## Inactive configuration

`.github/agent-workflow.json` contains one exact-key `orchestrator` object with
format `chess-echo-orchestrator-config-v1`. It declares:

- `mode`, currently and intentionally `inactive`;
- sorted unique `frozen_issues`;
- exactly `implementer`, `planner`, and `reviewer` rows in lexical order;
- each role's fixed command prefix, repository-relative cwd, #131 limits,
  `external-sandbox-v1` requirement, and provider name/source hash;
- exact `git` and `gh` command names and limits;
- an explicit absolute-directory `validation_path`; and
- human accounts sorted by numeric account ID plus lexically sorted allowed
  author associations.

Unknown, missing, duplicate, noncanonical, shell, dispatch-wrapper, traversal,
PATH-separator injection, or out-of-range values fail closed. The committed provider identity is an
inactive activation marker, not a claim that a provider is installed or
verified. No `runtime-test-*` provider or fixture source hash may appear in the
committed config.

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
- agent execution chooses one configured role and appends only
  `--request-binding <canonical #132 reference>` to its fixed provider prefix;
  and
- the only write operation is `create-draft-pr`, constructed from an exact
  typed payload whose refs and title/body hashes match its reconciliation
  expectation.

Each call receives a newly isolated `HOME`. Common environment keys are exactly
`PATH`, `HOME`, `LC_ALL=C.UTF-8`, `LANG=C.UTF-8`, and `TZ=UTC`. Git additionally
sets `GIT_OPTIONAL_LOCKS=0`, `GIT_NO_LAZY_FETCH=1`,
`GIT_NO_REPLACE_OBJECTS=1`, `GIT_CONFIG_NOSYSTEM=1`,
`GIT_TERMINAL_PROMPT=0`, and empty `GIT_ASKPASS`. GitHub additionally receives
only `GH_HOST=github.com`, `GH_PROMPT_DISABLED=1`, and the designated
`GH_TOKEN`. Caller `GH_*`, `SSH_*`, `GNUPGHOME`, askpass, Git redirection,
alternate-object, and other environment entries are never inherited.
GitHub write refs, title, and body are rejected before preflight if they contain
the designated token, and process output is rejected if it discloses that token.

The optional caller-owned `cancel_event` is passed to #131 unchanged for
preflight, primary execution, reconciliation, and postflight calls. A
pre-cancelled request skips external pre/post observations, invokes the primary
supervisor once to obtain its canonical cancelled result, and never starts the
child. A write cancelled before process start is not reconciled because no
mutation was attempted; cancellation after a possible start retains the
uncertain-write reconciliation rule. A cancellation before postflight can
never retain a `succeeded` outcome. Runtime does not watch or mutate authority.
Agent, validation, mutation,
authorization, migration, and repair calls are never automatically retried.

## Observation documents

The runtime constructs these canonical, exact-key documents without importing
#116:

- `chess-echo-work-type-issue-snapshot-v1`, returned with the exact bounded raw
  GitHub bytes referenced by its `source` field so the caller can publish both;
- `chess-echo-work-type-baseline-v1`; and
- `chess-echo-work-type-diff-observation-v1`.

The baseline requires the caller's previously published issue-snapshot binding
and family ID. The diff requires the caller's published triage binding. Tests
publish the runtime bytes through #132 and pass them through #116's public
`classify` and `assess_completion` APIs.

`observe_issue(issue)` returns `(snapshot_document, raw_source_bytes)`. The
caller publishes the raw bytes as `issue-snapshot`, publishes the document, and
passes that published binding to `build_baseline(...)`.

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
`sandbox`, `outcome`, and `result_sha256`. Candidate output contains only
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

## GitHub write uncertainty

A GitHub write carries an exact non-authoritative expectation containing
repository, base ref/SHA, head ref/SHA, title SHA-256, and body SHA-256.
Successful direct creation needs no reconciliation. Timeout, interruption, or
another non-success never repeats the mutation. One owner-qualified,
URL-encoded `--paginate --slurp` read-only query follows:

- exactly one matching open draft is `succeeded/confirmed`;
- zero matches or query failure is `uncertain/unknown`; and
- multiple matches are a typed `ambiguous` failure.

Runtime compares live values only. It does not dereference evidence to derive
the expectation and does not retry a failed reconciliation query.

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
