"""Persistence for the authenticated operational snapshot API."""

from __future__ import annotations

import os
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb


def persist_snapshot(records: list[dict], public_records: list[dict]) -> None:
    """Store the latest GRM record values and one audit row per successful poll."""
    database_url = os.getenv("SUPABASE_DB_URL", "").strip()
    if not database_url:
        raise RuntimeError("SUPABASE_DB_URL is not configured")

    snapshot_id = uuid4()
    tags = {str(item.get("equipmentTag", "")) for item in records if item.get("equipmentTag")}

    with psycopg.connect(
        database_url,
        connect_timeout=5,
        sslmode="require",
        prepare_threshold=None,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                insert into operational.snapshot_runs (id, record_count, equipment_count)
                values (%s, %s, %s)
                """,
                (snapshot_id, len(public_records), len(tags)),
            )
            cursor.executemany(
                """
                insert into operational.records (
                    grm_record_id, equipment_tag, reason_name, start_date, end_date,
                    payload, last_snapshot_id
                ) values (%s, %s, %s, %s, %s, %s, %s)
                on conflict (grm_record_id) do update set
                    equipment_tag = excluded.equipment_tag,
                    reason_name = excluded.reason_name,
                    start_date = excluded.start_date,
                    end_date = excluded.end_date,
                    payload = excluded.payload,
                    last_seen_at = now(),
                    last_snapshot_id = excluded.last_snapshot_id
                """,
                (
                    (
                        item["id"],
                        item["equipmentTag"],
                        item["reasonName"],
                        record.get("_inicio_dt"),
                        record.get("_fim_dt"),
                        Jsonb(item),
                        snapshot_id,
                    )
                    for record, item in zip(records, public_records, strict=True)
                ),
            )
