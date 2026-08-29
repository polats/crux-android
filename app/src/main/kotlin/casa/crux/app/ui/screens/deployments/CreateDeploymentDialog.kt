package casa.crux.app.ui.screens.deployments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import casa.crux.app.R
import casa.crux.app.data.crux.CruxCreateRequest
import casa.crux.app.data.crux.CruxTemplate
import casa.crux.app.data.crux.CruxWorkspace
import casa.crux.app.ui.components.AppDialog
import casa.crux.app.ui.components.AppDialogActions

/**
 * The web dashboard's create form, as a dialog. Which fields appear depends on the active
 * provider: Hugging Face names a Space under your account, Railway needs a workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDeploymentDialog(
    state: DeploymentsUiState,
    onDismiss: () -> Unit,
    onCreate: (CruxCreateRequest) -> Unit,
) {
    val provider = createTargetFor(state.account)
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(state.workspaces.firstOrNull()) }
    var template by remember { mutableStateOf<CruxTemplate?>(null) }

    val nameValid = isValidSpaceName(name)
    val canCreate = provider != null &&
        nameValid &&
        !state.isCreating &&
        (provider != "railway" || workspace != null)

    AppDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.deployments_create_title), style = MaterialTheme.typography.titleLarge)

            if (provider == null) {
                // GitHub alone signs you in but cannot hold a deployment, so say so rather
                // than offering a form that cannot succeed.
                Text(
                    stringResource(R.string.deployments_create_no_target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.deployments_field_name)) },
                    singleLine = true,
                    isError = name.isNotBlank() && !nameValid,
                    supportingText = {
                        Text(
                            if (name.isNotBlank() && !nameValid) {
                                stringResource(R.string.deployments_field_name_invalid)
                            } else {
                                stringResource(R.string.deployments_field_name_hint)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (provider == "railway") {
                    Picker(
                        label = stringResource(R.string.deployments_field_workspace),
                        selected = workspace?.name ?: workspace?.id
                            ?: stringResource(R.string.deployments_field_workspace_none),
                        options = state.workspaces.map { it.name ?: it.id },
                        onSelect = { index -> workspace = state.workspaces.getOrNull(index) },
                    )
                }

                val templateOptions = listOfNotNull(state.defaultTemplate) + state.templates
                if (templateOptions.isNotEmpty()) {
                    Picker(
                        label = stringResource(R.string.deployments_field_template),
                        selected = template?.label
                            ?: state.defaultTemplate?.label
                            ?: stringResource(R.string.deployments_field_template_default),
                        options = templateOptions.map { it.label },
                        onSelect = { index -> template = templateOptions.getOrNull(index) },
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.deployments_field_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(stringResource(R.string.deployments_field_password_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            AppDialogActions(
                dismissText = stringResource(R.string.cancel),
                confirmText = stringResource(R.string.deployments_create),
                onDismiss = onDismiss,
                onConfirm = {
                    val request = buildRequest(
                        provider = provider,
                        account = state.account,
                        name = name,
                        workspace = workspace,
                        template = template,
                        password = password,
                    )
                    if (request != null) onCreate(request)
                },
                confirmEnabled = canCreate,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Pure, so the two request shapes can be asserted on without a device. */
internal fun buildRequest(
    provider: String?,
    account: casa.crux.app.data.crux.CruxAccount?,
    name: String,
    workspace: CruxWorkspace?,
    template: CruxTemplate?,
    password: String,
): CruxCreateRequest? {
    val cleanPassword = password.takeIf { it.isNotBlank() }
    return when (provider) {
        "railway" -> {
            val id = workspace?.id ?: return null
            if (!isValidSpaceName(name)) return null
            CruxCreateRequest.Railway(
                name = name.trim(),
                workspaceId = id,
                password = cleanPassword,
                templateId = template?.id,
            )
        }
        "huggingface" -> {
            val repoId = huggingFaceRepoId(account, name) ?: return null
            CruxCreateRequest.HuggingFace(
                repoId = repoId,
                password = cleanPassword,
                templateId = template?.id,
            )
        }
        else -> null
    }
}
