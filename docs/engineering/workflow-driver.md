# Bounded workflow driver

`scripts/workflow_driver.py` is an audit-only, provider-agnostic loop around the
reviewed trusted-local host. It does not implement lifecycle policy. Every
continuation is derived from a fresh, strict
`workflow_orchestrator.py plan-next` result.

The driver invokes only:

```text
/usr/bin/python3 -I CONTROL/scripts/workflow_local_host.py ... plan-next
/usr/bin/python3 -I CONTROL/scripts/workflow_local_host.py ... step
```

It never invokes `init`, `approve`, `set-supervision`, `cancel`, `recover`,
merge, CI, or an end-to-end workflow. It never passes `--request`. When a
pending executable result exists, the next requestless `step` uses the
orchestrator's exact pending-result query and reviewed result-store discovery.
The journal is not read to select or reconstruct work.

## Invocation

```bash
printf '%s\n%s\n' "$GH_TOKEN" "$COPILOT_GITHUB_TOKEN" |
  python3 scripts/workflow_driver.py run ISSUE \
    --control-root "$CONTROL" \
    --repository NathanZK/ChessEcho \
    --git-executable "$GIT" \
    --gh-executable "$GH" \
    --agent-executable "$AGENT" \
    --agent-home "$AGENT_HOME" \
    --result-store "$RESULTS" \
    --workspace "$WORKSPACE" \
    --driver-state "$DRIVER_STATE" \
    --github-token-stdin \
    --trusted-worker-auth-stdin
```

`--max-steps` defaults to 64, `--deadline-seconds` to 14,400, and
`--max-no-progress` to 2. All accept smaller positive operator-selected bounds.
The deadline is checked before every host invocation. An already-dispatched
reviewed host operation is allowed to reach its own bounded result rather than
being interrupted outside the existing supervisor and recovery contracts.

## Stops and outcomes

Only exact `command: step` actions on the driver's fixed automatic allowlist
are dispatched. Human approval and supervision actions stop with exit 2.
Recovery and cancelled-attempt actions stop with exit 3. Exhausted bounds stop
with exit 4. A present issue lock stops with exit 5. Malformed, noncanonical,
inconclusive, stale, busy, or otherwise failed host output stops with exit 1.

On a nonzero host return, the driver accepts only the host's exact canonical
`chess-echo-trusted-local-host-failure-v1` document and required exit code 2.
The failure status must be a reviewed host outcome and its code must be a
bounded safe slug. The driver records those typed fields and the numeric host
return code, but discards the host message and both process streams. Missing,
oversized, malformed, noncanonical, incorrectly typed, or return-code-
inconsistent failure output stops with a distinct driver protocol code and no
untrusted text.

Success (exit 0) requires both exact terminal signals in the same `plan-next`
document: `phase: COMPLETED` and the complete `none-completed` read-only action.
Neither signal alone is accepted.

Issues #115 and #174 are refused before driver state creation or host
invocation.

## Lock and journal

`--driver-state` contains one issue-scoped `O_EXCL` lock and one append-only
JSONL journal. A present lock is never inspected, timed out, or automatically
broken; an operator must investigate it. The lock is removed only by an
orderly driver exit.

Each journal line is one canonical JSON object and is fsynced before the next
operation. Records contain only bounded control metadata such as phase,
action, generation, pointer digest, result code, and counters. Commands,
standard input, host output, handoffs, tokens, prompts, and environment values
are excluded. The journal is audit evidence only and cannot authorize or
resume workflow work. A final driver failure caused by the host similarly
contains only the driver outcome/code plus the bounded host status/code and
host return code; it never copies the host message or raw output.
