"""GRM report semantics verified against live rolling endDate records.

The report caps current rows at one common moving endDate; it does not return
null for those rows. Never substitute a historical latest row on each machine.
Explicit null/zero end dates remain supported for other GRM responses.
"""
import re
import unicodedata
from collections import defaultdict
from functools import lru_cache
from functions import chave_inicio_apontamento

MAINTENANCE = frozenset(('AG MANUTENCAO', 'EXEC MANUT CORRETIVA',
    'EXEC MANUT PREVENTIVA', 'EXEC MANUT PREVENTIVA-250 HRS'))

def normalized_reason(value):
    return _normalized_reason(str(value))

@lru_cache(maxsize=2048)
def _normalized_reason(value):
    value = ''.join(c for c in unicodedata.normalize('NFD', value.upper())
                    if unicodedata.category(c) != 'Mn')
    return re.sub(r'\s*-\s*', '-', ' '.join(value.split()))

def maintenance_reason(value): return normalized_reason(value) in MAINTENANCE

def pause_reason(value): return normalized_reason(value) == 'PAUSA ERGONOMICA'

def operational_state(row):
    """Classify by GRM statusType, not ambiguous KPI codes such as HAO/HMC."""
    status = normalized_reason(row.get('statusType', ''))
    if status in ('OPERATING', 'OPERACAO'):
        return 'operating'
    if status in ('OPERATIONAL DOWNTIME', 'PARADA OPERACIONAL'):
        return 'stopped'
    if status in ('PLANNED MAINTENANCE', 'UNPLANNED MAINTENANCE', 'MANUTENCAO PREVENTIVA', 'MANUTENCAO CORRETIVA'):
        return 'maintenance'
    return 'unknown'

def record_key(row):
    return (str(row['id']), row['equipmentTag'], normalized_reason(row['reasonName']))

class PointingHistory:
    """One linear index per report; never renormalize the full report per machine."""
    def __init__(self, records):
        self.slices = defaultdict(list)
        self.predecessors = defaultdict(set)
        for record in records:
            self.slices[record_key(record)].append(record)
            if record.get('_fim_dt'):
                self.predecessors[(record['equipmentTag'], record['_fim_dt'])].add(str(record['id']))

def original_start(records, row, index=None):
    """Join only touching/overlapping shift slices of the exact same pointing.

    No join by maintenance reason alone. A gap is a boundary, not downtime
    invented as maintenance. Confirmation requires a preceding different ID.
    """
    result = dict(row)
    begin = row.get('_inicio_dt')
    if begin is None:
        return result
    key = record_key(row)
    candidates = index.slices.get(key, ()) if index is not None else (r for r in records if record_key(r) == key)
    slices = sorted((r for r in candidates
                     if r.get('_inicio_dt') and r['_inicio_dt'] < begin),
                    key=lambda r: r['_inicio_dt'], reverse=True)
    for part in slices:
        if not part.get('_fim_dt') or part['_fim_dt'] < begin:
            break
        begin = part['_inicio_dt']
        result['_inicio_dt'] = begin
        result['startDate'] = part['startDate']
    result['_start_confirmed'] = (
        bool(index.predecessors.get((row['equipmentTag'], begin), set()) - {str(row['id'])})
        if index is not None else any(r['equipmentTag'] == row['equipmentTag']
            and str(r['id']) != str(row['id']) and r.get('_fim_dt') == begin for r in records))
    return result

def current_records(records):
    explicit = [r for r in records if r.get('aberto')]
    latest_end = max((r['_fim_dt'] for r in records if r.get('_fim_dt')), default=None)
    # A common cutoff across multiple machines is the report's current cohort.
    # One historical row alone must not be reclassified as current.
    cutoff_rows = [r for r in records if latest_end and r.get('_fim_dt') == latest_end]
    common_cutoff = len({r['equipmentTag'] for r in cutoff_rows}) >= 2
    candidates = explicit if explicit else cutoff_rows if common_cutoff else []
    by_tag = {}
    for r in candidates:
        tag = r['equipmentTag']; old = by_tag.get(tag)
        if old is None or (chave_inicio_apontamento(r), str(r['id'])) > (chave_inicio_apontamento(old), str(old['id'])):
            by_tag[tag] = r
    return list(by_tag.values())