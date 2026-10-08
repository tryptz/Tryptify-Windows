package tf.monochrome.desktop.player

/**
 * Which media URIs may be handed to the engine *before* they are needed.
 *
 * Gapless playback requires the next track to be open in the engine while the
 * current one is still going, so its decoder can spin up and hand over without
 * a gap. That means committing to a URI minutes ahead of use, and only some of
 * this app's URIs survive that wait:
 *
 *  - `file` — a path on disk. Stable by definition.
 *  - `qobuz` — resolved by the Qobuz partial cache at open time, not now, so
 *    there is nothing in it to expire. This is what makes Qobuz gapless.
 *  - `deezer` — the same, through the Deezer partial cache.
 *  - `http` / `https` — a signed or time-limited stream URL (TIDAL, Apple).
 *    Queuing one ahead is exactly the staleness the one-track-at-a-time design
 *    was built to avoid, so these are refused.
 *  - `data` — an inline DASH manifest (StreamResolver.kt's dashManifestUri), whose
 *    segment URLs expire like any TIDAL stream; meaningless to pre-queue.
 *
 * Refusing a scheme costs nothing but the gap: playback falls back to the
 * existing resolve-on-demand path for that transition.
 *
 * Desktop: `content`, `asset`, `rawresource` and `android.resource` were stable
 * schemes on Android. None of them exists here, so none is listed.
 */
internal object GaplessEligibility {

    private val STABLE_SCHEMES = setOf(
        "file",
        tf.monochrome.desktop.data.cache.QobuzStreamUri.SCHEME,
        tf.monochrome.desktop.data.cache.DeezerStreamUri.SCHEME,
    )

    /** True when [uri] will still resolve to the same audio some minutes from now. */
    fun isStableUri(uri: String?): Boolean {
        val scheme = schemeOf(uri) ?: return false
        return scheme in STABLE_SCHEMES
    }

    /**
     * The scheme of [uri], lowercased, or null when there isn't one. A bare
     * filesystem path counts as `file` — that's how the download store records
     * its paths — including a Windows one, whose drive letter would otherwise
     * read as a one-letter scheme.
     */
    private fun schemeOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        if (uri.startsWith("/") || LocalPaths.isWindowsDrivePath(uri)) return "file"
        val separator = uri.indexOf("://")
        if (separator <= 0) return null
        return uri.substring(0, separator).lowercase()
    }
}
