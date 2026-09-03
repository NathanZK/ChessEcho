#!/usr/bin/env python3
"""Bounded, policy-free supervision for one external process session."""

import base64
import hashlib
import json
import os
import selectors
import signal
import subprocess
import threading
import time
from contextlib import contextmanager


RESULT_FORMAT = "chess-echo-process-result-v1"
READ_SIZE = 64 * 1024
POLL_INTERVAL_SECONDS = 0.01


class _ProcessGroupOwnership:
    def __init__(self, process_group):
        self.process_group = process_group
        self.released = False


def _command_sha256(command):
    data = json.dumps(
        command, ensure_ascii=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _result(
    command,
    timeout_ms,
    grace_ms,
    output_limit_bytes,
    outcome,
    reason,
    stdout=b"",
    stderr=b"",
    exit_code=None,
    terminating_signal=None,
    forced_termination=False,
    cleanup_verified=True,
    supervisor_error=None,
    containment_kind="posix-process-group",
):
    containment = (
        {
            "kind": "posix-process-group",
            "cleanup_scope": "original-process-group",
            "escaped_descendants": "not-observable",
            "descendant_cleanup_verified": False,
        }
        if containment_kind == "posix-process-group"
        else {
            "kind": "none",
            "cleanup_scope": "none",
            "escaped_descendants": "not-applicable",
            "descendant_cleanup_verified": False,
        }
    )
    result = {
        "format": RESULT_FORMAT,
        "command_sha256": _command_sha256(command),
        "limits": {
            "timeout_ms": timeout_ms,
            "grace_ms": grace_ms,
            "output_bytes_per_stream": output_limit_bytes,
        },
        "containment": containment,
        "outcome": outcome,
        "reason": reason,
        "exit_code": exit_code,
        "terminating_signal": terminating_signal,
        "forced_termination": forced_termination,
        "cleanup_verified": cleanup_verified,
        "stdout": {
            "bytes": len(stdout),
            "base64": base64.b64encode(stdout).decode("ascii"),
        },
        "stderr": {
            "bytes": len(stderr),
            "base64": base64.b64encode(stderr).decode("ascii"),
        },
        "supervisor_error": supervisor_error,
    }
    return result


def _validate(command, timeout_ms, grace_ms, output_limit_bytes):
    if (
        not isinstance(command, (list, tuple))
        or not command
        or any(not isinstance(part, str) or not part for part in command)
    ):
        raise ValueError("command must be a non-empty sequence of strings")
    for name, value, allow_zero in (
        ("timeout_ms", timeout_ms, False),
        ("grace_ms", grace_ms, True),
        ("output_limit_bytes", output_limit_bytes, False),
    ):
        if type(value) is not int or value < (0 if allow_zero else 1):
            raise ValueError("%s is outside its supported range" % name)


def _group_exists(process_group):
    try:
        os.killpg(process_group, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _signal_group(process_group, signal_number):
    try:
        os.killpg(process_group, signal_number)
        return True
    except ProcessLookupError:
        return False


def _signal_group_with_retries(ownership, signal_number, deadline):
    if ownership.released:
        return "gone"
    while time.monotonic() < deadline:
        try:
            if _signal_group(ownership.process_group, signal_number):
                return "sent"
            ownership.released = True
            return "gone"
        except BaseException:
            continue
    return "failed"


def _read_ready(selector, streams, output_limit_bytes):
    exceeded = False
    for key, _ in selector.select(timeout=0):
        try:
            chunk = os.read(key.fd, READ_SIZE)
        except BlockingIOError:
            continue
        if not chunk:
            selector.unregister(key.fileobj)
            key.fileobj.close()
            continue
        available = max(0, output_limit_bytes - len(streams[key.data]))
        streams[key.data].extend(chunk[:available])
        if len(chunk) > available:
            exceeded = True
    return exceeded


def _drain_ready(selector):
    for key, _ in selector.select(timeout=0):
        try:
            chunk = os.read(key.fd, READ_SIZE)
        except BlockingIOError:
            continue
        if not chunk:
            selector.unregister(key.fileobj)
            key.fileobj.close()


@contextmanager
def _defer_cleanup_signals():
    if not hasattr(signal, "pthread_sigmask"):
        yield
        return
    blocked = {
        value
        for value in (
            getattr(signal, "SIGINT", None),
            getattr(signal, "SIGTERM", None),
            getattr(signal, "SIGHUP", None),
        )
        if value is not None
    }
    previous = signal.pthread_sigmask(signal.SIG_BLOCK, blocked)
    try:
        yield
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous)


def _wait_for_group_exit(process, ownership, selector, deadline):
    if ownership.released:
        return True
    while time.monotonic() < deadline:
        try:
            if not _group_exists(ownership.process_group):
                ownership.released = True
                return True
        except BaseException:
            continue
        try:
            if selector is not None:
                _drain_ready(selector)
        except BaseException:
            pass
        try:
            process.poll()
        except BaseException:
            pass
        try:
            time.sleep(POLL_INTERVAL_SECONDS)
        except BaseException:
            pass
    try:
        process.poll()
        if not _group_exists(ownership.process_group):
            ownership.released = True
            return True
        return False
    except BaseException:
        return False


def _terminate(
    process,
    ownership,
    selector,
    streams,
    grace_ms,
):
    forced = False
    cleanup_verified = ownership.released
    try:
        with _defer_cleanup_signals():
            signal_window = max(grace_ms / 1000, POLL_INTERVAL_SECONDS * 5)
            term_status = _signal_group_with_retries(
                ownership, signal.SIGTERM, time.monotonic() + signal_window
            )
            if term_status == "gone":
                cleanup_verified = True
            else:
                graceful_deadline = time.monotonic() + (grace_ms / 1000)
                cleanup_verified = _wait_for_group_exit(
                    process, ownership, selector, graceful_deadline
                )
            if not ownership.released:
                kill_deadline = time.monotonic() + max(
                    grace_ms / 1000, POLL_INTERVAL_SECONDS * 5
                )
                kill_status = _signal_group_with_retries(
                    ownership, signal.SIGKILL, kill_deadline
                )
                forced = kill_status == "sent"
                if kill_status == "gone":
                    cleanup_verified = True
                else:
                    cleanup_verified = _wait_for_group_exit(
                        process, ownership, selector, kill_deadline
                    )
            try:
                process.wait(
                    timeout=max(
                        grace_ms / 1000, POLL_INTERVAL_SECONDS * 5
                    )
                )
            except BaseException:
                cleanup_verified = False
    except BaseException:
        cleanup_verified = ownership.released
    return forced, cleanup_verified


def _close_streams(selector, process):
    if selector is not None:
        try:
            keys = list(selector.get_map().values())
        except BaseException:
            keys = []
        for key in keys:
            try:
                selector.unregister(key.fileobj)
            except BaseException:
                pass
        try:
            selector.close()
        except BaseException:
            pass
    for stream in (process.stdout, process.stderr):
        try:
            stream.close()
        except BaseException:
            pass


def _capture_external_signals():
    received = []
    previous = {}

    def capture(signal_number, frame):
        received.append(signal_number)

    try:
        for signal_number in (
            getattr(signal, "SIGINT", None),
            getattr(signal, "SIGTERM", None),
            getattr(signal, "SIGHUP", None),
        ):
            if signal_number is None:
                continue
            handler = signal.getsignal(signal_number)
            if handler == signal.SIG_IGN:
                continue
            previous[signal_number] = handler
            signal.signal(signal_number, capture)
    except BaseException:
        _restore_external_signals(previous)
        raise
    return received, previous


def _restore_external_signals(previous):
    first_error = None
    for signal_number, handler in previous.items():
        try:
            signal.signal(signal_number, handler)
        except BaseException as error:
            if first_error is None:
                first_error = error
    return first_error


def _redeliver_external_signals(signal_numbers):
    pending = tuple(dict.fromkeys(signal_numbers))
    if not pending:
        return
    if hasattr(signal, "pthread_sigmask"):
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, set(pending))
        try:
            for signal_number in pending:
                os.kill(os.getpid(), signal_number)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        return
    for signal_number in pending:
        os.kill(os.getpid(), signal_number)


