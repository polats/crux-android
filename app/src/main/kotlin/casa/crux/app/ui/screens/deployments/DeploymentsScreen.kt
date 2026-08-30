package casa.crux.app.ui.screens.deployments

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import casa.crux.app.R
import casa.crux.app.data.crux.CruxDeployment
import casa.crux.app.data.crux.CruxDeploymentStatus
import casa.crux.app.data.crux.CruxIntent
import casa.crux.app.domain.model.ServerConfig
import casa.crux.app.ui.components.AppCardShape
import casa.crux.app.ui.components.AppDialog
import casa.crux.app.ui.components.AppPrimaryButton
import casa.crux.app.ui.components.AppSecondaryButton
import casa.crux.app.ui.components.ProviderIcon
import casa.crux.app.ui.screens.account.MAIN_PROVIDER
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.flow.SharedFlow

/**
 * Hosted OpenCode deployments, as crux.casa sees them — the same list and the same create
 * form as the dashboard, so neither frontend is the poor relation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploymentsScreen(
    onServerConnected: (ServerConfig) -> Unit,
    onNavigateToSettings: () -> Unit = {},
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
    // A sign-in leaves and comes back through the browser, so without this the app simply
    // looks different on return with nothing said about why.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
                // No back arrow: this is the app's first screen now, so there is nothing
                // beneath it to go back to.
                actions = {
                    if (state.signedIn) {
                        IconButton(onClick = { viewModel.showCreateDialog(true) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.deployments_create))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.deployments.isEmpty() -> LoadingSkeleton()
                !state.signedIn -> SignedOut(
                    // Straight into GitHub's authorization rather than by way of the Accounts
                    // screen, which had exactly one button on it and nothing else to decide.
                    onSignIn = { viewModel.signIn(MAIN_PROVIDER) },
                    // Only the list renders state.error, and it is not on screen here — so a
                    // sign-in that failed to start would say nothing at all.
                    error = state.error,
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

    if (state.showCreateDialog) {
        CreateDeploymentDialog(
            state = state,
            onDismiss = { viewModel.showCreateDialog(false) },
            onCreate = viewModel::create,
            onSwitchTarget = viewModel::switchDeployTarget,
            // Linking, not signing in: there is already an account, and this attaches the
            // provider to it.
            onConnectProvider = { viewModel.signIn(it, CruxIntent.LINK) },
        )
    }
}

@Composable
private fun SignedOut(
    onSignIn: () -> Unit,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // GitHub's own mark, because GitHub is the only way in — a generic cloud said
        // nothing about which button was about to be pressed.
        ProviderIcon(
            providerId = "github",
            size = 48.dp,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.deployments_signed_out_title), style = MaterialTheme.typography.titleMedium)
        AppPrimaryButton(onClick = onSignIn) {
            Text(stringResource(R.string.deployments_signed_out_action))
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                    // Headline, then what a space actually is, then the action. The old one
                    // was a single line and a button, which told a new arrival nothing about
                    // what they were about to make.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.deployments_empty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.deployments_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        AppPrimaryButton(
                            onClick = onCreate,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
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

/**
 * One space.
 *
 * The card is the button. It used to carry three of them — Delete, Retry, Connect — in a row,
 * with the destructive one nearest the thumb and the same weight as the one you press every
 * time. Tapping the card connects; everything else moved into the overflow.
 */
@Composable
private fun DeploymentCard(
    deployment: CruxDeployment,
    busy: Boolean,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    // Deleting a space destroys a running server on the provider, and it cannot be undone
    // from here or anywhere else, so it is worth one tap of friction.
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val connectable = deployment.status.isConnectable && !busy

    Card(
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (connectable) Modifier.clickable(onClick = onConnect) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Which provider a space runs on was buried in a line of grey text next to its
            // status, where the two read as one indistinguishable sentence.
            ProviderIcon(providerId = deployment.provider, size = 24.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(deployment.displayName, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusMark(deployment.status)
                    Text(
                        statusLabel(deployment.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deployment.status.isPending) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                deployment.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.deployments_more_actions),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        deployment.appUrl?.let { url ->
                            // The thing you reach for when something is wrong, and there was
                            // nowhere in the app to get it from.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.deployments_copy_url)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(url))
                                    Toast.makeText(
                                        context,
                                        R.string.deployments_url_copied,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                        if (deployment.status == CruxDeploymentStatus.ERROR) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.deployments_retry)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = { menuOpen = false; onRetry() },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.deployments_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteSpaceDialog(
            name = deployment.displayName,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

/**
 * Card-shaped placeholders while the list loads.
 *
 * A centred spinner says only "wait"; these say what is coming and hold its shape, so the
 * screen does not jump when the answer arrives.
 */
@Composable
private fun LoadingSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Card(
                shape = AppCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().height(76.dp),
            ) {}
        }
    }
}

/**
 * Status as a glyph, not only a colour.
 *
 * Each state gets a different shape as well as a different colour, so the four are told apart
 * without relying on hue — which matters for colourblind users, and in AMOLED where the
 * palette is already doing less work.
 */
@Composable
private fun StatusMark(status: CruxDeploymentStatus) {
    val (icon, tint) = when (status) {
        CruxDeploymentStatus.RUNNING ->
            Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        CruxDeploymentStatus.QUEUED, CruxDeploymentStatus.PROVISIONING ->
            Icons.Filled.Schedule to MaterialTheme.colorScheme.tertiary
        CruxDeploymentStatus.ERROR ->
            Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        CruxDeploymentStatus.DELETING, CruxDeploymentStatus.DELETED ->
            Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        CruxDeploymentStatus.UNKNOWN ->
            Icons.Filled.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
}

@Composable
private fun DeleteSpaceDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.deployments_delete_confirm_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
        )
        Text(
            text = stringResource(R.string.deployments_delete_confirm_message, name),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppSecondaryButton(onClick = onDismiss, outlined = true) {
                Text(stringResource(R.string.cancel))
            }
            AppPrimaryButton(onClick = onConfirm, destructive = true) {
                Text(stringResource(R.string.deployments_delete))
            }
        }
    }
}

internal fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}
