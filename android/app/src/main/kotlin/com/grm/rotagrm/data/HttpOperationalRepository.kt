package com.grm.rotagrm.data

import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.domain.model.EquipmentStatus
import com.grm.rotagrm.domain.model.OperationalRecord
import com.grm.rotagrm.domain.model.OperationalSnapshot
import com.grm.rotagrm.domain.repository.OperationalRepository
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpOperationalRepository(private val auth: OidcAuthManager) : OperationalRepository {
    private companion object {
        const val MAX_SNAPSHOT_BYTES = 10L * 1024L * 1024L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun fetchSnapshot(): OperationalSnapshot = withContext(Dispatchers.IO) {
        val configuredBase = BuildConfig.API_BASE_URL.trim()
        val baseUri = Uri.parse(configuredBase)
        require(
            baseUri.scheme == "https" && !baseUri.host.isNullOrBlank() &&
                baseUri.userInfo == null && baseUri.query == null && baseUri.fragment == null,
        ) { "URL do backend deve ser HTTPS e nao pode conter credenciais ou parametros." }
        val base = configuredBase.trimEnd('/')
        val token = auth.validAccessToken()
        val request = Request.Builder()
            .url("$base/v1/snapshot")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(
                when (response.code) {
                    401, 403 -> "Acesso expirado ou sem autorização. Entre novamente."
                    503 -> "O serviço GRM está temporariamente indisponível."
                    else -> "Não foi possível atualizar os dados (HTTP ${response.code})."
                },
            )
            val responseBody = response.body ?: error("Resposta vazia do servidor.")
            require(responseBody.contentLength() <= MAX_SNAPSHOT_BYTES || responseBody.contentLength() == -1L) {
                "Resposta do servidor excede o limite permitido."
            }
            val buffer = okio.Buffer()
            val source = responseBody.source()
            while (buffer.size <= MAX_SNAPSHOT_BYTES && source.read(
                    buffer,
                    minOf(8192L, MAX_SNAPSHOT_BYTES + 1 - buffer.size),
                ) != -1L
            ) { }
            require(buffer.size <= MAX_SNAPSHOT_BYTES) { "Resposta do servidor excede o limite permitido." }
            val bytes = buffer.readByteArray()
            val body = bytes.toString(Charsets.UTF_8)
            body.toSnapshot()
        }
    }

    private fun String.toSnapshot(): OperationalSnapshot {
        val root = JSONObject(this)
        val updatedAt = root.optString("updatedAt")
        require(updatedAt.isNotBlank()) { "Resposta inválida do servidor." }
        fun requiredArray(name: String) = root.optJSONArray(name)
            ?: throw IllegalStateException("Resposta inválida do servidor.")

        return OperationalSnapshot(
            updatedAt = updatedAt,
            equipment = requiredArray("equipment").objects().map { item ->
                val tag = item.optString("equipmentTag")
                require(tag.isNotBlank()) { "Resposta inválida do servidor." }
                val current = if (item.isNull("current")) null else item.optJSONObject("current")
                    ?: throw IllegalStateException("Resposta inválida do servidor.")
                EquipmentStatus(tag, current?.toRecord())
            },
            pauses = requiredArray("pauses").objects().map { it.toRecord() },
            maintenance = requiredArray("maintenance").objects().map { it.toRecord() },
            maintenanceAlerts = requiredArray("maintenanceAlerts").objects().map { it.toRecord() },
        )
    }

    private fun JSONObject.toRecord() = OperationalRecord(
        id = optString("id"),
        equipmentTag = optString("equipmentTag"),
        reasonName = optString("reasonName"),
        startEpochMillis = if (isNull("startEpochMillis")) null else optLong("startEpochMillis"),
        durationSeconds = optLong("durationSeconds"),
        operator = optString("operator"),
        location = optString("location"),
        equipmentType = optString("equipmentType"),
        kpiCategory = optString("kpiCategory"),
    )

    private fun org.json.JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
