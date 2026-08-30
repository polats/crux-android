package casa.crux.app.ui.screens.deployments

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.crux.app.data.crux.CruxAccount
import casa.crux.app.data.crux.CruxCreateRequest
import casa.crux.app.data.crux.CruxDeployment
import casa.crux.app.data.crux.CruxDeploymentStatus
import casa.crux.app.data.crux.CruxIdentity
import casa.crux.app.data.crux.CruxIntent
import casa.crux.app.data.crux.CruxRepo
import casa.crux.app.data.crux.CruxRepository
import casa.crux.app.data.crux.CruxTemplate
import casa.crux.app.data.crux.CruxUnauthorizedException
import casa.crux.app.data.crux.CruxWorkspace
import casa.crux.app.data.update.UpdateRepository
import casa.crux.app.domain.model.ServerConfig
import casa.crux.app.service.OpenCodeConnectionService
import casa.crux.app.data.crux.CruxSignInEvent
import casa.crux.app.ui.screens.account.signInMessage
import casa.crux.app.ui.screens.account.configuredProviders
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeploymentsUiState(
    val signedIn: Boolean = false,
    val account: CruxAccount? = null,
    val deployments: List<CruxDeployment> = emptyList(),
    val templates: List<CruxTemplate> = emptyList(),
    val defaultTemplate: CruxTemplate? = null,
    val workspaces: List<CruxWorkspace> = emptyList(),
    /** The user's GitHub repositories, one of which a new space can start from. */
    val repositories: List<CruxRepo> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val busyId: String? = null,
    /** What the busy space is doing, when it is doing something worth naming. */
    val busyNote: String? = null,
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
    private val updateRepository: UpdateRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeploymentsUiState())
    val uiState: StateFlow<DeploymentsUiState> = _uiState.asStateFlow()

    /**
     * One-shot text for the screen to toast.
     *
     * Sign-in and linking both leave for the browser and come back, so without a word on
     * return the app has simply changed and not said why. Separate from `error` in the state,
     * which persists and describes the screen rather than announcing something that happened.
     */
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** URLs for the screen to open in a Custom Tab, as ServerMcpViewModel does for MCP auth. */
    private val _authorizationUrls = MutableSharedFlow<String>()
    val authorizationUrls: SharedFlow<String> = _authorizationUrls.asSharedFlow()

    /**
     * Emitted once a deployment has become a server, so the screen can open it.
     *
     * Carries the whole [ServerConfig] rather than its id because the session route is built
     * from the url, credentials and name — connecting used to drop the user on the server list
     * to find and tap the thing they had just connected.
     */
    private val _connected = MutableSharedFlow<ServerConfig>()
    val connected: SharedFlow<ServerConfig> = _connected.asSharedFlow()

    private var pollJob: Job? = null

    init {
        // The launch-time update check used to ride on HomeViewModel, which is no longer the
        // first screen. Without this the app would quietly stop noticing its own updates.
        viewModelScope.launch {
            updateRepository.restore()
            updateRepository.check(manual = false)
        }
        // The shared signed-in flow drives this, so signing in or out anywhere is reflected
        // here without the screen polling on entry.
        viewModelScope.launch {
            repository.signedIn.collect { signedIn ->
                _uiState.update { it.copy(signedIn = signedIn == true) }
                if (signedIn == true) refresh() else pollJob?.cancel()
            }
        }
        // What the sign-in actually did — signed in, linked, or switched account. The account
        // it landed on is worth saying out loud, since a switch is the surprising one.
        viewModelScope.launch {
            repository.events.collect { event ->
                when (event) {
                    is CruxSignInEvent.SignedIn -> _messages.emit(signInMessage(event.outcome))
                    is CruxSignInEvent.Failed -> _messages.emit(event.message)
                }
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
                // Concurrent, not sequential: none of the three needs another's answer, and
                // run one after another on serverless they add up to a visible wait. Only the
                // workspace list has to follow, since it depends on the active provider.
                val accountAsync = async { repository.refreshAccount() }
                val deploymentsAsync = async { repository.deployments() }
                val templatesAsync = async { runCatching { repository.templates() }.getOrNull() }
                // Only needed by the create dialog, but fetched with everything else so the
                // list is already there when it opens rather than filling in underneath.
                val reposAsync = async { runCatching { repository.githubRepositories() }.getOrDefault(emptyList()) }
                val account = accountAsync.await()
                val deployments = deploymentsAsync.await()
                val templates = templatesAsync.await()
                val repositories = reposAsync.await()
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
                        repositories = repositories,
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

    /** Moves where new spaces are created; the list itself already spans every account. */
    fun switchDeployTarget(provider: String) {
        viewModelScope.launch {
            try {
                // POST /api/session answers with the updated account, so the old refresh()
                // went straight back to GET it again — and then fetched the deployments and
                // templates too. Four sequential requests, on serverless, before the dropdown
                // would so much as change its label.
                val account = repository.switchProvider(provider)
                _uiState.update { it.copy(account = account, error = null) }

                // Only the workspace list depends on where spaces land. The deployment list
                // already spans every linked account, and templates are scoped to the user
                // rather than the provider, so neither changes here.
                if (account.activeProvider == "railway" && _uiState.value.workspaces.isEmpty()) {
                    val workspaces = runCatching { repository.workspaces() }.getOrDefault(emptyList())
                    _uiState.update { it.copy(workspaces = workspaces) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Could not switch account") }
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

    fun retry(deployment: CruxDeployment) =
        act(deployment.id, done = "Retrying ${deployment.displayName}") { repository.retry(deployment.id) }

    fun delete(deployment: CruxDeployment) =
        // Deleting destroys a running server on the provider and cannot be undone, so it is
        // worth confirming out loud that it happened rather than only by the row vanishing.
        act(deployment.id, done = "Deleted ${deployment.displayName}") { repository.delete(deployment.id) }

    fun connect(deployment: CruxDeployment) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyId = deployment.id, busyNote = null, error = null) }
            try {
                // Cloning a repository into a fresh space is the slowest thing connect does,
                // and silence for a minute reads as a hang.
                val server = repository.connect(deployment) { note ->
                    _uiState.update { it.copy(busyNote = note.take(80)) }
                }
                startConnectionService(server)
                _uiState.update { it.copy(busyId = null, busyNote = null) }
                _connected.emit(server)
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect deployment", e)
                _uiState.update { it.copy(busyId = null, busyNote = null, error = e.message ?: "Could not connect") }
            }
        }
    }

    /**
     * Hands the server to the connection service, which is what makes it *connected* rather
     * than merely saved.
     *
     * Writing a ServerConfig is not connecting: the session and chat screens read
     * [casa.crux.app.data.repository.ServerConnectionStateRepository], which only the service
     * populates. Opening a space without this shows "Server disconnected" on a server that is
     * running perfectly — connecting used to route through the server list, which did this
     * step on the user's behalf.
     *
     * Deliberately a copy of the service start in HomeViewModel.connectToServer rather than a
     * refactor of it: that file comes from upstream, and keeping our hands off it is what
     * keeps a manual port readable.
     */
    private fun startConnectionService(server: ServerConfig) {
        val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
            putExtra("server_id", server.id)
            putExtra("server_name", server.name)
            putExtra("server_url", server.url)
            putExtra("server_username", server.username)
            putExtra("server_password", server.password)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun act(id: String, done: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyId = id, error = null) }
            try {
                block()
                _uiState.update { it.copy(busyId = null) }
                done?.let { _messages.emit(it) }
                refresh()
            } catch (e: CruxUnauthorizedException) {
                signedOut(e)
            } catch (e: Exception) {
                Log.e(TAG, "Deployment action failed", e)
                val message = e.message ?: "That action failed"
                _uiState.update { it.copy(busyId = null, error = message) }
                // A failed delete leaves the row exactly where it was, which on its own reads
                // as nothing having happened at all.
                _messages.emit(message)
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
    account?.activeProvider?.takeIf { it in DEPLOY_PROVIDERS }

/** Mirrors DEPLOY_PROVIDERS on crux.casa: every login provider can now hold a deployment. */
internal val DEPLOY_PROVIDERS = setOf("huggingface", "railway", "github")

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
