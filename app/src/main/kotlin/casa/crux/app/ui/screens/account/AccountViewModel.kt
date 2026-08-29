package casa.crux.app.ui.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.crux.app.data.crux.CruxAccount
import casa.crux.app.R
import casa.crux.app.data.crux.CruxDeploymentStatus
import casa.crux.app.data.crux.CruxIntent
import casa.crux.app.data.crux.CruxRepository
import casa.crux.app.data.crux.CruxSignInEvent
import casa.crux.app.data.crux.CruxUnauthorizedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    /** Null until the stored token has been read, so nothing flashes the wrong state. */
    val signedIn: Boolean? = null,
    val account: CruxAccount? = null,
    /** Which row is mid-action, so only that row shows progress. */
    val busyProvider: String? = null,
    val signingOut: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** How many spaces each provider account still owns; blocks disconnecting it. */
    val spacesByProvider: Map<String, Int> = emptyMap(),
)

/** One provider, as the screen draws it. */
data class AccountRow(
    val provider: String,
    val connected: Boolean,
    val username: String?,
    /** False for the last account left: there is nothing useful to offer, so offer nothing. */
    val canDisconnect: Boolean,
    /** Non-null when a disconnect is offered but refused, and why. */
    val blockedReason: Int?,
)

/**
 * Who you are signed in as.
 *
 * Reads the repository's flows rather than fetching on entry: the account is already cached
 * in DataStore, so the screen paints immediately, and a sign-out anywhere is reflected here
 * without this screen asking.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: CruxRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private val _authorizationUrls = MutableSharedFlow<String>()
    val authorizationUrls: SharedFlow<String> = _authorizationUrls.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(repository.signedIn, repository.account) { signedIn, account ->
                signedIn to account
            }.collect { (signedIn, account) ->
                _uiState.update { it.copy(signedIn = signedIn, account = account) }
                // Only reach the network once we know we have a token, and only to
                // revalidate what is already on screen.
                if (signedIn == true && account == null) revalidate()
                if (signedIn == true) countSpaces()
            }
        }
        viewModelScope.launch {
            repository.events.collect { event ->
                when (event) {
                    is CruxSignInEvent.SignedIn ->
                        _uiState.update { it.copy(busyProvider = null, notice = outcomeNotice(event.outcome)) }
                    is CruxSignInEvent.Failed ->
                        _uiState.update { it.copy(busyProvider = null, error = event.message) }
                }
            }
        }
    }

    /** Refreshes in the background; the cached account stays on screen meanwhile. */
    fun revalidate() {
        viewModelScope.launch {
            if (repository.signedIn.value != true) return@launch
            try {
                repository.refreshAccount()
            } catch (e: CruxUnauthorizedException) {
                repository.onUnauthorized()
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh the account", e)
            }
        }
    }

    /**
     * Signs in when nothing is linked yet, and links onto the existing account after that.
     * The distinction is the server's to enforce, so the button does not have to explain it.
     */
    fun connect(provider: String) =
        start(provider, if (uiState.value.signedIn == true) CruxIntent.LINK else CruxIntent.SIGN_IN)

    fun disconnect(provider: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyProvider = provider, error = null, notice = null) }
            try {
                repository.unlinkProvider(provider)
                _uiState.update { it.copy(busyProvider = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busyProvider = null, error = e.message ?: "Could not disconnect") }
            }
        }
    }

    private fun start(provider: String, intent: CruxIntent) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyProvider = provider, error = null, notice = null) }
            try {
                _authorizationUrls.emit(repository.beginSignIn(provider, intent))
            } catch (e: Exception) {
                _uiState.update { it.copy(busyProvider = null, error = e.message ?: "Could not start sign-in") }
            }
        }
    }

    /** Counts spaces per provider so the row can disable a disconnect the server would refuse. */
    private fun countSpaces() {
        viewModelScope.launch {
            val counts = runCatching { repository.deployments() }.getOrNull()
                ?.filter { it.status != CruxDeploymentStatus.DELETED }
                ?.groupingBy { it.provider }
                ?.eachCount()
                ?: return@launch
            _uiState.update { it.copy(spacesByProvider = counts) }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(signingOut = true, error = null, notice = null) }
            repository.signOut()
            _uiState.update { it.copy(signingOut = false) }
        }
    }

    /** The browser took over; on return the row should not still be spinning. */
    fun onResumed() {
        _uiState.update { it.copy(busyProvider = null) }
        revalidate()
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null, error = null) }

    companion object {
        private const val TAG = "AccountViewModel"
    }
}

/**
 * Explains a login that moved you between accounts. Saying nothing is what made the flow
 * feel arbitrary — the account silently changed and the UI never mentioned it.
 */
internal fun outcomeNotice(outcome: String?): String? = when (outcome) {
    "switch" -> "That account was already signed in with elsewhere, so you are now signed in " +
        "as it. Anything you had linked before stayed where it was."
    "absorb" -> "Your linked accounts were moved across to that one. Nothing was lost."
    "link" -> "Linked to the account you are signed in with."
    else -> null
}

/**
 * One row per provider the server offers, in a fixed order so the list never reshuffles.
 *
 * A disconnect is refused for two reasons, and both are decided here rather than discovered
 * by tapping: the last remaining account cannot go, because that would leave you unable to
 * sign in at all; and one still holding spaces cannot go, because unlinking deletes the
 * credential Crux needs to manage or even delete them.
 */
internal fun accountRows(
    account: CruxAccount?,
    spacesByProvider: Map<String, Int> = emptyMap(),
): List<AccountRow> {
    val offered = configuredProviders(account).ifEmpty { LOGIN_PROVIDERS }
    val linked = account?.identities.orEmpty().associateBy { it.provider }
    return offered.map { provider ->
        val identity = linked[provider]
        AccountRow(
            provider = provider,
            connected = identity != null,
            username = identity?.username,
            // The last one left has no useful action: disconnecting it would leave you unable
            // to sign in, and sign-out already sits at the bottom of the screen.
            canDisconnect = identity != null && linked.size > 1,
            blockedReason = when {
                identity == null || linked.size <= 1 -> null
                (spacesByProvider[provider] ?: 0) > 0 -> R.string.deployments_account_blocked_spaces
                else -> null
            },
        )
    }.sortedByDescending { it.connected }
}

/** Providers the server has configured, in a stable order. */
internal fun configuredProviders(account: CruxAccount?): List<String> =
    LOGIN_PROVIDERS.filter { account?.providers?.get(it) == true }

internal val LOGIN_PROVIDERS = listOf("huggingface", "railway", "github")
