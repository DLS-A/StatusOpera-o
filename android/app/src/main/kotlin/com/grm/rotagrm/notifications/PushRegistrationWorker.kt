package com.grm.rotagrm.notifications

import android.content.Context
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.data.EncryptedOperationalStore
import com.grm.rotagrm.data.HttpOperationalRepository
import com.grm.rotagrm.data.SessionAccessException
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No OIDC/session login: a Keystore-protected install secret and FCM proof. */
class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.FCM_ENABLED) return Result.success()
        val store = EncryptedOperationalStore(applicationContext)
        val install = store.pushCredentials()
        val device = install.getString("deviceId")
        val secret = install.getString("secret")
        val repository = HttpOperationalRepository()
        try {
            if (!OperationalNotifications.hasPermission(applicationContext) || !OperationalMonitoringService.enabled(applicationContext)) {
                if (install.optBoolean("registered")) repository.disablePush(device, secret)
                store.updatePushCredentials { it.put("registered", false) }
                status.value = "Push pausado ou sem permissão de notificações."
                return Result.success()
            }
            val challenge = install.optString("challenge")
            if (challenge.isNotBlank()) {
                try {
                    repository.confirmPush(device, secret, challenge)
                    store.updatePushCredentials { it.remove("challenge"); it.put("registered", true) }
                } catch (_: SessionAccessException) {
                    store.updatePushCredentials { it.remove("challenge") }
                }
            }
            val token = suspendCancellableCoroutine<String> { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM indisponível."))
                }
            }
            val registered = repository.enrollPush(device, secret, token, MonitoringSettingsRepository(applicationContext).settings.first())
            if (registered) {
                store.updatePushCredentials { it.put("registered", true) }
                status.value = "Push cadastrado · recebe avisos com a tela do app fechada."
                return Result.success()
            }
            status.value = "Push aguardando confirmação recebida neste aparelho."
            return Result.retry()
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            status.value = "Push aguardando conexão/cadastro; monitor local continua disponível."
            return Result.retry()
        }
    }

    companion object {
        private const val NAME = "rota-push-registration"
        private val status = kotlinx.coroutines.flow.MutableStateFlow("Push ainda não cadastrado neste aparelho.")
        val registrationStatus = status.asStateFlow()
        fun enqueue(context: Context) {
            if (!BuildConfig.FCM_ENABLED) return
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}