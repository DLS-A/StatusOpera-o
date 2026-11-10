"""Private PostgreSQL coordination, snapshots, session revocation and notification outbox."""
from contextlib import contextmanager
from datetime import datetime, timezone
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


class PostgresStore:
    def __init__(self, settings):
        self.settings = settings

    @contextmanager
    def connection(self):
        # Network/SQL timeouts; server-only credentials. Set a trusted root CA via
        # PGSSLROOTCERT for verify-full in production (see installation guide).
        with psycopg.connect(self.settings.database_url, connect_timeout=5,
                             sslmode="verify-full", prepare_threshold=None,
                             options="-c statement_timeout=10000 -c lock_timeout=3000",
                             row_factory=dict_row) as connection:
            yield connection

    def latest(self, connection=None):
        if connection is None:
            with self.connection() as conn:
                return self.latest(conn)
        row = connection.execute("""
            select payload from operational.snapshot_runs
            where payload is not null order by captured_at desc limit 1
        """).fetchone()
        state = connection.execute("select attempted_at, failed from operational.poll_state where singleton").fetchone()
        return (row["payload"] if row else None, state or {})

    def poll(self, producer):
        """Only one server polls GRM per interval, including when its last poll failed.

        Hold the transaction advisory lock through the read and persistence. A
        producer failure is caught before commit so the attempt time survives.
        No upstream request occurs without a working coordination database.
        """
        with self.connection() as conn:
            locked = conn.execute("select pg_try_advisory_xact_lock(7150231103) as acquired").fetchone()["acquired"]
            if not locked:
                return self.latest(conn)
            state = conn.execute("select attempted_at from operational.poll_state where singleton").fetchone()
            now = datetime.now(timezone.utc)
            if state and state["attempted_at"] and (now - state["attempted_at"]).total_seconds() < self.settings.interval:
                return self.latest(conn)
            conn.execute("update operational.poll_state set attempted_at=%s, failed=true where singleton", (now,))
            try:
                records, payload = producer()
            except Exception:
                # Never store raw errors: requests can contain token query strings.
                return self.latest(conn)
            snapshot_id = uuid4()
            conn.execute("""insert into operational.snapshot_runs
                (id,captured_at,record_count,equipment_count,payload) values (%s,%s,%s,%s,%s)""",
                (snapshot_id, now, len(records), len(payload["equipment"]), Jsonb(payload)))
            with conn.cursor() as cur:
                cur.executemany("""insert into operational.records
                    (grm_record_id,equipment_tag,reason_name,start_date,end_date,payload,last_snapshot_id)
                    values (%s,%s,%s,%s,%s,%s,%s) on conflict (grm_record_id) do update set
                    equipment_tag=excluded.equipment_tag, reason_name=excluded.reason_name,
                    start_date=excluded.start_date, end_date=excluded.end_date, payload=excluded.payload,
                    last_seen_at=now(),last_snapshot_id=excluded.last_snapshot_id""",
                    [(str(r["id"]), str(r.get("equipmentTag") or ""), str(r.get("reasonName") or ""),
                      r.get("_inicio_dt"), r.get("_fim_dt"), Jsonb(payload["records"][i]), snapshot_id)
                     for i, r in enumerate(records)])
            conn.execute("update operational.poll_state set failed=false where singleton")
            conn.execute("""delete from operational.snapshot_runs s where captured_at < now() - %s * interval '1 day'
                and not exists (select 1 from operational.records r where r.last_snapshot_id=s.id)""",
                (self.settings.retention_days,))
            conn.execute("""update operational.snapshot_runs set payload=null
                where captured_at < now() - %s * interval '1 day' and id<>%s""",
                (self.settings.retention_days, snapshot_id))
            conn.execute("""delete from operational.notification_outbox
                where status in ('sent','cancelled') and created_at < now() - interval '30 days'""")
            return self.latest(conn)

    def session_active(self, claims):
        with self.connection() as conn:
            row = conn.execute("""select 1 as active from auth.sessions s
                where s.id=%s and s.user_id=%s and (s.not_after is null or s.not_after>now())
                and not exists(select 1 from operational.revoked_sessions r where r.session_id=s.id)""",
                (claims["session_id"], claims["sub"])).fetchone()
            return bool(row)

    def revoke_session(self, claims):
        with self.connection() as conn:
            conn.execute("""insert into operational.revoked_sessions(session_id,user_id) values (%s,%s)
                on conflict(session_id) do nothing""", (claims["session_id"], claims["sub"]))
            conn.execute("update operational.devices set enabled=false where session_id=%s and user_id=%s",
                         (claims["session_id"], claims["sub"]))
            conn.execute("""update operational.notification_outbox set status='cancelled'
                where status in ('pending','sending') and device_id in
                (select id from operational.devices where session_id=%s and user_id=%s)""",
                (claims["session_id"], claims["sub"]))

    def register_device(self, claims, device_id, token, preferences):
        with self.connection() as conn:
            # A device ID is not authority to take over another user's record.
            old = conn.execute("select user_id,session_id from operational.devices where id=%s for update", (device_id,)).fetchone()
            if old and str(old["user_id"]) != claims["sub"]:
                raise PermissionError("Device ownership mismatch")
            # Token ownership may change on a shared phone only after the old
            # session was disabled/revoked. Refuse takeover of an active token.
            owner = conn.execute("select id,user_id,enabled from operational.devices where token=%s for update", (token,)).fetchone()
            if owner and str(owner["id"]) != str(device_id):
                if owner["enabled"] and str(owner["user_id"]) != claims["sub"]:
                    raise PermissionError("Token already registered")
                conn.execute("delete from operational.devices where id=%s", (owner["id"],))
            changed = old and str(old["session_id"]) != claims["session_id"]
            if changed:
                conn.execute("update operational.notification_outbox set status='cancelled' where device_id=%s and status in ('pending','sending')", (device_id,))
            conn.execute("""insert into operational.devices(id,user_id,session_id,token,preferences)
                values (%s,%s,%s,%s,%s) on conflict(id) do update set
                session_id=excluded.session_id, token=excluded.token,preferences=excluded.preferences,
                last_seen_at=now(),enabled=true,
                observed_maintenance=case when operational.devices.session_id<>excluded.session_id
                    then null else operational.devices.observed_maintenance end""",
                (device_id, claims["sub"], claims["session_id"], token, Jsonb(preferences)))

    def remove_device(self, claims, device_id):
        with self.connection() as conn:
            conn.execute("update operational.devices set enabled=false where id=%s and user_id=%s and session_id=%s",
                         (device_id, claims["sub"], claims["session_id"]))
            conn.execute("""update operational.notification_outbox set status='cancelled' where status in ('pending','sending')
                and device_id in (select id from operational.devices where id=%s and user_id=%s and not enabled)""",
                (device_id, claims["sub"]))

    def eligible_devices(self, conn, allowed_emails):
        return conn.execute("""select d.* from operational.devices d
            join auth.users u on u.id=d.user_id join auth.sessions s on s.id=d.session_id and s.user_id=d.user_id
            where d.enabled and d.last_seen_at>now()-interval '30 days'
            and u.email_confirmed_at is not null and (u.banned_until is null or u.banned_until<now())
            and lower(u.email)=any(%s) and (s.not_after is null or s.not_after>now())
            and not exists(select 1 from operational.revoked_sessions r where r.session_id=d.session_id)""",
            (list(allowed_emails),)).fetchall()

    def queue_alerts(self, snapshot, allowed_emails, planner):
        with self.connection() as conn:
            if not conn.execute("select pg_try_advisory_xact_lock(7150231104) as acquired").fetchone()["acquired"]:
                return
            for device in self.eligible_devices(conn, allowed_emails):
                events, observed, active_ids = planner(snapshot, device)
                conn.execute("update operational.devices set observed_maintenance=%s where id=%s",
                             (Jsonb(observed), device["id"]))
                for event_key, record_id in events:
                    conn.execute("""insert into operational.notification_outbox(id,device_id,event_key,record_id)
                        values(%s,%s,%s,%s) on conflict(device_id,event_key) do nothing""",
                        (uuid4(), device["id"], event_key, record_id))
                # Do not deliver notices for conditions that have since closed,
                # or for preferences disabled before a pending delivery.
                valid_keys = [e[0] for e in events]
                conn.execute("""update operational.notification_outbox set status='cancelled'
                    where device_id=%s and status in ('pending','sending') and
                    (not (record_id=any(%s)) or (event_key like 'limit:%%' and not(event_key=any(%s))))""",
                    (device["id"], active_ids, valid_keys))

    def deliver_one(self, allowed_emails, sender):
        """Lock one queue row through delivery, re-check authorization before send.

        FCM is at-least-once, not exactly-once. Stable event IDs let Android
        suppress redelivery after a crash between provider acceptance and commit.
        """
        with self.connection() as conn:
            row = conn.execute("""select * from operational.notification_outbox
                where status in ('pending','sending') and next_attempt_at<=now()
                order by created_at limit 1 for update skip locked""").fetchone()
            if not row:
                return False
            device = next((d for d in self.eligible_devices(conn, allowed_emails) if d["id"] == row["device_id"]), None)
            if not device:
                conn.execute("update operational.notification_outbox set status='cancelled' where id=%s", (row["id"],))
                return True
            try:
                sender(device, row)
            except Exception as error:
                if type(error).__name__ in {"UnregisteredError", "SenderIdMismatchError"}:
                    conn.execute("update operational.devices set enabled=false where id=%s", (device["id"],))
                    conn.execute("update operational.notification_outbox set status='cancelled' where id=%s", (row["id"],))
                else:
                    attempts = row["attempts"] + 1
                    conn.execute("""update operational.notification_outbox set attempts=%s,status=%s,
                        next_attempt_at=now()+%s*interval '1 second' where id=%s""",
                        (attempts, 'cancelled' if attempts >= 5 else 'pending', min(900, 30 * 2 ** attempts), row["id"]))
                return True
            conn.execute("update operational.notification_outbox set status='sent',sent_at=now() where id=%s", (row["id"],))
            return True