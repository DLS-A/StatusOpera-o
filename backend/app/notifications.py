"""Optional FCM transport. No equipment/operator/status is sent to the lock screen."""
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
import threading
import unicodedata

from .auth import allowed_emails

PAUSE = "PAUSA ERGONOMICA"


def digest(value):
    return hashlib.sha256(str(value).encode()).hexdigest()


def plan_alerts(snapshot, device):
    if snapshot.get("stale") or not snapshot.get("sourceAvailable", True) or not snapshot.get("persistenceAvailable", True):
        return [], device.get("observed_maintenance") or [], []
    preferences = device.get("preferences") or {}
    def canonical(value):
        return " ".join("".join(c for c in unicodedata.normalize("NFD", value.upper()) if not unicodedata.combining(c)).split())
    current = [e["current"] for e in snapshot["equipment"] if e.get("current")]
    limits = {canonical(k): v for k, v in preferences.get("limitsMinutesByStatus", {PAUSE: 15}).items()}
    enabled = {canonical(k): v for k, v in preferences.get("alertsEnabledByStatus", {}).items()}
    events, active_ids = [], []
    now = datetime.now(timezone.utc).timestamp() * 1000
    for record in current:
        status = canonical(record["reasonName"])
        key = status
        if not preferences.get("visualEnabled", True) or not enabled.get(status, enabled.get(key, key == PAUSE)):
            continue
        active_ids.append(record["id"])
        minutes = limits.get(key)
        # Report clock is authoritative, not the untrusted phone clock nor an
        # inferred accumulated stop across unrelated pointings.
        measured = record.get("durationMeasuredAtEpochMillis")
        elapsed = max(0, record.get("durationSeconds", 0))
        if measured is not None:
            elapsed += max(0, (now - measured) / 1000)
        if minutes and elapsed > minutes * 60:
            events.append((f'limit:{record["id"]}:{minutes * 60}', record["id"]))
    maintenance = {r["id"]: r for r in snapshot["maintenanceAlerts"]}
    if preferences.get("visualEnabled", True):
        active_ids.extend(record_id for record_id, record in maintenance.items()
                          if enabled.get(canonical(record["reasonName"]), True))
    observed = device.get("observed_maintenance")
    if observed is not None and preferences.get("visualEnabled", True):
        for record_id, record in maintenance.items():
            if record_id not in observed and enabled.get(canonical(record["reasonName"]), True):
                events.append(("maintenance:" + record_id, record_id))
    return events, list(maintenance), active_ids


class FirebaseNotifier:
    def __init__(self, store, sender=None):
        self.store = store
        self.sender = sender or self._send
        self._app = None
        self._app_lock = threading.Lock()

    def _firebase_app(self):
        import firebase_admin
        from firebase_admin import credentials
        with self._app_lock:
            if self._app is None:
                value = os.getenv("FCM_SERVICE_ACCOUNT_JSON", "").strip()
                credential = credentials.Certificate(json.loads(value)) if value else credentials.ApplicationDefault()
                self._app = firebase_admin.initialize_app(credential, options={"httpTimeout": 10}, name="rota-grm-server")
        return self._app

    def _send(self, device, event):
        from firebase_admin import messaging
        self._firebase_app()
        key = event["event_key"]
        local_id = "maintenance:" + event["record_id"] if key.startswith("maintenance:") else event["record_id"]
        data = {"event_id": digest(key), "alert_id": digest(local_id),
                "session_tag": digest(device["session_id"]),
                "sound": str(device["preferences"].get("soundEnabled", True)).lower()}
        message = messaging.Message(token=device["token"], data=data,
                                    android=messaging.AndroidConfig(priority="high", ttl=timedelta(seconds=300)))
        messaging.send(message, app=self._app)

    def process(self, snapshot):
        self.store.queue_alerts(snapshot, allowed_emails(), plan_alerts)
        # Bounded batch: a provider outage cannot monopolize the snapshot loop.
        for _ in range(50):
            if not self.store.deliver_one(allowed_emails(), self.sender):
                break