#!/usr/bin/env python3
"""Build Orthanc DicomModalities from allowlist.json (local source of truth)."""
from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    allowlist_path = Path(sys.argv[1] if len(sys.argv) > 1 else "allowlist.json")
    out_path = Path(sys.argv[2] if len(sys.argv) > 2 else "orthanc/orthanc.json")
    base = {
        "Name": "TeleRad Edge Orthanc",
        "DicomAet": "TELERAD_EDGE",
        "DicomPort": 4242,
        "HttpPort": 8042,
        "RemoteAccessAllowed": False,
        "AuthenticationEnabled": True,
        "RegisteredUsers": {"agent": "agent"},
        "DicomAlwaysAllowEcho": True,
        "DicomAlwaysAllowStore": False,
        "DicomCheckModalityHost": True,
        "StableAge": 30,
        "StrictAetComparison": False,
    }
    data = json.loads(allowlist_path.read_text())
    senders = data.get("senders", data if isinstance(data, list) else [])
    modalities = {}
    for i, s in enumerate(senders):
        ae = str(s["ae"]).strip()
        ip = str(s["ip"]).strip()
        if not ae or not ip:
            raise SystemExit(f"Invalid sender entry: {s}")
        key = f"{ae}_{i}".replace(" ", "_")
        modalities[key] = [ae, ip, 11112]
    base["DicomModalities"] = modalities
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(base, indent=2) + "\n")
    print(f"Wrote {out_path} with {len(modalities)} modalities")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
