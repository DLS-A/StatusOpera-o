from datetime import datetime, time as datetime_time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo
import requests
import traceback
import json
import time
import re

from keys import URL_API_GRM, TOKEN_API_APONTAMENTOS_GRM_PUSH, TOKEN_BOT_GRM

INTERVALO_MONITORAMENTO = 60

FUSO_HORARIO_LOCAL = ZoneInfo("America/Sao_Paulo")

SEQUENCIA_ROTA = [
    "MOVIMENTANDO VAZIO",
    "CAPTACAO DE AGUA",
    "MOVIMENTANDO CHEIO",
    "PRODUZINDO/OPERANDO",
]


def converter_timestamp(timestamp):
    if timestamp is None or timestamp == 0:
        return None
    try:
        if isinstance(timestamp, datetime):
            return timestamp.astimezone(timezone.utc) if timestamp.tzinfo else timestamp.replace(tzinfo=timezone.utc)
        if isinstance(timestamp, str):
            value = timestamp.strip()
            if not value:
                return None
            if not value.lstrip("+-").isdigit():
                parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
                return parsed.astimezone(timezone.utc) if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
        return datetime.fromtimestamp(
            int(timestamp) / 1000,
            tz=timezone.utc
        )
    except (TypeError, ValueError, OSError, OverflowError):
        return None


def to_local(dt):
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(FUSO_HORARIO_LOCAL)


def formatar_datetime(dt):
    if dt is None:
        return "Não informado"
    return to_local(dt).strftime("%d/%m/%Y %H:%M:%S")


def formatar_data_hora(dt):
    if dt is None:
        return "Não informado"
    local_dt = to_local(dt)
    return local_dt.strftime("%d/%m/%Y"), local_dt.strftime("%H:%M:%S")


def salvar_log(e):
    error = str(e)
    # Bibliotecas HTTP podem incluir URLs de autenticação nas exceções.
    # Remova os segredos conhecidos antes de gravar ou imprimir qualquer erro.
    for secret in (TOKEN_API_APONTAMENTOS_GRM_PUSH, TOKEN_BOT_GRM):
        if secret:
            error = error.replace(secret, "[REDACTED]")
    error = re.sub(r"(?i)([?&](?:token|access_token|api_key|password)=)[^&\s]+", r"\1[REDACTED]", error)
    timestamp = datetime.now(timezone.utc).strftime("%d/%m/%Y %H:%M:%S")

    tb = traceback.extract_tb(e.__traceback__) if e.__traceback__ else []
    if tb:
        last_call = tb[-1]
        line_number = last_call.lineno
        file_name = last_call.filename
        file_name = Path(file_name).name
        msg = f"[Erro] {timestamp} - {error} ocorreu na linha {line_number} do módulo {file_name}\n"
    else:
        msg = f"[Erro] {timestamp} - {error}\n"

    caminho = Path(__file__).parent / "log.txt"
    with open(caminho, "a", encoding="utf-8") as arquivo:
        arquivo.write(msg)
    print(msg.strip())


def export_json(content, name_file="content"):
    try:
        base_path = Path(__file__).parent
        json_dir = base_path / 'json'
        json_dir.mkdir(exist_ok=True)
        name_json_file = json_dir / f"{name_file}.json"
        with open(name_json_file, "w", encoding="utf-8") as file:
            json.dump(content, file, indent=4, ensure_ascii=False, default=str)
    except Exception as e:
        pass


