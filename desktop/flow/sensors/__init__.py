"""Sensors — capture pipelines that produce indexed Items.

  * ``files``      — extract + chunk + embed documents from a folder.
  * ``trove``      — index image files (optional OCR + CLIP type + embeddings).
  * ``trail_win``  — Windows foreground-window timeline (no-op off Windows).

Each sensor degrades gracefully when its optional dependencies are missing.
"""

from . import files  # noqa: F401

__all__ = ["files"]
