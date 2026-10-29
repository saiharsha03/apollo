"""Lemnos terminal key: the terminal and shell create/close need the phone's fingerprint-locked key, not just the token."""
import base64

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from test_server import PHONE


def b64(b):
    return base64.urlsafe_b64encode(b).decode()


def der(k):
    return b64(k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


@pytest.fixture
def c(monkeypatch):
    from apollo import lemnos, main
    main.store.x("DELETE FROM settings WHERE name IN (?, ?)", (lemnos.KEY, lemnos.PENDING))
    lemnos._challenges.clear()
    monkeypatch.setattr(lemnos, "helper", lambda prefix, *a, **k: b'{"session": "shell-2", "ok": true}')
    return TestClient(main.app)


def proof(c, k, what):
    n = c.get("/v1/lemnos/challenge", headers=PHONE).json()["nonce"]
    return {"nonce": n, "sig": b64(k.sign(f"{n}:{what}".encode(), ec.ECDSA(hashes.SHA256())))}


def register(c, k):
    return c.post("/v1/lemnos/key", headers=PHONE, json={"key": der(k), **proof(c, k, "register")})


def enrol(c, k):
    from apollo import lemnos, main
    assert lemnos.approve(main.store, register(c, k).json()["pending"].upper())


def test_the_key_registers_once_and_a_replacement_is_refused(c):
    k = ec.generate_private_key(ec.SECP256R1())
    assert c.get("/v1/lemnos/challenge").status_code == 401
    assert c.get("/v1/lemnos/challenge", headers=PHONE).json()["key"] is None
    n = c.get("/v1/lemnos/challenge", headers=PHONE).json()["nonce"]       # signed by another key: refused
    other = ec.generate_private_key(ec.SECP256R1())
    bad = b64(other.sign(f"{n}:register".encode(), ec.ECDSA(hashes.SHA256())))
    assert c.post("/v1/lemnos/key", headers=PHONE, json={"key": der(k), "nonce": n, "sig": bad}).status_code == 403
    p384 = c.post("/v1/lemnos/key", headers=PHONE, json={"key": der(ec.generate_private_key(ec.SECP384R1())), "nonce": "x", "sig": "x"})
    assert p384.status_code == 400
    from apollo import lemnos, main
    pending = register(c, k).json()["pending"]
    assert len(pending) == 16 and register(c, k).status_code == 200          # the same key again is fine
    assert register(c, other).status_code == 409                              # another key never replaces a pending one
    pid = c.get("/v1/lemnos/challenge", headers=PHONE).json()["pending"]
    assert pid.startswith(pending) and len(pid) == 64
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=proof(c, k, "new-shell")).status_code == 403    # pending opens nothing
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/v1/lemnos/term", params={"session": "shell-1", **proof(c, k, "term:shell-1")}, headers=PHONE) as ws:
            ws.receive_text()
    assert not lemnos.approve(main.store, pending[:8]) and not lemnos.approve(main.store, "0" * 16)    # short or wrong code
    assert lemnos.approve(main.store, pending)
    kid = c.get("/v1/lemnos/challenge", headers=PHONE).json()["key"]
    assert kid.startswith(pending) and register(c, k).status_code == 409 and register(c, other).status_code == 409
    assert not lemnos.approve(main.store, pending)
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=proof(c, k, "new-shell")).status_code == 200

def test_shells_need_a_fresh_single_use_signature(c, monkeypatch):
    from apollo import lemnos
    k = ec.generate_private_key(ec.SECP256R1())
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=proof(c, k, "new-shell")).status_code == 403     # no key yet
    enrol(c, k)
    assert c.post("/v1/lemnos/sessions", headers=PHONE).status_code == 403
    p = proof(c, k, "new-shell")
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=p).json()["session"] == "shell-2"
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=p).status_code == 403             # spent
    assert c.delete("/v1/lemnos/sessions/shell-2", headers=PHONE, params=proof(c, k, "new-shell")).status_code == 403   # other action
    assert c.delete("/v1/lemnos/sessions/shell-2", headers=PHONE, params=proof(c, k, "kill:shell-2")).status_code == 200
    p = proof(c, k, "new-shell")
    monkeypatch.setattr(lemnos, "CHALLENGE_TTL", -1)
    late = proof(c, k, "new-shell")
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=late).status_code == 403          # expired
    assert c.post("/v1/lemnos/sessions", headers=PHONE, params=p).status_code == 200


def test_the_terminal_closes_4403_without_a_good_signature(c, monkeypatch):
    from apollo import lemnos
    k = ec.generate_private_key(ec.SECP256R1())
    enrol(c, k)
    opened = []

    async def bridge(ws, session, ro, cols, rows):
        opened.append(session)
        await ws.send_text("hi")
        await ws.close()

    monkeypatch.setattr(lemnos, "term_bridge", bridge)
    other = ec.generate_private_key(ec.SECP256R1())
    for params in ({}, proof(c, other, "term:shell-1"), proof(c, k, "term:shell-9")):
        with pytest.raises(WebSocketDisconnect) as e:
            with c.websocket_connect("/v1/lemnos/term?session=shell-1&" + "&".join(f"{a}={v}" for a, v in params.items()), headers=PHONE) as ws:
                ws.receive_text()
        assert e.value.code == 4403
    with c.websocket_connect("/v1/lemnos/term", params={"session": "shell-1", **proof(c, k, "term:shell-1")}, headers=PHONE) as ws:
        assert ws.receive_text() == "hi"
    def ticket():     # one prompt opens a new shell and its terminal
        return c.post("/v1/lemnos/sessions", headers=PHONE, params=proof(c, k, "new-shell")).json()["ticket"]

    t = ticket()
    for sess, tk, ok in (("shell-1", ticket(), False), ("shell-2", t, True), ("shell-2", t, False)):     # its own session, once
        try:
            with c.websocket_connect("/v1/lemnos/term", params={"session": sess, "ticket": tk}, headers=PHONE) as ws:
                ws.receive_text()
            assert ok
        except WebSocketDisconnect as e:
            assert not ok and e.code == 4403
    assert opened == ["shell-1", "shell-2"]
