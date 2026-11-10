"""One-time team-code enrollment; opaque, individually revocable phone credentials.

No GRM credential or activation code is returned in a snapshot or embedded in APKs.
Only credential hashes are persisted. Database-backed enrollment throttles survive restarts.
"""
import hashlib
import hmac
import os
import re
import secrets
from uuid import uuid4

from fastapi import HTTPException


def activation_enabled():
    return os.getenv("AUTH_MODE", "oauth") == "activation"


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def enroll(store, code, peer):
    expected = os.getenv("ACTIVATION_CODE_SHA256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise HTTPException(503, "Ativação indisponível.")
    # Count both successes and failures, without persisting peer addresses or codes.
    # Global counter also bounds distributed guesses and accidental mass enrollments.
    scopes = [(digest("ip:" + peer), 10), (digest("global"), 200)]
    allowed = True
    try:
        with store.connection() as conn:
            for scope, limit in sorted(scopes):
                row = conn.execute("""insert into operational.activation_attempts(scope,window_start,attempts)
                    values (%s,now(),1) on conflict(scope) do update set
                    attempts=case when operational.activation_attempts.window_start < now()-interval '15 minutes'
                        then 1 else operational.activation_attempts.attempts+1 end,
                    window_start=case when operational.activation_attempts.window_start < now()-interval '15 minutes'
                        then now() else operational.activation_attempts.window_start end returning attempts""", (scope,)).fetchone()
                allowed = allowed and row["attempts"] <= limit
            conn.execute("delete from operational.activation_attempts where window_start < now()-interval '1 day'")
    except Exception:
        raise HTTPException(503, "Ativação indisponível.") from None
    if not allowed:
        raise HTTPException(429, "Muitas tentativas. Aguarde 15 minutos.", headers={"Retry-After": "900"})
    if not hmac.compare_digest(digest(code.strip().upper()), expected):
        raise HTTPException(403, "Código de ativação inválido.")
    token = "soa_" + secrets.token_urlsafe(32)
    device = str(uuid4())
    try:
        with store.connection() as conn:
            conn.execute("""insert into operational.activated_devices(id,token_hash)
                values (%s,%s)""", (device, digest(token)))
    except Exception:
        raise HTTPException(503, "Ativação indisponível.") from None
    return {"deviceId": device, "accessToken": token}


def require_device(credentials, store):
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(401, "Ative este telefone.", headers={"WWW-Authenticate": "Bearer"})
    token = credentials.credentials
    if not re.fullmatch(r"soa_[A-Za-z0-9_-]{43}", token):
        raise HTTPException(401, "Ativação inválida.")
    if store is None:
        raise HTTPException(503, "Validação indisponível.")
    try:
        with store.connection() as conn:
            row = conn.execute("""select id from operational.activated_devices
                where token_hash=%s and enabled""", (digest(token),)).fetchone()
    except Exception:
        raise HTTPException(503, "Validação indisponível.") from None
    if not row:
        raise HTTPException(401, "Ativação revogada. Consulte o responsável.")
    device = str(row["id"])
    return {"sub": device, "session_id": device, "email": "", "activation": True}


def revoke_device(store, claims):
    with store.connection() as conn:
        conn.execute("update operational.activated_devices set enabled=false where id=%s", (claims["sub"],))