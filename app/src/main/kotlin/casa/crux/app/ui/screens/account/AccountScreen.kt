package casa.crux.app.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
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
import casa.crux.app.R
import casa.crux.app.ui.components.AppSecondaryButton
import casa.crux.app.ui.components.ProviderIcon
import casa.crux.app.ui.screens.deployments.openCustomTab

/**
 * The provider accounts you are signed in with.
 *
 * One row per provider, and nothing else: no explanation of what linking means, no separate
 * "sign in as someone else" list. Why an action is unavailable is a long-press tooltip
 * rather than a paragraph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val rows = accountRows(state.account, state.spacesByProvider)
            rows.forEach { row ->
                ProviderRow(
                    row = row,
                    busy = state.busy,
                    onConnect = { viewModel.connect(row.provider) },
                    onDisconnect = { viewModel.disconnect(row.provider) },
                )
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (state.signedIn == true) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = viewModel::signOut, enabled = !state.busy) {
                    Text(stringResource(R.string.deployments_sign_out))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderRow(
    row: AccountRow,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderIcon(providerId = row.provider, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(providerLabel(row.provider), style = MaterialTheme.typography.bodyLarge)
            row.username?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)

            !row.connected -> AppSecondaryButton(onClick = onConnect) {
                Text(stringResource(R.string.deployments_account_connect))
            }

            // Disabled rather than hidden, so the row still reads as an account you hold —
            // the reason lives in a long-press tooltip instead of a line of copy.
            row.blockedReason != null -> TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(stringResource(row.blockedReason)) } },
                state = rememberTooltipState(),
            ) {
                TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.deployments_account_disconnect))
                }
            }

            else -> TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.deployments_account_disconnect))
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
