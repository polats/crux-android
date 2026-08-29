package casa.crux.app.data.crux

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub tokens for servers running in a Codespace.
 *
 * A codespace's forwarded port is private. Reaching it needs the user's own GitHub token in
 * `X-Github-Token` on top of the server's Basic Auth, and that token expires — so it cannot be
 * captured when a screen builds its [casa.crux.app.data.api.ServerConnection], and it must not
 * be persisted alongside the server. It lives here, in memory, keyed by host, and is fetched
 * from crux.casa's connection endpoint, which resolves a fresh one on every call.
 *
 * Keyed by host rather than by server id because that is what an interceptor can see.
 */
@Singleton
class CodespaceTokens @Inject constructor() {

    private data class Entry(val deploymentId: String, val token: String?, val fetchedAt: Long)

    private val entries = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    /**
     * How the store reaches crux.casa. Set by [CruxRepository] rather than injected, because
     * the repository is built on the very HttpClient this store is installed into.
     */
    @Volatile
    var refresher: (suspend (deploymentId: String) -> String?)? = null

    fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    /** Records the deployment behind a server, and the token that came with it. */
    suspend fun remember(appUrl: String, deploymentId: String, token: String) {
        val host = hostOf(appUrl) ?: return
        mutex.withLock {
            entries[host] = Entry(deploymentId, token, System.currentTimeMillis())
        }
    }

    /**
     * Records which deployment a host belongs to without claiming to have a token for it, so
     * the first request there fetches one. Deliberately does not overwrite a token already
     * held: this is called on every emission of the saved-server list, which changes whenever
     * anything as ordinary as a health check touches a row.
     */
    suspend fun rememberDeployment(appUrl: String, deploymentId: String) {
        val host = hostOf(appUrl) ?: return
        mutex.withLock {
            val held = entries[host]
            if (held?.deploymentId == deploymentId) return
            entries[host] = Entry(deploymentId, null, 0L)
        }
    }

    suspend fun forget(appUrl: String) {
        val host = hostOf(appUrl) ?: return
        mutex.withLock { entries.remove(host) }
    }

    /** A usable token for [host], refreshing if it is stale or absent. Null if not a codespace. */
    suspend fun tokenFor(host: String): String? {
        val entry = mutex.withLock { entries[host] } ?: return null
        val fresh = entry.token != null && System.currentTimeMillis() - entry.fetchedAt < TTL_MS
        if (fresh) return entry.token
        return refresh(host, entry.deploymentId)
    }

    /** Forces a new token, used after a request was bounced to GitHub's login page. */
    suspend fun refresh(host: String, deploymentId: String? = null): String? {
        val id = deploymentId ?: mutex.withLock { entries[host]?.deploymentId } ?: return null
        val fetch = refresher ?: return null
        val token = runCatching { fetch(id) }
            .onFailure { Log.w(TAG, "Could not refresh the GitHub token for $host: ${it.message}") }
            .getOrNull()
        mutex.withLock { entries[host] = Entry(id, token, System.currentTimeMillis()) }
        return token
    }

    private companion object {
        const val TAG = "CodespaceTokens"
        /** Well inside GitHub's eight-hour token life, so a refresh is never on the hot path. */
        const val TTL_MS = 30 * 60 * 1000L
    }
}

/**
 * Attaches the GitHub token to every request bound for a codespace, and retries once when one
 * is rejected.
 *
 * This is an interceptor rather than a header on [casa.crux.app.data.api.ServerConnection]
 * because the token outlives no screen: a ViewModel builds its connection once and keeps it for
 * hours. One seam here covers Ktor's HTTP calls, the SSE stream and the WebSocket handshake
 * alike.
 *
 * A rejected token does not look like a 401. The forwarding layer answers with a redirect to
 * GitHub's login page, which OkHttp follows, so the app sees a 200 of HTML from github.com —
 * hence the check on the *final* request's host rather than on a status code.
 */
class CodespaceTokenInterceptor(private val tokens: CodespaceTokens) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val token = runBlocking { tokens.tokenFor(host) } ?: return chain.proceed(chain.request())

        val response = chain.proceed(withToken(chain, token))
        if (!bouncedToLogin(response)) return response

        val retry = runBlocking { tokens.refresh(host) } ?: return response
        if (retry == token) return response
        response.close()
        return chain.proceed(withToken(chain, retry))
    }

    private fun withToken(chain: Interceptor.Chain, token: String) =
        chain.request().newBuilder().header("X-Github-Token", token).build()

    private fun bouncedToLogin(response: Response): Boolean =
        response.request.url.host == "github.com"
}
