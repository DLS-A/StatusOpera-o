"""Public read-only API and rolling GRM report regression tests."""
from datetime import datetime, timedelta, timezone
from unittest import TestCase
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.snapshot_service import build_snapshot
from backend.app.report_contract import current_records, maintenance_reason, pause_reason
from functions import normalizar_apontamento

class PublicReportTests(TestCase):
    def row(self, id, tag, reason, start, end):
        return normalizar_apontamento(dict(id=id,equipmentTag=tag,reasonName=reason,
            startDate=start.isoformat(),endDate=end.isoformat() if end else None))

    def test_current_report_cutoff_includes_named_examples_not_history(self):
        now=datetime.now(timezone.utc)
        old=self.row('old','CB-303','EXEC MANUT PREVENTIVA',now-timedelta(hours=4),now-timedelta(hours=3))
        cb303=self.row('1','CB-303','EXEC MANUT CORRETIVA',now-timedelta(hours=2),now)
        cb310=self.row('2','CB-310','EXEC MANUT PREVENTIVA',now-timedelta(hours=2),now)
        other=self.row('3','OLD','EXEC MANUT CORRETIVA',now-timedelta(hours=5),now-timedelta(hours=4))
        data=build_snapshot([old,cb303,cb310,other])
        self.assertEqual({'1','2'},{r['id'] for r in data['maintenance']})
        self.assertEqual('EXEC MANUT CORRETIVA',next(e for e in data['equipment'] if e['equipmentTag']=='CB-303')['current']['reasonName'])
        self.assertIsNone(next(e for e in data['equipment'] if e['equipmentTag']=='OLD')['current'])
        self.assertEqual(7200,data['maintenance'][0]['durationSeconds'])

    def test_all_four_types_with_spacing_variants_and_accents(self):
        now=datetime.now(timezone.utc)
        names=['AG MANUTENCAO','EXEC MANUT CORRETIVA','EXEC MANUT PREVENTIVA','EXEC MANUT PREVENTIVA - 250 HRS']
        rows=[self.row(str(i),'EQ-'+str(i),reason,now-timedelta(minutes=20),now) for i,reason in enumerate(names)]
        self.assertEqual(4,len(build_snapshot(rows)['maintenance']))
        self.assertTrue(maintenance_reason('EXEC MANUT PREVENTIVA-250 HRS'))
        self.assertFalse(maintenance_reason('LIBERADO PELA MANUT'))
        self.assertTrue(pause_reason('PAUSA ERGONÔMICA'))
        self.assertFalse(pause_reason('REFEICAO'))

    def test_new_pause_appears_and_disappears_on_next_report(self):
        now=datetime.now(timezone.utc)
        operation=self.row('1','EQ-1','PRODUZINDO/OPERANDO',now-timedelta(hours=1),now)
        other=self.row('2','EQ-2','DISPONIVEL PARA OPERAR',now-timedelta(hours=1),now)
        self.assertEqual([],build_snapshot([operation,other])['pauses'])
        pause=self.row('3','EQ-1','PAUSA ERGONÔMICA',now-timedelta(minutes=16),now)
        self.assertEqual(['3'],[r['id'] for r in build_snapshot([operation,other,pause])['pauses']])
        later=now+timedelta(minutes=1)
        closed=self.row('3','EQ-1','PAUSA ERGONÔMICA',now-timedelta(minutes=16),now)
        resumed=self.row('4','EQ-1','PRODUZINDO/OPERANDO',now,later)
        other=self.row('2','EQ-2','DISPONIVEL PARA OPERAR',now-timedelta(hours=1),later)
        self.assertEqual([],build_snapshot([closed,resumed,other])['pauses'])

    def test_single_historical_row_is_not_current_and_explicit_open_supported(self):
        now=datetime.now(timezone.utc)
        old=self.row('1','EQ','EXEC MANUT CORRETIVA',now-timedelta(hours=2),now-timedelta(hours=1))
        self.assertEqual([],current_records([old]))
        opened=self.row('2','EQ','PAUSA ERGONOMICA',now-timedelta(minutes=16),None)
        self.assertEqual([opened],current_records([old,opened]))

    def test_public_reads_no_code_no_bearer_and_no_operational_writes(self):
        with patch.dict('os.environ',{'AUTH_MODE':'public'}), TestClient(app,base_url='https://testserver') as client:
            service=Mock();store=Mock();app.state.service=service;app.state.store=store
            service.read.return_value={'stale':False,'updatedAt':'2026-11-10T00:00:00Z'}
            self.assertEqual(200,client.get('/v1/snapshot').status_code)
            self.assertEqual(200,client.get('/v1/readiness').status_code)
            self.assertEqual(400,client.get('http://testserver/v1/snapshot').status_code)
            self.assertEqual(404,client.post('/v1/activation',json={'code':'old-code-is-not-needed'}).status_code)
            for method in ('POST','PUT','PATCH','DELETE'):
                self.assertEqual(405,client.request(method,'/v1/snapshot').status_code)
            self.assertEqual(401,client.post('/v1/session/logout').status_code)
            self.assertEqual(401,client.put('/v1/devices/00000000-0000-0000-0000-000000000001',json={'token':'mock-not-a-real-device-token'}).status_code)
            self.assertEqual(404,client.get('/oauth/consent').status_code)
            self.assertEqual([],store.mock_calls)