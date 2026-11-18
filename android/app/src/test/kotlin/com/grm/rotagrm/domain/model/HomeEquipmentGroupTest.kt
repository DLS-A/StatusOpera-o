package com.grm.rotagrm.domain.model

import org.junit.Assert.*
import org.junit.Test

class HomeEquipmentGroupTest {
    private fun equipment(tag: String, reason: String, state: String = "unknown") = EquipmentStatus(tag,
        OperationalRecord("id-$tag", tag, reason, null, 123L, "", "", "ESCAVADEIRA", "", durationFromReport = true), state)

    @Test fun allFourteenOperatingReasons() {
        listOf("PRODUZINDO/OPERANDO", "ACERTO DE PRACA", "MOVIMENTANDO VAZIO", "CONTRAPILHANDO MATERIAL",
            "FILA NO CARREGAMENTO", "CARREGANDO CAMINHAO", "MANOBRA CARREGAMENTO", "EM CARREGAMENTO",
            "MOVIMENTANDO CHEIO", "FILA NO BASCULAMENTO", "MANOBRA BASCULAMENTO", "BASCULANDO",
            "SERVICO DE DRENAGEM", "CAPTACAO DE AGUA").forEach { assertEquals(it, HomeEquipmentGroup.OPERATING, homeEquipmentGroup(it)) }
    }
    @Test fun allTwentyStoppedReasons() {
        listOf("AG MANUTENCAO", "EQPTO NAO PROGRAMADO", "DISPONIVEL PARA OPERAR", "LOCOMOCAO", "LIBERADO PELA MANUT",
            "TROCA DE TURNO/INSPECAO", "REFEICAO", "MAU TEMPO", "PALESTRA OPERADOR", "FALTA DE OPERADOR",
            "AG COMBOIO", "ABASTECENDO", "AG PARE/SIGA", "PESAGEM", "PAUSA ERGONÔMICA", "PARADA PARTICULAR",
            "AG ACERTO DE PRACA", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA - 250 HRS")
            .forEach { assertEquals(it, HomeEquipmentGroup.STOPPED, homeEquipmentGroup(it)) }
    }
    @Test fun normalizesAccentsCaseSpacesAndHyphen() {
        assertEquals(HomeEquipmentGroup.STOPPED, homeEquipmentGroup("  exec  manut preventiva - 250 hrs "))
        assertEquals(HomeEquipmentGroup.OPERATING, homeEquipmentGroup(" captação de água "))
        assertEquals(HomeEquipmentGroup.STOPPED, homeEquipmentGroup(" pausa ergonômica "))
    }
    @Test fun excludesOtherReasonsAndMissingPointing() {
        listOf(null, "", "OUTRO", "HAO", "HMC", "PAUSA PARTICULAR", "AG MANUTENCAO EXTRA").forEach { assertNull(homeEquipmentGroup(it)) }
        assertTrue(homeEquipment(listOf(EquipmentStatus("EH-1", null, "operating")), HomeEquipmentGroup.OPERATING).isEmpty())
    }
    @Test fun includesAllTypesAndOverridesGrmOnlyInHome() {
        val water = equipment("EH-1", "CAPTACAO DE AGUA", "stopped")
        val maintenance = equipment("TR-1", "EXEC MANUT CORRETIVA", "maintenance")
        assertEquals(listOf(water), homeEquipment(listOf(water, maintenance), HomeEquipmentGroup.OPERATING))
        assertEquals(listOf(maintenance), homeEquipment(listOf(water, maintenance), HomeEquipmentGroup.STOPPED))
        assertEquals("stopped", water.operationalState)
    }
    @Test fun deduplicatesCountsAndReflectsNewPointing() {
        val stopped = equipment("EH-1", "REFEICAO")
        assertEquals(1, homeEquipment(listOf(stopped, stopped), HomeEquipmentGroup.STOPPED).size)
        val operating = equipment("EH-1", "PRODUZINDO/OPERANDO")
        assertTrue(homeEquipment(listOf(operating), HomeEquipmentGroup.STOPPED).isEmpty())
        assertEquals(1, homeEquipment(listOf(operating), HomeEquipmentGroup.OPERATING).size)
    }
    @Test fun staleDurationDoesNotGrowInEitherGroup() {
        val record = equipment("EH-1", "REFEICAO").current!!
        val snapshot = OperationalSnapshot("2026-11-18T12:00:00Z", emptyList(), emptyList(), emptyList(), stale = true)
        assertEquals(123L, record.elapsedSeconds(snapshot, java.time.Instant.parse("2026-11-18T14:00:00Z").toEpochMilli()))
    }
}