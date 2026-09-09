# Workflow Process Supervisor

`scripts/workflow_supervisor.py` is a policy-free trusted mechanism for running
one bounded external command. It is a standard-library-only leaf beside
`workflow_kernel.py`; it does not import the legacy workflow, inspector, repair,
or lifecycle policy.

## API

```python
result = supervise(
    ["command", "argument"],
    timeout_ms=30_000,
    grace_ms=1_000,
    output_limit_bytes=1_048_576,
    stderr_limit_bytes=65_536,
    cwd=None,
    env=None,
    cancel_event=None,
)
```

The command is passed directly to `subprocess.Popen` without a shell. The
absolute execution deadline is armed before that call and is never reset.
Elapsed process creation and selector setup time therefore consumes the same
budget as output handling and normal wait time. POSIX does not provide a
portable way to preempt the process-creation syscall before it returns a PID;
the deadline is enforced immediately when that syscall returns. Limits are
positive exact integers except `grace_ms`, which may be zero. Invalid API
arguments raise `ValueError`; process startup failures are structured results.
`stderr_limit_bytes` is additive and optional. Omitting it preserves the original
API behavior by using `output_limit_bytes` for both streams.

## Retention, consumption, and process lifetime

Without a sink these three are the same quantity: stdout is accumulated into a
bounded buffer, and the first chunk that exceeds `output_limit_bytes` stops the
read loop and terminates the process group. That is correct for commands whose
entire output is the result, and it is unchanged.

It is wrong for a streamed protocol whose authoritative record arrives last,
because unbounded intermediate traffic then evicts the answer and kills a
healthy process. `supervise` therefore accepts an optional `stdout_sink`
callable that receives every stdout chunk in arrival order. With a sink the
caller owns retention: nothing is retained here, `output_limit_bytes` no longer
stops the read loop, and stdout is consumed to EOF under the existing timeout,
cancellation, and signal bounds. A sink can never terminate the supervised
process; it must not raise, and an exception from it surfaces as a supervisor
failure. `stderr` is never sinked and always stops at its own limit.

Every stream is accounted exactly regardless of retention, including bytes read
while a process group is being torn down.

The result format is `chess-echo-process-result-v3`. It contains:

- SHA-256 identity of the canonical command vector and the configured timeout,
  grace, stdout, and stderr limits;
- `outcome` and stable semantic `reason`;
- exit code or terminating signal when available;
- whether forceful termination was required and cleanup of the identified
  process group was verified;
- byte counts and base64 for bounded stdout and stderr, plus `observed_bytes`
  and `observed_sha256` for the complete stream each one was read from;
- a stable exception class for startup or supervisor failures.

`observed_bytes` is never smaller than the retained byte count, and the two are
equal exactly when retention was complete, in which case `observed_sha256` is
the digest of the retained bytes.

It contains no PID, absolute path, timestamp, wall-clock duration, retry
decision, or workflow state. Repeating a command with the same behavior and
output produces the same result.

## Outcomes

| Outcome | Meaning |
|---|---|
| `success` | Process exited zero and its isolated process group is gone |
| `nonzero-exit` | Process exited with a non-zero code |
| `signal` | Process exited because of a signal |
| `timeout` | The execution deadline expired |
| `output-limit` | A retained stream exceeded its independent byte limit; a sinked stdout stream cannot produce this outcome |
| `terminated` | Cancellation occurred or the original process group remained after the parent exited |
| `startup-failure` | The process could not be started |
| `supervisor-failure` | Supervision failed after cleanup was attempted |
| `unsupported` | Safe process-session isolation is unavailable |

The supervisor reports mechanism facts only. Callers decide whether an outcome
is acceptable, retryable, or requires a workflow transition.

## Isolation and cleanup

On POSIX, every command starts with `start_new_session=True`, so its PID is the
new process-group ID. The supervisor only signals that group; it never kills by
name or searches for unrelated processes.

Timeout, cancellation, output overflow, a surviving descendant, and internal
supervisor failure all use the same cleanup sequence:

1. send `SIGTERM` to the isolated process group;
2. wait no longer than `grace_ms`;
3. send `SIGKILL` if the group remains;
4. wait a bounded interval and verify that the group no longer exists;
5. report `cleanup_verified` for that original process group and whether
   escalation occurred.

Before spawning, the main-thread supervisor installs process-wide handlers for
SIGINT, SIGTERM, and SIGHUP. A received signal initiates cleanup; those signals
are then deferred while mandatory cleanup runs and their prior behavior is
restored afterward. Repeated control-flow interruptions during cleanup are
tolerated within bounded signal and wait windows. A pre-existing
`KeyboardInterrupt` or `SystemExit` remains the primary exception and is
re-raised only after cleanup.
Successful or ordinarily failing parents are not reported complete while a
descendant remains in their process group; that group is terminated and the
outcome is `terminated`.

stdout and stderr are read incrementally without reader threads.
`output_limit_bytes` bounds stdout, while `stderr_limit_bytes` bounds stderr.
The budgets are independent, so readiness ordering cannot transfer capacity
from one stream to the other. Omitting the stderr limit gives both streams the
stdout limit for backwards compatibility. Either stream crossing its budget
terminates the group and returns `output-limit`; truncation is never reported
as success. Capture freezes when termination begins; bytes emitted during the
grace period are drained only to prevent pipe blockage and are not retained.

## Platform boundary

The supported enforcement contract requires POSIX `killpg` and new-session
process creation, and `supervise()` must run on the Python main thread so it can
install process-wide signal handlers before spawning. Other platforms and
non-main-thread calls return `unsupported` without starting the command. The
supervisor does not silently fall back to parent-only termination, because that
could leave descendants behind.

Process groups contain ordinary descendants, including grandchildren. A child
can escape by creating another process group or session. The result therefore
states `cleanup_scope: original-process-group`,
`escaped_descendants: not-observable`, and
`descendant_cleanup_verified: false`. `cleanup_verified` never claims more than
termination of the original process group. Callers requiring containment of
group-escaping code must fail closed on `descendant_cleanup_verified: false`
and use an OS facility such as a container or cgroup instead.

## Non-goals

The supervisor is not a scheduler, agent runner, retry engine, validation
policy, lifecycle transition, risk tier, or evidence-invalidation mechanism.
Legacy subprocess callers remain unchanged in #131; later work may adopt this
primitive through separately reviewed adapters.
