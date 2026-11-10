"""Read-only operational API; session/device writes never reach the GRM API."""
import asyncio
from contextlib import asynccontextmanager, suppress
from html import escape
import logging
import os
from pathlib import Path
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.security import HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, field_validator
from starlette.exceptions import HTTPException as StarletteHTTPException

from .auth import require_user, provider_logout, public_key
from .activation import activation_enabled, enroll, require_device, revoke_device
from .grm_client import GrmClient
from .notifications import FirebaseNotifier
from .settings import Settings, https_url
from .snapshot_service import SnapshotService
from .store import PostgresStore

log = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)


@asynccontextmanager
async def lifespan(app):
    task = None
    app.state.service = None
    app.state.store = None
    try:
        settings = Settings.from_env()
        store = PostgresStore(settings)
        notifier = FirebaseNotifier(store) if settings.push_enabled else None
        service = SnapshotService(settings, store, GrmClient(settings), notifier)
        app.state.store, app.state.service = store, service
        await asyncio.to_thread(service.restore)
        task = asyncio.create_task(service.run())
    except Exception:
        log.warning("Server configuration incomplete; operational endpoints remain protected")
    try:
        yield
    finally:
        if task:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task


app = FastAPI(title="ROTA GRM API", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


def get_store(request: Request):
    store = getattr(request.app.state, "store", None)
    if store is None:
        raise HTTPException(503, "Serviço operacional indisponível.")
    return store


def authenticated_user(request: Request, credentials=Depends(bearer)):
    if activation_enabled():
        return require_device(credentials, getattr(request.app.state, "store", None))
    # Missing bearer must return 401 even on an unconfigured server.
    if credentials is None:
        return require_user(None)
    return require_user(credentials, getattr(request.app.state, "store", None))


@app.exception_handler(StarletteHTTPException)
async def http_error(request, exception):
    codes = {401: "AUTH_REQUIRED", 403: "ACCESS_DENIED", 405: "METHOD_NOT_ALLOWED", 503: "SERVICE_UNAVAILABLE"}
    message = str(exception.detail)
    return JSONResponse({"error": {"code": codes.get(exception.status_code, "REQUEST_FAILED"), "message": message}, "detail": message},
                        status_code=exception.status_code, headers=exception.headers or {})


@app.exception_handler(RequestValidationError)
async def validation_error(request, exception):
    return JSONResponse({"error": {"code": "INVALID_REQUEST", "message": "Requisição inválida."}}, status_code=422)


@app.middleware("http")
async def protect_transport(request: Request, call_next):
    operational = request.url.path.startswith(("/v1/", "/oauth/"))
    if operational and request.url.scheme != "https":
        return JSONResponse({"error": {"code": "HTTPS_REQUIRED", "message": "HTTPS obrigatório."}}, status_code=400)
    if operational and request.method in ("POST", "PUT", "PATCH"):
        content = bytearray()
        async for chunk in request.stream():
            content.extend(chunk)
            if len(content) > 65536:
                return JSONResponse({"error": {"code": "BODY_TOO_LARGE", "message": "Requisição excede o limite."}}, status_code=413)
        request._body = bytes(content)
    try:
        response = await call_next(request)
    except Exception:
        log.error("Unhandled request failure (details suppressed)")
        response = JSONResponse({"error": {"code": "INTERNAL_ERROR", "message": "Falha interna do serviço."}}, status_code=500)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    if operational:
        response.headers["Cache-Control"] = "private, no-store"
        response.headers["Strict-Transport-Security"] = "max-age=31536000"
    return response


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


def snapshot_reader(request: Request, credentials=Depends(bearer)):
    if os.getenv('AUTH_MODE') == 'public':
        return None
    return authenticated_user(request, credentials)


@app.get("/v1/readiness")
def readiness(request: Request, claims=Depends(snapshot_reader)):
    service = getattr(request.app.state, "service", None)
    snapshot = service.read() if service else None
    if not snapshot or snapshot["stale"]:
        raise HTTPException(503, "Dados operacionais ainda não estão disponíveis ou atualizados.")
    return {"status": "ready", "updatedAt": snapshot["updatedAt"]}


@app.get("/v1/snapshot")
def snapshot(request: Request, claims=Depends(snapshot_reader)):
    service = getattr(request.app.state, "service", None)
    result = service.read() if service else None
    if result is None:
        raise HTTPException(503, "Nenhum snapshot válido disponível.")
    # No GRM request or database write here. All devices receive the shared result.
    return result


@app.get("/v1/session")
def session(claims=Depends(authenticated_user)):
    return {"userId": claims["sub"], "sessionId": claims["session_id"], "email": claims["email"]}


class ActivationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(min_length=12, max_length=80)


@app.post("/v1/activation")
def activate(body: ActivationRequest, request: Request, store=Depends(get_store)):
    if not activation_enabled():
        raise HTTPException(404, "Ativação não disponível.")
    peer = request.client.host if request.client else "unknown"
    return enroll(store, body.code, peer)


@app.post("/v1/session/logout")
def logout(claims=Depends(authenticated_user), credentials=Depends(bearer), store=Depends(get_store)):
    if claims.get("activation"):
        revoke_device(store, claims)
        return {"serverRevoked": True, "providerRevoked": True}
    store.revoke_session(claims)
    return {"serverRevoked": True, "providerRevoked": provider_logout(credentials.credentials)}


class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    limitsMinutesByStatus: dict[str, int] = Field(default_factory=lambda: {"PAUSA ERGONOMICA": 15})
    alertsEnabledByStatus: dict[str, bool] = Field(default_factory=dict)
    soundEnabled: bool = True
    visualEnabled: bool = True

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


class DeviceRegistration(BaseModel):
    model_config = ConfigDict(extra="forbid")
    token: str = Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_:\-]+$")
    preferences: Preferences = Field(default_factory=Preferences)


