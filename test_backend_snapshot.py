"""Operational HTTP tests; upstream, auth and database are deliberately isolated."""
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from backend.app.auth import require_user
from backend.app.main import app, authenticated_user, get_store, snapshot_reader
from backend.app.snapshot_service import SnapshotService, build_snapshot
from backend.app.settings import Settings
from functions import normalizar_apontamento

USER = "00000000-0000-0000-0000-000000000001"
SESSION = "00000000-0000-0000-0000-000000000002"


class BackendSnapshotTests(TestCase):
    def setUp(self):
        self.context = TestClient(app, base_url="https://testserver")
        self.client = self.context.__enter__()
        self.store = Mock()
        self.service = Mock()
        app.state.store, app.state.service = self.store, self.service
        now = datetime.now(timezone.utc) - timedelta(minutes=3)
        self.record = normalizar_apontamento({"id": 17, "equipmentTag": "EQ-17", "reasonName": "EXEC MANUT CORRETIVA",
                                            "startDate": now.isoformat(), "endDate": None})
        self.data = build_snapshot([self.record])
        self.data.update(stale=False, sourceAvailable=True, persistenceAvailable=True)
        self.data.pop("records")
        self.service.read.return_value = self.data

    def tearDown(self):
        app.dependency_overrides.clear()
        self.context.__exit__(None, None, None)

    def authorize(self):
        app.dependency_overrides[authenticated_user] = lambda: {"sub": USER, "session_id": SESSION, "email": "allowed@example.com"}
        app.dependency_overrides[snapshot_reader] = app.dependency_overrides[authenticated_user]

    def test_missing_bearer_is_rejected(self):
        self.assertEqual(401, self.client.get("/v1/snapshot").status_code)

    def test_health_does_not_imply_operational_access(self):
        self.assertEqual(200, self.client.get("/healthz").status_code)
        self.assertEqual(401, self.client.get("/v1/readiness").status_code)

    def test_snapshot_includes_open_maintenance_alert_candidates(self):
        self.authorize()
        data = self.client.get("/v1/snapshot").json()
        self.assertEqual(["17"], [item["id"] for item in data["maintenanceAlerts"]])
        self.assertEqual("EQ-17", data["equipment"][0]["equipmentTag"])
        self.assertGreaterEqual(data["maintenanceAlerts"][0]["durationSeconds"], 180)

    def test_snapshot_reads_never_poll_grm_or_write_database(self):
        self.authorize()
        with patch("backend.app.grm_client.GrmClient.fetch") as upstream:
            for _ in range(20):
                self.assertEqual(200, self.client.get("/v1/snapshot").status_code)
            upstream.assert_not_called()
        self.assertEqual([], self.store.mock_calls)

    def test_operational_write_methods_rejected(self):
        self.authorize()
        for method in ["POST", "PUT", "PATCH", "DELETE"]:
            self.assertEqual(405, self.client.request(method, "/v1/snapshot").status_code)

    def test_no_valid_cache_returns_503(self):
        self.authorize(); self.service.read.return_value = None
        self.assertEqual(503, self.client.get("/v1/snapshot").status_code)

    def test_stale_snapshot_is_visible_but_not_ready(self):
        self.authorize(); self.data["stale"] = True
        self.assertTrue(self.client.get("/v1/snapshot").json()["stale"])
        self.assertEqual(503, self.client.get("/v1/readiness").status_code)

    def test_http_transport_rejected_without_redirecting_token(self):
        self.authorize()
        response = self.client.get("http://testserver/v1/snapshot", headers={"Authorization": "Bearer unit-test"})
        self.assertEqual(400, response.status_code)
        self.assertNotIn("location", response.headers)

    def test_private_response_headers(self):
        self.authorize()
        response = self.client.get("/v1/snapshot")
        self.assertIn("no-store", response.headers["cache-control"])
        self.assertEqual("nosniff", response.headers["x-content-type-options"])

    def test_session_endpoint_identifies_verified_identity(self):
        self.authorize()
        self.assertEqual(USER, self.client.get("/v1/session").json()["userId"])

    def test_logout_revokes_server_and_provider(self):
        self.authorize()
        with patch("backend.app.main.provider_logout", return_value=True):
            data = self.client.post("/v1/session/logout", headers={"Authorization": "Bearer unit-test"}).json()
        self.assertTrue(data["serverRevoked"]); self.assertTrue(data["providerRevoked"])
        self.store.revoke_session.assert_called_once()

    def test_device_registration_needs_authentication(self):
        self.assertEqual(401, self.client.put("/v1/devices/" + USER, json={"token": "unit-test-token-0123456789"}).status_code)

    def test_device_preferences_are_not_operational_writes(self):
        self.authorize()
        response = self.client.put("/v1/devices/" + USER, json={"token": "unit-test-token-0123456789", "preferences": {"soundEnabled": False}})
        self.assertEqual(200, response.status_code)
        self.store.register_device.assert_called_once()

    def test_device_payload_rejects_operational_fields(self):
        self.authorize()
        self.assertEqual(422, self.client.put("/v1/devices/" + USER,
            json={"token": "unit-test-token-0123456789", "equipmentTag": "NO-WRITE"}).status_code)

    def test_large_device_body_rejected(self):
        self.authorize()
        self.assertEqual(413, self.client.put("/v1/devices/" + USER, content=b"x" * 65537).status_code)

    def test_device_invalid_limits_rejected(self):
        self.authorize()
        self.assertEqual(422, self.client.put("/v1/devices/" + USER, json={"token": "unit-test-token-0123456789",
            "preferences": {"limitsMinutesByStatus": {"PAUSA": -1}}}).status_code)