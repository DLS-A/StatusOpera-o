package com.grm.rotagrm.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.data.EncryptedOperationalStore
import com.grm.rotagrm.data.HttpOperationalRepository
import com.grm.rotagrm.data.SessionAccessException
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.FCM_ENABLED) return Result.success()
        val store = EncryptedOperationalStore(applicationContext)
        val previous = store.identity() ?: return Result.success()
        val auth = OidcAuthManager.getInstance(applicationContext)
        try {
            if (!auth.hasStoredSession) return Result.success()
            val repository = HttpOperationalRepository(auth)
            val verified = repository.session()
            if (verified != previous || store.identity() != previous) return Result.success()
            val device = PushDeliveryStore(applicationContext).deviceId(verified)
            if (!OperationalNotifications.hasPermission(applicationContext)) {
                repository.removeDevice(device)
                return Result.success()
            }
            val token = suspendCancellableCoroutine<String> { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM indisponível."))
                }
            }
            if (store.identity() != verified) return Result.success()
            repository.registerDevice(device, token, MonitoringSettingsRepository(applicationContext).settings.first())
            return Result.success()
        } catch (error: CancellationException) { throw error
        } catch (_: SessionAccessException) {
            if (store.identity() == previous) {
                store.clear(); auth.signOut(); OperationalNotifications.cancelAll(applicationContext)
                PushDeliveryStore(applicationContext).clear()
            }
            return Result.failure()
        } catch (_: Exception) { return if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }

    companion object {
        private const val NAME = "rota-push-registration"
        fun enqueue(context: Context) {
            if (!BuildConfig.FCM_ENABLED) return
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}