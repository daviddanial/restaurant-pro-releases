"""Header-only copies of DICOM objects for the cloud index (no pixel data leaves the hospital)."""
from __future__ import annotations

import io
from typing import Callable, Iterable

import pydicom
from pydicom.filewriter import dcmwrite

# Explicit/implicit VR little endian tag (7FE0,0010) Pixel Data.
_PIXEL_TAG_LE = b"\xe0\x7f\x10\x00"
# (0088,0200) Icon Image Sequence.
_ICON_SEQ_LE = b"\x88\x00\x00\x02"


def header_only(data: bytes) -> bytes:
    """Re-encode a DICOM object without its pixel data (file meta kept)."""
    ds = pydicom.dcmread(io.BytesIO(data), stop_before_pixels=True, force=True)
    out = io.BytesIO()
    dcmwrite(out, ds)
    return out.getvalue()


def header_from_stream(chunks: Iterable[bytes], read_rest: Callable[[], bytes] | None = None) -> bytes:
    """Read only up to the pixel data from a streamed object, then strip it.

    Stops reading at the first Pixel Data tag after the preamble, so a 300 MB object costs a few
    kilobytes. If that cut turns out wrong (the byte pattern occurred inside another value),
    falls back to the whole object.
    """
    buf = bytearray()
    cut = None
    for chunk in chunks:
        start = max(132, len(buf) - 3)
        buf.extend(chunk)
        pos = buf.find(_PIXEL_TAG_LE, start)
        if pos != -1:
            cut = pos
            break
    # An Icon Image Sequence has its own Pixel Data inside, so the first one is not the top-level one.
    if cut is not None:
        if _ICON_SEQ_LE not in buf[:cut]:
            try:
                return header_only(bytes(buf[:cut]))
            except Exception:
                pass
        if read_rest is not None:
            buf.extend(read_rest())
    return header_only(bytes(buf))
