package casa.crux.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Server Configuration - stored server connection details
 */
@Serializable
data class ServerConfig(
    val id: String, // UUID
    val url: String, // e.g. http://192.168.1.100:4096
    val username: String = "opencode",
    val password: String? = null,
    val name: String? = null, // User-friendly name
    val autoConnect: Boolean = false,
    val lastConnected: Long? = null,
    val isHealthy: Boolean = false,
    /**
     * The crux.casa deployment this server came from, when it came from one.
     *
     * Only Codespaces-backed servers actually need it: their forwarded port is private, so
     * every request carries a GitHub token as well as Basic Auth, and the token expires. It is
     * fetched on demand from `/api/deployments/{id}/connection` and held in memory — a secret
     * with an eight-hour life has no business in a DataStore-persisted, sync-exported model,
     * which is why only the id is stored here.
     */
    val cruxDeploymentId: String? = null,
    /**
     * Where new sessions start, when this server was created from a repository.
     *
     * Sent as `x-opencode-directory`, which is how opencode resolves a session's project. Null
     * for a server with nothing checked out, which starts wherever the server itself does.
     */
    val defaultDirectory: String? = null
) {
    val displayName: String
        get() = name ?: url
    
    val host: String
        get() = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore(":")
        }
    
    val port: Int
        get() = try {
            val parsed = java.net.URL(url)
            val explicitPort = parsed.port
            if (explicitPort != -1) {
                explicitPort
            } else {
                parsed.defaultPort // 80 for http, 443 for https
            }
        } catch (e: Exception) {
            url.substringAfterLast(":").toIntOrNull() ?: 80
        }
}

/**
 * Server Health - result of health check
 */
@Serializable
data class ServerHealth(
    val healthy: Boolean,
    val version: String? = null
)
