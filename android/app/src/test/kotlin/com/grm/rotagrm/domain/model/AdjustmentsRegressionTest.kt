package com.grm.rotagrm.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AdjustmentsRegressionTest {
    private val now = 1_800_000_000_000L
    private val mono = 5_000_000_000L
    private fun record(id: String = "m", reason: String = "AG MANUTENCAO") = OperationalRecord(
        id, "EQ", reason, now - 3_600_000L, 3600L, "", "", "", "HMC",
        durationFromReport = true, startConfirmed = true, durationMeasuredAtEpochMillis = now)
    private fun snapshot(row: OperationalRecord = record()) = OperationalSnapshot(
        Instant.ofEpochMilli(now).toString(), listOf(EquipmentStatus("EQ", row)), emptyList(), listOf(row),
        receivedAtMillis = now + 10_800_000L, serverTimeEpochMillis = now, receivedAtMonotonicNanos = mono)

    @Test fun adjustmentsAcceptOnlyExactFiveDigitPassword() {
        assertTrue(settingsPasswordAccepted("30824"))
        listOf("", "3082", "308240", "30825", " 30824", "30824 ", "abcde", "３０８２４").forEach {
            assertFalse(settingsPasswordAccepted(it))
        }
    }

    @Test fun authorFallbackPreservesCompleteName() {
        assertEquals("OPERADOR FICTICIO COMPLETO", operatorLabel("  OPERADOR FICTICIO COMPLETO  "))
        listOf("", " ", "-", "null", "NONE", "Não informado", "SEM OPERADOR").forEach {
            assertEquals("Apontado via Web", operatorLabel(it))
        }
    }

    @Test fun locationShownOnlyWhenSupplied() {
        assertEquals("PATIO TESTE", pointingLocation("  PATIO TESTE  "))
        listOf("", " ", "-", "null", "NONE", "Não informado").forEach { assertNull(pointingLocation(it)) }
    }

    @Test fun homeAlertFindsExactOpenPointingIncludingMaintenancePrefix() {
        val data = snapshot()
        val alert = OperationalAlert("maintenance:m", "EQ", "AG MANUTENÇÃO", 0L, 0L, 0L, true)
        assertEquals(data.equipment[0].current, alertPointing(alert, data))
        assertNull(alertPointing(alert.copy(id = "maintenance:other"), data))
        assertNull(alertPointing(alert.copy(equipmentTag = "OTHER"), data))
        assertNull(alertPointing(alert.copy(status = "EXEC MANUT CORRETIVA"), data))
    }

    @Test fun clockUsesServerAndMonotonicElapsedNotPhoneTimezoneOrClockChanges() {
        val data = snapshot()
        assertEquals(now + 5000L, data.serverNowMillis(now + 10_800_000L, mono + 5_000_000_000L))
        assertEquals(3605L, record().elapsedSeconds(data, now - 86_400_000L, mono + 5_000_000_000L))
        assertEquals(3605L, record().elapsedSeconds(data, now + 86_400_000L, mono + 5_000_000_000L))
    }

    @Test fun fullElapsedUsesSourceMeasurementNotPayloadConstructionTime() {
        val row = record().copy(durationMeasuredAtEpochMillis = now - 30_000L)
        assertEquals(3635L, row.elapsedSeconds(snapshot(row), now, mono + 5_000_000_000L))
        // Full duration, not only amount over a configured threshold.
        assertTrue(row.elapsedSeconds(snapshot(row), now, mono) > 900L)
    }

    @Test fun staleOfflineAndPersistenceFailuresFreezeApiDuration() {
        val data = snapshot()
        listOf(data.copy(stale = true), data.copy(sourceAvailable = false), data.copy(persistenceAvailable = false)).forEach {
            assertFalse(it.isFresh(now, mono + 10_000_000_000L))
            assertEquals(3600L, record().elapsedSeconds(it, now, mono + 10_000_000_000L))
        }
    }

    @Test fun oldOrRestoredCacheCannotInventLiveTime() {
        val data = snapshot()
        assertFalse(data.isFresh(now, mono + 121_000_000_000L))
        assertEquals(3600L, record().elapsedSeconds(data, now, mono + 121_000_000_000L))
        val restored = data.copy(stale = true, sourceAvailable = false, receivedAtMillis = now + 86_400_000L,
            receivedAtMonotonicNanos = mono + 86_400_000_000_000L)
        assertEquals(3600L, record().elapsedSeconds(restored, now + 86_400_000L, restored.receivedAtMonotonicNanos))
    }

    @Test fun olderApiWithoutMeasurementRetainsUpdatedAtFallback() {
        val row = record().copy(durationMeasuredAtEpochMillis = null)
        assertEquals(3605L, row.elapsedSeconds(snapshot(row), now, mono + 5_000_000_000L))
        val oldApi = snapshot(row).copy(serverTimeEpochMillis = null)
        assertEquals(3605L, row.elapsedSeconds(oldApi, now + 5000L))
    }

    @Test fun excessiveFutureTimestampNeverCountsAsFresh() {
        val data = snapshot().copy(updatedAt = Instant.ofEpochMilli(now + 3_600_000L).toString())
        assertFalse(data.isFresh(now, mono))
        assertEquals(3600L, record().elapsedSeconds(data, now, mono))
    }
}