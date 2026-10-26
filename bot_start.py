from telebot import TeleBot
import logging
from keys import TOKEN_BOT_GRM
from datetime import datetime, timezone
from functions import (
    api_content_formatted,
    listar_equipamentos,
    obter_status_equipamento,
    obter_status_varios_equipamentos,
    obter_pausas_ergonomicas_abertas,
    obter_manutencoes_abertas,
    formatar_status_equipamento,
    formatar_mensagem_multiplos_status,
    formatar_pausa_unica,
    formatar_pausas_ergonomicas,
    formatar_manutencoes,
    formatar_resumo_atualizacao,
    salvar_log,
    to_local,
)
from keyboards import (
    teclado_principal,
    teclado_equipamentos,
    teclado_resultado,
    teclado_pausas,
    teclado_manutencoes,
    teclado_voltar,
)

if not TOKEN_BOT_GRM:
    raise EnvironmentError("Defina TOKEN_BOT_GRM no ambiente para iniciar o bot Telegram.")
bot = TeleBot(TOKEN_BOT_GRM)

selecao_equipamentos = {}
mensagens_anteriores = {}

from monitor import alertas_pausa, alertas_manutencao, iniciar_monitoramento


def apagar_mensagens_anteriores(chat_id, chave):
    for message_id in mensagens_anteriores.get(chat_id, {}).get(chave, []):
        try:
            bot.delete_message(chat_id, message_id)
        except Exception:
            pass
    mensagens_anteriores.setdefault(chat_id, {})[chave] = []


def registrar_mensagem(chat_id, chave, message_id):
    mensagens_anteriores.setdefault(chat_id, {}).setdefault(chave, []).append(message_id)


MENU_INICIAL = (
    "🚛 STATUS OPERACIONAL GRM\n\n"
    "Olá! 👋\n\n"
    "Sou o bot de monitoramento de rota do GRM.\n\n"
    "Aqui você pode consultar o status dos equipamentos,\n"
    "acompanhar as pausas ergonômicas e receber alertas\n"
    "de manutenção.\n\n"
    "Escolha uma opção:"
)


@bot.message_handler(commands=['start'])
def cmd_start(message):
    try:
        chat_id = message.chat.id
        selecao_equipamentos.pop(chat_id, None)
        bot.send_message(chat_id, MENU_INICIAL, reply_markup=teclado_principal())
    except Exception as e:
        salvar_log(e)


@bot.callback_query_handler(func=lambda call: call.data == "menu_consultar")
def callback_consultar(call):
    try:
        chat_id = call.message.chat.id
        bot.answer_callback_query(call.id, "Carregando equipamentos...")
        content = api_content_formatted()

        equipamentos = sorted({
            item.get("equipmentTag") for item in content if item.get("equipmentTag")
        })

        if not equipamentos:
            bot.answer_callback_query(call.id, "Nenhum equipamento encontrado na API.", show_alert=True)
            return

        selecao_equipamentos[chat_id] = {
            "equipamentos": equipamentos,
            "selecionados": set(),
            "pagina": 1,
        }

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=call.message.message_id,
            text="📋 Selecione os equipamentos que deseja consultar:\n\nℹ️ Você pode escolher mais de um.",
            reply_markup=teclado_equipamentos(equipamentos, set(), 1)
        )
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao carregar equipamentos.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data.startswith("pagina_"))
def callback_pagina(call):
    try:
        chat_id = call.message.chat.id
        state = selecao_equipamentos.get(chat_id)
        if not state:
            bot.answer_callback_query(call.id, "Sessão expirada. Recarregue o menu.", show_alert=True)
            return

        pagina = int(call.data.replace("pagina_", ""))
        state["pagina"] = pagina
        equipamentos = state.get("equipamentos", [])
        selecionados = state.get("selecionados", set())

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=call.message.message_id,
            text="📋 Selecione os equipamentos que deseja consultar:\n\nℹ️ Você pode escolher mais de um.",
            reply_markup=teclado_equipamentos(equipamentos, selecionados, pagina)
        )
    except Exception as e:
        salvar_log(e)


@bot.callback_query_handler(func=lambda call: call.data.startswith("toggle_"))
def callback_toggle(call):
    try:
        chat_id = call.message.chat.id
        equipamento = call.data.replace("toggle_", "")
        state = selecao_equipamentos.get(chat_id)
        if not state:
            return

        selecionados = state.get("selecionados", set())
        if equipamento in selecionados:
            selecionados.discard(equipamento)
        else:
            selecionados.add(equipamento)
        state["selecionados"] = selecionados

        equipamentos = state.get("equipamentos", [])
        pagina = state.get("pagina", 1)

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=call.message.message_id,
            text="📋 Selecione os equipamentos que deseja consultar:\n\nℹ️ Você pode escolher mais de um.",
            reply_markup=teclado_equipamentos(equipamentos, selecionados, pagina)
        )
    except Exception as e:
        salvar_log(e)


