package com.grm.rotagrm.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.data.EncryptedOperationalStore
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
        val identity = EncryptedOperationalStore(this).identity() ?: return
        val auth = OidcAuthManager.getInstance(this)
        // A generic push can arrive after the access JWT expires: the server
        // checks the still-active refresh session immediately before sending.
        // Offline operational data, unlike this generic notice, requires a fresh JWT.
        if (!auth.canReadOffline(identity, requireFreshAccessToken = false) || message.data["session_tag"] != identity.sessionTag) return
        val event = message.data["event_id"] ?: return
        val alert = message.data["alert_id"] ?: return
        if (!event.matches(Regex("[a-f0-9]{64}")) || !alert.matches(Regex("[a-f0-9]{64}"))) return
        val settings = runBlocking { MonitoringSettingsRepository(this@RotaFirebaseMessagingService).settings.first() }
        if (!settings.alertVisualEnabled) return
        PushDeliveryStore(this).deviceId(identity)
        OperationalNotifications.postGeneric(this, event, alert,
            settings.alertSoundEnabled && message.data["sound"] == "true")
    }
}