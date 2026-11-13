package com.grm.rotagrm.domain.model

import org.junit.Assert.*
import org.junit.Test

class RefreshPolicyTest {
    @Test fun requestBudgetIsBoundedForFastRecovery() {
        assertEquals(25_000L, RefreshPolicy.REQUEST_BUDGET_MILLIS)
    }
    @Test fun successfulRequestTimeIsNotAddedToTheUserInterval() {
        assertEquals(42_000L, RefreshPolicy.delayMillis(60, 18_000L, true, 0))
        assertEquals(1_000L, RefreshPolicy.delayMillis(60, 75_000L, true, 0))
    }
    @Test fun staleOrFailedResponseRetriesSoonEvenWithLongUserInterval() {
        assertEquals(4_000L, RefreshPolicy.delayMillis(3600, 1000, false, 1))
        assertEquals(9_000L, RefreshPolicy.delayMillis(3600, 1000, false, 2))
    }
    @Test fun recoveryBackoffIsCappedAndNeverBecomesABusyLoop() {
        assertEquals(29_000L, RefreshPolicy.delayMillis(60, 1000, false, 10))
        assertEquals(1_000L, RefreshPolicy.delayMillis(60, 25_000L, false, 1))
        assertEquals(1_000L, RefreshPolicy.delayMillis(60, Long.MAX_VALUE, false, 99))
    }
    @Test fun freshResponseResumesConfiguredCadence() {
        assertEquals(3_599_000L, RefreshPolicy.delayMillis(3600, 1000, true, 99))
        assertEquals(29_000L, RefreshPolicy.delayMillis(30, 1000, true, 0))
    }
    @Test fun invalidInputsAreClampedWithoutNegativeDelay() {
        assertEquals(30_000L, RefreshPolicy.delayMillis(-1, -1, true, 0))
        assertEquals(5_000L, RefreshPolicy.delayMillis(60, -1, false, -1))
    }
}