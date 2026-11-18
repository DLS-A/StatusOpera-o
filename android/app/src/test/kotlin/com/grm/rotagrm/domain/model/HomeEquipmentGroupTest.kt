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
    @Test fun allFourteenRemainingStoppedReasons() {
        listOf("EQPTO NAO PROGRAMADO", "DISPONIVEL PARA OPERAR", "LOCOMOCAO", "TROCA DE TURNO/INSPECAO",
            "MAU TEMPO", "PALESTRA OPERADOR", "FALTA DE OPERADOR", "AG COMBOIO", "ABASTECENDO", "AG PARE/SIGA",
            "PESAGEM", "PAUSA ERGONÔMICA", "PARADA PARTICULAR", "AG ACERTO DE PRACA")
            .forEach { assertEquals(it, HomeEquipmentGroup.STOPPED, homeEquipmentGroup(it)) }
    }
    @Test fun maintenanceAndMealExcludedOnlyFromStoppedHome() {
        listOf("AG MANUTENCAO", "LIBERADO PELA MANUT", "REFEICAO", "EXEC MANUT CORRETIVA",
            "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA - 250 HRS", " refeição ",
            " ag manutenção ", " liberado pela manut ").forEach {
            assertNull(it, homeEquipmentGroup(it))
            val item = equipment("EH-1", it, "maintenance")
            assertTrue(homeEquipment(listOf(item), HomeEquipmentGroup.STOPPED).isEmpty())
            assertEquals(it, item.current!!.reasonName)
            assertEquals("maintenance", item.operationalState)
        }
    }
    @Test fun normalizesAccentsCaseSpacesAndHyphen() {
        assertNull(homeEquipmentGroup("  exec  manut preventiva - 250 hrs "))
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
        assertTrue(homeEquipment(listOf(water, maintenance), HomeEquipmentGroup.STOPPED).isEmpty())
        assertEquals("stopped", water.operationalState)
    }
    @Test fun deduplicatesCountsAndReflectsNewPointing() {
        val stopped = equipment("EH-1", "MAU TEMPO")
        assertEquals(1, homeEquipment(listOf(stopped, stopped), HomeEquipmentGroup.STOPPED).size)
        val operating = equipment("EH-1", "PRODUZINDO/OPERANDO")
        assertTrue(homeEquipment(listOf(operating), HomeEquipmentGroup.STOPPED).isEmpty())
        assertEquals(1, homeEquipment(listOf(operating), HomeEquipmentGroup.OPERATING).size)
    }
    @Test fun staleDurationDoesNotGrowInEitherGroup() {
        val record = equipment("EH-1", "MAU TEMPO").current!!
        val snapshot = OperationalSnapshot("2026-11-18T12:00:00Z", emptyList(), emptyList(), emptyList(), stale = true)
        assertEquals(123L, record.elapsedSeconds(snapshot, java.time.Instant.parse("2026-11-18T14:00:00Z").toEpochMilli()))
    }
}