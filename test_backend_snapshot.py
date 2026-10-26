from datetime import datetime, timedelta, timezone
import os
from unittest import TestCase, skipUnless
from unittest.mock import patch

try:
    from fastapi import HTTPException
    from backend.app.auth import _oidc_config, require_user
    from backend.app.main import snapshot
    BACKEND_TEST_DEPS_AVAILABLE = True
except ModuleNotFoundError:
    HTTPException = None
    snapshot = None
    BACKEND_TEST_DEPS_AVAILABLE = False


@skipUnless(BACKEND_TEST_DEPS_AVAILABLE, "Instale backend/requirements.txt para executar os testes do backend")
class BackendSnapshotTests(TestCase):
    def test_missing_bearer_is_rejected(self):
        with self.assertRaises(HTTPException) as raised:
            require_user(None)
        self.assertEqual(401, raised.exception.status_code)

    def test_unconfigured_identity_provider_fails_closed(self):
        _oidc_config.cache_clear()
        with patch.dict(os.environ, {"OIDC_ISSUER": "", "OIDC_AUDIENCE": ""}):
            credentials = type("Credentials", (), {"scheme": "Bearer", "credentials": "not-a-token"})()
            with self.assertRaises(HTTPException) as raised:
                require_user(credentials)
        self.assertEqual(503, raised.exception.status_code)
        _oidc_config.cache_clear()

    def test_snapshot_includes_open_maintenance_alert_candidates(self):
        now = datetime.now(timezone.utc)
        maintenance = {
            "id": 17,
            "equipmentTag": "EQ-17",
            "reasonName": "EXEC MANUT CORRETIVA",
            "startDate": "2026-10-23T10:00:00Z",
            "endDate": None,
            "_inicio_dt": now - timedelta(minutes=3),
            "_fim_dt": None,
            "operator": "Operador",
            "location": "Setor A",
            "equipmentType": "TRATOR",
            "kpiCategory": "MANUTENCAO",
        }
        with (
            patch("backend.app.main.api_content_formatted", return_value=[maintenance]),
            patch("backend.app.main.obter_apontamentos_abertos", return_value=[maintenance]),
            patch("backend.app.main.obter_pausas_ergonomicas_abertas", return_value=[]),
            patch("backend.app.main.obter_manutencoes_abertas", return_value=[maintenance]),
            patch("backend.app.main.obter_manutencoes_atuais", return_value=[maintenance]),
            patch("backend.app.main.calcular_duracao_segundos", return_value=180),
        ):
            data = snapshot(_claims={"sub": "test"})

        self.assertEqual(["17"], [item["id"] for item in data["maintenanceAlerts"]])
        self.assertEqual("EQ-17", data["equipment"][0]["equipmentTag"])
        self.assertEqual(180, data["maintenanceAlerts"][0]["durationSeconds"])

    def test_upstream_failure_returns_generic_service_unavailable(self):
        with patch("backend.app.main.api_content_formatted", side_effect=RuntimeError("private detail")):
            with self.assertRaises(HTTPException) as raised:
                snapshot(_claims={"sub": "test"})

        self.assertEqual(503, raised.exception.status_code)
        self.assertNotIn("private detail", raised.exception.detail)
