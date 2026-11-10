"""The existing GRM read contract, with bounded retries and no redirect leakage."""
from datetime import datetime, time as day_time, timedelta, timezone
import json
import time
from zoneinfo import ZoneInfo
import requests

from functions import normalizar_apontamento, chave_inicio_apontamento

MAX_BYTES = 10 * 1024 * 1024
TRANSIENT = {408, 429, 500, 502, 503, 504}


class GrmUnavailable(RuntimeError):
    pass


class GrmClient:
    def __init__(self, settings, session=None, sleeper=time.sleep):
        self.settings = settings
        self.session = session or requests.Session()
        self.sleep = sleeper

    def fetch(self):
        now = datetime.now(timezone.utc)
        local = ZoneInfo("America/Sao_Paulo")
        # Preserve the existing, documented query window. Do not invent an API filter.
        begin = datetime.combine(now.astimezone(local).date(), day_time.min, local) - timedelta(days=1)
        def timestamp(value):
            return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        params = {"token": self.settings.grm_token, "begin": timestamp(begin), "end": timestamp(now)}
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