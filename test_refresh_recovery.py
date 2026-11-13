"""Indexed history must be equivalent to the original, without quadratic key work."""
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch
from functions import normalizar_apontamento
from backend.app import report_contract as rules
from backend.app.snapshot_service import build_snapshot

class RefreshRecoveryTests(unittest.TestCase):
    def row(self, id, tag, reason, start, end):
        return normalizar_apontamento(dict(id=id,equipmentTag=tag,reasonName=reason,
            startDate=start.isoformat(),endDate=end.isoformat() if end else None))

    def fixture(self):
        now=datetime.now(timezone.utc);start=now-timedelta(hours=3);opening=start-timedelta(days=40)
        rows=[]
        for i in range(93):
            tag='EQ-'+str(i)
            rows.extend([self.row('prior-'+str(i),tag,'OPERANDO',opening-timedelta(minutes=1),opening),
                self.row('m-'+str(i),tag,'AG MANUTENÇÃO',opening,start),
                self.row('m-'+str(i),tag,'AG   MANUTENCAO',start,now)])
        return rows

    def test_index_matches_reference_for_normalized_slices_and_exact_predecessors(self):
        rows=self.fixture();index=rules.PointingHistory(rows)
        for r in rules.current_records(rows):
            self.assertEqual(rules.original_start(rows,r),rules.original_start(rows,r,index))

    def test_index_does_not_bridge_gap_other_equipment_or_changed_id(self):
        now=datetime.now(timezone.utc);start=now-timedelta(hours=1)
        rows=[self.row('same','A','AG MANUTENCAO',start-timedelta(days=4),start-timedelta(minutes=1)),
            self.row('same','OTHER','AG MANUTENCAO',start-timedelta(days=5),start),
            self.row('changed','A','AG MANUTENCAO',start-timedelta(minutes=30),start),
            self.row('same','A','AG MANUTENCAO',start,now),self.row('o','B','OPERANDO',start,now)]
        row=rows[3];original=dict(row)
        result=rules.original_start(rows,row,rules.PointingHistory(rows))
        self.assertEqual(rules.original_start(rows,row),result)
        self.assertEqual(start,result['_inicio_dt']);self.assertTrue(result['_start_confirmed'])
        self.assertEqual(original,row)

    def test_snapshot_build_key_work_is_linear_not_per_equipment_full_scan(self):
        rows=self.fixture()
        with patch.object(rules,'record_key',wraps=rules.record_key) as key:
            snapshot=build_snapshot(rows)
        self.assertEqual(93,len(snapshot['equipment']))
        self.assertLessEqual(key.call_count,len(rows)+93)
        self.assertTrue(all(r['startConfirmed'] for r in snapshot['maintenance']))

    def test_reason_cache_is_bounded_and_normalization_preserved(self):
        self.assertEqual('EXEC MANUT PREVENTIVA-250 HRS',rules.normalized_reason('  exec manut preventiva - 250 hrs '))
        self.assertEqual('AG MANUTENCAO',rules.normalized_reason('Ag manutenção'))
        self.assertEqual('NONE',rules.normalized_reason(None))
        for i in range(3000):rules.normalized_reason('REASON '+str(i))
        self.assertLessEqual(rules._normalized_reason.cache_info().currsize,2048)

if __name__=='__main__':unittest.main()