def buscar_dados():
    now_utc = datetime.now(tz=timezone.utc)
    now_date_time = now_utc.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    inicio_dia_local = datetime.combine(
        now_utc.astimezone(FUSO_HORARIO_LOCAL).date(),
        datetime_time.min,
        tzinfo=FUSO_HORARIO_LOCAL,
    )
    # Inclui apontamentos iniciados no dia civil anterior e ainda ativos após
    # a meia-noite. A janela só define a consulta; não muda startDate/endDate.
    inicio_consulta_local = inicio_dia_local - timedelta(days=1)
    begin_day = inicio_consulta_local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

    url = URL_API_GRM
    params = {
        "token": TOKEN_API_APONTAMENTOS_GRM_PUSH,
        "begin": begin_day,
        "end": now_date_time,
    }

    try:
        response = requests.get(url, params=params, timeout=30)
    except requests.exceptions.Timeout:
        raise RuntimeError("Timeout ao consultar API GRM.") from None
    except requests.exceptions.ConnectionError:
        raise RuntimeError("Falha de conexão com a API GRM.") from None
    except Exception:
        raise RuntimeError("Falha inesperada ao consultar a API GRM.") from None

    if response.status_code != 200:
        raise RuntimeError(f"A API GRM respondeu com status HTTP {response.status_code}.")

    if not response.text or not response.text.strip():
        return []

    try:
        data = response.json()
    except (json.JSONDecodeError, ValueError):
        raise RuntimeError("A API GRM retornou JSON inválido.") from None

    if not isinstance(data, list):
        raise RuntimeError("A API GRM retornou um formato inesperado.")

    return data


def normalizar_apontamento(dado):
    if not isinstance(dado, dict):
        return None

    apontamento_id = dado.get("id")
    if apontamento_id is None:
        return None

    start_raw = dado.get("startDate")
    end_raw = dado.get("endDate")

    inicio_dt = converter_timestamp(start_raw)
    fim_dt = converter_timestamp(end_raw)

    aberto = (end_raw is None or end_raw == 0)

    log_duration = dado.get("logDuration", 0)
    if log_duration is None:
        log_duration = 0

    operator = dado.get("operator")
    if isinstance(operator, str) and operator.strip():
        parts = operator.split()
        first_name = parts[0]
        last_name = parts[-1]
        if len(parts) > 3:
            middle_name = parts[-2]
            operator = f"{first_name} {middle_name} {last_name}".title()
        else:
            operator = f"{first_name} {last_name}".title()
    else:
        operator = "Não informado"

    location = dado.get("location")
    if location is None:
        location = ""

    apontamento = {
        "id": apontamento_id,
        "equipmentTag": dado.get("equipmentTag", ""),
        "reasonName": dado.get("reasonName", ""),
        "startDate": start_raw,
        "endDate": end_raw,
        "logDuration": log_duration,
        "operator": operator,
        "location": location,
        "equipmentType": dado.get("equipmentType", ""),
        "kpiCategory": dado.get("kpiCategory", ""),
        "aberto": aberto,
        "_inicio_dt": inicio_dt,
        "_fim_dt": fim_dt,
    }

    return apontamento


def api_content_formatted():
    raw_data = buscar_dados()
    if not raw_data:
        return []

    apontamentos = []
    for item in raw_data:
        norm = normalizar_apontamento(item)
        if norm is not None:
            apontamentos.append(norm)

    apontamentos.sort(key=chave_inicio_apontamento)
    # Não persistir automaticamente dados operacionais sensíveis em disco.
    return apontamentos


def listar_equipamentos():
    content = api_content_formatted()
    if not content:
        return []
    equipamentos = sorted({
        item.get("equipmentTag")
        for item in content
        if item.get("equipmentTag")
    })
    print(f"[MONITOR] Equipamentos identificados: {len(equipamentos)}")
    return equipamentos


def chave_inicio_apontamento(item):
    inicio = item.get("_inicio_dt") or converter_timestamp(item.get("startDate"))
    return inicio or datetime.min.replace(tzinfo=timezone.utc)


def obter_status_equipamento(equipment_tag):
    content = api_content_formatted()
    if not content:
        return None

    abertos = [
        item for item in content
        if item.get("aberto") is True
        and item.get("equipmentTag") == equipment_tag
    ]

    if not abertos:
        return None

    abertos.sort(key=chave_inicio_apontamento, reverse=True)
    return abertos[0]


