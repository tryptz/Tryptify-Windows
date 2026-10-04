package tf.monochrome.desktop.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import tf.monochrome.desktop.BuildConfig
import tf.monochrome.desktop.data.api.SpotifyAuthError
import tf.monochrome.desktop.data.api.SpotifyTokenResponse
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.platform.DesktopActions
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * Spotify OAuth via Authorization Code + PKCE — the only grant that works
 * from a mobile app without a client secret or backend. Mirrors the
 * browser + loopback-redirect pattern used by [SupabaseAuthManager]:
 *
 *  1. [connect] opens accounts.spotify.com/authorize in the default browser.
 *  2. Spotify redirects to http://127.0.0.1:48621/spotify-callback
 *     ([BuildConfig.SPOTIFY_REDIRECT_URI]), which [LoopbackRedirectServer]
 *     routes to [handleCallback]. (Android: tryptify://spotify-callback,
 *     routed by MainActivity.) The Spotify app's dashboard must list that
 *     redirect URI.
 *  3. The code is exchanged for access + refresh tokens, persisted in
 *     DataStore via [PreferencesManager].
 *  4. [getValidAccessToken] transparently refreshes on expiry. Spotify
 *     rotates refresh tokens for PKCE clients, so a rotated token in the
 *     refresh response must be persisted or the next refresh breaks.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: PreferencesManager,
    private val loopback: LoopbackRedirectServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    // Desktop: the PKCE verifier and state live in memory. Android persisted
    // them so they survived process death while the Custom Tab was open; here
    // the callback can only arrive while this process's loopback server is
    // listening, so they never need to outlive it (and never touch the disk).
    private val pkce = PkceState()

    /** The loopback wait of the connect attempt in flight, if any. */
    @Volatile private var pendingCallback: LoopbackRedirectServer.Pending? = null

    val isConnected: StateFlow<Boolean> = preferences.spotifyRefreshToken
        .map { !it.isNullOrBlank() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val connectedUserName: StateFlow<String?> = preferences.spotifyUserName
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Launch the Spotify consent page in the default browser. */
    fun connect(@Suppress("UNUSED_PARAMETER") activityContext: Context) {
        _errorMessage.value = null
        _isConnecting.value = true

        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        pkce.set(verifier, state)

        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            BASE64_URL_FLAGS,
        )

        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()

        // Listen before the browser opens: a busy port is reported here, in the
        // app, and a fast redirect cannot beat the server.
        pendingCallback?.let { older ->
            pendingCallback = null
            older.close()
        }
        val pending = try {
            loopback.expect(CALLBACK_PATH)
        } catch (e: IOException) {
            _isConnecting.value = false
            _errorMessage.value = "Spotify connection failed: ${e.message}"
            return
        }
        pendingCallback = pending

        scope.launch {
            try {
                DesktopActions.openLink(url.toString())
            } catch (e: Exception) {
                pending.close()
                if (pendingCallback === pending) pendingCallback = null
                _isConnecting.value = false
                _errorMessage.value = "Could not open browser: ${e.message}"
                return@launch
            }
            val callback = try {
                pending.await(CALLBACK_TIMEOUT)
            } finally {
                pending.close()
            }
            if (pendingCallback !== pending) return@launch // a newer connect() took over
            pendingCallback = null
            if (callback != null) {
                handleCallback(callback)
            } else {
                // Desktop: Android had no timeout (the deep link simply never
                // came); here the loopback server stops listening after one.
                _isConnecting.value = false
                _errorMessage.value = "Spotify authorization timed out. Please try connecting again."
            }
        }
    }

    /** Handle http://127.0.0.1:48621/spotify-callback?code=...&state=... from [LoopbackRedirectServer]. */
    suspend fun handleCallback(uri: Uri) {
        try {
            val attempt = pkce.current()
            val expectedState = attempt?.second
            val verifier = attempt?.first

            uri.getQueryParameter("error")?.let { error ->
                _errorMessage.value = if (error == "access_denied") {
                    "Spotify authorization was cancelled."
                } else {
                    "Spotify authorization failed: $error"
                }
                return
            }

            val returnedState = uri.getQueryParameter("state")
            if (expectedState.isNullOrBlank() || returnedState != expectedState) {
                _errorMessage.value = "Spotify authorization failed: state mismatch. Please try again."
                return
            }
            val code = uri.getQueryParameter("code")
            if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
                _errorMessage.value = "Spotify authorization failed: missing code. Please try again."
                return
            }

            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
                    append("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                    append("code_verifier", verifier)
                },
            )
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                _errorMessage.value = mapTokenError(response.status.value, body)
                return
            }

            val tokens = json.decodeFromString<SpotifyTokenResponse>(body)
            val refresh = tokens.refreshToken
            if (refresh.isNullOrBlank()) {
                _errorMessage.value = "Spotify did not return a refresh token. Please try again."
                return
            }
            preferences.setSpotifyTokens(
                accessToken = tokens.accessToken,
                refreshToken = refresh,
                expiresAtMillis = System.currentTimeMillis() + tokens.expiresIn * 1000L,
            )
            pkce.clear()
            _errorMessage.value = null
            Log.d(TAG, "Spotify connected; token expires in ${tokens.expiresIn}s")
        } catch (e: Exception) {
            Log.e(TAG, "Spotify callback failed", e)
            _errorMessage.value = "Spotify connection failed: ${e.message}"
        } finally {
            _isConnecting.value = false
        }
    }

    /**
     * Returns a non-expired access token, refreshing if needed; null when
     * not connected or the refresh token was revoked (in which case local
     * state is cleared so the UI falls back to "Connect").
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val refreshToken = preferences.spotifyRefreshToken.first() ?: return null
        val expiresAt = preferences.spotifyTokenExpiresAt.first()
        val access = preferences.spotifyAccessToken.first()

        if (!forceRefresh && !access.isNullOrBlank() && expiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return access
        }

        return try {
            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                },
            )
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                Log.w(TAG, "Spotify token refresh failed (${response.status.value}): $body")
                if (response.status.value == 400) {
                    // invalid_grant — refresh token revoked/expired; force reconnect.
                    disconnect()
                }
                return null
            }
            val tokens = json.decodeFromString<SpotifyTokenResponse>(body)
            preferences.setSpotifyTokens(
                accessToken = tokens.accessToken,
                // Spotify rotates PKCE refresh tokens — persist the new one when present.
                refreshToken = tokens.refreshToken ?: refreshToken,
                expiresAtMillis = System.currentTimeMillis() + tokens.expiresIn * 1000L,
            )
            tokens.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token refresh error", e)
            null
        }
    }

    suspend fun setConnectedUserName(name: String?) {
        preferences.setSpotifyUserName(name)
    }

    /** Spotify has no token-revocation endpoint for PKCE; clearing local tokens is the disconnect. */
    suspend fun disconnect() {
        preferences.clearSpotifyTokens()
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun mapTokenError(status: Int, body: String): String {
        val parsed = runCatching { json.decodeFromString<SpotifyAuthError>(body) }.getOrNull()
        return when {
            status == 403 || body.contains("not be registered", ignoreCase = true) ->
                "This Spotify app is in Development mode — your Spotify account must be " +
                    "added to the allowlist in the Spotify Developer Dashboard (User Management)."
            parsed?.error == "invalid_grant" ->
                "Authorization expired. Please try connecting again."
            else ->
                "Spotify token exchange failed: ${parsed?.errorDescription ?: parsed?.error ?: "HTTP $status"}"
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, BASE64_URL_FLAGS)
    }

    companion object {
        private const val TAG = "SpotifyAuth"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val BASE64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private const val CALLBACK_PATH = "spotify-callback"
        private val CALLBACK_TIMEOUT = 5.minutes
    }

    /** The in-flight attempt's verifier and state, replaced together and read together. */
    private class PkceState {
        @Volatile private var pair: Pair<String, String>? = null
        /** (verifier, state), or null when no attempt is in flight. */
        fun current(): Pair<String, String>? = pair
        fun set(verifier: String, state: String) { pair = verifier to state }
        fun clear() { pair = null }
    }
}
