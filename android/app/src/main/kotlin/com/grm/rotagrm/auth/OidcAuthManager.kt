package com.grm.rotagrm.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.grm.rotagrm.data.SessionIdentity
import org.json.JSONObject
import com.grm.rotagrm.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class OidcAuthManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val authService = AuthorizationService(appContext)
    private val secureStore = SecureAuthStateStore(appContext)
    private val stateMutex = Mutex()
    private var authState = secureStore.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState()
    private val _sessionAvailable = MutableStateFlow(authState.isAuthorized)
    val sessionAvailable = _sessionAvailable.asStateFlow()

    val isConfigured: Boolean
        get() = BuildConfig.OIDC_ISSUER.startsWith("https://") &&
            BuildConfig.OIDC_CLIENT_ID.isNotBlank() &&
            BuildConfig.OIDC_AUDIENCE.isNotBlank() &&
            isSafeHttpsUrl(BuildConfig.API_BASE_URL) && isSafeHttpsUrl(BuildConfig.OIDC_ISSUER)

    val hasStoredSession: Boolean
        get() = authState.isAuthorized

    /** Only matches a previously server-verified, encrypted offline cache.
     * This is NOT token signature validation and never grants backend access.
     */
    fun canReadOffline(identity: SessionIdentity, requireFreshAccessToken: Boolean = true): Boolean = runCatching {
        val token = authState.accessToken ?: return@runCatching false
        val payload = JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        authState.isAuthorized && payload.getString("sub") == identity.userId &&
            payload.getString("session_id") == identity.sessionId &&
            (!requireFreshAccessToken || payload.getLong("exp") * 1000L > System.currentTimeMillis())
    }.getOrDefault(false)

    suspend fun authorizationIntent(): Intent {
        check(isConfigured) { "Aplicativo não configurado pelo administrador." }
        val issuer = Uri.parse(BuildConfig.OIDC_ISSUER)
        val configuration = suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(issuer) { result, exception ->
                if (!continuation.isActive) return@fetchFromIssuer
                if (exception != null) continuation.resumeWithException(exception)
                else if (result == null) continuation.resumeWithException(IllegalStateException("Descoberta OIDC vazia."))
                else continuation.resume(result)
            }
        }
        check(isSafeHttpsUrl(configuration.authorizationEndpoint.toString()) &&
            isSafeHttpsUrl(configuration.tokenEndpoint.toString())) { "Provedor de login inválido." }

        val request = AuthorizationRequest.Builder(
            configuration,
            BuildConfig.OIDC_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse("${BuildConfig.APPLICATION_ID}:/oauth2redirect"),
        )
            .setScope("openid email profile")
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    suspend fun completeAuthorization(resultIntent: Intent) {
      withContext(NonCancellable) { stateMutex.withLock {
        val response = AuthorizationResponse.fromIntent(resultIntent)
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (response == null) throw exception ?: IllegalStateException("Login OIDC cancelado ou sem resposta.")
        check(response.state != null && response.state == response.request.state) { "Resposta de login inválida." }
        authState.update(response, exception)

        suspendCoroutine<Unit> { continuation ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
                authState.update(tokenResponse, tokenException)
                _sessionAvailable.value = authState.isAuthorized
                if (tokenException != null) continuation.resumeWithException(tokenException)
                else if (tokenResponse?.accessToken.isNullOrBlank()) continuation.resumeWithException(
                    IllegalStateException("Login sem token de acesso."),
                )
                else {
                    runCatching { secureStore.write(authState.jsonSerializeString()) }
                        .onSuccess { continuation.resume(Unit) }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }
        }
      } }
      currentCoroutineContext().ensureActive()
    }

    suspend fun validAccessToken(): String {
      // Refresh tokens can rotate. Do not release the mutex or discard the new
      // token when the caller is cancelled while AppAuth's callback is pending.
      val token = withContext(NonCancellable) { stateMutex.withLock {
        suspendCoroutine<String> { continuation ->
            authState.performActionWithFreshTokens(authService) { accessToken, _, exception ->
                _sessionAvailable.value = authState.isAuthorized
                if (exception != null) continuation.resumeWithException(exception)
                else if (accessToken.isNullOrBlank()) continuation.resumeWithException(
                    IllegalStateException("Sessão expirada; entre novamente."),
                ) else {
                    runCatching { secureStore.write(authState.jsonSerializeString()) }
                        .onSuccess { continuation.resume(accessToken) }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }
        }
      } }
      currentCoroutineContext().ensureActive()
      return token
    }

    suspend fun signOut() = stateMutex.withLock {
        authState = AuthState()
        secureStore.clear()
        _sessionAvailable.value = false
    }

    fun dispose() = authService.dispose()

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null
    }.getOrDefault(false)

    companion object {
        @Volatile private var instance: OidcAuthManager? = null
        fun getInstance(context: Context): OidcAuthManager = instance ?: synchronized(this) {
            instance ?: OidcAuthManager(context.applicationContext).also { instance = it }
        }
    }
}
