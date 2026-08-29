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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Crux account on this device: the bearer token, who it belongs to, and the deployments
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
) {
    private val accountKey = stringPreferencesKey(ACCOUNT_KEY)

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
    fun beginSignIn(provider: String): String {
        val verifier = CruxPkce.newVerifier()
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, verifier)
        return api.authorizeUrl(provider, CruxPkce.challengeOf(verifier))
    }

    suspend fun completeSignIn(code: String): CruxAccount {
        val verifier = secrets.get(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER)
            ?: throw CruxApiException("This sign-in did not start on this device")
        val issued = api.exchangeCode(code, verifier, deviceLabel())
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, null)
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_TOKEN, issued.token)
        return refreshAccount()
    }

    suspend fun signOut() {
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_TOKEN, null)
        secrets.put(LocalSyncSecretStore.SecretKey.CRUX_PKCE_VERIFIER, null)
        dataStore.edit { it.remove(accountKey) }
    }

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
        val connection = api.connection(requireToken(), deployment.id)
        val url = connection.appUrl ?: deployment.appUrl
            ?: throw CruxApiException("This deployment has no address yet")
        val result = serverRepository.upsertLocalServer(
            url = url,
            username = connection.username,
            password = connection.password,
            defaultName = deployment.displayName,
        )
        result.removedServerIds.forEach { Log.i(TAG, "Replaced a stale entry for the same address") }
        return result.server
    }

    private fun requireToken(): String =
        token() ?: throw CruxUnauthorizedException("Sign in to Crux first")

    private fun deviceLabel(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar(Char::uppercase), Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android device" }

    companion object {
        private const val TAG = "CruxRepository"
        private const val ACCOUNT_KEY = "crux_account"
    }
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