def obter_status_varios_equipamentos(equipment_tags):
    content = api_content_formatted()
    if not content:
        return []

    resultados = []
    for tag in equipment_tags:
        abertos = [
            item for item in content
            if item.get("aberto") is True
            and item.get("equipmentTag") == tag
        ]
        if abertos:
            abertos.sort(key=chave_inicio_apontamento, reverse=True)
            resultados.append(abertos[0])
        else:
            resultados.append({
                "equipmentTag": tag,
                "reasonName": "",
                "aberto": False,
                "_inicio_dt": None,
                "_fim_dt": None,
            })
    return resultados


def calcular_tempo_apontamento(inicio_dt, fim_dt=None):
    if inicio_dt is None:
        return "00:00:00"
    agora = datetime.now(timezone.utc) if fim_dt is None else fim_dt
    if inicio_dt > agora:
        return "00:00:00"
    diferenca = agora - inicio_dt
    total_segundos = int(diferenca.total_seconds())
    if total_segundos < 0:
        total_segundos = 0
    horas, resto = divmod(total_segundos, 3600)
    minutos, segundos = divmod(resto, 60)
    return f"{horas:02d}:{minutos:02d}:{segundos:02d}"


def calcular_minutos_apontamento(inicio_dt, fim_dt=None):
    if inicio_dt is None:
        return 0
    agora = datetime.now(timezone.utc) if fim_dt is None else fim_dt
    if inicio_dt > agora:
        return 0
    diferenca = agora - inicio_dt
    total_segundos = int(diferenca.total_seconds())
    if total_segundos < 0:
        return 0
    return total_segundos // 60


def calcular_duracao_segundos(inicio_dt, fim_dt=None):
    if inicio_dt is None:
        return 0
    agora = datetime.now(timezone.utc) if fim_dt is None else fim_dt
    if inicio_dt > agora:
        return 0
    total_segundos = int((agora - inicio_dt).total_seconds())
    return max(0, total_segundos)


def formatar_duracao(minutos):
    if minutos is None or minutos <= 0:
        return "0min"
    horas, mins = divmod(int(minutos), 60)
    if horas > 0:
        return f"{horas}h{mins:02d}min"
    return f"{mins}min"


def formatar_duracao_completa(segundos):
    if segundos is None or segundos <= 0:
        return "0min0s"
    horas, resto = divmod(int(segundos), 3600)
    mins, secs = divmod(resto, 60)
    if horas > 0:
        return f"{horas}h{mins:02d}min{secs:02d}s"
    if mins > 0:
        return f"{mins}min{secs:02d}s"
    return f"{secs}s"


def timedelta_safe(seconds):
    from datetime import timedelta
    return timedelta(seconds=seconds)


def obter_apontamentos_abertos(content=None):
    if content is None:
        content = api_content_formatted()
    if not content:
        return []

    # A API/normalizador define abertura por endDate nulo ou zero. O maior
    # endDate global selecionava encerramentos e podia omitir todos os abertos.
    por_equipamento = {}
    for item in content:
        if not item.get("aberto"):
            continue
        tag = item.get("equipmentTag")
        if not tag:
            continue
        atual = por_equipamento.get(tag)
        inicio_atual = chave_inicio_apontamento(atual) if atual else datetime.min.replace(tzinfo=timezone.utc)
        inicio_item = chave_inicio_apontamento(item)
        if atual is None or (inicio_item, str(item.get("id", ""))) > (inicio_atual, str(atual.get("id", ""))):
            por_equipamento[tag] = item

    return list(por_equipamento.values())


def obter_pausas_ergonomicas_abertas(content=None):
    abertos = obter_apontamentos_abertos(content)
    pausas = []
    for item in abertos:
        razao = str(item.get("reasonName", "")).upper()
        if "PAUSA" in razao or "ERGONOMICA" in razao or "ERGONÔMICA" in razao:
            pausas.append(item)
    print(f"[MONITOR] Pausas abertas: {len(pausas)}")
    return pausas


