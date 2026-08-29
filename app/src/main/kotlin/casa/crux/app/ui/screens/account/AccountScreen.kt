package casa.crux.app.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import casa.crux.app.R
import casa.crux.app.ui.components.AppSecondaryButton
import casa.crux.app.ui.screens.deployments.configuredProviders
import casa.crux.app.ui.screens.deployments.openCustomTab
import casa.crux.app.ui.screens.deployments.deployableIdentities
import casa.crux.app.ui.screens.deployments.linkableProviders
import casa.crux.app.ui.screens.deployments.showsDeployTarget
import casa.crux.app.ui.screens.deployments.LOGIN_PROVIDERS

/**
 * Everything about *who you are*, in one place.
 *
 * A top-level destination rather than a dialog inside Spaces: the account decides who you
 * are and where new spaces are created, which is not something to bury two levels down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Signing in, linking and switching all open the provider in a real browser.
    LaunchedEffect(Unit) {
        viewModel.authorizationUrls.collect { url -> openCustomTab(context, url) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deployments_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        AccountContent(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            onSwitchDeployTarget = viewModel::switchDeployTarget,
            onLink = viewModel::linkProvider,
            onSwitchAccount = viewModel::switchAccount,
            onUnlinkGithub = viewModel::unlinkGithub,
            onSignIn = viewModel::signIn,
            onSignOut = viewModel::signOut,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountContent(
    state: AccountUiState,
    modifier: Modifier = Modifier,
    onSwitchDeployTarget: (String) -> Unit,
    onLink: (String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onUnlinkGithub: () -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val account = state.account
    val deployable = deployableIdentities(account)
    val linkable = linkableProviders(account)
    val github = account?.identities.orEmpty().firstOrNull { it.provider == "github" }

    if (state.signedIn != true) {
        Column(
            modifier = modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.deployments_signed_out_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.deployments_signed_out_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val offered = configuredProviders(state.account).ifEmpty { LOGIN_PROVIDERS }
            offered.forEach { provider ->
                AppSecondaryButton(onClick = { onSignIn(provider) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.deployments_account_sign_in, providerLabel(provider)))
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        run {
            account?.activeIdentity?.let { active ->
                Text(
                    stringResource(
                        R.string.deployments_account,
                        active.username ?: active.provider,
                        providerLabel(active.provider),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (account?.stale == true) {
                Text(
                    stringResource(
                        R.string.deployments_account_stale,
                        providerLabel(account.activeProvider ?: ""),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Only worth showing when there is an actual choice between two hosts.
            if (showsDeployTarget(account)) {
                var expanded by remember { mutableStateOf(false) }
                val selected = deployable.firstOrNull { it.provider == account?.activeProvider }
                    ?: deployable.firstOrNull()
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.let { "${providerLabel(it.provider)} — ${it.username}" }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.deployments_account_deploy_into)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        deployable.forEach { identity ->
                            DropdownMenuItem(
                                text = { Text("${providerLabel(identity.provider)} — ${identity.username}") },
                                onClick = {
                                    expanded = false
                                    onSwitchDeployTarget(identity.provider)
                                },
                            )
                        }
                    }
                }
            }

            github?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.deployments_account_github, it.username.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onUnlinkGithub) {
                        Text(stringResource(R.string.deployments_account_disconnect))
                    }
                }
            }

            if (linkable.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.deployments_account_link_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                linkable.forEach { provider ->
                    AppSecondaryButton(onClick = { onLink(provider) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.deployments_account_link, providerLabel(provider)))
                    }
                }
            }

            HorizontalDivider()
            // The escape hatch the app did not have: a sign-in that is guaranteed not to
            // hand back the account you are already in.
            Text(
                stringResource(R.string.deployments_account_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            configuredProviders(account).forEach { provider ->
                TextButton(onClick = { onSwitchAccount(provider) }) {
                    Text(stringResource(R.string.deployments_account_switch, providerLabel(provider)))
                }
            }

            HorizontalDivider()
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.deployments_sign_out))
            }
        }
    }
}

internal fun providerLabel(provider: String): String = when (provider) {
    "huggingface" -> "Hugging Face"
    "railway" -> "Railway"
    "github" -> "GitHub"
    else -> provider
}