def _record_secondary_signals(error, signal_numbers):
    try:
        error.workflow_supervisor_signals = tuple(
            dict.fromkeys(signal_numbers)
        )
    except BaseException:
        pass


def _supervise_posix(
    command,
    *,
    timeout_ms,
    grace_ms,
    output_limit_bytes,
    cwd=None,
    env=None,
    cancel_event=None,
    deadline,
    external_signals,
):
    if external_signals:
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "terminated",
            "external-signal-before-start",
            containment_kind="none",
        )
    if cancel_event is not None and cancel_event.is_set():
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "terminated",
            "cancelled-before-start",
            containment_kind="none",
        )
    if time.monotonic() >= deadline:
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "timeout",
            "execution-timeout-before-start",
            containment_kind="none",
        )
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
    except OSError as error:
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "startup-failure",
            "process-not-started",
            supervisor_error=type(error).__name__,
            containment_kind="none",
        )

    ownership = _ProcessGroupOwnership(process.pid)
    streams = {"stdout": bytearray(), "stderr": bytearray()}
    selector = None
    setup_timed_out = False
    setup_interrupted = bool(external_signals)
    if time.monotonic() >= deadline and not setup_interrupted:
        setup_timed_out = True
    try:
        if not setup_timed_out and not setup_interrupted:
            selector = selectors.DefaultSelector()
            setup_interrupted = bool(external_signals)
            if time.monotonic() >= deadline and not setup_interrupted:
                setup_timed_out = True
            if not setup_timed_out and not setup_interrupted:
                selector.register(
                    process.stdout, selectors.EVENT_READ, "stdout"
                )
                setup_interrupted = bool(external_signals)
                if time.monotonic() >= deadline and not setup_interrupted:
                    setup_timed_out = True
            if not setup_timed_out and not setup_interrupted:
                selector.register(
                    process.stderr, selectors.EVENT_READ, "stderr"
                )
                setup_interrupted = bool(external_signals)
                if time.monotonic() >= deadline and not setup_interrupted:
                    setup_timed_out = True
    except BaseException as error:
        try:
            forced, cleanup_verified = _terminate(
                process, ownership, selector, streams, grace_ms
            )
        finally:
            _close_streams(selector, process)
        if not isinstance(error, Exception):
            raise
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "supervisor-failure",
            "supervision-setup-error",
            forced_termination=forced,
            cleanup_verified=cleanup_verified,
            supervisor_error=type(error).__name__,
        )
    if setup_interrupted:
        forced, cleanup_verified = _terminate(
            process, ownership, selector, streams, grace_ms
        )
        _close_streams(selector, process)
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "terminated",
            "external-signal",
            forced_termination=forced,
            cleanup_verified=cleanup_verified,
        )
    if setup_timed_out:
        forced, cleanup_verified = _terminate(
            process, ownership, selector, streams, grace_ms
        )
        _close_streams(selector, process)
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "timeout",
            "execution-timeout",
            exit_code=(
                process.returncode
                if process.returncode is not None and process.returncode >= 0
                else None
            ),
            terminating_signal=(
                -process.returncode
                if process.returncode is not None and process.returncode < 0
                else None
            ),
            forced_termination=forced,
            cleanup_verified=cleanup_verified,
        )
    stop_reason = None
    forced = False
    cleanup_verified = True
    supervisor_error = None
    try:
        while True:
            if external_signals:
                stop_reason = "external-signal"
                break
            if cancel_event is not None and cancel_event.is_set():
                stop_reason = "cancelled"
                break
            now = time.monotonic()
            if now >= deadline:
                stop_reason = "timeout"
                break
            events = selector.select(
                timeout=min(POLL_INTERVAL_SECONDS, deadline - now)
            )
            for key, _ in events:
                try:
                    chunk = os.read(key.fd, READ_SIZE)
                except BlockingIOError:
                    continue
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                available = max(
                    0, output_limit_bytes - len(streams[key.data])
                )
                streams[key.data].extend(chunk[:available])
                if len(chunk) > available:
                    stop_reason = "output-limit"
            if stop_reason is not None:
                break
            return_code = process.poll()
            if return_code is not None:
                if _group_exists(ownership.process_group):
                    stop_reason = "descendants-remained"
                    break
                ownership.released = True
                if not selector.get_map():
                    break
        if stop_reason is not None:
            return_code = process.poll()
            if (
                return_code is not None
                and not ownership.released
                and not _group_exists(ownership.process_group)
            ):
                ownership.released = True
            if ownership.released:
                cleanup_verified = True
            else:
                forced, cleanup_verified = _terminate(
                    process, ownership, selector, streams, grace_ms
                )
    except BaseException as error:
        if ownership.released:
            forced, cleanup_verified = False, True
        else:
            forced, cleanup_verified = _terminate(
                process, ownership, selector, streams, grace_ms
            )
        if not isinstance(error, Exception):
            raise
        stop_reason = "supervisor-failure"
        supervisor_error = type(error).__name__
    finally:
        _close_streams(selector, process)

    stdout = bytes(streams["stdout"])
    stderr = bytes(streams["stderr"])
    return_code = process.poll()
    if stop_reason == "timeout":
        outcome, reason = "timeout", "execution-timeout"
    elif stop_reason == "output-limit":
        outcome, reason = "output-limit", "per-stream-output-limit"
    elif stop_reason == "descendants-remained":
        outcome, reason = "terminated", "process-group-remained"
    elif stop_reason == "cancelled":
        outcome, reason = "terminated", "cancelled"
    elif stop_reason == "external-signal":
        outcome, reason = "terminated", "external-signal"
    elif stop_reason == "supervisor-failure":
        outcome, reason = "supervisor-failure", "supervision-error"
    elif return_code is not None and return_code < 0:
        outcome, reason = "signal", "process-signaled"
    elif return_code == 0:
        outcome, reason = "success", "process-exited"
    else:
        outcome, reason = "nonzero-exit", "process-exited"
    return _result(
        command,
        timeout_ms,
        grace_ms,
        output_limit_bytes,
        outcome,
        reason,
        stdout=stdout,
        stderr=stderr,
        exit_code=return_code if return_code is not None and return_code >= 0 else None,
        terminating_signal=-return_code if return_code is not None and return_code < 0 else None,
        forced_termination=forced,
        cleanup_verified=cleanup_verified,
        supervisor_error=supervisor_error,
    )


