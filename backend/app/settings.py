"""Validated server-only configuration. No private values are logged."""
from dataclasses import dataclass
import os
from urllib.parse import urlparse


def https_url(value: str) -> str:
    parsed = urlparse(value.strip())
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or
            parsed.password or parsed.query or parsed.fragment):
        raise ValueError("An HTTPS URL without credentials/query/fragment is required")
    return value.strip().rstrip("/")


def integer(name: str, default: int, minimum: int, maximum: int) -> int:
    value = int(os.getenv(name, str(default)))
    if not minimum <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return value


@dataclass(frozen=True)
class Settings:
    grm_url: str
    grm_token: str
    database_url: str
    interval: int = 60
    stale_after: int = 120
    retention_days: int = 7
    push_enabled: bool = False
    operating_categories: frozenset[str] = frozenset()
    stopped_categories: frozenset[str] = frozenset()

    @classmethod
    def from_env(cls):
        url = https_url(os.getenv("URL_API_GRM", ""))
        token = os.getenv("TOKEN_API_APONTAMENTOS_GRM_PUSH", "").strip()
        database = os.getenv("SUPABASE_DB_URL", "").strip()
        if not token or not database:
            raise ValueError("GRM/database configuration is incomplete")
        operating = frozenset(c.strip() for c in os.getenv("OPERATING_KPI_CATEGORIES", "").split(",") if c.strip())
        stopped = frozenset(c.strip() for c in os.getenv("STOPPED_KPI_CATEGORIES", "").split(",") if c.strip())
        if operating & stopped:
            raise ValueError("Operational classification categories overlap")
        return cls(url, token, database,
                   integer("SNAPSHOT_INTERVAL_SECONDS", 60, 30, 3600),
                   integer("SNAPSHOT_STALE_SECONDS", 120, 60, 86400),
                   integer("SNAPSHOT_RETENTION_DAYS", 7, 1, 90),
                   os.getenv("FCM_ENABLED", "false").lower() == "true", operating, stopped)