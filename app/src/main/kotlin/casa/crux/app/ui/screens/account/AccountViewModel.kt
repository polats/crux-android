package casa.crux.app.ui.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.crux.app.data.crux.CruxAccount
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
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
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
            }
        }
        viewModelScope.launch {
            repository.events.collect { event ->
                when (event) {
                    is CruxSignInEvent.SignedIn ->
                        _uiState.update { it.copy(busy = false, notice = outcomeNotice(event.outcome)) }
                    is CruxSignInEvent.Failed ->
                        _uiState.update { it.copy(busy = false, error = event.message) }
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

    fun signIn(provider: String) = start(provider, CruxIntent.SIGN_IN)

    fun linkProvider(provider: String) = start(provider, CruxIntent.LINK)

    fun switchAccount(provider: String) = start(provider, CruxIntent.SWITCH)

    private fun start(provider: String, intent: CruxIntent) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = null) }
            try {
                _authorizationUrls.emit(repository.beginSignIn(provider, intent))
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = e.message ?: "Could not start sign-in") }
            }
        }
    }

    fun switchDeployTarget(provider: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                repository.switchProvider(provider)
                _uiState.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = e.message ?: "Could not switch account") }
            }
        }
    }

    fun unlinkGithub() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                repository.unlinkGithub()
                _uiState.update { it.copy(busy = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = e.message ?: "Could not disconnect GitHub") }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = null) }
            repository.signOut()
            _uiState.update { it.copy(busy = false) }
        }
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
