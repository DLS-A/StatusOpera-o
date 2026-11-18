"""Scoped install credentials plus FCM token-possession challenge. No user login.

An unverified token receives only a silent random challenge, never operational alerts.
The API never returns that challenge. Confirmation requires both the install secret
and the challenge received by FCM on the same installation.
"""
import hashlib
import hmac
import os
import secrets
from datetime import datetime, timezone, timedelta
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator
from psycopg.types.json import Jsonb

from .notifications import FirebaseNotifier, digest, plan_alerts

class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    limitsMinutesByStatus: dict[str, int] = Field(default_factory=lambda: {"PAUSA ERGONOMICA": 15})
    alertsEnabledByStatus: dict[str, bool] = Field(default_factory=dict)
    soundEnabled: bool = True
    visualEnabled: bool = True
    repeatMinutes: int = Field(default=5, ge=1, le=1440)

    @field_validator("limitsMinutesByStatus", "alertsEnabledByStatus")
    @classmethod
    def valid_statuses(cls, value):
        if len(value) > 100 or any(not name.strip() or len(name) > 150 for name in value):
            raise ValueError("Invalid status preferences")
        return value

    @field_validator("limitsMinutesByStatus")
    @classmethod
    def valid_limits(cls, value):
        if any(not 1 <= minutes <= 1440 for minutes in value.values()):
            raise ValueError("Invalid limit")
        return value


class EnrollPush(BaseModel):
    model_config = ConfigDict(extra="forbid")
    deviceId: UUID
    token: str = Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_:\-]+$")
    preferences: Preferences = Field(default_factory=Preferences)


class ConfirmPush(BaseModel):
    model_config = ConfigDict(extra="forbid")
    challenge: str = Field(min_length=40, max_length=100, pattern=r"^[A-Za-z0-9_\-]+$")


def secret_from(request):
    value = request.headers.get("authorization", "")
    if not value.startswith("Bearer "):
        raise HTTPException(401, "Cadastro do aparelho necessário.")
    value = value[7:]
    import re
    if not re.fullmatch(r"[A-Za-z0-9_\-]{40,100}", value):
        raise HTTPException(401, "Credencial inválida.")
    return digest(value)


def owner(row, secret_hash):
    if not row or not hmac.compare_digest(row["secret_hash"], secret_hash):
        raise HTTPException(403, "Cadastro pertence a outro aparelho.")


class PublicPushNotifier(FirebaseNotifier):
    def send_challenge(self, token, device_id, challenge):
        from firebase_admin import messaging
        self._firebase_app()
        messaging.send(messaging.Message(token=token,
            data={"kind": "enrollment", "device_id": str(device_id), "challenge": challenge},
            android=messaging.AndroidConfig(priority="high", ttl=timedelta(minutes=10))), app=self._app)

    def _send(self, device, event):
        from firebase_admin import messaging
        self._firebase_app()
        key = event["event_key"]
        original_key = key.split(":repeat:", 1)[0]
        local_id = "maintenance:" + event["record_id"] if original_key.startswith("maintenance:") else event["record_id"]
        data = {"kind": "operational", "event_id": digest(original_key), "alert_id": digest(local_id),
                "device_tag": digest(device["id"]), "sound": str(device["preferences"].get("soundEnabled", True)).lower(),
                "repeat": str(not original_key.startswith("maintenance:")).lower()}
        messaging.send(messaging.Message(token=device["token"], data=data,
            android=messaging.AndroidConfig(priority="high", ttl=timedelta(minutes=5))), app=self._app)

    def process(self, snapshot):
        if snapshot.get("stale") or not snapshot.get("sourceAvailable", True) or not snapshot.get("persistenceAvailable", True):
            return
        with self.store.connection() as conn:
            if not conn.execute("select pg_try_advisory_xact_lock(7150231105) as acquired").fetchone()["acquired"]:
                return
            devices = conn.execute("select * from operational.public_push_devices where enabled and token is not null and last_seen_at>now()-interval '30 days'").fetchall()
            for device in devices:
                events, observed, active = plan_alerts(snapshot, device)
                conn.execute("update operational.public_push_devices set observed_maintenance=%s where id=%s", (Jsonb(observed), device["id"]))
                interval = max(1, min(1440, device["preferences"].get("repeatMinutes", 5))) * 60
                slot = int(datetime.now(timezone.utc).timestamp()) // interval
                keys = [(key if key.startswith("maintenance:") else key + ":repeat:" + str(slot), record) for key, record in events]
                for key, record in keys:
                    conn.execute("insert into operational.public_push_outbox(id,device_id,event_key,record_id) values(%s,%s,%s,%s) on conflict(device_id,event_key) do nothing", (uuid4(), device["id"], key, record))
                conn.execute("update operational.public_push_outbox set status='cancelled' where device_id=%s and status='pending' and not(event_key=any(%s))", (device["id"], [k for k, _ in keys]))
            conn.execute("delete from operational.public_push_outbox where created_at<now()-interval '30 days'")
        for _ in range(50):
            with self.store.connection() as conn:
                row = conn.execute("select * from operational.public_push_outbox where status='pending' and next_attempt_at<=now() order by created_at limit 1 for update skip locked").fetchone()
                if not row:
                    break
                device = conn.execute("select * from operational.public_push_devices where id=%s and enabled and last_seen_at>now()-interval '30 days' for update", (row["device_id"],)).fetchone()
                if not device or not device["preferences"].get("visualEnabled", True):
                    conn.execute("update operational.public_push_outbox set status='cancelled' where id=%s", (row["id"],))
                    continue
                try:
                    self.sender(device, row)
                except Exception as error:
                    attempts = row["attempts"] + 1
                    invalid = type(error).__name__ in {"UnregisteredError", "SenderIdMismatchError"}
                    if invalid:
                        conn.execute("update operational.public_push_devices set enabled=false where id=%s", (device["id"],))
                    conn.execute("update operational.public_push_outbox set status=%s,attempts=%s,next_attempt_at=now()+%s*interval '1 second' where id=%s", ('cancelled' if invalid or attempts >= 5 else 'pending', attempts, min(900, 30 * 2 ** attempts), row["id"]))
                else:
                    conn.execute("update operational.public_push_outbox set status='sent',sent_at=now() where id=%s", (row["id"],))