@bot.callback_query_handler(func=lambda call: call.data == "consultar_selecionados")
def callback_consultar_selecionados(call):
    try:
        chat_id = call.message.chat.id
        state = selecao_equipamentos.get(chat_id)
        if not state:
            bot.answer_callback_query(call.id, "Sessão expirada.", show_alert=True)
            return

        selecionados = list(state.get("selecionados", set()))
        if not selecionados:
            bot.answer_callback_query(call.id, "Selecione pelo menos um equipamento.", show_alert=True)
            return

        bot.answer_callback_query(call.id, "Consultando API...")

        if len(selecionados) == 1:
            item = obter_status_equipamento(selecionados[0])
            if item:
                texto = formatar_status_equipamento(item)
            else:
                texto = f"🚛 {selecionados[0]}\n\n📌 Apontamento atual:\nNenhum apontamento em aberto."
        else:
            itens = obter_status_varios_equipamentos(selecionados)
            texto = formatar_mensagem_multiplos_status(itens)

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=call.message.message_id,
            text=texto,
            reply_markup=teclado_resultado()
        )
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao consultar status.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "atualizar_consulta")
def callback_atualizar_consulta(call):
    try:
        chat_id = call.message.chat.id
        state = selecao_equipamentos.get(chat_id)
        if not state:
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=call.message.message_id,
                text="Sessão expirada. Volte ao menu.",
                reply_markup=teclado_voltar()
            )
            return

        selecionados = list(state.get("selecionados", set()))
        if not selecionados:
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=call.message.message_id,
                text="Sessão expirada. Volte ao menu.",
                reply_markup=teclado_voltar()
            )
            return

        bot.answer_callback_query(call.id, "Atualizando...")

        if len(selecionados) == 1:
            item = obter_status_equipamento(selecionados[0])
            if item:
                texto = formatar_status_equipamento(item)
            else:
                texto = f"🚛 {selecionados[0]}\n\n📌 Apontamento atual:\nNenhum apontamento em aberto."
        else:
            itens = obter_status_varios_equipamentos(selecionados)
            texto = formatar_mensagem_multiplos_status(itens)

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=call.message.message_id,
            text=texto,
            reply_markup=teclado_resultado()
        )
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao atualizar.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "menu_pausas")
def callback_menu_pausas(call):
    try:
        chat_id = call.message.chat.id
        message_id = call.message.message_id
        bot.answer_callback_query(call.id, "Carregando pausas...")
        pausas = obter_pausas_ergonomicas_abertas()

        if not pausas:
            agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=message_id,
                text=f"✅ Nenhuma pausa ergonômica em aberto no momento.\n\n🔄 Atualizado em: {agora}\n",
                reply_markup=teclado_pausas()
            )
            return

        pausas_ordenadas = sorted(
            pausas,
            key=lambda x: x.get("startDate") or 0
        )

        apagar_mensagens_anteriores(chat_id, "pausas")

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=message_id,
            text="⏱ PAUSAS ERGONÔMICAS EM ABERTO",
            reply_markup=None
        )

        for i, item in enumerate(pausas_ordenadas):
            texto = formatar_pausa_unica(item)
            markup = teclado_pausas() if i == len(pausas_ordenadas) - 1 else None
            msg = bot.send_message(
                chat_id=chat_id,
                text=texto,
                reply_markup=markup
            )
            registrar_mensagem(chat_id, "pausas", msg.message_id)
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao carregar pausas.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "atualizar_pausas")
def callback_atualizar_pausas(call):
    try:
        chat_id = call.message.chat.id
        message_id = call.message.message_id
        bot.answer_callback_query(call.id, "Atualizando pausas...")
        pausas = obter_pausas_ergonomicas_abertas()

        if not pausas:
            agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=message_id,
                text=f"✅ Nenhuma pausa ergonômica em aberto no momento.\n\n🔄 Atualizado em: {agora}\n",
                reply_markup=teclado_pausas()
            )
            return

        pausas_ordenadas = sorted(
            pausas,
            key=lambda x: x.get("startDate") or 0
        )

        apagar_mensagens_anteriores(chat_id, "pausas")

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=message_id,
            text="⏱ PAUSAS ERGONÔMICAS EM ABERTO\n\n🔄 Atualizado!",
            reply_markup=None
        )

        for i, item in enumerate(pausas_ordenadas):
            texto = formatar_pausa_unica(item)
            markup = teclado_pausas() if i == len(pausas_ordenadas) - 1 else None
            msg = bot.send_message(
                chat_id=chat_id,
                text=texto,
                reply_markup=markup
            )
            registrar_mensagem(chat_id, "pausas", msg.message_id)
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao atualizar pausas.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "menu_manutencao")
def callback_menu_manutencao(call):
    try:
        chat_id = call.message.chat.id
        message_id = call.message.message_id
        bot.answer_callback_query(call.id, "Carregando manutenções...")
        manutencoes = obter_manutencoes_abertas()

        if not manutencoes:
            agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=message_id,
                text=f"✅ Nenhuma manutenção em andamento no momento.\n\n🔄 Atualizado em: {agora}\n",
                reply_markup=teclado_manutencoes()
            )
            return

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=message_id,
            text="🔧 MANUTENÇÕES EM ANDAMENTO",
            reply_markup=None
        )

        apagar_mensagens_anteriores(chat_id, "manutencoes")

        for i, item in enumerate(manutencoes):
            texto = formatar_manutencoes([item])
            markup = teclado_manutencoes() if i == len(manutencoes) - 1 else None
            msg = bot.send_message(
                chat_id=chat_id,
                text=texto,
                reply_markup=markup
            )
            registrar_mensagem(chat_id, "manutencoes", msg.message_id)
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao carregar manutenções.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "atualizar_manutencao")
def callback_atualizar_manutencao(call):
    try:
        chat_id = call.message.chat.id
        message_id = call.message.message_id
        bot.answer_callback_query(call.id, "Atualizando manutenções...")
        manutencoes = obter_manutencoes_abertas()

        if not manutencoes:
            agora = to_local(datetime.now(timezone.utc)).strftime("%d/%m/%Y %H:%M:%S")
            bot.edit_message_text(
                chat_id=chat_id,
                message_id=message_id,
                text=f"✅ Nenhuma manutenção em andamento no momento.\n\n🔄 Atualizado em: {agora}\n",
                reply_markup=teclado_manutencoes()
            )
            return

        bot.edit_message_text(
            chat_id=chat_id,
            message_id=message_id,
            text="🔧 MANUTENÇÕES EM ANDAMENTO\n\n🔄 Atualizado!",
            reply_markup=None
        )

        apagar_mensagens_anteriores(chat_id, "manutencoes")

        for i, item in enumerate(manutencoes):
            texto = formatar_manutencoes([item])
            markup = teclado_manutencoes() if i == len(manutencoes) - 1 else None
            msg = bot.send_message(
                chat_id=chat_id,
                text=texto,
                reply_markup=markup
            )
            registrar_mensagem(chat_id, "manutencoes", msg.message_id)
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao atualizar manutenções.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "atualizar_principal")
def callback_atualizar_principal(call):
    try:
        texto = formatar_resumo_atualizacao()
        bot.edit_message_text(
            chat_id=call.message.chat.id,
            message_id=call.message.message_id,
            text=texto,
            reply_markup=teclado_resultado()
        )
    except Exception as e:
        salvar_log(e)
        bot.answer_callback_query(call.id, "Erro ao atualizar.", show_alert=True)