def obter_manutencoes_abertas(content=None):
    """Retorna TODAS as manutenções abertas (para a aba/ menu)."""
    abertos = obter_apontamentos_abertos(content)
    manutencoes = []
    for item in abertos:
        razao = str(item.get("reasonName", "")).upper().strip()
        if razao in ("EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA"):
            manutencoes.append(item)
    print(f"[MONITOR] Manutenções abertas: {len(manutencoes)}")
    return manutencoes


def obter_manutencoes_atuais(content=None):
    """Retorna apenas manutenções iniciadas HOJE (para alertas e status atual)."""
    abertos = obter_apontamentos_abertos(content)
    manutencoes = []
    now = datetime.now(timezone.utc)
    hoje = to_local(now).date()

    for item in abertos:
        razao = str(item.get("reasonName", "")).upper().strip()
        if razao not in ("EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA"):
            continue

        inicio_dt = item.get("_inicio_dt")
        if inicio_dt is None:
            continue

        if to_local(inicio_dt).date() != hoje:
            continue
        if inicio_dt > now:
            continue

        manutencoes.append(item)

    print(f"[MONITOR] Manutenções atuais (hoje/iniciadas): {len(manutencoes)}")
    return manutencoes


def emoji_pausa(segundos):
    if segundos <= 600:
        return "🟢"
    elif segundos <= 900:
        return "🟡"
    else:
        return "🔴"


def emoji_status_rota(reason_name, current_reason):
    if str(reason_name).upper().strip() == str(current_reason).upper().strip():
        return "🟢"
    return "⚪"


def formatar_rota(item):
    if item is None:
        return ""

    tag = item.get("equipmentTag", "???")
    reason_name = str(item.get("reasonName", "")).upper().strip()

    current_index = None
    for i, etapa in enumerate(SEQUENCIA_ROTA):
        if etapa == reason_name:
            current_index = i
            break

    if current_index is None:
        rota_str = f"\n🚛 {tag}\n\n⚪ {reason_name}\n"
    else:
        linhas = []
        for i, etapa in enumerate(SEQUENCIA_ROTA):
            marker = "🟢" if i == current_index else "⚪"
            linhas.append(f"{marker} {etapa}")
            if i < len(SEQUENCIA_ROTA) - 1:
                linhas.append("↓")
        rota_str = f"\n🚛 {tag}\n\n" + "\n".join(linhas) + "\n"

    return rota_str


def formatar_status_equipamento(item):
    tag = item.get("equipmentTag", "???")
    reason_name = item.get("reasonName", "")

    if not reason_name or reason_name == "":
        return (
            f"🚛 {tag}\n\n"
            f"📌 Apontamento atual:\n"
            f"Nenhum apontamento em aberto.\n"
        )

    inicio_dt = item.get("_inicio_dt")
    fim_dt = item.get("_fim_dt")
    data_inicio, hora_inicio = formatar_data_hora(inicio_dt)
    tempo = calcular_tempo_apontamento(inicio_dt, fim_dt)
    segundos = calcular_duracao_segundos(inicio_dt, fim_dt)
    operador = item.get("operator", "Não informado")
    location = item.get("location", "")
    if location is None or str(location).strip() == "":
        location = "Não informado"

    emoji = "🚛"
    tag_str = str(tag)
    if tag_str.startswith("CP"):
        emoji = "🚚"
    elif tag_str.startswith("TE"):
        emoji = "🚜"
    elif tag_str.startswith("EH"):
        emoji = "⚙️"
    elif tag_str.startswith("MN"):
        emoji = "🚜"
    elif tag_str.startswith("PC"):
        emoji = "🏗️"

    mensagem = (
        f"{emoji} {tag}\n\n"
        f"📌 Apontamento atual:\n{reason_name}\n\n"
        f"📅 Início:\n{data_inicio}\n\n"
        f"🕐 Hora:\n{hora_inicio}\n\n"
        f"⏱ Tempo:\n{tempo} ({formatar_duracao_completa(segundos)})\n\n"
    )

    mensagem += formatar_rota(item)

    return mensagem


