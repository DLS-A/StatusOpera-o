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

    @Test
    fun staleOrUnavailableSnapshotsNeverGenerateNewThresholdAlerts() {
        val fresh = snapshot(record("p1", start))
        listOf(fresh.copy(stale = true), fresh.copy(sourceAvailable = false),
            fresh.copy(persistenceAvailable = false)).forEach { unavailable ->
            assertTrue(AlertEvaluator.activeAlerts(unavailable, settings(), start + 20 * 60_000L).isEmpty())
        }
    }

    @Test
    fun maintenanceResetCreatesANewBaselineWithoutAlertingOldRecords() {
        val tracker = NewMaintenanceTracker()
        val maintenance = record("m1", start).copy(reasonName = "EXEC MANUT CORRETIVA")
        tracker.update(emptyList())
        assertEquals(listOf(maintenance), tracker.update(listOf(maintenance)))
        tracker.reset()
        assertTrue(tracker.update(listOf(maintenance)).isEmpty())
        assertTrue(tracker.activeEvents.isEmpty())
    }

    @Test
    fun missingStartUsesReportedDurationAndMatchesServerLimitKey() {
        val pause = record("p1", start, duration = 901L).copy(startEpochMillis = null)
        val alert = AlertEvaluator.activeAlerts(snapshot(pause), settings(), start).single()
        assertEquals(1L, alert.exceededSeconds)
        assertEquals("limit:p1:900", "limit:${alert.id}:${alert.limitSeconds}")
    }

    @Test
    fun reportDurationAvoidsTimezoneInflationAndTriggersOnlyAfterLimit() {
        val pause = record("p-report", start - 3 * 3_600_000L, duration = 900L)
            .copy(durationFromReport = true, reasonName = "PAUSA ERGONÔMICA")
        val data = snapshot(pause).copy(updatedAt = java.time.Instant.ofEpochMilli(start).toString())
        assertTrue(AlertEvaluator.activeAlerts(data, settings(), start).isEmpty())
        val alert = AlertEvaluator.activeAlerts(data, settings(), start + 1_000L).single()
        assertEquals(1L, alert.exceededSeconds)
    }

    @Test
    fun pauseTabFeedsAlertsEvenWhenEquipmentListDoesNotContainIt() {
        val pause = record("pause-tab", start, duration = 960L).copy(durationFromReport = true)
        val data = OperationalSnapshot(updatedAt = java.time.Instant.ofEpochMilli(start).toString(),
            equipment = emptyList(), pauses = listOf(pause), maintenance = emptyList())
        assertEquals("pause-tab", AlertEvaluator.activeAlerts(data, settings(), start).single().id)
        assertTrue(AlertEvaluator.activeAlerts(data.copy(pauses = emptyList()), settings(), start).isEmpty())
    }

    @Test
    fun staleReportedDurationDoesNotContinueGrowing() {
        val pause = record("pause-offline", start, duration = 960L).copy(durationFromReport = true)
        val data = snapshot(pause).copy(updatedAt = java.time.Instant.ofEpochMilli(start).toString(), stale = true)
        assertEquals(960L, pause.elapsedSeconds(data, start + 5 * 60_000L))
        assertTrue(AlertEvaluator.activeAlerts(data, settings(), start + 5 * 60_000L).isEmpty())
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
