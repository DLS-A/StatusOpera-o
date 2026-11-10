"""The existing GRM read contract, with bounded retries and no redirect leakage."""
from datetime import datetime, time as day_time, timedelta, timezone
import json
import time
from zoneinfo import ZoneInfo
import requests

from functions import normalizar_apontamento, chave_inicio_apontamento
from .report_contract import current_records, maintenance_reason, record_key, original_start

MAX_BYTES = 10 * 1024 * 1024
TRANSIENT = {408, 429, 500, 502, 503, 504}


class GrmUnavailable(RuntimeError):
    pass


class GrmClient:
    def __init__(self, settings, session=None, sleeper=time.sleep):
        self.settings = settings
        self.session = session or requests.Session()
        self.sleep = sleeper
        self._history = []
        self._history_attempt = 0.0
        self._failed_history_keys = set()

    def fetch(self):
        records = self._fetch_window(1)
        active = current_records(records)
        keys = {record_key(r) for r in active}
        known = {record_key(r) for r in self._history} - self._failed_history_keys
        missing = {record_key(r) for r in active if record_key(r) not in known
                   and not original_start(records, r).get('_start_confirmed')}
        now = time.monotonic()
        # Historical bootstrap is bounded and cached, never 7 days every minute.
        # Failure to retrieve history must not erase valid current operation.
        if missing and (not self._history_attempt or now - self._history_attempt >= 3600):
            self._history_attempt = now
            try:
                # Retain source slices for still-active IDs across later windows.
                # Otherwise a long maintenance would lose its opening next week.
                remembered = [r for r in self._history if record_key(r) in keys]
                earliest = {(r['equipmentTag'], r['_inicio_dt']) for r in remembered}
                predecessors = [r for r in self._history if record_key(r) not in keys
                    and (r['equipmentTag'], r.get('_fim_dt')) in earliest]
                self._history = self._fetch_window(7) + remembered + predecessors
                # Older slices come in separate bounded 7-day queries, rather
                # than one unbounded download. Stop once openings are confirmed.
                for end_days in (7, 14, 21):
                    unresolved = [r for r in active if record_key(r) in missing
                        and not original_start(self._history + records, r).get('_start_confirmed')]
                    if not unresolved:
                        break
                    self._history.extend(self._fetch_window(end_days + 7, end_days))
                self._failed_history_keys.difference_update(missing)
            except GrmUnavailable:
                self._failed_history_keys.update(missing)
        # Persist/cache only slices of current IDs and their exact predecessors,
        # not four weeks of unrelated operation in every minute's snapshot.
        relevant = [r for r in self._history + records if record_key(r) in keys]
        event_starts = {(r['equipmentTag'], r['_inicio_dt']) for r in relevant}
        relevant += [r for r in self._history + records if record_key(r) not in keys
                     and (r['equipmentTag'], r.get('_fim_dt')) in event_starts]
        self._history = list({(record_key(r), r['_inicio_dt']): r for r in relevant}.values())
        starts = {record_key(r): r['_inicio_dt'] for r in active}
        history = []
        for part in self._history:
            boundary = starts.get(record_key(part))
            if boundary and part['_inicio_dt'] < boundary and part.get('_fim_dt'):
                copy = dict(part)
                copy['_fim_dt'] = min(part['_fim_dt'], boundary)
                copy['endDate'] = int(copy['_fim_dt'].timestamp() * 1000)
                history.append(copy)
            # Predecessors confirm an actual opening rather than query boundary.
            elif record_key(part) not in keys and (part['equipmentTag'], part.get('_fim_dt')) in event_starts:
                history.append(part)
        return sorted(history + records, key=chave_inicio_apontamento)

    def _fetch_window(self, days, end_days_ago=0):
        now = datetime.now(timezone.utc)
        local = ZoneInfo("America/Sao_Paulo")
        # Same endpoint/filter contract; bounded history resolves shift splits.
        begin = datetime.combine(now.astimezone(local).date(), day_time.min, local) - timedelta(days=days)
        finish = now if not end_days_ago else datetime.combine(now.astimezone(local).date(), day_time.min, local) - timedelta(days=end_days_ago)
        def timestamp(value):
            return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        params = {"token": self.settings.grm_token, "begin": timestamp(begin), "end": timestamp(finish)}
        for attempt in range(3):
            retry = False
            try:
                with self.session.get(self.settings.grm_url, params=params, timeout=(5, 15),
                                      allow_redirects=False, stream=True) as response:
                    if response.status_code in TRANSIENT:
                        retry = True
                    elif response.status_code != 200:
                        raise GrmUnavailable("GRM rejected the read request")
                    else:
                        content = bytearray()
                        for chunk in response.iter_content(8192):
                            content.extend(chunk)
                            if len(content) > MAX_BYTES:
                                raise GrmUnavailable("GRM response exceeds the limit")
                        if not content.strip():
                            # An empty body is not a confirmed empty operation.
                            raise GrmUnavailable("Empty GRM response")
                        try:
                            data = json.loads(content)
                        except (ValueError, UnicodeError):
                            raise GrmUnavailable("Invalid GRM JSON") from None
                        if not isinstance(data, list) or any(not isinstance(i, dict) or i.get("id") is None for i in data):
                            raise GrmUnavailable("Invalid GRM record structure")
                        records = [normalizar_apontamento(item) for item in data]
                        if any(not str(r["id"]).strip() or not isinstance(r["equipmentTag"], str)
                               or not r["equipmentTag"].strip() or not isinstance(r["reasonName"], str)
                               or not r["reasonName"].strip() or r["_inicio_dt"] is None
                               or (not r["aberto"] and r["_fim_dt"] is None) for r in records):
                            raise GrmUnavailable("Invalid GRM record fields")
                        return sorted(records, key=chave_inicio_apontamento)
            except (requests.Timeout, requests.ConnectionError):
                retry = True
            except requests.RequestException:
                raise GrmUnavailable("GRM transport unavailable") from None
            if retry and attempt < 2:
                self.sleep(0.5 * (2 ** attempt))
            else:
                break
        raise GrmUnavailable("GRM temporarily unavailable")