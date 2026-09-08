# Trusted-local workflow provider

`scripts/workflow_local_host.py` is the only Phase 1 production entry point for
the replacement orchestrator. It installs the runtime, local execution, and
pending-result providers explicitly; `workflow_orchestrator.py` remains
fail-closed when imported or invoked without those providers.

## Security boundary

Phase 1 trusts the local operator and treats every coding-agent response and
workspace change as untrusted candidate material. The host preserves the
existing authority pointer, expected-tip, immutable evidence, runtime
reconstruction, independent validation, and human-gate contracts.

The local provider is **not an OS sandbox**. It does not isolate the network,
the host filesystem, credentials available to the same operating-system user,
or the authority store from a hostile same-UID process. Its process guarantee
is limited to the bounded POSIX process-group behavior reported by
`workflow_supervisor.py`. Containers, VMs, namespaces, cgroups, Seatbelt,
hostile filesystem isolation, hardened credential isolation, and production
cloud execution remain Phase 2 work in issue #160.

## Reviewed control plane

Run the host only from a clean control checkout whose `HEAD` equals
`refs/remotes/origin/main`. The host must be started with isolated Python mode:

```bash
/usr/bin/python3 -I /path/to/reviewed/ChessEcho/scripts/workflow_local_host.py ...
```

Before importing workflow modules, the host verifies:

- its own source identity and the provider identity against the base-pinned
  `.github/agent-workflow.json`;
- the exact Python and coding-agent executable identities;
- every controller module against the selected control commit;
- a clean control checkout with no source replacement; and
- active Phase 1 configuration.

It then removes the caller cwd from `sys.path` and imports only from the
reviewed control checkout. Candidate `scripts/**`, `.github/**`, `PYTHONPATH`,
and executable-path substitutions therefore cannot become the current or a
fresh process's controller. Missing, changed, malformed, or mismatched inputs
fail closed.

## Dedicated worktree

Choose explicit host paths:

```bash
CONTROL=/path/to/clean/ChessEcho
WORKSPACES="$HOME/.local/state/chess-echo/workspaces"
RESULTS="$HOME/.local/state/chess-echo/results/issue-176"
GIT=/absolute/path/to/git
GH=/absolute/path/to/gh
AGENT=/absolute/path/to/the/pinned/copilot
AGENT_HOME="$HOME/.local/state/chess-echo/worker-home"
```

Create `AGENT_HOME` as an empty operator-owned directory with mode `0700` before
each command that may launch an agent. The provider rejects a populated or
less-restricted directory. Do not point it at the operator's normal home or copy
`.copilot`, `.config/gh`, SSH, signing, workflow, or publication credentials
into it. This prevents accidental ambient CLI fallback; it is not same-UID
hostile-process isolation.

## Trusted local worker authentication

The base-pinned host configuration declares
`trusted-local-development-v1`. In this mode the operator intentionally trusts
the local coding agent with its authentication credential. Agent output and
candidate repository changes remain independently validated, but the worker is
not treated as hostile to the credential and no OS-level credential isolation
is claimed.

Authentication is absent by default. For a `step` that may launch an agent,
pass `--trusted-worker-auth-stdin` and provide the coding-agent credential as
the second standard-input line, after the trusted runtime's GitHub token. The
host reads that line lazily only when the orchestrator requests the agent
provider, then injects it as `COPILOT_GITHUB_TOKEN` into the agent environment.
It never inherits `GH_TOKEN`, `GITHUB_TOKEN`, ambient credentials, or generic
environment values.

The credential value is not placed in argv, prompts, requests, provider facts,
workflow evidence/state, pending-result records, logs, or repository files.
Persistent facts record only the configured mode, trust assumption, and secret
environment key name. Missing mode opt-in or a missing/malformed credential
fails before agent launch. The agent command disables built-in MCPs, marks
`COPILOT_GITHUB_TOKEN` secret, and disables logging.

This is a local-development trust decision, not hostile-process containment.
Phase 2 issue #160 remains responsible for credential-backed execution of an
untrusted worker.

## Copilot JSONL transport decision

Issue #176 exposed two independent Phase 1 transport failures. The initial
prompt could exceed the generic 4 KiB command-part limit, so the reviewed
provider argv received a dedicated 64 KiB prompt bound. After that correction,
the first authenticated canary showed that treating all human-readable stdout
as candidate bytes included intermediate prose before a valid candidate. The
candidate decoder remained strict, and the prompt was hardened to prohibit
intermediate output. A fresh authenticated canary then reproduced the same
shape, proving prompt-only enforcement insufficient.
Issue #183 records the resulting provider-boundary correction.