def formatar_mensagem_multiplos_status(lista):
    if not lista:
        return "⚠️ Nenhum apontamento aberto encontrado para os equipamentos selecionados."

    mensagem = ""
    for item in lista:
        mensagem += formatar_status_equipamento(item)

    return mensagem[:4096]


def formatar_pausa_unica(item):
    tag = item.get("equipmentTag", "???")
    reason_name = item.get("reasonName", "PAUSA ERGONÔMICA")
    inicio_dt = item.get("_inicio_dt")
    fim_dt = item.get("_fim_dt")
    segundos = calcular_duracao_segundos(inicio_dt, fim_dt)
    tempo_str = formatar_duracao_completa(segundos)
    icone = emoji_pausa(segundos)
    alerta = " ⚠️" if segundos > 900 else ""

    return (
        f"{icone} {tag}\n"
        f"📌 {reason_name}\n\n"
        f"⏱ Tempo:\n{tempo_str}{alerta}\n"
    )


def formatar_pausas_ergonomicas(pausas):
    if not pausas:
        agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
        return f"✅ Nenhuma pausa ergonômica em aberto no momento.\n\n🔄 Atualizado em: {agora}\n"

    pausas_ordenadas = sorted(
        pausas,
        key=chave_inicio_apontamento
    )

    mensagem = "⏱ PAUSAS ERGONÔMICAS EM ABERTO\n\n"
    for item in pausas_ordenadas:
        mensagem += formatar_pausa_unica(item)
        mensagem += "━━━━━━━━━━━━━━━━━━━━\n\n"

    mensagem += f"🔄 Última atualização:\n{to_local(datetime.now(timezone.utc)).strftime('%d/%m/%Y %H:%M:%S')}\n"
    return mensagem[:4096]


def formatar_manutencoes(manutencoes):
    if not manutencoes:
        agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
        return f"✅ Nenhuma manutenção em andamento no momento.\n\n🔄 Atualizado em: {agora}\n"

    por_equipamento = {}
    for item in manutencoes:
        tag = item.get("equipmentTag", "???")
        if tag not in por_equipamento:
            por_equipamento[tag] = []
        por_equipamento[tag].append(item)

    mensagem = "🔧 MANUTENÇÕES EM ANDAMENTO\n\n"

    for tag in sorted(por_equipamento.keys()):
        items = por_equipamento[tag]
        emoji = "🚛"
        tag_str = str(tag)
        if tag_str.startswith("CP"):
            emoji = "🚚"
        elif tag_str.startswith("TE"):
            emoji = "🚜"
        elif tag_str.startswith("EH"):
            emoji = "⚙️"
        elif tag_str.startswith("MN"):
            emoji = "🚜"
        elif tag_str.startswith("PC"):
            emoji = "🏗️"

        mensagem += f"━━━━━━━━━━━━━━━━━━━━\n\n{emoji} {tag}\n\n"

        for item in items:
            reason_name = item.get("reasonName", "")
            # A data da manutenção é sempre a data de abertura informada pela API.
            # Não use o dia atual, endDate ou qualquer data operacional neste campo.
            inicio_dt = converter_timestamp(item.get("startDate")) or item.get("_inicio_dt")
            data_inicio = formatar_data_hora(inicio_dt)[0]
            operador = item.get("operator", "Não informado")

            if str(reason_name).upper().strip() == "EXEC MANUT CORRETIVA":
                icone = "🔴"
            else:
                icone = "🟠"

            mensagem += (
                f"{icone} Apontamento: {reason_name}\n\n"
                f"👷 Operador:\n{operador}\n\n"
            )

    mensagem += "━━━━━━━━━━━━━━━━━━━━\n"
    mensagem += f"🔄 Última atualização:\n{to_local(datetime.now(timezone.utc)).strftime('%d/%m/%Y %H:%M:%S')}\n"
    return mensagem[:4096]


