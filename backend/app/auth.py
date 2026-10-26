"""Fail-closed authentication for the Supabase OAuth Server tokens."""

from functools import lru_cache
import os
from urllib.parse import urlparse

import jwt
import requests
from fastapi import HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials
from jwt import PyJWKClient
from jwt.exceptions import PyJWKClientConnectionError


@lru_cache(maxsize=1)
def _oidc_config():
    issuer = os.getenv("OIDC_ISSUER", "").strip().rstrip("/")
    audience = os.getenv("OIDC_AUDIENCE", "").strip()
    client_id = os.getenv("OIDC_CLIENT_ID", "").strip()
    allowed_emails = frozenset(
        item.strip().casefold()
        for item in os.getenv("OIDC_ALLOWED_EMAILS", "").split(",")
        if item.strip()
    )

    parsed = urlparse(issuer)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or not audience
        or not client_id
        or not allowed_emails
    ):
        raise RuntimeError("OIDC issuer, audience, client ID, or user allowlist is not configured.")

    response = requests.get(f"{issuer}/.well-known/openid-configuration", timeout=(3.05, 8))
    response.raise_for_status()
    metadata = response.json()
    jwks_uri = metadata.get("jwks_uri", "")
    jwks = urlparse(jwks_uri)
    if (
        metadata.get("issuer", "").rstrip("/") != issuer
        or jwks.scheme != "https"
        or not jwks.hostname
        or jwks.username
        or jwks.password
    ):
        raise RuntimeError("OIDC discovery metadata is invalid.")

    return issuer, audience, client_id, allowed_emails, PyJWKClient(jwks_uri, timeout=8)


def require_user(credentials: HTTPAuthorizationCredentials | None):
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Autenticação necessária.")

    try:
        issuer, audience, client_id, allowed_emails, jwks_client = _oidc_config()
    except Exception:
        # Missing configuration or an unavailable identity provider must never
        # make the operational endpoint public.
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Autenticação indisponível.") from None

    try:
        signing_key = jwks_client.get_signing_key_from_jwt(credentials.credentials).key
        claims = jwt.decode(
            credentials.credentials,
            signing_key,
            algorithms=["RS256", "ES256"],
            audience=audience,
            issuer=issuer,
            options={"require": ["exp", "iat", "iss", "aud", "sub", "client_id", "email"]},
        )
    except PyJWKClientConnectionError:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Autenticação indisponível.") from None
    except Exception:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token inválido ou expirado.") from None

    email = str(claims.get("email", "")).strip().casefold()
    if claims.get("client_id") != client_id or claims.get("email_verified") is not True or email not in allowed_emails:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Usuário sem acesso ao serviço.")
    return claims