@bot.callback_query_handler(func=lambda call: call.data == "voltar_menu")
def callback_voltar_menu(call):
    try:
        chat_id = call.message.chat.id
        selecao_equipamentos.pop(chat_id, None)
        bot.edit_message_text(
            chat_id=call.message.chat.id,
            message_id=call.message.message_id,
            text=MENU_INICIAL,
            reply_markup=teclado_principal()
        )
    except Exception as e:
        salvar_log(e)


@bot.callback_query_handler(func=lambda call: call.data == "cancelar_selecao")
def callback_cancelar_selecao(call):
    try:
        chat_id = call.message.chat.id
        selecao_equipamentos.pop(chat_id, None)
        bot.edit_message_text(
            chat_id=call.message.chat.id,
            message_id=call.message.message_id,
            text=MENU_INICIAL,
            reply_markup=teclado_principal()
        )
    except Exception as e:
        salvar_log(e)


@bot.message_handler(func=lambda message: True)
def fallback(message):
    try:
        bot.send_message(
            message.chat.id,
            "Use o menu abaixo para navegar.",
            reply_markup=teclado_principal()
        )
    except Exception as e:
        salvar_log(e)


def iniciar_bot_status():
    import time
    import socket
    import random
    from keys import CHAT_IDS_ALERTA

    if not CHAT_IDS_ALERTA:
        raise EnvironmentError("Defina CHAT_IDS_ALERTA no ambiente para ativar os alertas.")
    socket.setdefaulttimeout(30)

    iniciar_monitoramento()
    print("Bot de Status Operacional iniciado.", flush=True)

    tentativa = 0
    while True:
        tentativa += 1
        try:
            # Propaga a falha para que o loop aplique backoff controlado.
            bot.polling(
                non_stop=False,
                timeout=60,
                long_polling_timeout=60,
                logger_level=logging.WARNING,
            )
        except Exception as e:
            salvar_log(e)
            # Evita despejar exceções HTTP que podem conter caminhos de
            # autenticação ou dados internos no log de inicialização.
            print(f"[RETRY] Falha temporária no polling ({type(e).__name__}).", flush=True)
        else:
            print(f"[RETRY] Tentativa {tentativa}: polling parou inesperadamente", flush=True)

        # Timeout/DNS são falhas transitórias de rede. Repetir com backoff
        # reduz carga e evita ciclos de log quando a conexão está indisponível.
        atraso = min(300, 5 * (2 ** min(tentativa - 1, 6)))
        atraso += random.uniform(0, min(5, atraso * 0.2))
        print(f"[RETRY] Nova tentativa em aproximadamente {int(atraso)}s.", flush=True)
        time.sleep(atraso)


if __name__ == "__main__":
    iniciar_bot_status()
