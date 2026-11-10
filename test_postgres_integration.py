"""Disposable local PostgreSQL tests; never uses SUPABASE_DB_URL or external hosts.
Run with RUN_LOCAL_POSTGRES=1 after creating rota_grm_local_test owned by the OS user.
"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import threading
import unittest
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
from backend.app.store import PostgresStore
from backend.app.settings import Settings
from backend.app.snapshot_service import build_snapshot
from backend.app.notifications import plan_alerts
from functions import normalizar_apontamento

DSN = "dbname=rota_grm_local_test host=/var/run/postgresql"

class LocalStore(PostgresStore):
    @contextmanager
    def connection(self):
        # Local socket/peer only, intentionally separate from production verify-full.
        with psycopg.connect(DSN, row_factory=dict_row) as conn:
            yield conn

@unittest.skipUnless(os.getenv("RUN_LOCAL_POSTGRES") == "1", "Explicit local disposable database opt-in required")
class PostgresIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with psycopg.connect(DSN, autocommit=True) as conn:
            conn.execute("drop schema if exists operational cascade")
            conn.execute("drop schema if exists auth cascade")
            conn.execute("""do $$ begin
                if not exists(select from pg_roles where rolname='anon') then create role anon; end if;
                if not exists(select from pg_roles where rolname='authenticated') then create role authenticated; end if;
                end $$""")
            conn.execute("create schema auth")
            conn.execute("create table auth.users(id uuid primary key,email text,email_confirmed_at timestamptz,banned_until timestamptz)")
            conn.execute("create table auth.sessions(id uuid primary key,user_id uuid,not_after timestamptz)")
            for migration in sorted((Path(__file__).parent / 'supabase/migrations').glob('*.sql')):
                conn.execute(migration.read_text())

    def setUp(self):
        self.store = LocalStore(Settings('https://example.invalid/grm','test',DSN))
        self.claims = {'sub':str(uuid4()), 'session_id':str(uuid4())}
        with self.store.connection() as conn:
            conn.execute("truncate operational.notification_outbox,operational.devices,operational.records,operational.snapshot_runs,operational.revoked_sessions")
            conn.execute("update operational.poll_state set attempted_at=null,failed=false")
            conn.execute("truncate auth.users,auth.sessions")
            conn.execute("insert into auth.users values(%s,'allowed@example.com',now(),null)", (self.claims['sub'],))
            conn.execute("insert into auth.sessions values(%s,%s,null)", (self.claims['session_id'],self.claims['sub']))

    def produce(self):
        start = (datetime.now(timezone.utc)-timedelta(minutes=20)).isoformat()
        records = [normalizar_apontamento({'id':'record-1','equipmentTag':'TEST','reasonName':'PAUSA ERGONOMICA','startDate':start,'endDate':None})]
        return records, build_snapshot(records,self.store.settings)

    def register(self, claims=None, device_id=None, token=None):
        device_id = device_id or uuid4()
        self.store.register_device(claims or self.claims,device_id,token or 'mock-token-'+str(device_id),
                                   {'limitsMinutesByStatus':{'PAUSA ERGONOMICA':15},'alertsEnabledByStatus':{'PAUSA ERGONOMICA':True}})
        return device_id

    def test_migrations_and_private_role_permissions(self):
        with self.store.connection() as conn:
            rows = conn.execute("select relname,relrowsecurity from pg_class join pg_namespace n on n.oid=relnamespace where n.nspname='operational' and relkind='r'").fetchall()
            self.assertEqual(len(rows),6)
            self.assertTrue(all(row['relrowsecurity'] for row in rows))
            for role in ('anon','authenticated'):
                self.assertFalse(conn.execute("select has_schema_privilege(%s,'operational','USAGE') as allowed",(role,)).fetchone()['allowed'])
                for row in rows:
                    self.assertFalse(conn.execute("select has_table_privilege(%s,%s,'SELECT,INSERT,UPDATE,DELETE') as allowed",(role,'operational.'+row['relname'])).fetchone()['allowed'])

    def test_poll_persists_full_payload_and_restores_after_restart(self):
        payload,state = self.store.poll(self.produce)
        self.assertFalse(state['failed'])
        self.assertEqual(payload['records'][0]['id'],'record-1')
        other = LocalStore(self.store.settings)
        self.assertEqual(other.latest()[0],payload)
        with other.connection() as conn:
            self.assertEqual(conn.execute("select count(*) as n from operational.records").fetchone()['n'],1)

    def test_interval_blocks_duplicate_poll(self):
        calls = []
        def producer(): calls.append(1); return self.produce()
        self.store.poll(producer); self.store.poll(producer)
        self.assertEqual(len(calls),1)

    def test_cross_instance_concurrency_single_producer(self):
        entered,release = threading.Event(),threading.Event()
        calls=[]
        def producer():
            calls.append(1); entered.set(); self.assertTrue(release.wait(3)); return self.produce()
        with ThreadPoolExecutor(max_workers=2) as pool:
            first=pool.submit(self.store.poll,producer)
            self.assertTrue(entered.wait(3))
            second=pool.submit(LocalStore(self.store.settings).poll,producer)
            second.result(timeout=3)
            release.set(); first.result(timeout=3)
        self.assertEqual(len(calls),1)

    def test_failed_producer_preserves_snapshot_and_attempt(self):
        good,_=self.store.poll(self.produce)
        with self.store.connection() as conn:
            conn.execute("update operational.poll_state set attempted_at=now()-interval '2 minutes'")
        def failure(): raise RuntimeError('simulated upstream failure')
        last,state=self.store.poll(failure)
        self.assertEqual(last,good); self.assertTrue(state['failed']); self.assertIsNotNone(state['attempted_at'])

    def test_revocation_disables_session_devices_and_pending_alerts(self):
        self.register(); payload,_=self.store.poll(self.produce)
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        self.assertTrue(self.store.session_active(self.claims))
        self.store.revoke_session(self.claims)
        self.assertFalse(self.store.session_active(self.claims))
        with self.store.connection() as conn:
            self.assertFalse(conn.execute('select enabled from operational.devices').fetchone()['enabled'])
            self.assertEqual(conn.execute('select status from operational.notification_outbox').fetchone()['status'],'cancelled')

    def test_device_and_token_ownership(self):
        device=self.register(token='shared-mock-token')
        other={'sub':str(uuid4()),'session_id':str(uuid4())}
        with self.assertRaises(PermissionError): self.register(other,device,'different-mock-token')
        with self.assertRaises(PermissionError): self.register(other,token='shared-mock-token')
        self.store.remove_device(self.claims,device)
        self.register(other,token='shared-mock-token')

    def test_queue_dedup_retry_and_success(self):
        self.register(); payload,_=self.store.poll(self.produce)
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute('select count(*) as n from operational.notification_outbox').fetchone()['n'],1)
        def failure(*args): raise RuntimeError('simulated FCM failure')
        self.assertTrue(self.store.deliver_one({'allowed@example.com'},failure))
        with self.store.connection() as conn:
            row=conn.execute('select * from operational.notification_outbox').fetchone()
            self.assertEqual(row['attempts'],1); self.assertEqual(row['status'],'pending')
            conn.execute('update operational.notification_outbox set next_attempt_at=now()')
        calls=[]
        self.assertTrue(self.store.deliver_one({'allowed@example.com'},lambda *args:calls.append(args)))
        self.assertEqual(len(calls),1)
        self.assertFalse(self.store.deliver_one({'allowed@example.com'},lambda *args:None))

    def test_closed_maintenance_today_is_not_cancelled_by_current_equipment(self):
        self.register(); payload,_=self.store.poll(self.produce)
        payload['equipment']=[]; payload['maintenanceAlerts']=[]
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        payload['maintenanceAlerts']=[{'id':'maintenance-closed','reasonName':'EXEC MANUT CORRETIVA'}]
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute('select status from operational.notification_outbox').fetchone()['status'],'pending')
        payload['maintenanceAlerts']=[]
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        with self.store.connection() as conn:
            self.assertEqual(conn.execute('select status from operational.notification_outbox').fetchone()['status'],'cancelled')

    def test_allowlist_removed_before_delivery_cancels_queue(self):
        self.register(); payload,_=self.store.poll(self.produce)
        self.store.queue_alerts(payload,{'allowed@example.com'},plan_alerts)
        calls=[]
        self.store.deliver_one(set(),lambda *args:calls.append(args))
        self.assertEqual(calls,[])

if __name__ == '__main__': unittest.main()