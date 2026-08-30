package casa.crux.app.ui.screens.deployments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import casa.crux.app.R
import casa.crux.app.data.crux.CruxCreateRequest
import casa.crux.app.data.crux.CruxRepo
import casa.crux.app.data.crux.CruxTemplate
import casa.crux.app.data.crux.CruxWorkspace
import casa.crux.app.ui.components.AppDialog
import casa.crux.app.ui.components.AppDialogActions
import casa.crux.app.ui.screens.account.LOGIN_PROVIDERS
import casa.crux.app.ui.screens.account.providerLabel
import kotlin.random.Random

/**
 * The web dashboard's create form, as a dialog. Which fields appear depends on the active
 * provider: Hugging Face names a Space under your account, Railway needs a workspace, and a
 * codespace takes a bare name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDeploymentDialog(
    state: DeploymentsUiState,
    onDismiss: () -> Unit,
    onCreate: (CruxCreateRequest) -> Unit,
    onSwitchTarget: (String) -> Unit = {},
    onConnectProvider: (String) -> Unit = {},
) {
    // Which account a space lands in is a decision you make while creating one, so it lives
    // here rather than taking permanent room on the Accounts page.
    val deployable = state.account?.identities.orEmpty().filter { it.provider in DEPLOY_PROVIDERS }
    val provider = createTargetFor(state.account)
    // Prefilled rather than a placeholder: Material3 hides a placeholder behind the label
    // until the field is focused, so a suggestion you cannot see is no help at all.
    var name by rememberSaveable { mutableStateOf(randomSpaceName()) }
    // The suggestion is a starting point, not a value to edit around: the first tap clears it,
    // so typing a name of your own does not begin with deleting one you did not choose.
    var suggestionSpent by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var workspaceRepo by rememberSaveable { mutableStateOf<String?>(null) }
    val notConnected = stringResource(R.string.deployments_provider_unconnected_suffix)
    // Keyed on the list: the workspaces arrive after the dialog opens, and a plain remember
    // would hold the null it was born with and leave Create disabled forever.
    var workspace by remember(state.workspaces) { mutableStateOf(state.workspaces.firstOrNull()) }
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

            run {
                // Every provider, always — the dropdown is where you learn that Railway and
                // Hugging Face exist at all. Unconnected ones are listed but cannot be chosen;
                // picking one offers to connect it instead of silently doing nothing.
                // The same fixed order as the Accounts screen, GitHub first — not the order
                // DEPLOY_PROVIDERS happens to be declared in, and not grouped by what is
                // connected. A list that rearranges under you is one you have to re-read.
                val choices = LOGIN_PROVIDERS.filter { candidate ->
                    candidate in DEPLOY_PROVIDERS &&
                        (state.availableProviders.isEmpty() || candidate in state.availableProviders)
                }
                val connectedBy = state.account?.identities.orEmpty().associateBy { it.provider }
                Picker(
                    label = stringResource(R.string.deployments_field_create_in),
                    selected = connectedBy[provider]
                        ?.let { "${providerLabel(it.provider)} — ${it.username}" }
                        ?: stringResource(R.string.deployments_field_create_in_none),
                    options = choices.map { candidate ->
                        connectedBy[candidate]
                            ?.let { "${providerLabel(candidate)} — ${it.username}" }
                            ?: "${providerLabel(candidate)} — ${notConnected}"
                    },
                    onSelect = { index ->
                        val candidate = choices.getOrNull(index) ?: return@Picker
                        if (connectedBy.containsKey(candidate)) {
                            onSwitchTarget(candidate)
                        } else {
                            // Picking one you have no account with *is* the request to connect
                            // it, so it goes straight to the provider. The selection itself is
                            // not made: it would arm a Create button that cannot succeed, and
                            // linking makes it the target on the way back anyway.
                            onConnectProvider(candidate)
                        }
                    },
                )
            }

            if (provider == null) {
                Text(
                    stringResource(R.string.deployments_create_no_target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { suggestionSpent = true; name = it },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            // Tapping in is how you say "I want my own name", so the
                            // suggestion gets out of the way rather than needing to be
                            // selected and deleted first.
                            if (focus.isFocused && !suggestionSpent) {
                                suggestionSpent = true
                                name = ""
                            }
                        },
                )

                if (state.repositories.isNotEmpty()) {
                    // Beside the name rather than under Advanced: what a space starts with is
                    // the decision worth making here, and an empty one is only the default.
                    RepoPicker(
                        repositories = state.repositories,
                        selected = workspaceRepo,
                        onSelect = { workspaceRepo = it },
                    )
                }

                // A name is the only thing anyone usually supplies. The rest have working
                // defaults — first workspace, default template, generated password — so they
                // are here for the times you want them rather than in the way every time.
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(
                        if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                    Text(
                        stringResource(R.string.deployments_advanced),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                if (showAdvanced) {
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
                        workspaceRepo = workspaceRepo,
                    )
                    if (request != null) onCreate(request)
                },
                confirmEnabled = canCreate,
            )
        }
    }
}

/**
 * The repository to start from.
 *
 * A field that opens a search dialog, rather than a dropdown with a text field in it. An
 * ExposedDropdownMenu anchors directly under its field, so inside a dialog the menu and the
 * software keyboard between them covered the very text being typed. Its own dialog has room
 * for the query and the results at once.
 */