@app.put("/v1/devices/{device_id}")
def register_device(device_id: UUID, body: DeviceRegistration, claims=Depends(authenticated_user), store=Depends(get_store)):
    if claims.get("activation"):
        raise HTTPException(403, "Push desabilitado nesta versão.")
    try:
        store.register_device(claims, device_id, body.token, body.preferences.model_dump())
    except PermissionError:
        raise HTTPException(403, "Dispositivo sem autorização.") from None
    return {"registered": True}


@app.delete("/v1/devices/{device_id}")
def remove_device(device_id: UUID, claims=Depends(authenticated_user), store=Depends(get_store)):
    if claims.get("activation"):
        raise HTTPException(403, "Push desabilitado nesta versão.")
    store.remove_device(claims, device_id)
    return {"removed": True}


@app.get("/oauth/consent", response_class=HTMLResponse)
def oauth_consent():
    if activation_enabled() or os.getenv('AUTH_MODE') == 'public':
        raise HTTPException(404, "Não disponível nesta versão.")
    try:
        supabase = https_url(os.getenv("SUPABASE_URL", ""))
        key = public_key()
    except ValueError:
        raise HTTPException(503, "Login indisponível.") from None
    page = Path(__file__).with_name("oauth_consent.html").read_text(encoding="utf-8")
    page = page.replace("__SUPABASE_URL__", escape(supabase, quote=True)).replace("__SUPABASE_PUBLISHABLE_KEY__", escape(key, quote=True))
    return HTMLResponse(page, headers={"Content-Security-Policy":
        "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'; "
        f"script-src 'self' https://esm.sh; style-src 'unsafe-inline'; connect-src 'self' {supabase}"})


@app.get("/oauth/consent.js")
def oauth_script():
    if activation_enabled() or os.getenv('AUTH_MODE') == 'public':
        raise HTTPException(404, "Não disponível nesta versão.")
    return Response(Path(__file__).with_name("oauth_consent.js").read_text(encoding="utf-8"), media_type="text/javascript")