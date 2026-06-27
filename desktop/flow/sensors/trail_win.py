"""Trail (Windows) — foreground-window activity timeline (§3.3).

Captures which app/document was in the foreground over time using pywin32:
either the ``EVENT_SYSTEM_FOREGROUND`` WinEvent hook, or a simple polling loop
over ``GetForegroundWindow`` as a robust fallback. Each coalesced activity
becomes an ``Item{source:trail, type:activity, app_context, text, ts}``.

Privacy: window titles that look like password / login / OTP contexts are
excluded (never captured). No keyboard hooks are used (AV-flag risk, spec §3.3).

On non-Windows platforms (or without pywin32) the runner is a no-op that logs
a warning, so importing this module never breaks the engine.
"""

from __future__ import annotations

import sys
import threading
import time
import warnings
from typing import Optional

from .. import config
from ..index import Index
from ..protocol import Item, new_id

_IS_WINDOWS = sys.platform.startswith("win")

try:
    import win32gui  # type: ignore
    import win32process  # type: ignore
    _PYWIN32 = True
except Exception:
    _PYWIN32 = False

# Title substrings that indicate a sensitive context we must not capture.
_EXCLUDE = ("password", "sign in", "log in", "login", "otp", "passcode",
            "authenticator", "credential", "unlock", "2fa", "verification code")


def _is_sensitive(title: str) -> bool:
    t = (title or "").lower()
    return any(tok in t for tok in _EXCLUDE)


def _proc_name(hwnd) -> str:
    try:
        import os
        import psutil  # optional; fall back to pid if absent
    except Exception:
        psutil = None  # type: ignore
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        if psutil is not None:
            return psutil.Process(pid).name()
        return f"pid:{pid}"
    except Exception:
        return ""


class WindowsTrail:
    """Polling foreground-window timeline. Coalesces consecutive same-window samples."""

    def __init__(self, index: Index, device_id: Optional[str] = None,
                 poll_interval: float = 1.0) -> None:
        self.index = index
        self.device_id = device_id or config.DEVICE_ID or "flow-desktop"
        self.poll_interval = poll_interval
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._last_title: Optional[str] = None

    def available(self) -> bool:
        return _IS_WINDOWS and _PYWIN32

    def start(self) -> bool:
        if not self.available():
            warnings.warn("WindowsTrail unavailable (needs Windows + pywin32); no-op.")
            return False
        if self._thread and self._thread.is_alive():
            return True
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return True

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2.0)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self._sample_once()
            except Exception:
                pass
            self._stop.wait(self.poll_interval)

    def _sample_once(self) -> None:
        hwnd = win32gui.GetForegroundWindow()
        if not hwnd:
            return
        title = win32gui.GetWindowText(hwnd) or ""
        if not title or title == self._last_title:
            return
        self._last_title = title
        if _is_sensitive(title):
            return  # never capture sensitive contexts

        proc = _proc_name(hwnd)
        app_context = f"{proc} / {title}" if proc else title
        item = Item(
            id=new_id(), device_id=self.device_id, source="trail",
            ts=int(time.time()), app_context=app_context,
            text=f"Used {proc or 'app'}: {title}", type="activity", fields={},
        )
        # Embed lazily here to keep the import light.
        from .. import embeddings
        vec = embeddings.embed_text([item.text])
        self.index.ingest(item, text_vec=(vec[0] if vec.shape[0] else None))


def run(index: Index, device_id: Optional[str] = None, duration_s: Optional[float] = None) -> bool:
    """Convenience runner used by the CLI. Blocks for ``duration_s`` (or forever)."""
    trail = WindowsTrail(index, device_id=device_id)
    if not trail.start():
        return False
    try:
        if duration_s is None:
            while True:
                time.sleep(1.0)
        else:
            time.sleep(duration_s)
    except KeyboardInterrupt:
        pass
    finally:
        trail.stop()
    return True
