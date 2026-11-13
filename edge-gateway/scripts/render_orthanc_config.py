#!/usr/bin/env python3
"""Write secrets/orthanc.json (the Orthanc configuration) for the hospital server.

  python3 scripts/render_orthanc_config.py allowlist.json secrets/orthanc.json \
      [--agent-password-file secrets/orthanc_agent_password] \
      [--emergency-password-file secrets/emergency_viewer_password]

- DicomModalities come from allowlist.json (the modalities allowed to send).
- The agent's Orthanc password comes from a secrets file (never committed). Without one, the
  development password "agent" is used and a warning is printed.
- With --emergency-password-file, the emergency viewer is switched on: a second Orthanc user
  "emergency" can open Orthanc Explorer 2 / the OHIF viewer on the hospital network during an
  internet outage (also publish port 8042 with docker-compose.emergency.yml). Without it, the
  user does not exist and the HTTP port stays internal.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def read_secret(path: str | None) -> str | None:
    if not path:
        return None
    value = Path(path).read_text().strip()
    if len(value) < 12:
        raise SystemExit(f"{path}: use at least 12 characters")
    return value


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("allowlist", nargs="?", default="allowlist.json")
    p.add_argument("out", nargs="?", default="secrets/orthanc.json")
    p.add_argument("--agent-password-file")
    p.add_argument("--emergency-password-file")
    args = p.parse_args()

    agent_password = read_secret(args.agent_password_file)
    if agent_password is None:
        print("WARNING: no --agent-password-file; using the development password", file=sys.stderr)
        agent_password = "agent"
    emergency_password = read_secret(args.emergency_password_file)

    users = {"agent": agent_password}
    if emergency_password:
        users["emergency"] = emergency_password

    base = {
        "Name": "TeleRad Edge Orthanc",
        "DicomAet": "TELERAD_EDGE",
        "DicomPort": 4242,
        "HttpPort": 8042,
        # Containers reach Orthanc over the compose network, which is not "localhost" to Orthanc.
        # The HTTP port is only published to the LAN in emergency mode; a password is always required.
        "RemoteAccessAllowed": True,
        "AuthenticationEnabled": True,
        "RegisteredUsers": users,
        "DicomAlwaysAllowEcho": True,
        "DicomAlwaysAllowStore": False,
        "DicomCheckModalityHost": True,
        "StableAge": 30,
        "StrictAetComparison": False,
        "HttpVerbose": False,
        "DicomVerbose": False,
    }
    data = json.loads(Path(args.allowlist).read_text())
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
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(base, indent=2) + "\n")
    out_path.chmod(0o640)
    print(f"Wrote {out_path} with {len(modalities)} modalities"
          + ("; emergency viewer user ON" if emergency_password else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
