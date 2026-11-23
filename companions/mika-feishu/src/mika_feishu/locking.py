"""One event consumer per configuration, using OS locks released on process exit."""
from contextlib import contextmanager
import errno
import os


# A lock request that failed because another process holds it: fcntl reports EACCES or
# EWOULDBLOCK, and msvcrt.locking reports EACCES or EDEADLK.
CONTENDED = {errno.EACCES, errno.EAGAIN, errno.EWOULDBLOCK, errno.EDEADLK}


@contextmanager
def instance_lock(path):
    with open(path, "a+b") as stream:
        os.chmod(path, 0o600)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            if exc.errno in CONTENDED:
                raise ValueError("Another connector or setup process is using this config") from None
            # The lock itself failed, so no other connector is involved: ENOLCK and EOPNOTSUPP come
            # from filesystems without lock support, e.g. some network and container mounts.
            raise ValueError("Cannot lock this configuration ("
                             + (exc.strerror or "os error " + str(exc.errno))
                             + "); check that this directory is on a local filesystem that supports locking") from None
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)