router = APIRouter(prefix="/v1/push")


def push_service(request: Request):
    service = getattr(request.app.state, "public_push", None)
    if service is None or os.getenv("AUTH_MODE") != "public":
        raise HTTPException(503, "Push ainda não disponível.")
    return service


@router.post("/enroll")
def enroll(body: EnrollPush, request: Request, service=Depends(push_service)):
    credential = secret_from(request)
    peer = digest(request.client.host if request.client else "unknown")
    challenge = secrets.token_urlsafe(32)
    with service.store.connection() as conn:
        conn.execute("delete from operational.public_push_rates where bucket<%s", (int(datetime.now(timezone.utc).timestamp()) // 3600 - 2,))
        bucket = int(datetime.now(timezone.utc).timestamp()) // 3600
        attempts = conn.execute("insert into operational.public_push_rates(peer_hash,bucket) values(%s,%s) on conflict(peer_hash,bucket) do update set attempts=operational.public_push_rates.attempts+1 returning attempts", (peer, bucket)).fetchone()["attempts"]
        # Commit rate counters even for refused requests, without registering anything.
        if attempts > 30:
            conn.commit()
            raise HTTPException(429, "Muitas tentativas de cadastro; tente mais tarde.")
        conn.commit()
        conn.execute("select pg_advisory_xact_lock(7150231106)")
        conn.execute("delete from operational.public_push_devices where token is null and challenge_expires<now()")
        row = conn.execute("select * from operational.public_push_devices where id=%s for update", (body.deviceId,)).fetchone()
        if row:
            owner(row, credential)
            if row["token"] == body.token:
                conn.execute("update operational.public_push_devices set preferences=%s,enabled=true,last_seen_at=now() where id=%s", (Jsonb(body.preferences.model_dump()), body.deviceId))
                return {"registered": True}
        if conn.execute("select id from operational.public_push_devices where (token=%s or pending_token=%s) and id<>%s", (body.token, body.token, body.deviceId)).fetchone():
            raise HTTPException(403, "Token já possui cadastro.")
        if not row and conn.execute("select count(*) as n from operational.public_push_devices").fetchone()["n"] >= 5000:
            raise HTTPException(429, "Limite de cadastros atingido.")
        conn.execute("insert into operational.public_push_devices(id,secret_hash,pending_token,challenge_hash,challenge_expires,preferences) values(%s,%s,%s,%s,now()+interval '10 minutes',%s) on conflict(id) do update set pending_token=excluded.pending_token,challenge_hash=excluded.challenge_hash,challenge_expires=excluded.challenge_expires,preferences=excluded.preferences,last_seen_at=now()", (body.deviceId, credential, body.token, digest(challenge), Jsonb(body.preferences.model_dump())))
    # Send only after the pending transaction has committed. Provider failure
    # leaves an expiring, unconfirmed row, never an enabled device.
    try:
        service.send_challenge(body.token, body.deviceId, challenge)
    except Exception:
        raise HTTPException(503, "Confirmação de push indisponível; tente novamente.") from None
    return {"registered": False, "confirmationPending": True}


@router.put("/{device_id}/confirm")
def confirm(device_id: UUID, body: ConfirmPush, request: Request, service=Depends(push_service)):
    credential = secret_from(request)
    with service.store.connection() as conn:
        row = conn.execute("select * from operational.public_push_devices where id=%s for update", (device_id,)).fetchone()
        owner(row, credential)
        if not row["challenge_hash"] or not row["challenge_expires"] or row["challenge_expires"] <= datetime.now(timezone.utc) or not hmac.compare_digest(row["challenge_hash"], digest(body.challenge)):
            raise HTTPException(403, "Confirmação inválida ou expirada.")
        conn.execute("update operational.public_push_devices set token=pending_token,pending_token=null,challenge_hash=null,challenge_expires=null,enabled=true,last_seen_at=now() where id=%s", (device_id,))
    return {"registered": True}


@router.delete("/{device_id}")
def disable(device_id: UUID, request: Request, service=Depends(push_service)):
    credential = secret_from(request)
    with service.store.connection() as conn:
        row = conn.execute("select * from operational.public_push_devices where id=%s for update", (device_id,)).fetchone()
        owner(row, credential)
        conn.execute("update operational.public_push_devices set enabled=false where id=%s", (device_id,))
        conn.execute("update operational.public_push_outbox set status='cancelled' where device_id=%s and status='pending'", (device_id,))
    return {"removed": True}