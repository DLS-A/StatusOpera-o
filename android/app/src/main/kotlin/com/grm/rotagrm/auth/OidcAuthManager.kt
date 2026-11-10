package com.grm.rotagrm.auth

import android.content.Context
import android.net.Uri
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.data.SessionAccessException
import com.grm.rotagrm.data.SessionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Compatibility class name; this build uses phone activation, not interactive OAuth.
 * The shared code is sent once over HTTPS and never stored on the phone.
 * The individual credential is AES-GCM encrypted with Android Keystore.
 */
class OidcAuthManager private constructor(context: Context) {
    private val secureStore = SecureAuthStateStore(context.applicationContext)
    private val mutex = Mutex()
    private var state = secureStore.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.optString("mode") == "device_activation" }
    private val _sessionAvailable = MutableStateFlow(hasStoredSession)
    val sessionAvailable = _sessionAvailable.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    val isConfigured: Boolean
        get() = runCatching {
            val uri = Uri.parse(BuildConfig.API_BASE_URL)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false)
    val hasStoredSession: Boolean
        get() = state?.optString("accessToken")?.matches(Regex("soa_[A-Za-z0-9_-]{43}")) == true

    suspend fun activate(code: String) = mutex.withLock {
        check(isConfigured) { "Servidor não configurado." }
        val result = withContext(Dispatchers.IO) {
            val body = JSONObject().put("code", code.trim().uppercase()).toString()
            val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/activation")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (response.code == 403) throw IllegalArgumentException("Código de ativação inválido.")
                if (response.code == 429) throw IllegalArgumentException("Muitas tentativas. Aguarde quinze minutos.")
                check(response.isSuccessful) { "Ativação indisponível. Tente novamente." }
                val source = response.body?.source() ?: error("Resposta vazia.")
                check(!source.request(8193)) { "Resposta inválida." }
                JSONObject(source.readUtf8())
            }
        }
        val device = result.getString("deviceId")
        UUID.fromString(device)
        val token = result.getString("accessToken")
        check(token.matches(Regex("soa_[A-Za-z0-9_-]{43}")))
        val saved = JSONObject().put("mode", "device_activation").put("deviceId", device)
            .put("accessToken", token).put("verifiedAt", System.currentTimeMillis())
        secureStore.write(saved.toString())
        state = saved
        _sessionAvailable.value = true
    }

    fun canReadOffline(identity: SessionIdentity, requireFreshAccessToken: Boolean = true): Boolean {
        val stored = state ?: return false
        val age = System.currentTimeMillis() - stored.optLong("verifiedAt", 0)
        return hasStoredSession && stored.optString("deviceId") == identity.userId &&
            identity.userId == identity.sessionId && age in 0..86_400_000L
    }

    suspend fun markVerified() = mutex.withLock {
        state?.let {
            it.put("verifiedAt", System.currentTimeMillis())
            secureStore.write(it.toString())
        }
    }

    suspend fun validAccessToken(): String = state?.optString("accessToken")
        ?.takeIf { hasStoredSession } ?: throw SessionAccessException()

    suspend fun signOut() = mutex.withLock {
        secureStore.clear()
        state = null
        _sessionAvailable.value = false
    }

    companion object {
        @Volatile private var instance: OidcAuthManager? = null
        fun getInstance(context: Context): OidcAuthManager = instance ?: synchronized(this) {
            instance ?: OidcAuthManager(context.applicationContext).also { instance = it }
        }
    }
}