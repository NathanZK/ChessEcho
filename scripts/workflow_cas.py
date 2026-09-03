#!/usr/bin/env python3
"""Atomic immutable-object publication for the workflow content-addressed store."""

import errno
import os
import stat
import threading


def fsync_directory(path):
    descriptor = os.open(str(path), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_all(descriptor, data):
    view = memoryview(data)
    written = 0
    while written < len(data):
        count = os.write(descriptor, view[written:])
        if count < 1:
            raise OSError(errno.EIO, "short write")
        written += count


def ensure_directory(path, fail):
    try:
        info = path.lstat()
    except FileNotFoundError:
        ensure_directory(path.parent, fail)
        try:
            os.mkdir(str(path), 0o700)
            fsync_directory(path.parent)
        except FileExistsError:
            pass
        info = path.lstat()
    if not stat.S_ISDIR(info.st_mode):
        fail("conflict", "unsafe-directory", "Repair store path is not a directory")


def verify_existing(path, data, fail):
    try:
        initial = os.lstat(str(path))
    except FileNotFoundError:
        return False
    except OSError:
        fail(
            "conflict",
            "immutable-destination-unreadable",
            "Immutable destination is unreadable",
        )
    if not stat.S_ISREG(initial.st_mode):
        fail(
            "conflict",
            "immutable-destination-not-regular",
            "Immutable destination is not regular",
        )
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(str(path), flags)
    except FileNotFoundError:
        fail(
            "conflict",
            "immutable-destination-changed",
            "Immutable destination changed during verification",
        )
    except OSError as error:
        if error.errno in (errno.ELOOP, errno.ENOTDIR):
            fail(
                "conflict",
                "immutable-destination-not-regular",
                "Immutable destination is not regular",
            )
        fail(
            "conflict",
            "immutable-destination-unreadable",
            "Immutable destination is unreadable",
        )
    try:
        info = os.fstat(descriptor)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_dev != initial.st_dev
            or info.st_ino != initial.st_ino
        ):
            fail(
                "conflict",
                "immutable-destination-changed",
                "Immutable destination changed during verification",
            )
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        existing = b"".join(chunks)
    except OSError:
        fail(
            "conflict",
            "immutable-destination-unreadable",
            "Immutable destination is unreadable",
        )
    finally:
        os.close(descriptor)
    try:
        current = os.lstat(str(path))
    except OSError:
        fail(
            "conflict",
            "immutable-destination-changed",
            "Immutable destination changed during verification",
        )
    if (
        not stat.S_ISREG(current.st_mode)
        or current.st_dev != info.st_dev
        or current.st_ino != info.st_ino
    ):
        fail(
            "conflict",
            "immutable-destination-changed",
            "Immutable destination changed during verification",
        )
    if len(existing) != len(data) or _sha256(existing) != _sha256(data):
        fail(
            "conflict",
            "immutable-object-collision",
            "Immutable destination has conflicting bytes",
        )
    return True


def publish_immutable(
    path, data, fail, temporary_label="repair", legacy_temporary_name=False
):
    ensure_directory(path.parent, fail)
    if verify_existing(path, data, fail):
        return
    if legacy_temporary_name:
        suffix = ".%s-%s-%s" % (temporary_label, os.getpid(), id(data))
    else:
        suffix = ".%s-%s-%s-%s" % (
            temporary_label,
            os.getpid(),
            threading.get_ident(),
            id(data),
        )
    temporary = path.parent / (".%s%s" % (path.name, suffix))
    descriptor = None
    try:
        descriptor = os.open(
            str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
        )
        write_all(descriptor, data)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        try:
            os.link(str(temporary), str(path))
            fsync_directory(path.parent)
        except FileExistsError:
            verify_existing(path, data, fail)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
            fsync_directory(path.parent)
        except FileNotFoundError:
            pass


def _sha256(data):
    import hashlib

    return hashlib.sha256(data).hexdigest()
