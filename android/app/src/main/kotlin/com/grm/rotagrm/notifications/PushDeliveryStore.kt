package com.grm.rotagrm.notifications

import android.content.Context
import com.grm.rotagrm.data.SessionIdentity
import java.util.UUID

/** Contains only hashes and random device IDs, never a bearer token or operational fields. */
class PushDeliveryStore(context: Context) {
    private val preferences = context.getSharedPreferences("push_delivery", Context.MODE_PRIVATE)

    fun deviceId(identity: SessionIdentity): String = synchronized(lock) {
        if (preferences.getString("session", null) != identity.sessionTag) {
            preferences.edit().clear().putString("session", identity.sessionTag)
                .putString("device", UUID.randomUUID().toString()).commit()
        }
        preferences.getString("device", null)!!
    }

    fun acknowledge(alertHash: String) = synchronized(lock) {
        preferences.edit().putStringSet("ack", preferences.getStringSet("ack", emptySet())!!.toSet() + alertHash)
            .putLong("ackAt:$alertHash", System.currentTimeMillis()).commit()
    }

    fun lastAt(alertHash: String): Long? = synchronized(lock) {
        preferences.getLong("at:$alertHash", 0L).takeIf { it > 0L }
    }

    fun post(eventHash: String, alertHash: String, repeatMillis: Long?, action: () -> Unit): Boolean = synchronized(lock) {
        prune()
        // Recognition from older APKs is deliberately ignored. Current pointing
        // and repeat interval, not acknowledgment, govern notification lifetime.
        val delivered = preferences.getStringSet("events", emptySet()).orEmpty().toSet()
        val last = lastAt(alertHash)
        if (eventHash in delivered && (repeatMillis == null || (last != null && System.currentTimeMillis() - last < repeatMillis))) {
            return@synchronized false
        }
        try { action() } catch (_: SecurityException) { return@synchronized false }
        preferences.edit().putStringSet("events", delivered + eventHash)
            .putLong("eventAt:$eventHash", System.currentTimeMillis())
            .putLong("at:$alertHash", System.currentTimeMillis()).commit()
        true
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        val all = preferences.all
        val keptEvents = all.entries.filter { it.key.startsWith("eventAt:") && (it.value as? Long ?: 0L) >= cutoff }
            .sortedByDescending { it.value as Long }.take(4095).mapTo(mutableSetOf()) { it.key.removePrefix("eventAt:") }
        val keptAck = all.entries.filter { it.key.startsWith("ackAt:") && (it.value as? Long ?: 0L) >= cutoff }
            .sortedByDescending { it.value as Long }.take(4095).mapTo(mutableSetOf()) { it.key.removePrefix("ackAt:") }
        val keptTimes = all.entries.filter { it.key.startsWith("at:") && (it.value as? Long ?: 0L) >= cutoff }
            .sortedByDescending { it.value as Long }.take(4095).mapTo(mutableSetOf()) { it.key }
        val edit = preferences.edit().putStringSet("events", keptEvents).putStringSet("ack", keptAck)
        all.forEach { (key, value) ->
            if ((key.startsWith("eventAt:") && key.removePrefix("eventAt:") !in keptEvents) ||
                (key.startsWith("ackAt:") && key.removePrefix("ackAt:") !in keptAck) ||
                (key.startsWith("at:") && key !in keptTimes)) edit.remove(key)
        }
        edit.commit()
    }

    fun clear() = synchronized(lock) { preferences.edit().clear().commit(); Unit }

    companion object {
        private val lock = Any()
    }
}