def formatar_resumo_atualizacao():
    content = api_content_formatted()
    abertos = obter_apontamentos_abertos(content)
    pausas = obter_pausas_ergonomicas_abertas(content)
    manutencoes = obter_manutencoes_atuais(content)

    mensagem = "✅ Dados atualizados!\n\n"
    mensagem += "📊 STATUS DOS EQUIPAMENTOS\n"
    mensagem += "━━━━━━━━━━━━━━━━━━━━\n\n"

    if not abertos:
        mensagem += "Nenhum equipamento com apontamento aberto.\n\n"
    else:
        for item in abertos:
            tag = item.get("equipmentTag", "???")
            reason_name = item.get("reasonName", "")
            inicio_dt = item.get("_inicio_dt")
            fim_dt = item.get("_fim_dt")
            segundos = calcular_duracao_segundos(inicio_dt, fim_dt)
            tempo_str = formatar_duracao_completa(segundos)

            if "PAUSA" in str(reason_name).upper() or "ERGONOMICA" in str(reason_name).upper() or "ERGONÔMICA" in str(reason_name).upper():
                icone = emoji_pausa(segundos)
            elif reason_name in ("EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA"):
                icone = "🔴"
            else:
                icone = "🟢"

            mensagem += f"{icone} {tag} — {reason_name} ({tempo_str})\n"

    mensagem += f"\n⏱ Pausas ergonômicas: {len(pausas)} em aberto\n"
    mensagem += f"🔧 Manutenções: {len(manutencoes)} em andamento\n"
    mensagem += f"━━━━━━━━━━━━━━━━━━━━\n"
    mensagem += f"🕐 Atualizado às: {to_local(datetime.now(timezone.utc)).strftime('%d/%m/%Y %H:%M:%S')}\n"

    return mensagem[:4096]


def verificar_e_enviar_alertas_pausas(bot, chat_ids, alertas_enviados, content=None):
    try:
        pausas = obter_pausas_ergonomicas_abertas(content)
        ids_ativos = {item.get("id") for item in pausas if item.get("id") is not None}
        for apontamento_id in list(alertas_enviados):
            if apontamento_id not in ids_ativos:
                del alertas_enviados[apontamento_id]

        pausas_acima_limite = []
        for item in pausas:
            apontamento_id = item.get("id")
            if apontamento_id is None:
                continue
            duracao_seg = calcular_duracao_segundos(item.get("_inicio_dt"), item.get("_fim_dt"))
            if duracao_seg > 900 and apontamento_id not in alertas_enviados:
                pausas_acima_limite.append((item, apontamento_id, duracao_seg))

        if not pausas_acima_limite:
            return

        print("[MONITOR] Consultando API...")
        print(f"[MONITOR] Dados recebidos: {len(pausas)} pausas")

        for item, apontamento_id, duracao_seg in pausas_acima_limite:
            tag = item.get("equipmentTag", "???")
            reason_name = item.get("reasonName", "PAUSA ERGONÔMICA")
            inicio_dt = item.get("_inicio_dt")
            fim_dt = item.get("_fim_dt")
            data_inicio, hora_inicio = formatar_data_hora(inicio_dt)
            operador = item.get("operator", "Não informado")
            location = item.get("location", "")
            if location is None or str(location).strip() == "":
                location = "Não informado"

            duracao_seg = calcular_duracao_segundos(inicio_dt, fim_dt)

            mensagem = (
                "⚠️ ALERTA DE PAUSA ERGONÔMICA\n"
                "━━━━━━━━━━━━━━━━━━━━\n\n"
                f"🚛 Equipamento: {tag}\n\n"
                f"🔧 Apontamento:\n{reason_name}\n\n"
                f"📅 Data de início:\n{data_inicio}\n\n"
                f"🕐 Hora de início:\n{hora_inicio}\n\n"
                f"⏱ Tempo:\n{formatar_duracao_completa(duracao_seg)}\n\n"
                f"🔴 LIMITE DE 15 MINUTOS ULTRAPASSADO\n\n"
                f"👷 Operador:\n{operador}\n\n"
                f"📍 Local:\n{location}\n"
            )

            alertas_enviados[apontamento_id] = True

            for chat_id in chat_ids:
                try:
                    bot.send_message(chat_id, mensagem)
                    print(f"[ALERTA] Pausa acima de 15 minutos: {tag}")
                except Exception as e:
                    salvar_log(e)

    except Exception as e:
        salvar_log(e)


