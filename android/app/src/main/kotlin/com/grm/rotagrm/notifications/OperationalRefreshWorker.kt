package com.grm.rotagrm.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grm.rotagrm.OperationalApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Delayed fallback, NOT a substitute for minute-level continuous monitoring.
 * WorkManager owns reboot/process survival and scheduling; never exact alarms. */
class OperationalRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!OperationalMonitoringService.enabled(applicationContext)) return Result.success()
        if (OperationalMonitoringService.isRunning.value) return Result.success()
        return withContext(Dispatchers.Main.immediate) {
            val monitor = (applicationContext as OperationalApplication).monitor
            monitor.state.first { !it.loading }
            monitor.refresh()
            val state = monitor.state.value
            // HTTP 200 with an old GRM snapshot is not a successful refresh.
            if (state.apiAvailable && state.snapshot?.isFresh(System.currentTimeMillis()) == true) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val NAME = "operational-background-refresh"
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<OperationalRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}