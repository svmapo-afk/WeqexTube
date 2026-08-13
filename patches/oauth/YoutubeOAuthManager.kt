package com.github.libretube.oauth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

object YoutubeOAuthManager {
    const val ANDROID_CLIENT_ID =
        "208620062506-km7bqj0p959kqdnu72hhiv1mupgnqgfc.apps.googleusercontent.com"
    private const val YOUTUBE_READONLY =
        "https://www.googleapis.com/auth/youtube.readonly"
    private const val PREFS = "weqextube_google_authorization"
    private const val AUTHORIZED = "authorized"
    private lateinit var context: Context

    val requestedScopes: List<Scope> = listOf(Scope(YOUTUBE_READONLY))

    fun initialize(value: Context) { context = value.applicationContext }

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(requestedScopes)
        .build()

    fun isSignedIn(): Boolean = ::context.isInitialized &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTHORIZED, false)

    fun accept(result: AuthorizationResult): String? {
        if (result.hasResolution()) return null
        val token = result.accessToken ?: return null
        markAuthorized(true)
        return token
    }

    /** Google Play services keeps the account grant and refreshes the short-lived token. */
    fun authorizationHeader(): String? {
        if (!::context.isInitialized || !isSignedIn()) return null
        return runCatching {
            val result = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(authorizationRequest()),
                20,
                TimeUnit.SECONDS,
            )
            accept(result)?.let { "Bearer $it" }
        }.getOrNull()
    }

    fun markAuthorized(value: Boolean) {
        if (!::context.isInitialized) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTHORIZED, value).apply()
    }
}