@Composable
private fun RepoPicker(
    repositories: List<CruxRepo>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    val none = stringResource(R.string.deployments_field_repo_none)

    OutlinedTextField(
        value = selected ?: none,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(stringResource(R.string.deployments_field_repo)) },
        trailingIcon = {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.deployments_field_repo_filter))
        },
        modifier = Modifier
            .fillMaxWidth()
            // readOnly swallows taps, so the whole field is made clickable instead.
            .clickable { searching = true },
    )

    if (searching) {
        RepoSearchDialog(
            repositories = repositories,
            onPick = { onSelect(it); searching = false },
            onDismiss = { searching = false },
        )
    }
}

@Composable
private fun RepoSearchDialog(
    repositories: List<CruxRepo>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val matches = remember(repositories, query) {
        val term = query.trim()
        if (term.isEmpty()) repositories else repositories.filter { it.repo.contains(term, ignoreCase = true) }
    }
    LaunchedEffect(Unit) {
        // Two things, and neither alone is enough. The dialog gets its own window, which does
        // not exist yet in the frame that composes it — requesting focus there silently does
        // nothing — so this waits for a frame first. And taking focus does not by itself raise
        // the keyboard, which is why the field was focused but nothing came up.
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }

    AppDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.deployments_field_repo),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.deployments_field_repo_filter)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                // Only while nothing is typed. Leaving it in the results was the reason a
                // search that matched nothing still showed a row, reading as a bad match.
                if (query.isBlank()) {
                    item(key = "none") {
                        RepoRow(
                            label = stringResource(R.string.deployments_field_repo_none),
                            isPrivate = false,
                            onClick = { onPick(null) },
                        )
                    }
                }
                items(matches, key = { it.repo }) { repository ->
                    RepoRow(
                        label = repository.repo,
                        isPrivate = repository.isPrivate,
                        onClick = { onPick(repository.repo) },
                    )
                }
                if (matches.isEmpty() && query.isNotBlank()) {
                    item(key = "no-match") {
                        Text(
                            stringResource(R.string.deployments_field_repo_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun RepoRow(label: String, isPrivate: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (isPrivate) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(R.string.deployments_repo_private),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    workspaceRepo: String? = null,
): CruxCreateRequest? {
    val cleanPassword = password.takeIf { it.isNotBlank() }
    val cleanRepo = workspaceRepo?.takeIf { it.isNotBlank() }
    return when (provider) {
        "railway" -> {
            val id = workspace?.id ?: return null
            if (!isValidSpaceName(name)) return null
            CruxCreateRequest.Railway(
                name = name.trim(),
                workspaceId = id,
                password = cleanPassword,
                templateId = template?.id,
                workspaceRepo = cleanRepo,
            )
        }
        "huggingface" -> {
            val repoId = huggingFaceRepoId(account, name) ?: return null
            CruxCreateRequest.HuggingFace(
                repoId = repoId,
                password = cleanPassword,
                templateId = template?.id,
                workspaceRepo = cleanRepo,
            )
        }
        "github" -> {
            if (!isValidSpaceName(name)) return null
            CruxCreateRequest.Codespace(
                name = name.trim(),
                password = cleanPassword,
                templateId = template?.id,
                workspaceRepo = cleanRepo,
            )
        }
        else -> null
    }
}

/**
 * A suggested space name, so the form can be submitted without typing anything.
 *
 * Shaped to the name rule both providers enforce — letters, digits and hyphens, starting
 * with an alphanumeric — so a suggestion is never one the API would reject.
 */
internal fun randomSpaceName(random: Random = Random.Default): String {
    val adjective = NAME_ADJECTIVES.random(random)
    val noun = NAME_NOUNS.random(random)
    return "$adjective-$noun-${random.nextInt(100, 1000)}"
}

private val NAME_ADJECTIVES = listOf(
    "amber", "brisk", "calm", "clever", "coral", "eager", "fresh", "gentle", "golden",
    "hidden", "jolly", "keen", "lively", "lucid", "mellow", "nimble", "polar", "quiet",
    "rapid", "silver", "solar", "spry", "still", "sunny", "swift", "teal", "tidy", "vivid",
)

private val NAME_NOUNS = listOf(
    "arbor", "beacon", "cedar", "comet", "delta", "ember", "falcon", "forge", "grove",
    "harbor", "island", "lantern", "meadow", "nimbus", "orbit", "pier", "quarry", "ridge",
    "river", "summit", "thicket", "tundra", "valley", "willow", "zenith",
)
