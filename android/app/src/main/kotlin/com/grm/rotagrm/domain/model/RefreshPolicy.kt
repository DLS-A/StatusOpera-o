package com.grm.rotagrm.domain.model

/** Elapsed request time belongs to the cadence; recovery never waits a full user interval.
 * One failed-source retry cadence per phone, not parallel upstream GRM queries. */
object RefreshPolicy {
    const val REQUEST_BUDGET_MILLIS = 25_000L
    fun delayMillis(intervalSeconds: Int, elapsedMillis: Long, fresh: Boolean, failures: Int): Long {
        val target = if (fresh) intervalSeconds.coerceIn(30, 3600) * 1000L
            else (5_000L * (1L shl (failures - 1).coerceIn(0, 3))).coerceAtMost(30_000L)
        return (target - elapsedMillis.coerceAtLeast(0L)).coerceAtLeast(1_000L)
    }
}