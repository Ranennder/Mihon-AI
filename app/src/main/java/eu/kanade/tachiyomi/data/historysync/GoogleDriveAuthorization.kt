package eu.kanade.tachiyomi.data.historysync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Authorization uses the Android package/signing-certificate registration in
 * our Cloud project. No server client secret or refresh token belongs in the APK. */
class GoogleDriveAuthorization(context: Context) {
    private val context = context.applicationContext
    private val client by lazy { Identity.getAuthorizationClient(this.context) }

    fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    suspend fun begin(): AuthorizationResult {
        check(isConfigured && isAvailable()) { "Google authorization is unavailable" }
        return client.authorize(
            AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
                .setOptOutIncludingGrantedScopes(true)
                .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT).build(),
        ).awaitResult()
    }

    fun finish(data: Intent): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    suspend fun verify(result: AuthorizationResult): HistorySyncCredentials {
        var verifiedToken: String? = grantedToken(result)
        val account = GoogleDriveHistorySyncTransport.account(
            object : GoogleDriveTokenSource {
                override suspend fun accessToken(): String {
                    verifiedToken?.let { return it }
                    val renewed = client.authorize(
                        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
                            .setOptOutIncludingGrantedScopes(true).build(),
                    ).awaitResult()
                    return grantedToken(renewed).also { verifiedToken = it }
                }

                override suspend fun invalidate(token: String) {
                    verifiedToken = null
                    client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult()
                }
            },
        )
        return HistorySyncCredentials(
            provider = HistorySyncProvider.GOOGLE_DRIVE,
            googleProjectNumber = BuildConfig.GOOGLE_DRIVE_PROJECT_NUMBER,
            googlePermissionId = account.permissionId,
            googleEmail = account.emailAddress,
            googleDisplayName = account.displayName,
        )
    }

    fun tokensFor(account: HistorySyncCredentials): GoogleDriveTokenSource {
        require(account.provider == HistorySyncProvider.GOOGLE_DRIVE)
        return GoogleDriveSessionTokens(
            account = account,
            authorize = {
                if (!isConfigured || !isAvailable() ||
                    account.googleProjectNumber != BuildConfig.GOOGLE_DRIVE_PROJECT_NUMBER
                ) {
                    throw GoogleDriveReconnectRequired()
                }
                val result = try {
                    client.authorize(
                        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
                            .setOptOutIncludingGrantedScopes(true)
                            .setAccount(Account(account.googleEmail, "com.google")).build(),
                    ).awaitResult()
                } catch (error: ApiException) {
                    throw GoogleDriveReconnectRequired()
                }
                // Only the explicit sign-in button launches a PendingIntent.
                // Foreground polling and WorkManager never display account UI.
                if (result.hasResolution()) throw GoogleDriveReconnectRequired()
                grantedToken(result)
            },
            clear = { client.clearToken(ClearTokenRequest.builder().setToken(it).build()).awaitResult() },
        )
    }

    private fun grantedToken(result: AuthorizationResult): String {
        if (result.hasResolution() || SCOPE !in result.grantedScopes) throw GoogleDriveReconnectRequired()
        return result.accessToken?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: throw GoogleDriveReconnectRequired()
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        val isConfigured: Boolean
            get() = BuildConfig.GOOGLE_DRIVE_PROJECT_NUMBER.isNotBlank() &&
                BuildConfig.GOOGLE_DRIVE_PROJECT_ID.isNotBlank() &&
                BuildConfig.GOOGLE_DRIVE_ANDROID_CLIENT_ID.isNotBlank()
    }
}

/** A token is accepted only after Drive confirms the same immutable account ID.
 * This also protects against an account chooser/default account change. */
internal class GoogleDriveSessionTokens(
    private val account: HistorySyncCredentials,
    private val authorize: suspend () -> String,
    private val clear: suspend (String) -> Unit,
    private val identity: suspend (GoogleDriveTokenSource) -> GoogleDriveAccount = {
        GoogleDriveHistorySyncTransport.account(it)
    },
) : GoogleDriveTokenSource {
    private var token: String? = null

    override suspend fun accessToken(): String {
        token?.let { return it }
        var candidate: String? = authorize()
        val user = identity(
            object : GoogleDriveTokenSource {
                override suspend fun accessToken(): String = candidate ?: authorize().also { candidate = it }

                override suspend fun invalidate(token: String) {
                    clear(token)
                    candidate = null
                }
            },
        )
        if (user.permissionId != account.googlePermissionId) throw GoogleDriveReconnectRequired()
        return checkNotNull(candidate).also { token = it }
    }

    override suspend fun invalidate(token: String) {
        if (this.token == token) this.token = null
        clear(token)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Google authorization cancelled")) }
}
