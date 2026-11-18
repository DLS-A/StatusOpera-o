package com.grm.rotagrm.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.data.EncryptedOperationalStore
import com.grm.rotagrm.settings.alertStorageId
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RotaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Do not put the token in logs, intents or WorkManager input data.
        PushRegistrationWorker.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.FCM_ENABLED) return
        val store = EncryptedOperationalStore(this)
        val install = store.pushCredentials()
        if (message.data["kind"] == "enrollment") {
            if (message.data["device_id"] != install.getString("deviceId")) return
            val challenge = message.data["challenge"] ?: return
            if (!challenge.matches(Regex("[A-Za-z0-9_-]{40,100}"))) return
            store.updatePushCredentials { it.put("challenge", challenge) }
            PushRegistrationWorker.enqueue(this)
            return
        }
        if (!OperationalMonitoringService.enabled(this) || !install.optBoolean("registered") ||
            message.data["kind"] != "operational" || message.data["device_tag"] != alertStorageId(install.getString("deviceId"))) return
        val event = message.data["event_id"] ?: return
        val alert = message.data["alert_id"] ?: return
        if (!event.matches(Regex("[a-f0-9]{64}")) || !alert.matches(Regex("[a-f0-9]{64}"))) return
        val settings = runBlocking { MonitoringSettingsRepository(this@RotaFirebaseMessagingService).settings.first() }
        if (!settings.alertVisualEnabled) return
        OperationalNotifications.postGeneric(this, event, alert,
            settings.alertSoundEnabled && message.data["sound"] == "true",
            if (message.data["repeat"] == "true") settings.repeatAlertMinutes * 60_000L else null)
    }
}