package casa.crux.app.ui.screens.deployments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.crux.app.data.crux.CruxAccount
import casa.crux.app.data.crux.CruxCreateRequest
import casa.crux.app.data.crux.CruxDeployment
import casa.crux.app.data.crux.CruxDeploymentStatus
import casa.crux.app.data.crux.CruxIdentity
import casa.crux.app.data.crux.CruxIntent
import casa.crux.app.data.crux.CruxRepository
import casa.crux.app.data.crux.CruxTemplate
import casa.crux.app.data.crux.CruxUnauthorizedException
import casa.crux.app.data.crux.CruxWorkspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeploymentsUiState(
    val signedIn: Boolean = false,
    val account: CruxAccount? = null,
    val deployments: List<CruxDeployment> = emptyList(),
    val templates: List<CruxTemplate> = emptyList(),
    val defaultTemplate: CruxTemplate? = null,
    val workspaces: List<CruxWorkspace> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val busyId: String? = null,
    val error: String? = null,
    val showCreateDialog: Boolean = false,
    /** Providers the server actually has configured; empty until we have asked. */
    val availableProviders: List<String> = emptyList(),
    /** Explains a login that changed which account you are in, as the dashboard does. */
    val notice: String? = null,
)

@HiltViewModel
class DeploymentsViewModel @Inject constructor(
    private val repository: CruxRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeploymentsUiState())
    val uiState: StateFlow<DeploymentsUiState> = _uiState.asStateFlow()

    /** URLs for the screen to open in a Custom Tab, as ServerMcpViewModel does for MCP auth. */
    private val _authorizationUrls = MutableSharedFlow<String>()
    val authorizationUrls: SharedFlow<String> = _authorizationUrls.asSharedFlow()

    /** Emitted once a deployment has become a server, so the screen can navigate to it. */
    private val _connected = MutableSharedFlow<String>()
    val connected: SharedFlow<String> = _connected.asSharedFlow()

    private var pollJob: Job? = null

    init {
        // The shared signed-in flow drives this, so signing in or out anywhere is reflected
        // here without the screen polling on entry.
        viewModelScope.launch {
            repository.signedIn.collect { signedIn ->
                _uiState.update { it.copy(signedIn = signedIn == true) }
                if (signedIn == true) refresh() else pollJob?.cancel()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!repository.isSignedIn()) {
                // /api/session answers signed-out too, and its providers map is what decides
                // which sign-in buttons can succeed.
                val providers = runCatching { repository.publicProviders() }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        signedIn = false,
                        isLoading = false,
                        deployments = emptyList(),
                        account = null,
                        availableProviders = providers,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(signedIn = true, isLoading = true, error = null) }
            try {
                val account = repository.refreshAccount()
                val deployments = repository.deployments()
                val templates = runCatching { repository.templates() }.getOrNull()
                val workspaces = if (account.activeProvider == "railway") {
                    runCatching { repository.workspaces() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        signedIn = true,
                        account = account,
                        deployments = deployments,
                        templates = templates?.templates.orEmpty(),
                        defaultTemplate = templates?.default,
                        workspaces = workspaces,
                        availableProviders = configuredProviders(account),
                        isLoading = false,
                    )
                }
                schedulePollIfPending(deployments)
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load deployments", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load deployments") }
            }
        }
    }

    fun signIn(provider: String, intent: CruxIntent = CruxIntent.SIGN_IN) {
        viewModelScope.launch {
            try {
                _authorizationUrls.emit(repository.beginSignIn(provider, intent))
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Could not start sign-in") }
            }
        }
    }

    fun showCreateDialog(show: Boolean) = _uiState.update { it.copy(showCreateDialog = show, error = null) }

    fun create(request: CruxCreateRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                repository.create(request)
                _uiState.update { it.copy(isCreating = false, showCreateDialog = false) }
                refresh()
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create deployment", e)
                _uiState.update { it.copy(isCreating = false, error = e.message ?: "Could not create the space") }
            }
        }
    }

    fun retry(deployment: CruxDeployment) = act(deployment.id) { repository.retry(deployment.id) }

    fun delete(deployment: CruxDeployment) = act(deployment.id) { repository.delete(deployment.id) }

    fun connect(deployment: CruxDeployment) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyId = deployment.id, error = null) }
            try {
                val server = repository.connect(deployment)
                _uiState.update { it.copy(busyId = null) }
                _connected.emit(server.id)
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect deployment", e)
                _uiState.update { it.copy(busyId = null, error = e.message ?: "Could not connect") }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun act(id: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyId = id, error = null) }
            try {
                block()
                _uiState.update { it.copy(busyId = null) }
                refresh()
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Deployment action failed", e)
                _uiState.update { it.copy(busyId = null, error = e.message ?: "That action failed") }
            }
        }
    }

    private fun signedOut(cause: Exception) {
        viewModelScope.launch { repository.signOut() }
        pollJob?.cancel()
        _uiState.value = DeploymentsUiState(
            signedIn = false,
            isLoading = false,
            error = cause.message ?: "Sign in again",
        )
    }

    /**
     * Provisioning takes minutes, so a list with anything in flight re-reads itself until it
     * settles. Bounded rather than open-ended, following waitForLocalServerReady: a stuck
     * deployment must not leave a coroutine polling forever.
     */
    private fun schedulePollIfPending(deployments: List<CruxDeployment>) {
        pollJob?.cancel()
        if (!deployments.any { it.status.isPending }) return
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                val latest = runCatching { repository.deployments() }.getOrNull() ?: return@launch
                _uiState.update { it.copy(deployments = latest) }
                if (!latest.any { deployment -> deployment.status.isPending }) return@launch
            }
        }
    }

    companion object {
        private const val TAG = "DeploymentsViewModel"
        private const val POLL_INTERVAL_MS = 4000L
        private const val POLL_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

// ---------------------------------------------------------------------------------------
// Pure helpers. The codebase tests logic pulled out of ViewModels rather than the
// ViewModels themselves, so anything worth asserting on lives here.

/** Which provider a new space would be created under, and whether that is even possible. */
internal fun createTargetFor(account: CruxAccount?): String? =
    account?.activeProvider?.takeIf { it == "huggingface" || it == "railway" }

/** Hugging Face wants `owner/name`; the owner comes from the signed-in identity. */
internal fun huggingFaceRepoId(account: CruxAccount?, name: String): String? {
    val owner = account?.activeIdentity?.username?.takeIf { it.isNotBlank() } ?: return null
    val trimmed = name.trim()
    if (!trimmed.matches(SPACE_NAME)) return null
    return "$owner/$trimmed"
}

internal fun isValidSpaceName(name: String): Boolean = name.trim().matches(SPACE_NAME)

/** Newest first, but anything still moving floats to the top where it can be watched. */
internal fun orderDeployments(deployments: List<CruxDeployment>): List<CruxDeployment> =
    deployments.sortedWith(
        compareByDescending<CruxDeployment> { it.status.isPending }
            .thenByDescending { it.createdAt ?: "" }
    )

/** Identities that can actually hold a deployment; GitHub signs you in but cannot host. */
internal fun deployableIdentities(account: CruxAccount?): List<CruxIdentity> =
    account?.identities.orEmpty().filter { it.provider != "github" }

/** The deploy-target selector only earns its place when there is a choice to make. */
internal fun showsDeployTarget(account: CruxAccount?): Boolean =
    deployableIdentities(account).size >= 2

/**
 * Providers worth offering to link: configured on the server, and not already linked. The
 * app used to offer all three unconditionally, so "link" and "sign in" looked identical.
 */
internal fun linkableProviders(account: CruxAccount?): List<String> {
    val linked = account?.identities.orEmpty().map { it.provider }.toSet()
    return configuredProviders(account).filterNot { it in linked }
}

/** The configured providers, in a stable order the UI can render directly. */
internal fun configuredProviders(account: CruxAccount?): List<String> =
    LOGIN_PROVIDERS.filter { account?.providers?.get(it) == true }

internal val LOGIN_PROVIDERS = listOf("huggingface", "railway", "github")

internal fun statusLabel(status: CruxDeploymentStatus): String = when (status) {
    CruxDeploymentStatus.QUEUED -> "Queued"
    CruxDeploymentStatus.PROVISIONING -> "Provisioning"
    CruxDeploymentStatus.RUNNING -> "Running"
    CruxDeploymentStatus.ERROR -> "Failed"
    CruxDeploymentStatus.DELETING -> "Deleting"
    CruxDeploymentStatus.DELETED -> "Deleted"
    CruxDeploymentStatus.UNKNOWN -> "Unknown"
}

private val SPACE_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9-]{0,62}$")
