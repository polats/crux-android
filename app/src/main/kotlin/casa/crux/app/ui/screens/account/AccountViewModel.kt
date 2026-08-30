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

    /**
     * One-shot text for the screen to toast.
     *
     * Declared above init on purpose: repository.events replays its last value, so the collect
     * in init emits during construction — and a property initialised after init is still null
     * then, which crashed the screen outright.
     *
     * Connecting and signing out both change the account under you, and the row rearranging is
     * a thin thing to infer that from — especially connecting, which returns from the browser.
     */
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
                    is CruxSignInEvent.SignedIn -> {
                        _uiState.update { it.copy(busyProvider = null, notice = outcomeNotice(event.outcome)) }
                        _messages.emit(signInMessage(event.outcome))
                    }
                    is CruxSignInEvent.Failed -> {
                        _uiState.update { it.copy(busyProvider = null, error = event.message) }
                        _messages.emit(event.message)
                    }
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
    /**
     * Connect a provider, linking it when there is an account to link it to.
     *
     * The token is read here rather than the UI's copy of it. `signedIn` is a Boolean? that
     * starts null while the AndroidKeyStore is unlocked on a background thread, and
     * `null == true` is false — so pressing Connect early sent a *sign-in*, which created a
     * second account holding that provider instead of attaching it to this one. The account
     * then captured every later attempt, because signing in with an identity somebody already
     * owns switches to its owner rather than merging.
     */
    fun connect(provider: String) =
        start(provider, if (repository.isSignedIn()) CruxIntent.LINK else CruxIntent.SIGN_IN)

    fun disconnect(provider: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyProvider = provider, error = null, notice = null) }
            try {
                repository.unlinkProvider(provider)
                _uiState.update { it.copy(busyProvider = null) }
                _messages.emit("${providerLabel(provider)} disconnected")
            } catch (e: Exception) {
                val message = e.message ?: "Could not disconnect"
                _uiState.update { it.copy(busyProvider = null, error = message) }
                _messages.emit(message)
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
            _messages.emit("Signed out")
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
/**
 * A short line for a toast, saying what a sign-in did.
 *
 * Every outcome gets one, unlike [outcomeNotice], which speaks only when something surprising
 * happened and stays silent on the ordinary path. A toast that never fires on success reads as
 * a sign-in that did not work.
 */
internal fun signInMessage(outcome: String?): String = when (outcome) {
    "signup" -> "Account created"
    "link" -> "Account connected"
    "switch" -> "Signed in as the account that already had it"
    "absorb" -> "Accounts merged"
    else -> "Signed in"
}

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
 * by tapping: GitHub cannot go, because it is how you sign in; and one still holding spaces
 * cannot go, because unlinking deletes the credential Crux needs to manage or even delete
 * them.
 */
internal fun accountRows(
    account: CruxAccount?,
    spacesByProvider: Map<String, Int> = emptyMap(),
    signedIn: Boolean = account?.identities.orEmpty().isNotEmpty(),
): List<AccountRow> {
    val offered = configuredProviders(account).ifEmpty { LOGIN_PROVIDERS }
    val linked = account?.identities.orEmpty().associateBy { it.provider }
    // Signed out there is only one way in. Offering the other two would be offering a sign-in
    // the server refuses, since they can be connected to an account but cannot create one.
    val visible = if (signedIn) offered else offered.filter { it == MAIN_PROVIDER }
    return visible.map { provider ->
        val identity = linked[provider]
        AccountRow(
            provider = provider,
            connected = identity != null,
            username = identity?.username,
            // GitHub has no useful disconnect: it is the way in, so removing it would leave
            // you unable to sign in at all. Sign-out sits at the bottom of the screen for that.
            canDisconnect = identity != null && provider != MAIN_PROVIDER,
            blockedReason = when {
                identity == null || provider == MAIN_PROVIDER -> null
                (spacesByProvider[provider] ?: 0) > 0 -> R.string.deployments_account_blocked_spaces
                else -> null
            },
        )
    }
}

/** Providers the server has configured, in a stable order. */
internal fun configuredProviders(account: CruxAccount?): List<String> =
    LOGIN_PROVIDERS.filter { account?.providers?.get(it) == true }

/**
 * Display order, GitHub first because it is the way in.
 *
 * Fixed, and deliberately not sorted by whether an account is connected: rows that rearrange
 * as you connect and disconnect make you re-find the one you were looking at, and the list is
 * three items long — there is nothing to gain by grouping it.
 */
internal val LOGIN_PROVIDERS = listOf("github", "huggingface", "railway")

/**
 * The only provider that can create a Crux account, mirroring SIGNUP_PROVIDERS on crux.casa.
 * The other two are connected to an account that already exists.
 */
internal const val MAIN_PROVIDER = "github"
