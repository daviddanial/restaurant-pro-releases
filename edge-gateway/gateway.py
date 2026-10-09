"""Deprecated: use agent.py (Orthanc + TeleRad uploader)."""
from __future__ import annotations

import runpy
import sys
from pathlib import Path

print("edge-gateway/gateway.py is deprecated; forwarding to agent.py", file=sys.stderr)
sys.argv[0] = str(Path(__file__).with_name("agent.py"))
runpy.run_path(str(Path(__file__).with_name("agent.py")), run_name="__main__")
