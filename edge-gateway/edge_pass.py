"""Image passes: short-lived RS256 JWTs the cloud signs for one user, one hospital server and
specific studies (KB note 13 §4). The hospital server checks them with the cloud's public key,
so it never needs a list of users."""
from __future__ import annotations

import base64
import json
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ISSUER = "telerad"
LEEWAY_SECONDS = 30


class PassError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def _b64url(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def load_public_key(pem: str) -> rsa.RSAPublicKey:
    key = serialization.load_pem_public_key(pem.encode("ascii"))
    if not isinstance(key, rsa.RSAPublicKey):
        raise ValueError("image-pass key must be RSA")
    return key


def verify_pass(token: str, public_key: rsa.RSAPublicKey | None, gateway_id: str,
                study_uid: str, now: float | None = None) -> dict:
    """Return the pass claims when it lets its holder read ``study_uid`` here; raise PassError otherwise."""
    if public_key is None:
        raise PassError(503, "hospital server has not received the image-pass key yet")
    if not token:
        raise PassError(401, "image pass required")
    parts = token.split(".")
    if len(parts) != 3:
        raise PassError(401, "malformed image pass")
    try:
        header = json.loads(_b64url(parts[0]))
        claims = json.loads(_b64url(parts[1]))
        signature = _b64url(parts[2])
    except Exception:
        raise PassError(401, "malformed image pass")
    if header.get("alg") != "RS256":
        raise PassError(401, "unsupported image pass")
    try:
        public_key.verify(signature, f"{parts[0]}.{parts[1]}".encode("ascii"),
                          padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature:
        raise PassError(401, "invalid image pass")
    now = time.time() if now is None else now
    if claims.get("iss") != ISSUER:
        raise PassError(401, "invalid image pass issuer")
    if str(claims.get("aud")) != str(gateway_id):
        raise PassError(403, "image pass is for another hospital server")
    exp = claims.get("exp")
    if not isinstance(exp, (int, float)) or exp + LEEWAY_SECONDS < now:
        raise PassError(401, "image pass expired")
    studies = claims.get("studies") or []
    if study_uid not in studies:
        raise PassError(403, "image pass does not cover this study")
    return claims
