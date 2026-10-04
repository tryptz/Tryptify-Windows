package tf.monochrome.desktop

/**
 * What Android's generated BuildConfig carried. Values that are public by
 * design in the Android app (the Spotify PKCE client id, the Last.fm charts
 * key) are the same here; see the Android build file for why they are safe
 * to ship. VERSION_NAME is kept in step with app/build.gradle.kts by
 * tools/check_version.py.
 */
object BuildConfig {
    const val DEBUG: Boolean = false
    const val APPLICATION_ID: String = "tf.monochrome.desktop"
    const val VERSION_NAME: String = "1.9.3"
    const val VERSION_CODE: Int = 193
    const val BUILD_TYPE: String = "release"
    val SPOTIFY_CLIENT_ID: String = System.getProperty("tryptify.spotify.clientId") ?: "c9e571c8b81948feb6573014a3efdd2c"
    /** Desktop OAuth returns through a loopback port; see data/auth. */
    const val SPOTIFY_REDIRECT_URI: String = "http://127.0.0.1:48621/spotify-callback"
    val LASTFM_API_KEY: String = System.getProperty("tryptify.lastfm.apiKey") ?: "153452aaeaa3e666645274b3a9e5bb0a"
}
