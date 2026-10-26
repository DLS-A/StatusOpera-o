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

    fun post(context: Context, alert: OperationalAlert, soundEnabled: Boolean): Boolean {
        if (!hasPermission(context)) return false
        createChannels(context)
        val openApp = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
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
            .setContentTitle(if (alert.maintenanceEvent) "Nova manutenção: ${alert.equipmentTag}" else "Limite excedido: ${alert.equipmentTag}")
            .setContentText(
                if (alert.maintenanceEvent) alert.status
                else "${alert.status}: excedido em ${formatDuration(alert.exceededSeconds)}",
            )
            .setStyle(Notification.BigTextStyle().bigText(
                if (alert.maintenanceEvent) "Equipamento ${alert.equipmentTag}\nNova manutenção: ${alert.status}"
                else "Equipamento ${alert.equipmentTag}\n${alert.status}\nTempo excedido: ${formatDuration(alert.exceededSeconds)}",
            ))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(alert.id), notification)
        return true
    }

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
