package com.storyreader.data.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class GoogleDriveAccount(
    val email: String?,
    val displayName: String?
)

sealed interface GoogleDriveAuthorizationOutcome {
    data class Authorized(
        val accessToken: String,
        val account: GoogleDriveAccount?
    ) : GoogleDriveAuthorizationOutcome

    data class NeedsResolution(
        val intentSender: IntentSender
    ) : GoogleDriveAuthorizationOutcome
}

class GoogleDriveAuthManager(
    context: Context,
    private val credentialsManager: GoogleDriveCredentialsManager
) {

    private val authorizationClient = Identity.getAuthorizationClient(context)

    suspend fun authorize(): Result<GoogleDriveAuthorizationOutcome> {
        return runCatching {
            val result = authorizationClient
                .authorize(
                    AuthorizationRequest.Builder()
                        .setRequestedScopes(GOOGLE_DRIVE_SCOPES.map(::Scope))
                        .build()
                )
                .await()
            result.toOutcome().also { outcome ->
                if (outcome is GoogleDriveAuthorizationOutcome.Authorized) {
                    persistAccount(outcome.account)
                }
            }
        }
    }

    fun consumeAuthorizationResult(data: Intent?): Result<GoogleDriveAuthorizationOutcome.Authorized> {
        return runCatching {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            val outcome = result.toOutcome()
            require(outcome is GoogleDriveAuthorizationOutcome.Authorized) {
                "Google Drive authorization did not return an access token"
            }
            persistAccount(outcome.account)
            outcome
        }
    }

    suspend fun revokeAccess(): Result<Unit> {
        return runCatching {
            if (!credentialsManager.hasAccount) {
                throw IllegalStateException("No Google Drive account is connected")
            }
            val request = RevokeAccessRequest.builder()
                .setScopes(GOOGLE_DRIVE_SCOPES.map(::Scope))
                .apply {
                    credentialsManager.accountEmail?.let { accountEmail ->
                        setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE))
                    }
                }
                .build()
            authorizationClient.revokeAccess(request).await()
            credentialsManager.clear()
        }
    }

    private fun AuthorizationResult.toOutcome(): GoogleDriveAuthorizationOutcome {
        if (hasResolution()) {
            return GoogleDriveAuthorizationOutcome.NeedsResolution(
                intentSender = checkNotNull(pendingIntent).intentSender
            )
        }

        val accessToken = requireNotNull(accessToken) {
            "Google Drive authorization succeeded without an access token"
        }
        val signInAccount = toGoogleSignInAccount()
        return GoogleDriveAuthorizationOutcome.Authorized(
            accessToken = accessToken,
            account = GoogleDriveAccount(
                email = signInAccount?.email,
                displayName = signInAccount?.displayName
            )
        )
    }

    private fun persistAccount(account: GoogleDriveAccount?) {
        credentialsManager.isAuthorized = true
        credentialsManager.accountEmail = account?.email
        credentialsManager.accountDisplayName = account?.displayName
    }

    private suspend fun <T> Task<T>.await(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { continuation.resumeWithException(it) }
            addOnCanceledListener { continuation.cancel(null) }
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val EMAIL_SCOPE = "email"
        private const val PROFILE_SCOPE = "profile"
        private const val OPENID_SCOPE = "openid"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        val GOOGLE_DRIVE_SCOPES = listOf(
            DRIVE_APPDATA_SCOPE,
            EMAIL_SCOPE,
            PROFILE_SCOPE,
            OPENID_SCOPE
        )
    }
}