def supervise(
    command,
    *,
    timeout_ms,
    grace_ms,
    output_limit_bytes,
    cwd=None,
    env=None,
    cancel_event=None,
):
    """Execute one command in an isolated session and return a structured result."""
    _validate(command, timeout_ms, grace_ms, output_limit_bytes)
    command = list(command)
    deadline = time.monotonic() + (timeout_ms / 1000)
    if os.name != "posix" or not hasattr(os, "killpg"):
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "unsupported",
            "process-session-isolation-unavailable",
            containment_kind="none",
        )
    if threading.current_thread() is not threading.main_thread():
        return _result(
            command,
            timeout_ms,
            grace_ms,
            output_limit_bytes,
            "unsupported",
            "process-wide-signal-guard-unavailable",
            containment_kind="none",
        )
    received, previous = _capture_external_signals()
    primary_error = None
    restoration_error = None
    try:
        result = _supervise_posix(
            command,
            timeout_ms=timeout_ms,
            grace_ms=grace_ms,
            output_limit_bytes=output_limit_bytes,
            cwd=cwd,
            env=env,
            cancel_event=cancel_event,
            deadline=deadline,
            external_signals=received,
        )
    except BaseException as error:
        primary_error = error
    finally:
        try:
            restoration_error = _restore_external_signals(previous)
        except BaseException as error:
            restoration_error = error
    if primary_error is not None:
        _record_secondary_signals(primary_error, received)
        raise primary_error
    if restoration_error is not None:
        _record_secondary_signals(restoration_error, received)
        raise restoration_error
    if received:
        _redeliver_external_signals(received)
    return result
