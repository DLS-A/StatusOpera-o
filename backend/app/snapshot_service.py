"""Snapshots are refreshed by a shared server loop, never by each phone."""
import asyncio
from copy import deepcopy
from datetime import datetime, timezone
import logging
import threading

from functions import (calcular_duracao_segundos, obter_apontamentos_abertos,
                       obter_manutencoes_abertas, obter_manutencoes_atuais,
                       obter_pausas_ergonomicas_abertas)
from .report_contract import current_records, maintenance_reason, pause_reason, original_start, operational_state, PointingHistory

log = logging.getLogger(__name__)


def public_record(item):
    start = item.get("_inicio_dt")
    return {"id": str(item["id"]), "equipmentTag": str(item.get("equipmentTag") or ""),
            "reasonName": str(item.get("reasonName") or ""), "startDate": item.get("startDate"),
            "startEpochMillis": int(start.timestamp() * 1000) if start else None,
            "startConfirmed": item.get('_start_confirmed', False),
            "statusType": str(item.get('statusType') or ''),
            "endDate": item.get("endDate"),
            "durationMeasuredAtEpochMillis": int(item['_fim_dt'].timestamp()*1000) if item.get('_fim_dt') else None,
            "durationSeconds": calcular_duracao_segundos(start, item.get("_fim_dt")),
            **{name: str(item.get(name) or "") for name in
               ("operator", "location", "equipmentType", "kpiCategory")}}


def build_snapshot(records, settings=None):
    index = PointingHistory(records)
    active = [original_start(records, r, index) for r in current_records(records)]
    current = {r["equipmentTag"]: r for r in active}
    tags = sorted({str(r["equipmentTag"]) for r in records if r.get("equipmentTag")})
    def state(tag):
        return operational_state(current.get(tag, {}))
    return {"updatedAt": datetime.now(timezone.utc).isoformat(),
            "equipment": [{"equipmentTag": tag, "operationalState": state(tag), "current": public_record(current[tag]) if tag in current else None} for tag in tags],
            "pauses": [public_record(r) for r in active if pause_reason(r['reasonName'])],
            "maintenance": [public_record(r) for r in active if maintenance_reason(r['reasonName'])],
            "maintenanceAlerts": [public_record(r) for r in active if maintenance_reason(r['reasonName'])],
            "records": [public_record(r) for r in records]}


class SnapshotService:
    def __init__(self, settings, store, client, notifier=None, clock=None):
        self.settings, self.store, self.client, self.notifier = settings, store, client, notifier
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = threading.Lock()
        self._poll_lock = threading.Lock()
        self._payload = None
        self._attempt = None
        self._failed = True
        self._database_available = False

    def _set(self, result):
        payload, state = result
        with self._lock:
            if payload is not None:
                self._payload = payload
            self._attempt = state.get("attempted_at")
            self._failed = bool(state.get("failed", True))
            self._database_available = True

    def restore(self):
        try:
            result = self.store.latest()
            self._set(result)
            if result[0] and hasattr(self.client, 'restore_history'):
                self.client.restore_history(result[0])
        except Exception:
            with self._lock:
                self._database_available = False
            log.warning("Snapshot persistence unavailable during restore")

    def refresh(self):
        if not self._poll_lock.acquire(blocking=False):
            return
        try:
            def produce():
                records = self.client.fetch()
                return records, build_snapshot(records, self.settings)
            self._set(self.store.poll(produce))
        except Exception:
            with self._lock:
                self._failed = True
                self._database_available = False
            log.warning("Shared snapshot update unavailable; preserving last valid snapshot")
        finally:
            self._poll_lock.release()
        response = self.read()
        if self.notifier and response and not response["stale"]:
            try:
                self.notifier.process(response)
            except Exception:
                log.warning("Notification processing unavailable")

    def read(self):
        with self._lock:
            if self._payload is None:
                return None
            response = deepcopy(self._payload)
            response.pop("records", None)
            try:
                age = max(0, (self.clock() - datetime.fromisoformat(response["updatedAt"].replace("Z", "+00:00"))).total_seconds())
            except (KeyError, ValueError, TypeError):
                return None
            response.update({"stale": self._failed or not self._database_available or age > self.settings.stale_after,
                             "ageSeconds": int(age), "serverTime": self.clock().isoformat(),
                             "lastAttemptAt": self._attempt.isoformat() if self._attempt else None,
                             "sourceAvailable": not self._failed and age <= self.settings.stale_after,
                             "persistenceAvailable": self._database_available,
                             "refreshIntervalSeconds": self.settings.interval})
            return response

    async def run(self):
        # A background loop is application functionality, not an agent polling loop.
        while True:
            await asyncio.to_thread(self.refresh)
            # The poll itself takes time. Do not add another full interval.
            # Anchor to the database attempt, with a small boundary margin so
            # interval throttling cannot skip every other refresh.
            with self._lock:
                attempted = self._attempt
            elapsed = (self.clock() - attempted).total_seconds() if attempted else 0
            await asyncio.sleep(max(1.0, self.settings.interval + 1 - elapsed))