package com.grm.rotagrm.data

import android.net.Uri
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.domain.model.EquipmentStatus
import com.grm.rotagrm.domain.model.OperationalRecord
import com.grm.rotagrm.domain.model.OperationalSnapshot
import com.grm.rotagrm.domain.repository.OperationalRepository
import com.grm.rotagrm.settings.MonitoringSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SessionAccessException(val denied: Boolean = false) : IOException("Sessão expirada ou sem autorização.")
class TemporaryServiceException : IOException("Serviço temporariamente indisponível.")

class HttpOperationalRepository(private val unusedLegacyAuth: OidcAuthManager? = null) : OperationalRepository {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun fetchSnapshot(): OperationalSnapshot {
        for (attempt in 0..2) {
            try { return decodeSnapshot(request("/v1/snapshot"))
            } catch (error: SessionAccessException) { throw error
            } catch (error: TemporaryServiceException) {
                if (attempt == 2) throw error
            } catch (error: IOException) {
                if (attempt == 2) throw error
            }
            delay(500L * (1L shl attempt))
        }
        error("Consulta não concluída.")
    }

    suspend fun session(): SessionIdentity {
        val json = JSONObject(request("/v1/session"))
        val user = json.getString("userId")
        val session = json.getString("sessionId")
        UUID.fromString(user); UUID.fromString(session)
        return SessionIdentity(user, session)
    }

    suspend fun logout(): Boolean {
        val json = JSONObject(request("/v1/session/logout", "POST", "{}"))
        return json.optBoolean("serverRevoked") && json.optBoolean("providerRevoked")
    }

    suspend fun registerDevice(deviceId: String, token: String, settings: MonitoringSettings) {
        val preferences = JSONObject().put("limitsMinutesByStatus", JSONObject(settings.limitsMinutesByStatus))
            .put("alertsEnabledByStatus", JSONObject(settings.alertsEnabledByStatus))
            .put("soundEnabled", settings.alertSoundEnabled).put("visualEnabled", settings.alertVisualEnabled)
        request("/v1/devices/$deviceId", "PUT", JSONObject().put("token", token).put("preferences", preferences).toString())
    }

    suspend fun removeDevice(deviceId: String) { request("/v1/devices/$deviceId", "DELETE") }

    private suspend fun request(path: String, method: String = "GET", body: String? = null): String {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val uri = Uri.parse(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        val request = Request.Builder().url(base + path)
            .header("Accept", "application/json").method(method, body?.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(TemporaryServiceException())
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.code == 401 || response.code == 403) throw SessionAccessException(response.code == 403)
                            if (response.code in listOf(408, 429, 500, 502, 503, 504)) throw TemporaryServiceException()
                            check(response.isSuccessful) { "Resposta HTTP ${response.code}." }
                            val source = response.body?.source() ?: error("Resposta vazia.")
                            val buffer = okio.Buffer()
                            while (buffer.size <= MAX_BYTES && source.read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size)) != -1L) { }
                            check(buffer.size <= MAX_BYTES) { "Resposta excede o limite." }
                            if (continuation.isActive) continuation.resume(buffer.readUtf8())
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
    }

    companion object {
        private const val MAX_BYTES = 10L * 1024L * 1024L

        fun decodeSnapshot(body: String): OperationalSnapshot {
            val root = JSONObject(body)
            val updatedAt = root.getString("updatedAt")
            Instant.parse(updatedAt)
            fun records(name: String) = root.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it).toRecord() } }
            val equipment = root.getJSONArray("equipment").let { a -> (0 until a.length()).map {
                val item = a.getJSONObject(it)
                val tag = item.getString("equipmentTag")
                require(tag.isNotBlank())
                EquipmentStatus(tag, if (item.isNull("current")) null else item.getJSONObject("current").toRecord(),
                    item.optString("operationalState", "unknown"))
            } }
            return OperationalSnapshot(updatedAt, equipment, records("pauses"), records("maintenance"), records("maintenanceAlerts"),
                stale = root.optBoolean("stale", false), sourceAvailable = root.optBoolean("sourceAvailable", true),
                persistenceAvailable = root.optBoolean("persistenceAvailable", true),
                lastAttemptAt = root.optString("lastAttemptAt").takeIf { it.isNotBlank() && it != "null" },
                refreshIntervalSeconds = root.optInt("refreshIntervalSeconds", 60).coerceIn(30, 3600), cacheJson = body)
        }

        private fun JSONObject.toRecord(): OperationalRecord {
            val id = getString("id"); require(id.isNotBlank())
            return OperationalRecord(id, getString("equipmentTag"), getString("reasonName"),
                if (isNull("startEpochMillis")) null else getLong("startEpochMillis"),
                getLong("durationSeconds").coerceAtLeast(0L), optString("operator"), optString("location"),
                optString("equipmentType"), optString("kpiCategory"), durationFromReport = true,
                startConfirmed = optBoolean("startConfirmed", false))
        }
    }
}