package tf.monochrome.desktop.data.auth

import android.net.Uri
import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.desktop.BuildConfig

/**
 * Where the browser comes back to after an OAuth consent page.
 *
 * Android registered custom schemes (`tryptify://spotify-callback`,
 * `tryptify://lastfm-callback`, `tf.monotrypt.android://login-callback`) and
 * the system delivered the redirect to MainActivity as an intent. A Windows
 * app has no such routing, so the redirect goes to a loopback address instead
 * (RFC 8252 §7.3, the flow Spotify documents for desktop apps): a tiny HTTP
 * server on `127.0.0.1` answers the one request, shows a "return to the app"
 * page, and hands the full callback URI to whichever manager is waiting for
 * that path. The managers then run exactly the code MainActivity routed to on
 * Android (`handleCallback(uri)` / `handleDeepLink(uri)`).
 *
 * One fixed port for every flow, taken from [BuildConfig.SPOTIFY_REDIRECT_URI],
 * because each service's dashboard must list the redirect address verbatim:
 *  - Spotify:  `http://127.0.0.1:48621/spotify-callback`
 *  - Last.fm:  `http://127.0.0.1:48621/lastfm-callback` (sent as `cb`)
 *  - Supabase: `http://127.0.0.1:48621/supabase-callback` (redirect allow-list)
 *
 * The server listens only while a flow is waiting and only on the loopback
 * interface. Nothing it receives is trusted on its own: Spotify's callback is
 * checked against the `state` this process generated, a Supabase code is
 * useless without the PKCE verifier held in memory, and a Last.fm token is
 * useless without the listener's shared secret.
 */
@Singleton
class LoopbackRedirectServer @Inject constructor() {

    private val lock = Any()
    private var server: HttpServer? = null
    private val waiting = HashMap<String, Pending>()

    /** The redirect address to register and send for [path] (no leading slash). */
    fun redirectUri(path: String): String = "http://$HOST:$PORT/$path"

    /**
     * Start listening for one callback on [path].
     *
     * Call this *before* opening the browser: a bind failure (another program
     * on the port, a second Tryptify instance mid-sign-in) is then reported
     * while the user is still in the app, and a very fast redirect cannot
     * arrive before anything is listening. A newer [expect] on the same path
     * supersedes an older one, whose [Pending.await] then returns null.
     *
     * @throws IOException when the loopback port cannot be bound.
     */
    @Throws(IOException::class)
    fun expect(path: String): Pending {
        val key = path.trim('/')
        synchronized(lock) {
            ensureStarted()
            val pending = Pending(key)
            waiting.put(key, pending)?.deferred?.cancel()
            return pending
        }
    }

    inner class Pending internal constructor(val path: String) : AutoCloseable {
        internal val deferred = CompletableDeferred<Uri>()

        /** The callback URI (with its query), or null on timeout, supersession or [close]. */
        suspend fun await(timeout: Duration): Uri? = try {
            withTimeoutOrNull(timeout) { deferred.await() }
        } catch (e: CancellationException) {
            // The deferred was cancelled (superseded or closed): that is "no
            // callback". If the caller itself was cancelled, keep propagating.
            currentCoroutineContext().ensureActive()
            null
        }

        /** True once the browser has hit the callback, whether or not anyone awaited it yet. */
        val received: Boolean get() = deferred.isCompleted && !deferred.isCancelled

        /** Stops waiting; the server shuts down once nothing else is waiting. */
        override fun close() = release(this)
    }

    private fun release(pending: Pending) {
        val idle = synchronized(lock) {
            if (waiting[pending.path] === pending) waiting.remove(pending.path)
            pending.deferred.cancel()
            if (waiting.isEmpty()) server.also { server = null } else null
        }
        // Outside the lock: stop() joins the server's dispatcher thread, which
        // may itself be waiting for the lock in handle(). stop(0) cuts nothing
        // off: the handler writes and closes its exchange before completing.
        idle?.let { runCatching { it.stop(0) } }
    }

    private fun ensureStarted() {
        if (server != null) return
        val s = bindWithRetry()
        s.createContext("/") { exchange -> handle(exchange) }
        s.executor = null // the server's own dispatcher thread; each request is tiny
        s.start()
        server = s
        Log.d(TAG, "Listening for OAuth callbacks on $HOST:$PORT")
    }

    /**
     * The port can still be held for a moment by a server that is stopping
     * (a retry straight after a cancelled attempt), so a busy port is retried
     * briefly before it is reported.
     */
    private fun bindWithRetry(): HttpServer {
        var last: IOException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                return HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0)
            } catch (e: IOException) {
                last = e
                if (attempt < BIND_ATTEMPTS - 1) Thread.sleep(BIND_RETRY_MS)
            }
        }
        throw IOException(
            "Port $PORT on this computer is in use, so the sign-in cannot come back to Tryptify. " +
                "Close whatever is using it (or another Tryptify window) and try again.",
            last,
        )
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            if (!ex.requestMethod.equals("GET", ignoreCase = true)) {
                respond(ex, 405, page("Method not allowed", "This address only receives sign-in redirects."))
                return
            }
            val requestUri: URI = ex.requestURI
            val key = requestUri.rawPath.orEmpty().trim('/')
            val pending = synchronized(lock) { waiting[key] }
            if (pending == null) {
                respond(ex, 404, page(
                    "Nothing is waiting for this",
                    "This sign-in link has expired or was already used. Start again from Tryptify.",
                ))
                return
            }
            // Answer the browser first, then wake the waiting manager, so the
            // server is never stopped with the page half written.
            respond(ex, 200, page(
                "Back to Tryptify",
                "Tryptify received the response. You can close this tab and return to the app.",
            ))
            val query = requestUri.rawQuery?.let { "?$it" }.orEmpty()
            pending.deferred.complete(Uri.parse("http://$HOST:$PORT/$key$query"))
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.apply {
            set("Content-Type", "text/html; charset=utf-8")
            set("Cache-Control", "no-store")
            set("Referrer-Policy", "no-referrer")
            set("X-Content-Type-Options", "nosniff")
        }
        runCatching {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.onFailure { Log.w(TAG, "Could not answer the browser: ${it.message}") }
    }

    // Desktop: the page is English-only; strings.tsv has no keys for it yet.
    private fun page(title: String, text: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>Tryptify</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
               background:#0b0b0d;color:#ececf1;font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
          main{max-width:28rem;padding:2rem;text-align:center}
          h1{font-size:1.4rem;font-weight:600;margin:0 0 .5rem}
          p{margin:0;color:#a6a6b0}
        </style></head>
        <body><main><h1>$title</h1><p>$text</p></main></body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "LoopbackRedirect"
        private const val BIND_ATTEMPTS = 5
        private const val BIND_RETRY_MS = 100L
        const val HOST = "127.0.0.1"

        /** The port every flow returns to: the one in the Spotify redirect URI. */
        val PORT: Int = runCatching { URI(BuildConfig.SPOTIFY_REDIRECT_URI).port }
            .getOrNull()?.takeIf { it > 0 } ?: 48621
    }
}
