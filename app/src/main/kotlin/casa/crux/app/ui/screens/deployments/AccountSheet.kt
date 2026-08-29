package casa.crux.app.ui.screens.deployments

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
import casa.crux.app.R
import casa.crux.app.ui.components.AppDialog
import casa.crux.app.ui.components.AppSecondaryButton

/**
 * Everything about *who you are*, in one place.
 *
 * Previously the only account control was a sign-out button, and the three sign-in buttons
 * meant different things depending on browser state nobody could see. Sign in, link and
 * switch are now separate, named actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    state: DeploymentsUiState,
    onDismiss: () -> Unit,
    onSwitchDeployTarget: (String) -> Unit,
    onLink: (String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onUnlinkGithub: () -> Unit,
    onSignOut: () -> Unit,
) {
    val account = state.account
    val deployable = deployableIdentities(account)
    val linkable = linkableProviders(account)
    val github = account?.identities.orEmpty().firstOrNull { it.provider == "github" }

    AppDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.deployments_account_title), style = MaterialTheme.typography.titleLarge)

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
