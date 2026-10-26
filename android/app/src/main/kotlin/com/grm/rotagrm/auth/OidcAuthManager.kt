package com.grm.rotagrm.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.grm.rotagrm.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OidcAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val authService = AuthorizationService(appContext)
    private val secureStore = SecureAuthStateStore(appContext)
    private val stateMutex = Mutex()
    private var authState = secureStore.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState()

    val isConfigured: Boolean
        get() = BuildConfig.OIDC_ISSUER.startsWith("https://") &&
            BuildConfig.OIDC_CLIENT_ID.isNotBlank() &&
            BuildConfig.OIDC_AUDIENCE.isNotBlank() &&
            isSafeHttpsUrl(BuildConfig.API_BASE_URL) && isSafeHttpsUrl(BuildConfig.OIDC_ISSUER)

    val hasStoredSession: Boolean
        get() = authState.isAuthorized

    suspend fun authorizationIntent(): Intent {
        check(isConfigured) { "Configure URL do backend, issuer OIDC e client ID público." }
        val issuer = Uri.parse(BuildConfig.OIDC_ISSUER)
        val configuration = suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(issuer) { result, exception ->
                if (exception != null) continuation.resumeWithException(exception)
                else if (result == null) continuation.resumeWithException(IllegalStateException("Descoberta OIDC vazia."))
                else continuation.resume(result)
            }
        }

        val request = AuthorizationRequest.Builder(
            configuration,
            BuildConfig.OIDC_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse("${BuildConfig.APPLICATION_ID}:/oauth2redirect"),
        )
            .setScope("openid email profile")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    suspend fun completeAuthorization(resultIntent: Intent) = stateMutex.withLock {
        val response = AuthorizationResponse.fromIntent(resultIntent)
        val exception = AuthorizationException.fromIntent(resultIntent)
        authState.update(response, exception)
        if (response == null) throw exception ?: IllegalStateException("Login OIDC cancelado ou sem resposta.")

        suspendCancellableCoroutine<Unit> { continuation ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenException ->
                authState.update(tokenResponse, tokenException)
                if (tokenException != null) continuation.resumeWithException(tokenException)
                else {
                    secureStore.write(authState.jsonSerializeString())
                    continuation.resume(Unit)
                }
            }
        }
    }

    suspend fun validAccessToken(): String = stateMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            authState.performActionWithFreshTokens(authService) { accessToken, _, exception ->
                if (exception != null) continuation.resumeWithException(exception)
                else if (accessToken.isNullOrBlank()) continuation.resumeWithException(
                    IllegalStateException("Sessão expirada; entre novamente."),
                ) else {
                    secureStore.write(authState.jsonSerializeString())
                    continuation.resume(accessToken)
                }
            }
        }
    }

    suspend fun signOut() = stateMutex.withLock {
        authState = AuthState()
        secureStore.clear()
    }

    fun dispose() = authService.dispose()

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null
    }.getOrDefault(false)
}
