"""Activation API security tests, with no real credentials or external services."""
from contextlib import contextmanager
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import patch
from fastapi import HTTPException
from fastapi.testclient import TestClient
from backend.app.activation import digest, enroll, require_device, revoke_device
from backend.app.main import app

CODE = "UNIT-TEST-ACTIVATION-CODE"

class MemoryStore:
    def __init__(self):
        self.attempts = {}; self.devices = {}; self.arguments = []

    @contextmanager
    def connection(self):
        yield self

    def execute(self, sql, args=()):
        self.arguments.append(args)
        if "insert into operational.activation_attempts" in sql:
            key = args[0]; self.attempts[key] = self.attempts.get(key, 0) + 1
            self.row = {"attempts": self.attempts[key]}
        elif "insert into operational.activated_devices" in sql:
            self.devices[args[1]] = {"id": args[0], "enabled": True}
        elif "select id from operational.activated_devices" in sql:
            item = self.devices.get(args[0]); self.row = item if item and item["enabled"] else None
        elif "update operational.activated_devices" in sql:
            for item in self.devices.values():
                if item["id"] == args[0]: item["enabled"] = False
        return self

    def fetchone(self): return self.row

class ActivationTests(TestCase):
    def setUp(self):
        self.env = patch.dict("os.environ", {"AUTH_MODE": "activation", "ACTIVATION_CODE_SHA256": digest(CODE)})
        self.env.start(); self.addCleanup(self.env.stop)
        self.store = MemoryStore()

    def credential(self, token): return SimpleNamespace(scheme="Bearer", credentials=token)

    def test_code_case_whitespace_and_hashed_storage(self):
        result = enroll(self.store, "  " + CODE.lower() + "  ", "test-peer")
        self.assertEqual(len(self.store.devices), 1)
        self.assertNotIn(result["accessToken"], repr(self.store.arguments))
        self.assertNotIn(CODE, repr(self.store.arguments))
        self.assertNotIn("test-peer", repr(self.store.arguments))
        self.assertEqual(result["deviceId"], require_device(self.credential(result["accessToken"]), self.store)["sub"])

    def test_invalid_code_counts_and_persistent_scope(self):
        for i in range(10):
            with self.assertRaises(HTTPException) as err: enroll(self.store, "WRONG-CODE-INVALID", "peer")
            self.assertEqual(err.exception.status_code, 403)
        with self.assertRaises(HTTPException) as err: enroll(self.store, CODE, "peer")
        self.assertEqual(err.exception.status_code, 429)
        self.assertEqual(err.exception.headers["Retry-After"], "900")
        self.assertEqual(self.store.devices, {})

    def test_per_phone_isolation_and_revocation(self):
        first = enroll(self.store, CODE, "peer")
        second = enroll(self.store, CODE, "peer")
        self.assertNotEqual(first["accessToken"], second["accessToken"])
        self.assertNotEqual(first["deviceId"], second["deviceId"])
        claims = require_device(self.credential(first["accessToken"]), self.store)
        revoke_device(self.store, claims)
        with self.assertRaises(HTTPException) as err: require_device(self.credential(first["accessToken"]), self.store)
        self.assertEqual(err.exception.status_code, 401)
        self.assertEqual(second["deviceId"], require_device(self.credential(second["accessToken"]), self.store)["sub"])

    def test_invalid_missing_and_unknown_tokens(self):
        for credential in (None, self.credential(CODE), self.credential("soa_" + "a" * 43)):
            with self.assertRaises(HTTPException) as err: require_device(credential, self.store)
            self.assertEqual(err.exception.status_code, 401)

    def test_no_configuration_fails_closed(self):
        with patch.dict("os.environ", {"ACTIVATION_CODE_SHA256": ""}):
            with self.assertRaises(HTTPException) as err: enroll(self.store, CODE, "peer")
            self.assertEqual(err.exception.status_code, 503)

    def test_http_session_logout_and_no_push(self):
        with TestClient(app, base_url="https://testserver") as client:
            app.state.store = self.store
            self.assertEqual(client.get("/v1/session").status_code, 401)
            self.assertEqual(client.post("/v1/activation", json={"code": "short"}).status_code, 422)
            self.assertEqual(client.post("/v1/activation", json={"code": CODE, "extra": True}).status_code, 422)
            self.assertEqual(client.post("http://testserver/v1/activation", json={"code": CODE}).status_code, 400)
            result = client.post("/v1/activation", json={"code": CODE}).json()
            headers = {"Authorization": "Bearer " + result["accessToken"]}
            response = client.get("/v1/session", headers=headers)
            self.assertEqual(response.json()["userId"], result["deviceId"])
            self.assertIn("no-store", response.headers["cache-control"])
            self.assertEqual(client.put("/v1/devices/" + result["deviceId"], headers=headers,
                json={"token": "unit-test-token-0123456789"}).status_code, 403)
            self.assertTrue(client.post("/v1/session/logout", headers=headers).json()["serverRevoked"])
            self.assertEqual(client.get("/v1/session", headers=headers).status_code, 401)