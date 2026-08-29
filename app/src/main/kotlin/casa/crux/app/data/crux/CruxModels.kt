package casa.crux.app.data.crux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The crux.casa control plane, as the app sees it.
 *
 * Field names mirror the JSON the dashboard already consumes, so the two frontends stay
 * describable by one API doc rather than drifting into separate shapes.
 */

@Serializable
data class CruxAccount(
    val user: CruxUser? = null,
    val userId: String? = null,
    val identities: List<CruxIdentity> = emptyList(),
    val activeProvider: String? = null,
    val activeIdentity: CruxIdentity? = null,
    val stale: Boolean = false,
    /**
     * Which providers the server has configured, as `{huggingface: true, ...}` — a map, not a
     * list. Sign-in buttons are driven from this so the app never offers a provider the
     * deployment cannot actually complete.
     */
    val providers: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class CruxUser(
    val id: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val email: String? = null,
)

@Serializable
data class CruxIdentity(
    val provider: String,
    val username: String? = null,
    val avatarUrl: String? = null,
    val linkedAt: String? = null,
)

@Serializable
data class CruxTemplate(
    val id: String? = null,
    val name: String? = null,
    val repo: String,
    val ref: String = "",
    @SerialName("private") val isPrivate: Boolean = false,
    /** The server composes this already; fall back only when it is absent. */
    @SerialName("label") val serverLabel: String? = null,
) {
    /** `owner/name` or `owner/name@ref`, the form the API accepts back. */
    val reference: String get() = if (ref.isBlank()) repo else "$repo@$ref"

    val label: String
        get() = serverLabel?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: reference
}

@Serializable
data class CruxTemplates(
    val default: CruxTemplate? = null,
    val templates: List<CruxTemplate> = emptyList(),
    val github: CruxGitHubStatus? = null,
)

@Serializable
data class CruxGitHubStatus(
    val linked: Boolean = false,
    val username: String? = null,
    val stale: Boolean = false,
    val connectUrl: String? = null,
    val installUrl: String? = null,
)

@Serializable
data class CruxWorkspace(val id: String, val name: String? = null)

@Serializable
data class CruxDeployment(
    val id: String,
    val provider: String = "huggingface",
    val repoId: String? = null,
    val template: CruxTemplate? = null,
    val username: String = "opencode",
    val state: String = "QUEUED",
    val error: String? = null,
    val appUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    /** What to call this in the UI, and what the server is named once connected. */
    val displayName: String
        get() = repoId?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: id

    val status: CruxDeploymentStatus get() = CruxDeploymentStatus.from(state)
}

/** `password` is only ever returned by the connection endpoint, which is `no-store`. */
@Serializable
data class CruxConnection(
    val id: String,
    val username: String = "opencode",
    val password: String? = null,
    val appUrl: String? = null,
)

@Serializable
data class CruxTokenResponse(
    val token: String,
    val user: CruxUser? = null,
    val userId: String? = null,
    val activeProvider: String? = null,
)

enum class CruxDeploymentStatus {
    QUEUED, PROVISIONING, RUNNING, ERROR, DELETING, DELETED, UNKNOWN;

    /** Still moving, so the list keeps polling. */
    val isPending: Boolean get() = this == QUEUED || this == PROVISIONING || this == DELETING

    val isConnectable: Boolean get() = this == RUNNING

    companion object {
        fun from(value: String?): CruxDeploymentStatus =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
