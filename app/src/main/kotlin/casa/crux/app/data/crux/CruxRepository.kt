package casa.crux.app.data.crux

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import casa.crux.app.data.repository.ServerRepository
import casa.crux.app.data.sync.LocalSyncSecretStore
import casa.crux.app.domain.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sign-in held on this device: the bearer token, who it belongs to, and the deployments
 * it can reach.
 *
 * The token and the in-flight PKCE verifier live in [LocalSyncSecretStore], which is backed by
 * the AndroidKeyStore. DataStore holds only the non-secret identity, so the signed-in name can
 * be rendered without unlocking anything.
 */
@Singleton
class CruxRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secrets: LocalSyncSecretStore,
    private val api: CruxApi,
    private val json: Json,
    private val serverRepository: ServerRepository,
    private val codespaceTokens: CodespaceTokens,
) {
    private val accountKey = stringPreferencesKey(ACCOUNT_KEY)

    /**
     * Work that must outlive the screen that started it. The browser can come back long
     * after the Account screen has gone, and an Activity-scoped job would be cancelled
     * halfway through the token exchange.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Set rather than injected: the token store is installed into the very HttpClient this
        // repository's api is built on, so injecting the repository there would be a cycle.
        codespaceTokens.refresher = { deploymentId ->
            api.connection(requireToken(), deploymentId).githubToken
        }
        // The store is in memory, so it is empty after a restart while the servers it describes
        // are not. Re-registering them with a null token makes the first request to one fetch
        // a fresh token instead of being bounced to a login page.
        scope.launch {
            serverRepository.servers.collect { servers ->
                servers.forEach { server ->
                    server.cruxDeploymentId?.let { codespaceTokens.rememberDeployment(server.url, it) }
                }
            }
        }
    }

    /**
     * Whether this device holds a token.
     *
     * The token lives in encrypted SharedPreferences, which is not observable, so its
     * presence is mirrored here and every screen reacts to a sign-in or sign-out wherever it
     * happened. Seeded lazily rather than in the constructor: reading it unlocks an
     * AndroidKeyStore key, and Hilt builds this singleton on the main thread.
     */
    private val _signedIn = MutableStateFlow<Boolean?>(null)
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    /** Sign-in results, kept until a screen is around to explain them. */
    private val _events = MutableSharedFlow<CruxSignInEvent>(replay = 1)
    val events: SharedFlow<CruxSignInEvent> = _events.asSharedFlow()

    init {
        scope.launch { _signedIn.value = isSignedIn() }
    }

    /** The signed-in account as last seen, or null when signed out. */
    val account: Flow<CruxAccount?> = dataStore.data.map { preferences ->
        preferences[accountKey]?.let { encoded ->
            runCatching { json.decodeFromString<CruxAccount>(encoded) }.getOrNull()
        }
    }

    fun token(): String? = secrets.get(LocalSyncSecretStore.SecretKey.CRUX_TOKEN)

    fun isSignedIn(): Boolean = !token().isNullOrBlank()

    // -------------------------------------------------------------- sign-in

    /**
     * Starts a sign-in and returns the URL to open in a Custom Tab. The verifier is stored
     * first: the callback arrives in a different process lifetime, and without it the code
     * cannot be exchanged.
     */
    suspend fun beginSignIn(provider: String, intent: CruxIntent = CruxIntent.SIGN_IN): String {
        val verifier = CruxPkce.newVerifier()
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, verifier)
        // Linking must prove which account it is attaching to. That proof comes from this
        // device's token, never from whatever session the system browser is holding.
        val ticket = if (intent == CruxIntent.LINK) api.linkTicket(requireToken()) else null
        return api.authorizeUrl(provider, CruxPkce.challengeOf(verifier), intent, ticket)
    }

    /** Returns the outcome alongside the account, so a changed identity can be explained. */
    /**
     * Completes a sign-in started anywhere in the app. Deliberately fire-and-forget: the
     * redirect lands on the Activity, but finishing it is the app's business, not that of
     * whichever screen happens to be on top — which is why signing in from the Account
     * screen used to do nothing at all.
     */
    fun submitAuthCode(code: String) {
        scope.launch {
            try {
                val verifier = secrets.get(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER)
                    ?: throw CruxApiException("This sign-in did not start on this device")
                val issued = api.exchangeCode(code, verifier, deviceLabel())
                secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, null)
                storeToken(issued.token)
                refreshAccount()
                _events.emit(CruxSignInEvent.SignedIn(issued.outcome))
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in failed", e)
                _events.emit(CruxSignInEvent.Failed(e.message ?: "Sign-in failed"))
            }
        }
    }

    /**
     * Revoke first, then forget. A token that is only forgotten locally stays valid on the
     * server for anyone who has a copy of it.
     */
    suspend fun signOut() {
        token()?.let { existing ->
            runCatching { api.revoke(existing) }
                .onFailure { Log.w(TAG, "Could not revoke the token; clearing it locally anyway") }
        }
        clearSession()
    }

    /** The one place the token is written, so [signedIn] can never drift from reality. */
    private fun storeToken(value: String) {
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_TOKEN, value)
        _signedIn.value = true
    }

    private suspend fun clearSession() {
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_TOKEN, null)
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, null)
        _signedIn.value = false
        dataStore.edit { it.remove(accountKey) }
    }

    /**
     * A token revoked elsewhere should drop this device to signed-out once, rather than
     * every screen discovering it separately.
     */
    suspend fun onUnauthorized() {
        if (_signedIn.value != false) clearSession()
    }

    suspend fun unlinkProvider(provider: String) {
        api.unlinkProvider(requireToken(), provider)
        refreshAccount()
    }

    /** Which providers the server offers, readable without being signed in. */
    suspend fun publicProviders(): List<String> =
        api.publicAccount().providers.filterValues { it }.keys.toList()

    suspend fun refreshAccount(): CruxAccount {
        val account = api.account(requireToken())
        dataStore.edit { it[accountKey] = json.encodeToString(CruxAccount.serializer(), account) }
        return account
    }

    // ----------------------------------------------------------- deployments

    suspend fun deployments(): List<CruxDeployment> = api.deployments(requireToken())

    suspend fun templates(): CruxTemplates = api.templates(requireToken())

    suspend fun workspaces(): List<CruxWorkspace> = api.workspaces(requireToken())

    suspend fun create(request: CruxCreateRequest): CruxDeployment =
        api.createDeployment(requireToken(), request)

    suspend fun retry(id: String): CruxDeployment = api.retryDeployment(requireToken(), id)

    suspend fun delete(id: String) = api.deleteDeployment(requireToken(), id)

    suspend fun switchProvider(provider: String): CruxAccount {
        val account = api.switchProvider(requireToken(), provider)
        dataStore.edit { it[accountKey] = json.encodeToString(CruxAccount.serializer(), account) }
        return account
    }

    /**
     * Turns a running deployment into an ordinary server.
     *
     * This is the whole point of the integration: once the URL and credentials are in
     * [ServerRepository], every existing screen — chat, terminal, sessions, files — works on
     * a hosted deployment with no further knowledge of Crux.
     */
    suspend fun connect(deployment: CruxDeployment): ServerConfig {
        val connection = awaitReady(deployment)
        val url = connection.appUrl ?: deployment.appUrl
            ?: throw CruxApiException("This deployment has no address yet")
        // A codespace's port is private, so every later request to this host needs a GitHub
        // token. Recorded before the server is saved, so the first health check already carries
        // one rather than being bounced to a login page.
        val githubToken = connection.githubToken
        if (connection.needsToken && githubToken != null) {
            codespaceTokens.remember(url, deployment.id, githubToken)
        } else if (connection.needsToken) {
            codespaceTokens.rememberDeployment(url, deployment.id)
        }
        val result = serverRepository.upsertLocalServer(
            url = url,
            username = connection.username,
            password = connection.password,
            defaultName = deployment.displayName,
            cruxDeploymentId = deployment.id.takeIf { connection.needsToken },
        )
        result.removedServerIds.forEach { Log.i(TAG, "Replaced a stale entry for the same address") }
        return result.server
    }

    /**
     * Fetches the connection, waiting out an idle codespace.
     *
     * A codespace stops itself after 30 minutes and answers nothing until it is started again.
     * The connection endpoint starts it and reports `ready = false` rather than blocking, so
     * this is where the waiting happens — a resume takes about 17 seconds and keeps the same
     * URL and disk, which is what makes "tap Connect, we wake it" honest.
     *
     * Only ever loops for a codespace: every other provider leaves `ready` null.
     */
    private suspend fun awaitReady(deployment: CruxDeployment): CruxConnection {
        var connection = api.connection(requireToken(), deployment.id)
        val deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
        while (connection.ready == false && System.currentTimeMillis() < deadline) {
            Log.i(TAG, "Waiting for ${deployment.displayName}: ${connection.codespaceState}")
            delay(WAKE_POLL_MS)
            connection = api.connection(requireToken(), deployment.id)
        }
        // Handed over even if it never came up: the address and credentials are right, and the
        // server list's own health check says more about why than a guess here would.
        return connection
    }

    private fun requireToken(): String =
        token() ?: throw CruxUnauthorizedException("Sign in first")

    private fun deviceLabel(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar(Char::uppercase), Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android device" }

    companion object {
        private const val TAG = "CruxRepository"
        /** A resume was measured at about 17 seconds; this leaves room for a cold one. */
        private const val WAKE_TIMEOUT_MS = 90_000L
        private const val WAKE_POLL_MS = 3_000L
        private const val ACCOUNT_KEY = "crux_account"
    }
}

/** What a completed sign-in attempt produced. */
sealed interface CruxSignInEvent {
    data class SignedIn(val outcome: String?) : CruxSignInEvent
    data class Failed(val message: String) : CruxSignInEvent
}

/**
 * Pulls the authorization code out of a `crux://auth/callback?code=…` redirect.
 *
 * Returns null for anything else, including the error redirect, so an unrelated intent
 * cannot be mistaken for a completed sign-in.
 */
internal fun cruxAuthCodeFrom(uri: Uri?): String? = cruxAuthCode(
    scheme = uri?.scheme,
    host = uri?.host,
    code = uri?.getQueryParameter("code"),
)

/**
 * The decision itself, kept off android.net.Uri so it can be asserted on: unit tests run with
 * returnDefaultValues, where every Uri accessor answers null.
 */
internal fun cruxAuthCode(scheme: String?, host: String?, code: String?): String? {
    if (!scheme.equals(CruxApi.CALLBACK_SCHEME, ignoreCase = true)) return null
    if (!host.equals("auth", ignoreCase = true)) return null
    return code?.takeIf { it.isNotBlank() }
}