def verificar_e_enviar_alertas_manutencao(bot, chat_ids, alertas_enviados, content=None):
    try:
        manutencoes = obter_manutencoes_atuais(content)
        ids_ativos = {item.get("id") for item in manutencoes if item.get("id") is not None}
        for apontamento_id in list(alertas_enviados):
            if apontamento_id not in ids_ativos:
                del alertas_enviados[apontamento_id]

        novas_manutencoes = []
        for item in manutencoes:
            apontamento_id = item.get("id")
            if apontamento_id is None:
                continue
            if apontamento_id not in alertas_enviados:
                novas_manutencoes.append((item, apontamento_id))

        if not novas_manutencoes:
            return

        print("[MONITOR] Consultando API...")
        print(f"[MONITOR] Dados recebidos: {len(manutencoes)} manutenções")

        print(f"[ALERTA] {len(novas_manutencoes)} nova(s) manutenção(ões) detectada(s)")

        for item, apontamento_id in novas_manutencoes:
            tag = item.get("equipmentTag", "???")
            reason_name = item.get("reasonName", "")
            inicio_dt = converter_timestamp(item.get("startDate")) or item.get("_inicio_dt")
            data_inicio = formatar_data_hora(inicio_dt)[0]
            operador = item.get("operator", "Não informado")

            mensagem = (
                "🔴 ALERTA DE MANUTENÇÃO\n"
                "━━━━━━━━━━━━━━━━━━━━\n\n"
                f"🚛 Equipamento: {tag}\n\n"
                f"🔧 Apontamento:\n{reason_name}\n\n"
                f"📅 Data início:\n{data_inicio}\n\n"
                f"👷 Operador:\n{operador}\n"
            )

            alertas_enviados[apontamento_id] = True

            for chat_id in chat_ids:
                try:
                    bot.send_message(chat_id, mensagem)
                except Exception as e:
                    salvar_log(e)

    except Exception as e:
        salvar_log(e)


def monitorar_alertas(bot, chat_ids, alertas_pausa, alertas_manutencao):
    try:
        print("[MONITOR] Consultando API...")
        try:
            content = api_content_formatted()
        except Exception as e:
            salvar_log(e)
            print("[MONITOR] Consulta indisponível; estado de alertas preservado.")
            return
        print(f"[MONITOR] Dados recebidos: {len(content)}")

        verificar_e_enviar_alertas_pausas(bot, chat_ids, alertas_pausa, content)
        verificar_e_enviar_alertas_manutencao(bot, chat_ids, alertas_manutencao, content)

        salvar_alertas(alertas_pausa, alertas_manutencao)
    except Exception as e:
        salvar_log(e)


def salvar_alertas(alertas_pausa, alertas_manutencao):
    try:
        data = {
            "alertas_pausa": list(alertas_pausa.keys()),
            "alertas_manutencao": list(alertas_manutencao.keys()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        caminho = "alertas_cache.json"
        with open(caminho, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
    except Exception as e:
        pass


def carregar_alertas():
    try:
        with open("alertas_cache.json", "r", encoding="utf-8") as f:
            data = json.load(f)
        return {k: True for k in data.get("alertas_pausa", [])}, {k: True for k in data.get("alertas_manutencao", [])}
    except Exception:
        return {}, {}
