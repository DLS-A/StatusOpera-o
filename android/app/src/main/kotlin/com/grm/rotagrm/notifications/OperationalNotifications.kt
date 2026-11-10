package com.grm.rotagrm.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import com.grm.rotagrm.MainActivity
import com.grm.rotagrm.domain.model.OperationalAlert
import com.grm.rotagrm.settings.alertStorageId

object OperationalNotifications {
    private const val CHANNEL_SOUND = "operational_alerts_sound"
    private const val CHANNEL_SILENT = "operational_alerts_silent"

    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SOUND, "Alertas operacionais", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos quando um apontamento ultrapassa o limite configurado."
                    enableVibration(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "Alertas operacionais silenciosos", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos visuais sem som."
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    fun openSoundSettings(context: Context) {
        createChannels(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_SOUND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun testSound(context: Context): Boolean = postGeneric(context,
        alertStorageId("sound-test:${System.nanoTime()}"), alertStorageId("sound-test"), true, 0L)

    fun post(context: Context, alert: OperationalAlert, soundEnabled: Boolean, repeatMillis: Long? = null): Boolean {
        val eventKey = if (alert.maintenanceEvent) "maintenance:${alert.id.removePrefix("maintenance:")}" else "limit:${alert.id}:${alert.limitSeconds}"
        return postGeneric(context, alertStorageId(eventKey), alertStorageId(alert.id), soundEnabled, repeatMillis)
    }

    fun postGeneric(context: Context, eventHash: String, alertHash: String, soundEnabled: Boolean, repeatMillis: Long? = null): Boolean {
        if (!hasPermission(context)) return false
        createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (manager.getNotificationChannel(if (soundEnabled) CHANNEL_SOUND else CHANNEL_SILENT)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return PushDeliveryStore(context).post(eventHash, alertHash, repeatMillis) {
        val openApp = PendingIntent.getActivity(
            context,
            alertHash.take(8).toLong(16).toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, if (soundEnabled) CHANNEL_SOUND else CHANNEL_SILENT)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Status Operacional — aviso")
            .setContentText("Há uma atualização de monitoramento. Abra o aplicativo para consultar.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(alertHash.take(8).toLong(16).toInt(), notification)
        }
    }

    fun cancelAll(context: Context) { context.getSystemService(NotificationManager::class.java).cancelAll() }

    fun cancel(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    fun cancelByStorageId(context: Context, storageId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(storageId.take(8).toLong(16).toInt())
    }

    private fun notificationId(id: String): Int = alertStorageId(id).take(8).toLong(16).toInt()

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return "${minutes}m ${remaining}s"
    }
}
