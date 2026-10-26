package com.grm.rotagrm.domain.model

import com.grm.rotagrm.settings.DEFAULT_PAUSE_STATUS
import com.grm.rotagrm.settings.MonitoringSettings
import com.grm.rotagrm.settings.alertStorageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {
    private val start = 1_800_000_000_000L

    @Test
    fun doesNotAlertAtExactlyTheLimit() {
        val alerts = AlertEvaluator.activeAlerts(snapshot(record("p1", start)), settings(), start + 15 * 60_000L)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun alertsOnlyAfterTheLimitAndCalculatesOverrunFromTimestamp() {
        val alerts = AlertEvaluator.activeAlerts(snapshot(record("p1", start, duration = 999_999)), settings(), start + 16 * 60_000L)
        assertEquals(1, alerts.size)
        assertEquals(60L, alerts.single().exceededSeconds)
    }

    @Test
    fun disabledAlertDoesNotCreateAnAlert() {
        val disabled = settings().copy(alertsEnabledByStatus = mapOf(DEFAULT_PAUSE_STATUS to false))
        val alerts = AlertEvaluator.activeAlerts(snapshot(record("p1", start)), disabled, start + 20 * 60_000L)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun deDuplicatesRecordsWithSameId() {
        val record = record("p1", start)
        val data = OperationalSnapshot(
            updatedAt = "",
            equipment = listOf(EquipmentStatus("EQ-1", record), EquipmentStatus("EQ-1-copy", record)),
            pauses = emptyList(),
            maintenance = emptyList(),
        )
        val alerts = AlertEvaluator.activeAlerts(data, settings(), start + 20 * 60_000L)
        assertEquals(1, alerts.size)
    }

    @Test
    fun newMaintenanceTrackerUsesFirstSnapshotAsBaselineAndDeduplicatesUpdates() {
        val tracker = NewMaintenanceTracker()
        val old = record("m1", start).copy(reasonName = "EXEC MANUT CORRETIVA")
        val new = record("m2", start).copy(reasonName = "EXEC MANUT PREVENTIVA")

        assertTrue(tracker.update(listOf(old)).isEmpty())
        assertEquals(listOf(new), tracker.update(listOf(old, new)))
        assertTrue(tracker.update(listOf(old, new)).isEmpty())
        assertEquals(listOf(new), tracker.activeEvents)
        tracker.update(listOf(old))
        assertTrue(tracker.activeEvents.isEmpty())
    }

    @Test
    fun ignoresRecordsWithoutStableIdsAndHashesAlertStorageIds() {
        val anonymous = record("", start)
        val alerts = AlertEvaluator.activeAlerts(snapshot(anonymous), settings(), start + 20 * 60_000L)
        assertTrue(alerts.isEmpty())
        val hashed = alertStorageId("private-operational-id")
        assertEquals(64, hashed.length)
        assertEquals(hashed, alertStorageId("private-operational-id"))
    }

    private fun settings() = MonitoringSettings(
        limitsMinutesByStatus = mapOf(DEFAULT_PAUSE_STATUS to 15),
        alertsEnabledByStatus = mapOf(DEFAULT_PAUSE_STATUS to true),
    )

    private fun snapshot(vararg records: OperationalRecord) = OperationalSnapshot(
        updatedAt = "",
        equipment = records.mapIndexed { index, item -> EquipmentStatus("EQ-$index", item) },
        pauses = emptyList(),
        maintenance = emptyList(),
    )

    private fun record(id: String, startedAt: Long, duration: Long = 0) = OperationalRecord(
        id = id,
        equipmentTag = "EQ-1",
        reasonName = "PAUSA ERGONOMICA",
        startEpochMillis = startedAt,
        durationSeconds = duration,
        operator = "",
        location = "",
        equipmentType = "",
        kpiCategory = "",
    )
}