A credential-free disposable probe could confirm command availability but not
the authenticated event lifecycle, so its result was inconclusive. The
subsequent authenticated disposable probe used Copilot CLI 1.0.80 at SHA-256
`fe779da7dd2342c1d23f0744873fa27d0251eaaee4dc6637fa53093639c0f3c9`.
With `--output-format json`, the pinned executable emitted LF-terminated JSONL.
`--silent` retained the relevant structured records; it changed only the
number of streaming delta records in the observed runs.

The provider distinguishes documented framing from observed schema. JSONL is
the documented transport format. The accepted event allowlist, fields,
relationships, and terminal sequence are empirical behavior of the pinned
1.0.80 executable and must be re-reviewed before changing that pin. The
observed lifecycle contains optional ordered startup and progress records, one
user message, assistant turns, correlated tool requests and executions, and a
root final `assistant.message`. That final message must have nonempty
`data.content` and
`data.toolRequests: []`, and must be completed by a matching
`assistant.turn_end`, ephemeral `assistant.idle`, and terminal `result` with
`exitCode: 0`. `assistant.idle` is distinct from `session.idle`; the latter was
not emitted and is not required. Startup parent records may be absent from
stdout, and ephemeral siblings may share a persisted parent, so validation
checks the observed causal graph rather than imposing a physical-line chain.

The provider reads the complete stdout bytes once, secret-scans those unchanged
bytes, and then strictly validates bounded UTF-8 JSONL. It independently
secret-scans the extracted candidate before returning it. It rejects missing
terminal LF, blank or oversized lines, excess records or bytes, duplicate JSON
keys, malformed JSON, non-object lines, unknown/error/abort/truncation events,
duplicate identifiers, broken parent/turn/interaction relationships,
unresolved tool calls, unsuccessful tools, malformed or nonzero results,
candidate-like nonfinal messages, missing final boundaries, multiple final
candidates, and records after the terminal result. It does not trim, normalize,
or recover output heuristically. In particular, selecting the last parseable
JSON object is prohibited because it can detach candidate selection from the
authenticated event and turn boundaries.

Only the exact UTF-8 encoding of the final message's `data.content` is passed to
the existing strict candidate decoder and schema validation. Final prose is
therefore still rejected downstream. Execution evidence keeps independent
identities for the complete raw transport and extracted candidate: the raw
process-result digest plus transport byte count/SHA-256 attest the JSONL input,
while candidate byte count/SHA-256 attest the decoder input. Persistent
evidence retains the exact raw JSONL bytes in `sandbox.transport_output`. The
persisted `process_result.stdout` contains the extracted candidate so the
unchanged candidate decoder remains byte-exact. To reproduce
`sandbox.process_result_sha256`, copy the persisted `process_result`, replace
its `stdout` value with `sandbox.transport_output`, serialize that reconstructed
process result with the existing canonical JSON encoding, and calculate its
SHA-256 digest. No parsed or normalized transcript participates in that
identity. Authentication remains unchanged and lazy: only
`COPILOT_GITHUB_TOKEN` is injected when the provider executes.

Failed supervised executions use the same immutable execution-result binding.
`sandbox.process_diagnostic` records the supervisor outcome and reason, exit
code or terminating signal, byte count and SHA-256 identity of each stream, the
fixed provider failure classification, and any typed JSONL parser failure.
The diagnostic contains no raw stream bytes or Base64 payloads. Raw and
candidate credential scans still run before this record can be constructed; a
disclosure is scrubbed and rejected instead of persisted.

Persisting the raw transport added a third independently bounded Base64 output
to each trusted-local execution result: adapted candidate stdout, stderr, and
`sandbox.transport_output`. The original preflight budget covered only stdout
and stderr, so the former 512 KiB per-stream limit could exceed the unchanged
2 MiB document limit before metadata. The first correction reduced the raw
limit to 384 KiB and corrected the preflight to account separately for the
complete generated prompt persisted in
`sandbox.command.argv`. Its 64 KiB raw UTF-8 limit can occupy up to 131,074
bytes as a canonical JSON string because quotes, backslashes, and newlines are
escaped and the surrounding JSON quotes add two bytes.

The preflight charges four terms independently: the actual canonical
repository-before observation, 131,074 bytes for the maximum serialized
prompt, three exact Base64 expansions of the configured per-stream limit, and
64 KiB for the remaining fixed result structure.

