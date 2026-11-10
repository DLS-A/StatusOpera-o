package com.grm.rotagrm.domain.model

import com.grm.rotagrm.settings.*
import org.junit.Assert.*
import org.junit.Test

class MonitoringRegressionTest {
    private val now = 1_800_000_000_000L
    private fun record(id: String = "p", reason: String = DEFAULT_PAUSE_STATUS) = OperationalRecord(
        id, "CB-1", reason, now - 960_000L, 960L, "", "", "", "", durationFromReport = true)
    private fun snapshot(record: OperationalRecord) = OperationalSnapshot(
        java.time.Instant.ofEpochMilli(now).toString(), listOf(EquipmentStatus(record.equipmentTag, record)), emptyList(), emptyList())

    @Test fun canonicalConfigurationHasOnlyPauseAndMeal() {
        val values = canonicalSettings(mapOf("PAUSA ERGONÔMICA" to 17, "REFEIÇÃO" to 50, "AG MANUTENCAO" to 200))
        assertEquals(mapOf(DEFAULT_PAUSE_STATUS to 17, MEAL_STATUS to 50), values)
        assertNull(configurableStatus("PAUSA PARA MANUTENCAO"))
    }

    @Test fun explicitCanonicalLimitWinsOverLegacyAccentDuplicate() {
        assertEquals(20, canonicalSettings(mapOf(DEFAULT_PAUSE_STATUS to 20, "PAUSA ERGONÔMICA" to 16))[DEFAULT_PAUSE_STATUS])
    }

    @Test fun mealUsesManuallyConfiguredLimitAndNoUnconfiguredDefault() {
        val data = snapshot(record(reason = "REFEIÇÃO"))
        assertTrue(AlertEvaluator.activeAlerts(data, MonitoringSettings(), now).isEmpty())
        val settings = MonitoringSettings(limitsMinutesByStatus = mapOf(MEAL_STATUS to 15), alertsEnabledByStatus = mapOf(MEAL_STATUS to true))
        assertEquals(60L, AlertEvaluator.activeAlerts(data, settings, now).single().exceededSeconds)
    }

    @Test fun maintenanceLimitsFromOldVersionNeverGenerateThresholdAlerts() {
        val settings = MonitoringSettings(limitsMinutesByStatus = mapOf("AG MANUTENCAO" to 1), alertsEnabledByStatus = mapOf("AG MANUTENCAO" to true))
        assertTrue(AlertEvaluator.activeAlerts(snapshot(record(reason = "AG MANUTENCAO")), settings, now).isEmpty())
    }

    @Test fun allFourMaintenanceReasonsStayOutOfEquipmentAndReturnAfterChange() {
        listOf("AG MANUTENÇÃO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA - 250 HRS").forEach { reason ->
            assertTrue(equipmentTabRecords(listOf(EquipmentStatus("CB-1", record(reason = reason)))).isEmpty())
        }
        val resumed = EquipmentStatus("CB-1", record("new", "DISPONIVEL PARA OPERAR"))
        assertEquals(listOf(resumed), equipmentTabRecords(listOf(resumed)))
        assertEquals(1, equipmentTabRecords(listOf(EquipmentStatus("CB-1", null))).size)
        assertFalse(isMaintenanceStatus("LIBERADO PELA MANUT"))
    }

    @Test fun raisedAlertStaysWithoutRecognitionUntilPointingChanges() {
        val data = snapshot(record())
        val raised = AlertEvaluator.activeAlerts(data, MonitoringSettings(), now)
        assertEquals(1, reconcileActiveAlerts(raised, emptyList(), data, now + 1_000L).size)
        assertTrue(reconcileActiveAlerts(raised, emptyList(), snapshot(record("new", "OPERANDO")), now).isEmpty())
        assertTrue(reconcileActiveAlerts(raised, emptyList(), snapshot(record("p", "REFEICAO")), now).isEmpty())
    }

    @Test fun offlineDoesNotFalselyClearAlertAndFreshEmptyDoes() {
        val data = snapshot(record())
        val raised = AlertEvaluator.activeAlerts(data, MonitoringSettings(), now)
        assertEquals(raised, reconcileActiveAlerts(raised, emptyList(), data.copy(stale = true, equipment = emptyList()), now))
        assertTrue(reconcileActiveAlerts(raised, emptyList(), data.copy(equipment = emptyList()), now).isEmpty())
    }

    @Test fun stalePauseListCannotResurrectChangedEquipmentPointing() {
        val pause = record()
        val original = snapshot(pause)
        val raised = AlertEvaluator.activeAlerts(original, MonitoringSettings(), now)
        val changed = snapshot(record("new", "OPERANDO")).copy(pauses = listOf(pause))
        assertTrue(AlertEvaluator.activeAlerts(changed, MonitoringSettings(), now).isEmpty())
        assertTrue(reconcileActiveAlerts(raised, emptyList(), changed, now).isEmpty())
    }

    @Test fun homeKeepsOnlyAwaitingMaintenanceAndMealButHidesPauseAndExecutionMaintenance() {
        val reasons = listOf("AG MANUTENCAO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA",
            "EXEC MANUT PREVENTIVA - 250 HRS", DEFAULT_PAUSE_STATUS, "REFEIÇÃO", "OPERANDO")
        val all = reasons.mapIndexed { index, reason -> OperationalAlert(
            "id-$index", "CB-$index", reason, 960L, 900L, 60L, maintenanceEvent = isMaintenanceStatus(reason)) }
        val original = all.toList()
        assertEquals(listOf(all[0], all[5]), homeAlerts(all))
        assertEquals(original, all) // Presentation never edits the monitor's list.
        assertEquals(4, all.count { it.maintenanceEvent })
        assertEquals(2, homeAlerts(all).size) // Home count uses the same filtered list.
        listOf("REFEIÇÃO", "refeicao", "  REFEICAO  ").forEach { reason ->
            val alert = all[5].copy(status = reason)
            assertEquals(listOf(alert), homeAlerts(listOf(alert)))
        }
        listOf(DEFAULT_PAUSE_STATUS, "PAUSA ERGONÔMICA", "  pausa   ergonomica  ").forEach { reason ->
            assertTrue(homeAlerts(listOf(all[4].copy(status = reason))).isEmpty())
        }
    }

    @Test fun awaitingMaintenanceMatchesAccentsCaseSpacingAndClearsAfterPointingChanges() {
        listOf("AG MANUTENÇÃO", "ag manutencao", "  AG   MANUTENCAO  ").forEach { reason ->
            assertTrue(isAwaitingMaintenanceStatus(reason))
            val alert = OperationalAlert("maintenance:m", "CB-1", reason, 0L, 0L, 0L, true)
            assertEquals(listOf(alert), homeAlerts(listOf(alert)))
            assertTrue(reconcileActiveAlerts(listOf(alert), emptyList(), snapshot(record("new", "OPERANDO")), now).isEmpty())
        }
        assertFalse(isAwaitingMaintenanceStatus("EXEC MANUT CORRETIVA"))
        assertFalse(isAwaitingMaintenanceStatus("LIBERADO PELA MANUT"))
        assertTrue(homeAlerts(emptyList()).isEmpty())
    }
}