"""Regressoes de data/hora dos apontamentos do GRM."""

import unittest
from unittest.mock import patch

from functions import (
    api_content_formatted,
    calcular_duracao_segundos,
    formatar_data_hora,
    formatar_manutencoes,
    normalizar_apontamento,
    obter_apontamentos_abertos,
    monitorar_alertas,
)


def registro(record_id, start, end=None, tag="CB-301"):
    return {
        "id": record_id,
        "equipmentTag": tag,
        "reasonName": "MOVIMENTANDO VAZIO",
        "startDate": start,
        "endDate": end,
    }


class DatasGRMTest(unittest.TestCase):
    def assert_exibicao(self, api_start, data, hora):
        item = normalizar_apontamento(registro(1, api_start))
        self.assertEqual(formatar_data_hora(item["_inicio_dt"]), (data, hora))

    def test_horarios_proximos_de_tres_horas(self):
        # API UTC -> America/Sao_Paulo, uma unica conversao.
        self.assert_exibicao("2026-09-22T05:50:00Z", "22/09/2026", "02:50:00")
        self.assert_exibicao("2026-09-22T06:00:00Z", "22/09/2026", "03:00:00")
        self.assert_exibicao("2026-09-22T06:30:00Z", "22/09/2026", "03:30:00")
        self.assert_exibicao("2026-09-22T07:00:00Z", "22/09/2026", "04:00:00")

    def test_apontamento_que_atravessa_meia_noite(self):
        item = normalizar_apontamento(
            registro(1, "2026-09-22T02:50:00Z", "2026-09-22T03:30:00Z")
        )
        self.assertEqual(formatar_data_hora(item["_inicio_dt"]), ("21/09/2026", "23:50:00"))
        self.assertEqual(formatar_data_hora(item["_fim_dt"]), ("22/09/2026", "00:30:00"))
        self.assertEqual(calcular_duracao_segundos(item["_inicio_dt"], item["_fim_dt"]), 40 * 60)

    def test_inicio_e_fim_em_dias_diferentes(self):
        item = normalizar_apontamento(
            registro(1, "2026-09-21T20:00:00Z", "2026-09-22T04:00:00Z")
        )
        self.assertEqual(calcular_duracao_segundos(item["_inicio_dt"], item["_fim_dt"]), 8 * 3600)

    def test_apontamento_aberto_nao_ganha_fim(self):
        item = normalizar_apontamento(registro(1, "2026-09-22T06:00:00Z"))
        self.assertTrue(item["aberto"])
        self.assertIsNone(item["_fim_dt"])

    def test_dois_apontamentos_preservam_fins_da_api(self):
        dados = [
            registro(1, "2026-09-22T05:00:00Z", "2026-09-22T05:20:00Z"),
            registro(2, "2026-09-22T05:30:00Z", "2026-09-22T05:45:00Z"),
        ]
        with patch("functions.buscar_dados", return_value=dados):
            itens = api_content_formatted()
        self.assertFalse(itens[0]["aberto"])
        self.assertFalse(itens[1]["aberto"])
        self.assertEqual(formatar_data_hora(itens[0]["_fim_dt"]), ("22/09/2026", "02:20:00"))
        self.assertEqual(formatar_data_hora(itens[1]["_fim_dt"]), ("22/09/2026", "02:45:00"))

    def test_manutencao_exibe_data_de_abertura_da_api(self):
        manutencao = normalizar_apontamento(
            registro(1, "2026-09-22T02:45:00Z", tag="MN-101")
        )
        manutencao["reasonName"] = "EXEC MANUT CORRETIVA"
        mensagem = formatar_manutencoes([manutencao])
        self.assertIn("EXEC MANUT CORRETIVA", mensagem)
        self.assertIn("MN-101", mensagem)

    def test_abertos_sao_selecionados_por_equipamento_sem_maior_fim_global(self):
        registros = [
            normalizar_apontamento(registro(1, 1000, 9000, "CB-301")),
            normalizar_apontamento(registro(2, 2000, None, "CB-301")),
            normalizar_apontamento(registro(3, 3000, 0, "CB-301")),
            normalizar_apontamento(registro(4, 2500, None, "CB-302")),
        ]

        abertos = obter_apontamentos_abertos(registros)

        self.assertEqual({item["equipmentTag"]: item["id"] for item in abertos}, {
            "CB-301": 3,
            "CB-302": 4,
        })

    def test_falha_de_consulta_preserva_estado_dos_alertas(self):
        pausas = {123: True}
        manutencoes = {456: True}

        with patch("functions.api_content_formatted", side_effect=RuntimeError("offline")), \
                patch("functions.salvar_log"):
            monitorar_alertas(None, [], pausas, manutencoes)

        self.assertEqual(pausas, {123: True})
        self.assertEqual(manutencoes, {456: True})


if __name__ == "__main__":
    unittest.main()
