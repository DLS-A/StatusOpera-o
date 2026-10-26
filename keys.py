"""Environment-backed configuration. Secrets must never be committed here."""

import os
from pathlib import Path
from urllib.parse import urlparse

from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name(".env"))

TOKEN_BOT_GRM = os.getenv("TOKEN_BOT_GRM", "").strip()
URL_API_GRM = os.getenv("URL_API_GRM", "").strip()
TOKEN_API_APONTAMENTOS_GRM_PUSH = os.getenv("TOKEN_API_APONTAMENTOS_GRM_PUSH", "").strip()
CHAT_IDS_ALERTA = os.getenv("CHAT_IDS_ALERTA", "").strip()
RECIPIENT_IDS_STATUS = os.getenv("RECIPIENT_IDS_STATUS", "").strip()

if not URL_API_GRM or not TOKEN_API_APONTAMENTOS_GRM_PUSH:
    raise EnvironmentError(
        "Configuração GRM incompleta. Defina URL_API_GRM e "
        "TOKEN_API_APONTAMENTOS_GRM_PUSH no ambiente do servidor."
    )

api_url = urlparse(URL_API_GRM)
if api_url.scheme.lower() != "https" or not api_url.hostname or api_url.username or api_url.password:
    raise EnvironmentError("URL_API_GRM deve usar HTTPS.")
