"""Deprecated per-request persistence; use the coordinated PostgresStore.

Kept as a fail-fast import compatibility shim. It must never poll or persist
operational state independently of the shared snapshot service.
"""
from .store import PostgresStore


def persist_snapshot(records: list[dict], public_records: list[dict]) -> None:
    raise RuntimeError("Retired per-request persistence; use PostgresStore.poll")
