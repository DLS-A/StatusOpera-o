"""GRM report semantics verified against live rolling endDate records.

The report caps current rows at one common moving endDate; it does not return
null for those rows. Never substitute a historical latest row on each machine.
Explicit null/zero end dates remain supported for other GRM responses.
"""
import re
import unicodedata
from functions import chave_inicio_apontamento

MAINTENANCE = frozenset(('AG MANUTENCAO', 'EXEC MANUT CORRETIVA',
    'EXEC MANUT PREVENTIVA', 'EXEC MANUT PREVENTIVA-250 HRS'))

def normalized_reason(value):
    value = ''.join(c for c in unicodedata.normalize('NFD', str(value).upper())
                    if unicodedata.category(c) != 'Mn')
    return re.sub(r'\s*-\s*', '-', ' '.join(value.split()))

def maintenance_reason(value): return normalized_reason(value) in MAINTENANCE

def pause_reason(value): return normalized_reason(value) == 'PAUSA ERGONOMICA'

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