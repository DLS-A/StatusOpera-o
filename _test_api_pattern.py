import json
from pathlib import Path
from datetime import datetime, timezone
from functions import buscar_dados, normalizar_apontamento
from collections import defaultdict

raw = buscar_dados()
print(f'Total registros: {len(raw)}')

# Group by equipment and find the latest by startDate
por_equipamento = defaultdict(list)
for x in raw:
    tag = x.get('equipmentTag', '')
    if tag:
        por_equipamento[tag].append(x)

print(f'Equipamentos unicos: {len(por_equipamento)}')

# For each equipment, show the latest record
print('\nUltimo registro por equipamento (amostra):')
for tag in sorted(list(por_equipamento.keys()))[:10]:
    items = por_equipamento[tag]
    items_sorted = sorted(items, key=lambda x: x.get('startDate') or 0, reverse=True)
    latest = items_sorted[0]
    start = latest.get('startDate')
    end = latest.get('endDate')
    start_dt = datetime.fromtimestamp(start/1000, tz=timezone.utc) if isinstance(start, (int, float)) else None
    end_dt = datetime.fromtimestamp(end/1000, tz=timezone.utc) if isinstance(end, (int, float)) else None
    diff = (end_dt - start_dt).total_seconds() if start_dt and end_dt else None
    print(f'  {tag}: start={start_dt.strftime("%d/%m %H:%M:%S") if start_dt else start} | end={end_dt.strftime("%d/%m %H:%M:%S") if end_dt else end} | diff={diff:.0f}s | reason={latest.get("reasonName")}')

# Check if there are any records with endDate == startDate (or very close)
same_time = 0
close_time = 0
for x in raw:
    start = x.get('startDate')
    end = x.get('endDate')
    if isinstance(start, (int, float)) and isinstance(end, (int, float)):
        diff = abs(end - start)
        if diff == 0:
            same_time += 1
        elif diff < 60000:  # less than 1 minute
            close_time += 1

print(f'\nRegistros com startDate == endDate: {same_time}')
print(f'Registros com startDate e endDate < 1min: {close_time}')

# Check the maximum endDate overall
max_end = max(x.get('endDate', 0) or 0 for x in raw)
max_end_dt = datetime.fromtimestamp(max_end/1000, tz=timezone.utc) if max_end else None
print(f'\nMax endDate: {max_end_dt.strftime("%d/%m/%Y %H:%M:%S UTC") if max_end_dt else max_end}')

# Check how many records have endDate within last 5 minutes of max_end
recent_count = 0
for x in raw:
    end = x.get('endDate', 0) or 0
    if max_end - end < 300000:  # 5 minutes
        recent_count += 1
print(f'Registros com endDate nos ultimos 5min do max: {recent_count}')
