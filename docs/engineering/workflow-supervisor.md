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

The result format is `chess-echo-process-result-v1`. It contains:

- SHA-256 identity of the canonical command vector and the configured limits;
- `outcome` and stable semantic `reason`;
- exit code or terminating signal when available;
- whether forceful termination was required and cleanup of the identified
  process group was verified;
- byte counts and base64 for bounded stdout and stderr;
- a stable exception class for startup or supervisor failures.

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
| `output-limit` | stdout or stderr exceeded its independent byte limit |
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
`output_limit_bytes` is an independent budget for each stream, so readiness
ordering cannot transfer capacity from one stream to the other. Each retained
stream is at most that size. Either stream crossing its budget terminates the
group and returns `output-limit`; truncation is never reported as success.
Capture freezes when termination begins; bytes emitted during the grace period
are drained only to prevent pipe blockage and are not retained.

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
