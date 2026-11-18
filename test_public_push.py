"""FCM enrollment/outbox regression on a disposable LOCAL database only."""
import os
import secrets
import unittest
from datetime import datetime, timezone, timedelta
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

from fastapi import HTTPException
from backend.app.public_push import EnrollPush, ConfirmPush, PublicPushNotifier, enroll, confirm, disable, secret_from
from backend.app.notifications import plan_alerts, digest
from test_postgres_integration import LocalStore, DSN
import test_postgres_integration as database_tests
from backend.app.settings import Settings


@unittest.skipUnless(os.getenv("RUN_LOCAL_POSTGRES") == "1", "Local disposable database opt-in required")
class PublicPushTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        database_tests.PostgresIntegrationTests.setUpClass()

    def setUp(self):
        self.store = LocalStore(Settings("https://example.invalid/grm", "test", DSN))
        with self.store.connection() as conn:
            conn.execute("truncate operational.public_push_devices,operational.public_push_rates,operational.public_push_outbox cascade")
        self.secret = secrets.token_urlsafe(32)
        self.device = uuid4()
        self.request = SimpleNamespace(headers={"authorization": "Bearer " + self.secret}, client=SimpleNamespace(host="local-test"))
        self.sent = []
        self.service = PublicPushNotifier(self.store, sender=lambda d, e: self.sent.append((d, e)))
        self.challenges = []
        self.service.send_challenge = lambda token, device, challenge: self.challenges.append((token, device, challenge))
        self.body = EnrollPush(deviceId=self.device, token="mock-token-abcdefghijklmnopqrstuvwxyz")

    def register(self):
        result = enroll(self.body, self.request, self.service)
        self.assertFalse(result["registered"])
        confirm(self.device, ConfirmPush(challenge=self.challenges[-1][2]), self.request, self.service)

    def snapshot(self, reason="PAUSA ERGONOMICA", minutes=20):
        now = datetime.now(timezone.utc)
        record = {"id": "pointing-one", "reasonName": reason, "durationSeconds": minutes * 60,
                  "durationMeasuredAtEpochMillis": int(now.timestamp()*1000), "startEpochMillis": int((now-timedelta(minutes=minutes)).timestamp()*1000)}
        return {"equipment": [{"current": record}], "maintenanceAlerts": [], "stale": False}

    def test_secret_required_and_scoped_to_owner(self):
        with self.assertRaises(HTTPException) as err:
            secret_from(SimpleNamespace(headers={}))
        self.assertEqual(err.exception.status_code, 401)
        self.register()
        wrong = SimpleNamespace(headers={"authorization": "Bearer " + secrets.token_urlsafe(32)}, client=self.request.client)
        with self.assertRaises(HTTPException) as err:
            enroll(self.body, wrong, self.service)
        self.assertEqual(err.exception.status_code, 403)
        with self.assertRaises(HTTPException): disable(self.device, wrong, self.service)

    def test_challenge_is_not_in_response_and_only_hash_is_stored(self):
        response = enroll(self.body, self.request, self.service)
        raw = self.challenges[-1][2]
        self.assertNotIn(raw, str(response))
        with self.store.connection() as conn:
            row = conn.execute("select * from operational.public_push_devices").fetchone()
        self.assertFalse(row["enabled"])
        self.assertIsNone(row["token"])
        self.assertEqual(row["secret_hash"], digest(self.secret))
        self.assertEqual(row["challenge_hash"], digest(raw))

    def test_wrong_expired_or_replayed_challenge_cannot_confirm(self):
        enroll(self.body, self.request, self.service)
        with self.assertRaises(HTTPException): confirm(self.device, ConfirmPush(challenge=secrets.token_urlsafe(32)), self.request, self.service)
        raw = self.challenges[-1][2]
        with self.store.connection() as conn:
            conn.execute("update operational.public_push_devices set challenge_expires=now()-interval '1 minute'")
        with self.assertRaises(HTTPException): confirm(self.device, ConfirmPush(challenge=raw), self.request, self.service)
        self.register()
        with self.assertRaises(HTTPException): confirm(self.device, ConfirmPush(challenge=self.challenges[-1][2]), self.request, self.service)

    def test_unconfirmed_device_never_receives_operational_alert(self):
        enroll(self.body, self.request, self.service)
        self.service.process(self.snapshot())
        self.assertEqual(self.sent, [])

    def test_confirmed_device_receives_and_disable_stops_delivery(self):
        self.register()
        self.service.process(self.snapshot())
        self.assertEqual(len(self.sent), 1)
        self.service.process(self.snapshot())
        self.assertEqual(len(self.sent), 1)
        disable(self.device, self.request, self.service)
        self.service.process(self.snapshot())
        self.assertEqual(len(self.sent), 1)

    def test_token_cannot_be_taken_by_another_install(self):
        self.register()
        other = EnrollPush(deviceId=uuid4(), token=self.body.token)
        with self.assertRaises(HTTPException) as err: enroll(other, self.request, self.service)
        self.assertEqual(err.exception.status_code, 403)

    def test_token_rotation_requires_new_proof(self):
        self.register()
        old = self.body.token
        self.body.token = "another-token-abcdefghijklmnopqrstuvwxyz"
        enroll(self.body, self.request, self.service)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute("select token from operational.public_push_devices").fetchone()["token"], old)
        confirm(self.device, ConfirmPush(challenge=self.challenges[-1][2]), self.request, self.service)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute("select token from operational.public_push_devices").fetchone()["token"], self.body.token)

    def test_provider_failure_does_not_enable_device(self):
        self.service.send_challenge = lambda *args: (_ for _ in ()).throw(RuntimeError("private suppressed"))
        with self.assertRaises(HTTPException) as err: enroll(self.body, self.request, self.service)
        self.assertEqual(err.exception.status_code, 503)
        with self.store.connection() as conn:
            self.assertFalse(conn.execute("select enabled from operational.public_push_devices").fetchone()["enabled"])

    def test_rate_limit_persists_and_does_not_expose_secrets(self):
        self.register()
        for _ in range(29): enroll(self.body, self.request, self.service)
        with self.assertRaises(HTTPException) as err: enroll(self.body, self.request, self.service)
        self.assertEqual(err.exception.status_code, 429)
        self.assertNotIn(self.secret, err.exception.detail)

    def test_stale_snapshot_does_not_queue_or_send(self):
        self.register()
        snapshot = self.snapshot()
        snapshot["stale"] = True
        self.service.process(snapshot)
        self.assertEqual(self.sent, [])

    def test_exact_accented_pause_matches_and_similar_reason_does_not(self):
        device = {"preferences": {"limitsMinutesByStatus": {"PAUSA ERGONOMICA": 15}, "alertsEnabledByStatus": {"PAUSA ERGONOMICA": True}}}
        self.assertEqual(len(plan_alerts(self.snapshot("PAUSA ERGONÔMICA"), device)[0]), 1)
        self.assertEqual(plan_alerts(self.snapshot("PAUSA PARTICULAR"), device)[0], [])

    def test_maintenance_is_baselined_then_emits_only_new_pointing(self):
        self.register()
        snapshot = self.snapshot()
        snapshot["equipment"] = []
        snapshot["maintenanceAlerts"] = [{"id": "maintenance-old", "reasonName": "AG MANUTENCAO"}]
        self.service.process(snapshot)
        self.assertEqual(self.sent, [])
        snapshot["maintenanceAlerts"] = [{"id": "maintenance-new", "reasonName": "AG MANUTENCAO"}]
        self.service.process(snapshot)
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(self.sent[0][1]["event_key"], "maintenance:maintenance-new")


if __name__ == "__main__": unittest.main()