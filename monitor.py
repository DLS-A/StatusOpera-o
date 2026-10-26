"""
Módulo de monitoramento em segundo plano.

Responsável por:
- Detectar novas manutenções (EXEC MANUT CORRETIVA / EXEC MANUT PREVENTIVA)
- Detectar pausas ergonômicas acima de 15 minutos
- Evitar alertas duplicados (deduplicação por ID do apontamento)
- Atualizar o estado interno

Pode ser executado de forma independente ou importado pelo bot_start.py.
"""

import threading
import time

from keys import CHAT_IDS_ALERTA
from functions import (
    monitorar_alertas,
    salvar_log,
    carregar_alertas,
    INTERVALO_MONITORAMENTO,
    api_content_formatted,
    obter_apontamentos_abertos,
    obter_pausas_ergonomicas_abertas,
    obter_manutencoes_abertas,
)
from telebot import TeleBot
from keys import TOKEN_BOT_GRM


CHAT_IDS_ALERTA_LISTA = [int(chat_id.strip()) for chat_id in CHAT_IDS_ALERTA.split(",") if chat_id.strip()]


alertas_pausa = {}
alertas_manutencao = {}
_thread_monitoramento = None
_lock_monitoramento = threading.Lock()


def iniciar_monitoramento(bot=None):
    """
    Inicia a thread de monitoramento em segundo plano.

    Args:
        bot: Instância do TeleBot. Se None, cria uma nova.
    """
    global alertas_pausa, alertas_manutencao, _thread_monitoramento
    with _lock_monitoramento:
        if _thread_monitoramento is not None and _thread_monitoramento.is_alive():
            return _thread_monitoramento

        if bot is None:
            if not TOKEN_BOT_GRM:
                raise EnvironmentError("Defina TOKEN_BOT_GRM para iniciar o monitor do Telegram.")
            if not CHAT_IDS_ALERTA_LISTA:
                raise EnvironmentError("Defina CHAT_IDS_ALERTA para receber alertas do monitor.")
            bot = TeleBot(TOKEN_BOT_GRM)

        alertas_pausa_salvos, alertas_manutencao_salvos = carregar_alertas()
        alertas_pausa.clear()
        alertas_pausa.update(alertas_pausa_salvos)
        alertas_manutencao.clear()
        alertas_manutencao.update(alertas_manutencao_salvos)
        print(f"[MONITOR] Alertas carregados: {len(alertas_pausa)} pausas, {len(alertas_manutencao)} manutenções")

    def loop_monitoramento():
        print("[MONITOR] Thread de monitoramento iniciada.", flush=True)
        while True:
            try:
                monitorar_alertas(bot, CHAT_IDS_ALERTA_LISTA, alertas_pausa, alertas_manutencao)
                time.sleep(INTERVALO_MONITORAMENTO)
            except Exception as e:
                salvar_log(e)
                time.sleep(INTERVALO_MONITORAMENTO)

        _thread_monitoramento = threading.Thread(target=loop_monitoramento, daemon=True, name="grm-alert-monitor")
        _thread_monitoramento.start()
        return _thread_monitoramento


def status_monitoramento():
    """
    Retorna informações sobre o estado atual do monitoramento.
    Útil para debug ou relatórios.

    Returns:
        dict com contagens de alertas ativos e dados da API.
    """
    try:
        content = api_content_formatted()
        if not content:
            return {
                "total_registros": 0,
                "apontamentos_abertos": 0,
                "pausas_abertas": 0,
                "manutencoes_abertas": 0,
                "alertas_pausa_ativos": len(alertas_pausa),
                "alertas_manutencao_ativos": len(alertas_manutencao),
            }
        abertos = obter_apontamentos_abertos(content)
        pausas = obter_pausas_ergonomicas_abertas(content)
        manutencoes = obter_manutencoes_abertas(content)

        return {
            "total_registros": len(content),
            "apontamentos_abertos": len(abertos),
            "pausas_abertas": len(pausas),
            "manutencoes_abertas": len(manutencoes),
            "alertas_pausa_ativos": len(alertas_pausa),
            "alertas_manutencao_ativos": len(alertas_manutencao),
        }
    except Exception as e:
        salvar_log(e)
        return {
            "total_registros": 0,
            "apontamentos_abertos": 0,
            "pausas_abertas": 0,
            "manutencoes_abertas": 0,
            "alertas_pausa_ativos": len(alertas_pausa),
            "alertas_manutencao_ativos": len(alertas_manutencao),
        }


if __name__ == "__main__":
    print("[MONITOR] Iniciando monitoramento em segundo plano...")
    iniciar_monitoramento()
    print(f"[MONITOR] Intervalo de consulta: {INTERVALO_MONITORAMENTO}s")
    print("[MONITOR] Pressione Ctrl+C para parar.")

    try:
        while True:
            status = status_monitoramento()
            print(f"[MONITOR] Registros: {status['total_registros']} | "
                  f"Abertos: {status['apontamentos_abertos']} | "
                  f"Pausas: {status['pausas_abertas']} | "
                  f"Manutenções: {status['manutencoes_abertas']} | "
                  f"Alertas pausa: {status['alertas_pausa_ativos']} | "
                  f"Alertas manutenção: {status['alertas_manutencao_ativos']}")
            time.sleep(INTERVALO_MONITORAMENTO)
    except KeyboardInterrupt:
        print("\n[MONITOR] Monitoramento interrompido pelo usuário.")
