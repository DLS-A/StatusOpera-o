from telebot import types


def teclado_principal():
    markup = types.InlineKeyboardMarkup(row_width=2)
    btn_consultar = types.InlineKeyboardButton("🚛 Consultar equipamentos", callback_data="menu_consultar")
    btn_pausas = types.InlineKeyboardButton("⏱ Pausas ergonômicas", callback_data="menu_pausas")
    btn_manutencao = types.InlineKeyboardButton("🔧 Manutenções", callback_data="menu_manutencao")
    btn_atualizar = types.InlineKeyboardButton("🔄 Atualizar", callback_data="atualizar_principal")
    markup.add(btn_consultar, btn_pausas, btn_manutencao, btn_atualizar)
    return markup


def teclado_equipamentos(equipamentos, selecionados, pagina=1, por_pagina=12):
    markup = types.InlineKeyboardMarkup(row_width=2)
    inicio = (pagina - 1) * por_pagina
    fim = inicio + por_pagina
    equipamentos_pagina = equipamentos[inicio:fim]

    botoes = []
    for equipamento in equipamentos_pagina:
        if equipamento in selecionados:
            texto = f"☑ {equipamento}"
        else:
            texto = f"☐ {equipamento}"
        btn = types.InlineKeyboardButton(texto, callback_data=f"toggle_{equipamento}")
        botoes.append(btn)

    for i in range(0, len(botoes), 2):
        markup.row(*botoes[i:i + 2])

    total_paginas = (len(equipamentos) + por_pagina - 1) // por_pagina
    navegacao = []
    if pagina > 1:
        navegacao.append(types.InlineKeyboardButton("◀ Anterior", callback_data=f"pagina_{pagina - 1}"))
    if pagina < total_paginas:
        navegacao.append(types.InlineKeyboardButton("Próxima ▶", callback_data=f"pagina_{pagina + 1}"))

    if navegacao:
        markup.row(*navegacao)

    markup.row(
        types.InlineKeyboardButton("🔎 Consultar", callback_data="consultar_selecionados"),
        types.InlineKeyboardButton("❌ Cancelar", callback_data="cancelar_selecao")
    )
    return markup


def teclado_resultado():
    markup = types.InlineKeyboardMarkup(row_width=2)
    markup.add(
        types.InlineKeyboardButton("🔄 Atualizar", callback_data="atualizar_consulta"),
        types.InlineKeyboardButton("◀ Voltar", callback_data="voltar_menu")
    )
    return markup


def teclado_pausas():
    markup = types.InlineKeyboardMarkup(row_width=2)
    markup.add(
        types.InlineKeyboardButton("🔄 Atualizar", callback_data="atualizar_pausas"),
        types.InlineKeyboardButton("🏠 Menu Principal", callback_data="voltar_menu")
    )
    return markup


def teclado_manutencoes():
    markup = types.InlineKeyboardMarkup(row_width=2)
    markup.add(
        types.InlineKeyboardButton("🔄 Atualizar", callback_data="atualizar_manutencao"),
        types.InlineKeyboardButton("🏠 Menu Principal", callback_data="voltar_menu")
    )
    return markup


def teclado_voltar():
    markup = types.InlineKeyboardMarkup()
    markup.add(types.InlineKeyboardButton("◀ Voltar", callback_data="voltar_menu"))
    return markup
