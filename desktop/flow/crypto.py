"""Encrypted channel — HKDF-SHA256 key derivation + XChaCha20-Poly1305 AEAD.

Implements §0.5 / protocol.md "Transport":
  * derive a 32-byte session key from the pairing PSK via HKDF-SHA256,
  * seal/open every WebSocket frame with XChaCha20-Poly1305 (libsodium / PyNaCl),
  * a fresh random 24-byte nonce per message,
  * a replay window that rejects nonces already seen.

Wire frame layout (bytes): ``nonce(24) || ciphertext+tag``. We base64 the whole
frame for transport over the (text) JSON websocket.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import threading
from typing import Optional

from nacl.bindings import (
    crypto_aead_xchacha20poly1305_ietf_decrypt as _xchacha_decrypt,
    crypto_aead_xchacha20poly1305_ietf_encrypt as _xchacha_encrypt,
    crypto_aead_xchacha20poly1305_ietf_NPUBBYTES as NONCE_BYTES,
)

from . import config

KEY_BYTES = 32
PSK_BYTES = config.PSK_BYTES
_HKDF_INFO = b"flow/session/v1"
_HASH = hashlib.sha256


# ---------------------------------------------------------------------------
# HKDF-SHA256 (RFC 5869)
# ---------------------------------------------------------------------------
def hkdf_sha256(ikm: bytes, *, salt: Optional[bytes] = None,
                info: bytes = _HKDF_INFO, length: int = KEY_BYTES) -> bytes:
    if salt is None:
        salt = b"\x00" * _HASH().digest_size
    prk = hmac.new(salt, ikm, _HASH).digest()  # extract
    okm, t, counter = b"", b"", 1               # expand
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), _HASH).digest()
        okm += t
        counter += 1
    return okm[:length]


def derive_key(psk: bytes, *, salt: Optional[bytes] = None) -> bytes:
    """Derive the 32-byte AEAD session key from the pairing PSK."""
    return hkdf_sha256(psk, salt=salt, info=_HKDF_INFO, length=KEY_BYTES)


def random_psk() -> bytes:
    """Generate a fresh 32-byte pre-shared key (for the QR payload)."""
    return os.urandom(PSK_BYTES)


# ---------------------------------------------------------------------------
# Secure channel
# ---------------------------------------------------------------------------
class ReplayError(Exception):
    """Raised when a frame's nonce has already been seen (replay)."""


class SecureChannel:
    """AEAD seal/open with per-message nonce and a replay-rejection window."""

    def __init__(self, psk: bytes, *, salt: Optional[bytes] = None,
                 replay_window: int = 4096) -> None:
        if len(psk) != PSK_BYTES:
            raise ValueError(f"psk must be {PSK_BYTES} bytes, got {len(psk)}")
        self._key = derive_key(psk, salt=salt)
        self._seen: set[bytes] = set()
        self._seen_order: list[bytes] = []
        self._replay_window = replay_window
        self._lock = threading.Lock()

    # -- key access (for HELLO/debug; never logged) --
    @property
    def key(self) -> bytes:
        return self._key

    def seal(self, plaintext: bytes, aad: Optional[bytes] = None) -> bytes:
        """Encrypt plaintext -> frame ``nonce || ciphertext+tag``."""
        nonce = os.urandom(NONCE_BYTES)
        ct = _xchacha_encrypt(plaintext, aad, nonce, self._key)
        return nonce + ct

    def open(self, frame: bytes, aad: Optional[bytes] = None) -> bytes:
        """Decrypt a frame; reject replays and tampering."""
        if len(frame) < NONCE_BYTES:
            raise ValueError("frame too short")
        nonce, ct = frame[:NONCE_BYTES], frame[NONCE_BYTES:]
        with self._lock:
            if nonce in self._seen:
                raise ReplayError("replayed nonce rejected")
        # Authenticated decryption — raises on tamper.
        pt = _xchacha_decrypt(ct, aad, nonce, self._key)
        with self._lock:
            self._seen.add(nonce)
            self._seen_order.append(nonce)
            if len(self._seen_order) > self._replay_window:
                old = self._seen_order.pop(0)
                self._seen.discard(old)
        return pt

    # -- base64 text helpers for the JSON websocket --
    def seal_b64(self, plaintext: bytes, aad: Optional[bytes] = None) -> str:
        return base64.b64encode(self.seal(plaintext, aad)).decode("ascii")

    def open_b64(self, frame_b64: str, aad: Optional[bytes] = None) -> bytes:
        return self.open(base64.b64decode(frame_b64), aad)
