"""Pick the memory backend for the current OS."""
import sys


def open_process(pid: int):
    if sys.platform == "win32":
        from .windows import WindowsProcess
        return WindowsProcess(pid)
    if sys.platform.startswith("linux"):
        from .linux import LinuxProcess
        return LinuxProcess(pid)
    raise NotImplementedError(f"no backend for {sys.platform}")


def enable_debug_privilege() -> bool:
    if sys.platform == "win32":
        from .windows import enable_debug_privilege as _e
        return _e()
    return False
