"""Make the ``flow`` package importable when running tests without installing.

Adds the parent ``desktop/`` directory (which contains the ``flow`` package)
to ``sys.path`` so ``pytest`` works straight from a checkout. Also points the
default DB at a temp location so tests never touch ``~/.flow``.
"""

import os
import sys
import tempfile
from pathlib import Path

_DESKTOP = Path(__file__).resolve().parents[1]
if str(_DESKTOP) not in sys.path:
    sys.path.insert(0, str(_DESKTOP))

# Isolate the default DB used by any code that reads config.DB_PATH.
os.environ.setdefault("FLOW_DB", str(Path(tempfile.gettempdir()) / "flow-test.db"))
