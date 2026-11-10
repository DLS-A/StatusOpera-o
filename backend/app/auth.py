"""Fail-closed Supabase OAuth access-token validation and authoritative user checks."""
from functools import lru_cache
from datetime import datetime, timezone
import os
from urllib.parse import urlparse
from uuid import UUID

from fastapi import HTTPException
import jwt
from jwt import PyJWKClient
from jwt.exceptions import PyJWKClientConnectionError
import requests

from .settings import https_url, Settings
from .store import PostgresStore


def allowed_emails():
    return frozenset(e.strip().casefold() for e in os.getenv("OIDC_ALLOWED_EMAILS", "").split(",") if e.strip())


def public_key():
    value = os.getenv("SUPABASE_PUBLISHABLE_KEY", "").strip()
    if not value or value.startswith("sb_secret_"):
        raise ValueError("A publishable key is required")
    # Reject legacy JWT service-role keys too. Parsing here does not grant access.
    if value.count(".") == 2:
        if jwt.decode(value, options={"verify_signature": False}).get("role") != "anon":
            raise ValueError("Not a public Supabase key")
    elif not value.startswith("sb_publishable_"):
        raise ValueError("Not a public Supabase key")
    return value


@lru_cache(maxsize=1)
def _oidc_config():
    issuer = https_url(os.getenv("OIDC_ISSUER", ""))
    supabase = https_url(os.getenv("SUPABASE_URL", ""))
    audience = os.getenv("OIDC_AUDIENCE", "").strip()
    client_id = os.getenv("OIDC_CLIENT_ID", "").strip()
    if issuer != supabase + "/auth/v1" or not audience or not client_id or not allowed_emails():
        raise ValueError("OIDC configuration is incomplete")
    public_key()
    response = requests.get(issuer + "/.well-known/openid-configuration", timeout=(3, 8), allow_redirects=False)
    if response.status_code != 200:
        raise ValueError("OIDC discovery unavailable")
    metadata = response.json()
    jwks = https_url(metadata.get("jwks_uri", ""))
    if metadata.get("issuer", "").rstrip("/") != issuer or urlparse(jwks).netloc != urlparse(issuer).netloc:
        raise ValueError("Invalid discovery metadata")
    return issuer, audience, client_id, PyJWKClient(jwks, timeout=8)


def _verified_user(token, issuer):
    try:
        response = requests.get(issuer + "/user", headers={"Authorization": "Bearer " + token, "apikey": public_key()},
                                timeout=(3, 8), allow_redirects=False)
    except requests.RequestException:
        raise HTTPException(503, "Autenticação indisponível.") from None
    if response.status_code in (401, 403):
        raise HTTPException(401, "Sessão inválida ou encerrada.")
    if response.status_code != 200:
        raise HTTPException(503, "Autenticação indisponível.")
    try:
        user = response.json()
        if not isinstance(user, dict):
            raise ValueError()
        return user
    except ValueError:
        raise HTTPException(503, "Autenticação indisponível.") from None


def require_user(credentials, store=None):
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(401, "Autenticação necessária.", headers={"WWW-Authenticate": "Bearer"})
    token = credentials.credentials
    if len(token) > 16384:
        raise HTTPException(401, "Token inválido.")
    try:
        issuer, audience, client_id, jwks = _oidc_config()
    except Exception:
        raise HTTPException(503, "Autenticação indisponível.") from None
    try:
        key = jwks.get_signing_key_from_jwt(token).key
        claims = jwt.decode(token, key, algorithms=["RS256", "ES256"], audience=audience, issuer=issuer,
                            options={"require": ["exp", "iat", "iss", "aud", "sub", "client_id", "role", "session_id"]})
        UUID(claims["sub"])
        UUID(claims["session_id"])
    except PyJWKClientConnectionError:
        raise HTTPException(503, "Autenticação indisponível.") from None
    except Exception:
        raise HTTPException(401, "Token inválido ou expirado.", headers={"WWW-Authenticate": "Bearer"}) from None
    if claims["client_id"] != client_id or claims["role"] != "authenticated":
        raise HTTPException(403, "Usuário sem acesso ao serviço.")
    # email_verified is NOT required on the access JWT. Use the authenticated
    # /user response, not an unverified claim or an ID token used as bearer.
    user = _verified_user(token, issuer)
    email = str(user.get("email") or "").strip().casefold()
    if user.get("id") != claims["sub"] or not user.get("email_confirmed_at") or email not in allowed_emails():
        raise HTTPException(403, "Usuário sem acesso ao serviço.")
    if user.get("banned_until"):
        try:
            banned = datetime.fromisoformat(user["banned_until"].replace("Z", "+00:00")) > datetime.now(timezone.utc)
        except (TypeError, ValueError):
            raise HTTPException(503, "Validação do usuário indisponível.") from None
        if banned:
            raise HTTPException(403, "Usuário sem acesso ao serviço.")
    claims["email"] = email
    try:
        session_store = store or PostgresStore(Settings.from_env())
        active = session_store.session_active(claims)
    except Exception:
        raise HTTPException(503, "Validação da sessão indisponível.") from None
    if not active:
        raise HTTPException(401, "Sessão encerrada. Entre novamente.")
    return claims


def provider_logout(token):
    issuer = https_url(os.getenv("OIDC_ISSUER", ""))
    try:
        response = requests.post(issuer + "/logout", params={"scope": "local"},
                                 headers={"apikey": public_key(), "Authorization": "Bearer " + token},
                                 timeout=(3, 8), allow_redirects=False)
        return response.status_code in (200, 204, 401)
    except (requests.RequestException, ValueError):
        return False