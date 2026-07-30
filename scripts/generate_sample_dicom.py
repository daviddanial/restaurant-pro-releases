#!/usr/bin/env python3
"""Generate a minimal valid Explicit VR LE DICOM CT-like file for upload testing."""
from __future__ import annotations
import argparse
import struct
import uuid
from pathlib import Path


def uid() -> str:
    return "2.25." + str(uuid.uuid4().int)


def encode_element(tag: tuple[int, int], vr: str, value: bytes) -> bytes:
    group, element = tag
    if vr in {"OB", "OW", "OF", "SQ", "UT", "UN"}:
        if len(value) % 2 == 1:
            value += b"\x00"
        return struct.pack("<HH", group, element) + vr.encode("ascii") + b"\x00\x00" + struct.pack("<I", len(value)) + value
    if len(value) % 2 == 1:
        value += b"\x00"
    return struct.pack("<HH", group, element) + vr.encode("ascii") + struct.pack("<H", len(value)) + value


def encode_str(tag: tuple[int, int], vr: str, text: str) -> bytes:
    raw = text.encode("ascii")
    if len(raw) % 2 == 1:
        raw += b" "
    return encode_element(tag, vr, raw)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", "--output", default="sample.dcm")
    parser.add_argument("--patient", default="DOE^JOHN")
    parser.add_argument("--modality", default="CT")
    args = parser.parse_args()

    study_uid = uid()
    series_uid = uid()
    sop_uid = uid()
    rows, cols = 64, 64
    pixels = bytes((i * 17) % 256 for i in range(rows * cols))

    ds = b""
    ds += encode_str((0x0008, 0x0016), "UI", "1.2.840.10008.5.1.4.1.1.2")
    ds += encode_str((0x0008, 0x0018), "UI", sop_uid)
    ds += encode_str((0x0008, 0x0060), "CS", args.modality)
    ds += encode_str((0x0008, 0x1030), "LO", "TeleRad Sample Study")
    ds += encode_str((0x0008, 0x0050), "SH", "ACC1001")
    ds += encode_str((0x0010, 0x0010), "PN", args.patient)
    ds += encode_str((0x0010, 0x0020), "LO", "MRN001")
    ds += encode_str((0x0010, 0x0040), "CS", "M")
    ds += encode_str((0x0020, 0x000D), "UI", study_uid)
    ds += encode_str((0x0020, 0x000E), "UI", series_uid)
    ds += encode_str((0x0020, 0x0011), "IS", "1")
    ds += encode_str((0x0020, 0x0013), "IS", "1")
    ds += encode_element((0x0028, 0x0002), "US", struct.pack("<H", 1))
    ds += encode_str((0x0028, 0x0004), "CS", "MONOCHROME2")
    ds += encode_element((0x0028, 0x0010), "US", struct.pack("<H", rows))
    ds += encode_element((0x0028, 0x0011), "US", struct.pack("<H", cols))
    ds += encode_element((0x0028, 0x0100), "US", struct.pack("<H", 8))
    ds += encode_element((0x0028, 0x0101), "US", struct.pack("<H", 8))
    ds += encode_element((0x0028, 0x0102), "US", struct.pack("<H", 7))
    ds += encode_element((0x0028, 0x0103), "US", struct.pack("<H", 0))
    ds += encode_element((0x7FE0, 0x0010), "OB", pixels)

    preamble = b"\x00" * 128 + b"DICM"
    meta_body = b""
    meta_body += encode_element((0x0002, 0x0001), "OB", b"\x00\x01")
    meta_body += encode_str((0x0002, 0x0002), "UI", "1.2.840.10008.5.1.4.1.1.2")
    meta_body += encode_str((0x0002, 0x0003), "UI", sop_uid)
    meta_body += encode_str((0x0002, 0x0010), "UI", "1.2.840.10008.1.2.1")
    meta_body += encode_str((0x0002, 0x0012), "UI", "1.2.826.0.1.3680043.10.555")
    meta = encode_element((0x0002, 0x0000), "UL", struct.pack("<I", len(meta_body))) + meta_body

    out = Path(args.output)
    out.write_bytes(preamble + meta + ds)
    print(f"Wrote {out} study={study_uid} sop={sop_uid}")


if __name__ == "__main__":
    main()