A controlled planning run on 2026-09-08 then reached the 384 KiB stdout limit
exactly and failed as `output-limit` / `per-stream-output-limit`, with strict
JSONL parsing independently reporting a truncated final record. The limit is
therefore 448 KiB: 64 KiB of deterministic transport headroom over the observed
failure, while remaining inside the same three-output persistence proof. Each
maximal stream expands to 611,672 Base64 bytes, so all three consume 1,835,016
bytes. After the 131,074-byte serialized-prompt reservation and 64 KiB fixed
headroom, the preflight still admits a 65,526-byte canonical repository
observation. Focused tests accept a valid JSONL transport at exactly the new
bound, reject the next byte, serialize the actual result envelope with all
three maximal blobs, a maximum-length quote/backslash/newline-heavy prompt, and
the maximal admitted observation through the production canonicalizer, and
reject either the next Base64 expansion or an additional
repository-observation byte.

Repository-after is observed only after the agent runs and can legitimately be
larger than repository-before, so preflight does not claim to bound that future
growth. Final canonicalization remains authoritative: if repository-after
growth or any other component makes the completed result exceed 2 MiB, the
runtime rejects the entire result with `document-too-large` before persistence.
It does not truncate either output, discard repository-after, or persist a
smaller success-shaped substitute. The lesson is that request-time bounds can
prove only the bounded inputs they charge; post-execution evidence must still
pass the complete document limit as one indivisible record.

Create the deterministic linked worktree:

```bash
/usr/bin/python3 -I "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  prepare-workspace 176 --workspace-parent "$WORKSPACES"
```

The path is
`$WORKSPACES/<repository-sha256-prefix>/issue-176`, and its branch is exactly
`chess-echo-agent/issue-176`. Reuse is accepted only when the provider can
reconstruct that same linked-worktree identity.

## Activation and operation

Activation is the reviewed `mode: active` configuration plus the exact host,
provider, Python, and agent hashes in `.github/agent-workflow.json`. There is no
dynamic provider discovery or environment-selected provider class. The
operator supplies executable paths, but their resolved hashes must equal the
base-pinned identities.

All commands after workspace preparation revalidate the linked-worktree root,
common Git directory, deterministic branch, and separation from the reviewed
checkout. They read the GitHub token as one line from standard input. First
verify bootstrap:

```bash
printf '%s\n' "$GH_TOKEN" | /usr/bin/python3 -I \
  "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  bootstrap 176 --workspace "$WORKSPACE"
```

Use the same prefix for `publish-issue-source`, `init`, `status`, `plan-next`,
`step`, `approve`, `cancel`, and `recover`. `init` and an exact execution
handoff use `--request FILE`; every mutation uses the `pointer_sha256` returned
by `status` as `--expected-tip`.

For a `step` that may launch an agent, add the explicit trusted-worker option
and second input line:

```bash
printf '%s\n%s\n' "$GH_TOKEN" "$COPILOT_GITHUB_TOKEN" | /usr/bin/python3 -I \
  "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  --trusted-worker-auth-stdin \
  step 176 --workspace "$WORKSPACE" --expected-tip "$EXPECTED_TIP"
```

The host calls `workflow_runtime.bootstrap()` only for trusted intake and
`workflow_runtime.reconstruct()` for every selected later command. Agent
commands run in the dedicated worktree through `workflow_supervisor`. The host
projects only the exact request-selected immutable evidence inputs into the
prompt and binds that projection's digest. The fully encoded prompt is capped
at 64 KiB so it remains below the supported host argument budget. Runtime
validation applies that larger bound only to the prompt position in the exact
trusted-local provider argv shape; configured commands and every other command
part retain the generic 4 KiB bound. The execution result also records the exact
executable, argv, cwd, selected commit, authority binding, controlled environment
identity, bounded process result, raw JSONL transport identity, and separately
extracted candidate output identity.
Write phases require the agent to commit all intended changes and leave its
worktree clean; read-only phases explicitly prohibit candidate changes.

Before the immutable execution-result binding is published, the orchestrator
durably records the exact pending-result query, candidate binding, and binding
bytes. A later `step` with no `--request` may complete an interrupted binding
publication and answer that exact query from the bounded index. It never scans
CAS or reruns the operation. Supplying the exact live handoff remains the normal
path and is distinct from restart rediscovery.

## Canary

Use a disposable issue, never #115, #174, or production work. The bounded
sequence is:

```text
prepare-workspace
  -> bootstrap
  -> publish-issue-source
  -> construct the canonical init request with that publication
  -> init
  -> status / plan-next
  -> step with the exact expected tip
  -> persist the returned handoff
  -> step with that exact handoff (or, in a fresh host process, no handoff)
  -> stop at the next normal review or human gate
```

Stop immediately on any identity, reconstruction, authority, provider,
workspace, process, candidate, or gate failure. Do not substitute a fixture,
change the selected base, infer approval, or bypass the failed guard.
