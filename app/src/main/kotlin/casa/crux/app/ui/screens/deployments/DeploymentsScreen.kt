package casa.crux.app.ui.screens.deployments

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import casa.crux.app.R
import casa.crux.app.data.crux.CruxDeployment
import casa.crux.app.ui.components.AppCardShape
import casa.crux.app.ui.components.AppPrimaryButton
import casa.crux.app.ui.components.AppSecondaryButton
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.SharedFlow

/**
 * Hosted OpenCode deployments, as crux.casa sees them — the same list and the same create
 * form as the dashboard, so neither frontend is the poor relation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentsScreen(
    onNavigateBack: () -> Unit,
    onServerConnected: (String) -> Unit,
    authCodeFlow: SharedFlow<String>? = null,
    viewModel: DeploymentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sign-in must happen in a real browser: providers reject embedded WebViews, and a
    // Custom Tab reuses the browser session, so an existing crux.casa login signs in at once.
    LaunchedEffect(Unit) {
        viewModel.authorizationUrls.collect { url -> openCustomTab(context, url) }
    }
    LaunchedEffect(authCodeFlow) {
        authCodeFlow?.collect { code -> viewModel.completeSignIn(code) }
    }
    LaunchedEffect(Unit) {
        viewModel.connected.collect(onServerConnected)
    }
    // Returning from the Custom Tab is the moment a just-created space may have moved on.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deployments_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.signedIn) {
                        IconButton(onClick = { viewModel.showCreateDialog(true) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.deployments_create))
                        }
                        IconButton(onClick = { viewModel.showAccountSheet(true) }) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = stringResource(R.string.deployments_account_open),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.deployments.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                !state.signedIn -> SignedOut(
                    error = state.error,
                    providers = state.availableProviders,
                    onSignIn = viewModel::signIn,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> DeploymentList(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onConnect = viewModel::connect,
                    onRetry = viewModel::retry,
                    onDelete = viewModel::delete,
                    onCreate = { viewModel.showCreateDialog(true) },
                )
            }
        }
    }

    if (state.showAccountSheet) {
        AccountSheet(
            state = state,
            onDismiss = { viewModel.showAccountSheet(false) },
            onSwitchDeployTarget = viewModel::switchProvider,
            onLink = { viewModel.showAccountSheet(false); viewModel.linkProvider(it) },
            onSwitchAccount = { viewModel.showAccountSheet(false); viewModel.switchAccount(it) },
            onUnlinkGithub = viewModel::unlinkGithub,
            onSignOut = { viewModel.showAccountSheet(false); viewModel.signOut() },
        )
    }

    if (state.showCreateDialog) {
        CreateDeploymentDialog(
            state = state,
            onDismiss = { viewModel.showCreateDialog(false) },
            onCreate = viewModel::create,
        )
    }
}

@Composable
private fun SignedOut(
    error: String?,
    providers: List<String>,
    onSignIn: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.deployments_signed_out_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.deployments_signed_out_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // Only what the server has configured: offering a provider it cannot complete
        // sends you to a browser that fails at the end.
        val offered = providers.ifEmpty { LOGIN_PROVIDERS }
        offered.forEachIndexed { index, provider ->
            val label = when (provider) {
                "huggingface" -> stringResource(R.string.deployments_sign_in_hugging_face)
                "railway" -> stringResource(R.string.deployments_sign_in_railway)
                else -> stringResource(R.string.deployments_sign_in_github)
            }
            if (index == 0) {
                AppPrimaryButton(onClick = { onSignIn(provider) }) { Text(label) }
            } else {
                AppSecondaryButton(onClick = { onSignIn(provider) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun DeploymentList(
    state: DeploymentsUiState,
    onRefresh: () -> Unit,
    onConnect: (CruxDeployment) -> Unit,
    onRetry: (CruxDeployment) -> Unit,
    onDelete: (CruxDeployment) -> Unit,
    onCreate: () -> Unit,
) {
    val ordered = orderDeployments(state.deployments)
    SwipeRefresh(
        state = rememberSwipeRefreshState(state.isLoading && state.deployments.isNotEmpty()),
        onRefresh = onRefresh,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.account?.activeIdentity?.let { identity ->
                item(key = "account") {
                    Text(
                        stringResource(
                            R.string.deployments_account,
                            identity.username ?: identity.provider,
                            identity.provider,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.notice?.let { message ->
                item(key = "notice") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            state.error?.let { message ->
                item(key = "error") {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ordered.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.deployments_empty))
                        AppPrimaryButton(onClick = onCreate) {
                            Text(stringResource(R.string.deployments_create))
                        }
                    }
                }
            }
            items(ordered, key = { it.id }) { deployment ->
                DeploymentCard(
                    deployment = deployment,
                    busy = state.busyId == deployment.id,
                    onConnect = { onConnect(deployment) },
                    onRetry = { onRetry(deployment) },
                    onDelete = { onDelete(deployment) },
                )
            }
        }
    }
}

@Composable
private fun DeploymentCard(
    deployment: CruxDeployment,
    busy: Boolean,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(deployment.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.deployments_status_line,
                    statusLabel(deployment.status),
                    deployment.provider,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            deployment.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.deployments_delete))
                }
                if (deployment.status.name == "ERROR") {
                    AppSecondaryButton(onClick = onRetry, enabled = !busy) {
                        Text(stringResource(R.string.deployments_retry))
                    }
                }
                if (deployment.status.isConnectable) {
                    AppPrimaryButton(onClick = onConnect, enabled = !busy) {
                        Text(stringResource(R.string.deployments_connect))
                    }
                }
            }
        }
    }
}

private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}
