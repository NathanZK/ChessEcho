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
AGENT_HOME="$HOME"
```

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

The host calls `workflow_runtime.bootstrap()` only for trusted intake and
`workflow_runtime.reconstruct()` for every selected later command. Agent
commands run in the dedicated worktree through `workflow_supervisor`. The host
projects only the exact request-selected immutable evidence inputs into the
prompt and binds that projection's digest. The fully encoded prompt is capped
at 64 KiB so it remains below the supported host argument budget. The execution result also records
the exact executable, argv, cwd, selected commit, authority binding, controlled
environment identity, bounded process result, and candidate output identity.
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
