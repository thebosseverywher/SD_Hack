"""Crypto tests — HKDF determinism, AEAD seal/open, tamper + replay rejection."""

import pytest

from flow.crypto import SecureChannel, ReplayError, derive_key, random_psk


def test_hkdf_deterministic():
    psk = b"\x01" * 32
    assert derive_key(psk) == derive_key(psk)
    assert derive_key(psk) != derive_key(b"\x02" * 32)


def test_seal_open_roundtrip():
    psk = random_psk()
    ch = SecureChannel(psk)
    msg = b"hello flow {json}"
    frame = ch.seal(msg)
    # decrypt with a separate channel sharing the same psk
    other = SecureChannel(psk)
    assert other.open(frame) == msg


def test_tamper_rejected():
    psk = random_psk()
    ch = SecureChannel(psk)
    frame = bytearray(ch.seal(b"important"))
    frame[-1] ^= 0xFF  # flip a tag byte
    with pytest.raises(Exception):
        SecureChannel(psk).open(bytes(frame))


def test_replay_rejected():
    psk = random_psk()
    sender = SecureChannel(psk)
    receiver = SecureChannel(psk)
    frame = sender.seal(b"once")
    assert receiver.open(frame) == b"once"
    with pytest.raises(ReplayError):
        receiver.open(frame)  # same nonce again


def test_b64_helpers():
    psk = random_psk()
    a, b = SecureChannel(psk), SecureChannel(psk)
    s = a.seal_b64(b"data")
    assert b.open_b64(s) == b"data"
