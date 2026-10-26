"""Authenticated snapshot endpoint; GRM credentials remain server-side."""

from datetime import datetime, timezone
from html import escape
import logging
import os
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.responses import HTMLResponse, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from functions import (
    calcular_duracao_segundos,
    obter_apontamentos_abertos,
    obter_manutencoes_abertas,
    obter_manutencoes_atuais,
    obter_pausas_ergonomicas_abertas,
    api_content_formatted,
)
from .auth import require_user
from .database import persist_snapshot


app = FastAPI(
    title="ROTA GRM API",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
_logger = logging.getLogger(__name__)
_bearer = HTTPBearer(auto_error=False)


def _authenticated_user(credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)):
    return require_user(credentials)


def _public_record(item: dict) -> dict:
    start = item.get("startDate")
    end = item.get("endDate")
    start_at = item.get("_inicio_dt")
    return {
        "id": str(item.get("id", "")),
        "equipmentTag": str(item.get("equipmentTag", "")),
        "reasonName": str(item.get("reasonName", "")),
        "startDate": start,
        "startEpochMillis": int(start_at.timestamp() * 1000) if start_at is not None else None,
        "endDate": end,
        "durationSeconds": calcular_duracao_segundos(item.get("_inicio_dt"), item.get("_fim_dt")),
        "operator": str(item.get("operator", "")),
        "location": str(item.get("location", "")),
        "equipmentType": str(item.get("equipmentType", "")),
        "kpiCategory": str(item.get("kpiCategory", "")),
    }


@app.get("/healthz")
def healthz():
    # Liveness only; does not reveal upstream or configuration details.
    return {"status": "ok"}


@app.get("/oauth/consent", response_class=HTMLResponse, include_in_schema=False)
def oauth_consent():
    supabase_url = os.getenv("SUPABASE_URL", "").strip().rstrip("/")
    publishable_key = os.getenv("SUPABASE_PUBLISHABLE_KEY", "").strip()
    if not supabase_url.startswith("https://") or not publishable_key:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Login indisponível.")

    page = Path(__file__).with_name("oauth_consent.html").read_text(encoding="utf-8")
    page = page.replace("__SUPABASE_URL__", escape(supabase_url, quote=True))
    page = page.replace("__SUPABASE_PUBLISHABLE_KEY__", escape(publishable_key, quote=True))
    return HTMLResponse(
        page,
        headers={
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": (
                "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                "script-src 'self' https://esm.sh; style-src 'unsafe-inline'; "
                "connect-src 'self' https://byjmnvnojwssjswyeybj.supabase.co"
            ),
        },
    )


@app.get("/oauth/consent.js", include_in_schema=False)
def oauth_consent_script():
    script = Path(__file__).with_name("oauth_consent.js").read_text(encoding="utf-8")
    return Response(
        script,
        media_type="text/javascript",
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
    )


@app.get("/v1/snapshot")
def snapshot(_claims: dict = Depends(_authenticated_user)):
    try:
        records = api_content_formatted()
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Não foi possível consultar os dados GRM.",
        ) from None

    open_records = obter_apontamentos_abertos(records)
    latest_by_tag = {record["equipmentTag"]: record for record in open_records}
    tags = sorted({str(record.get("equipmentTag", "")) for record in records if record.get("equipmentTag")})
    equipment = []
    for tag in tags:
        current = latest_by_tag.get(tag)
        equipment.append({"equipmentTag": tag, "current": _public_record(current) if current else None})

    pauses = [_public_record(item) for item in obter_pausas_ergonomicas_abertas(records)]
    maintenance = [_public_record(item) for item in obter_manutencoes_abertas(records)]
    maintenance_alerts = [_public_record(item) for item in obter_manutencoes_atuais(records)]
    public_records = [_public_record(item) for item in records]
    try:
        persist_snapshot(records, public_records)
    except Exception as error:
        _logger.error("Falha ao persistir snapshot operacional (%s).", type(error).__name__)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="N\u00e3o foi poss\u00edvel salvar os dados operacionais.",
        ) from None

    return {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "equipment": equipment,
        "pauses": pauses,
        "maintenance": maintenance,
        # Preserves the legacy alert rule: only maintenance opened today in the
        # project's configured America/Sao_Paulo timezone is eligible.
        "maintenanceAlerts": maintenance_alerts,
    }
