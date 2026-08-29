package casa.crux.app.data.crux

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Raised when the token is gone or revoked, so callers can drop to signed-out. */
class CruxUnauthorizedException(message: String) : Exception(message)

class CruxApiException(message: String) : Exception(message)

/**
 * The crux.casa HTTP surface.
 *
 * The bearer is attached per request rather than through Ktor's Auth plugin, matching how
 * [casa.crux.app.data.api.ServerConnection] attaches Basic auth — the shared client is used
 * by every server at once, so it can hold no single credential.
 */
@Singleton
class CruxApi @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    private fun url(path: String) = "$BASE_URL$path"

    private suspend fun HttpResponse.decodeOrThrow(): String {
        val text = bodyAsText()
        if (status == HttpStatusCode.Unauthorized) {
            throw CruxUnauthorizedException(errorMessage(text) ?: "Sign in to Crux again")
        }
        if (!status.isSuccess()) {
            throw CruxApiException(errorMessage(text) ?: "Crux request failed (${status.value})")
        }
        return text
    }

    /** The API reports failures as `{ "error": "..." }`; fall back to the raw body. */
    private fun errorMessage(text: String): String? = runCatching {
        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull() ?: text.takeIf { it.isNotBlank() && it.length < 300 }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    // ------------------------------------------------------------------ auth

    fun authorizeUrl(provider: String, challenge: String): String =
        "${url("/auth/native/login")}?provider=$provider&challenge=$challenge&method=S256"

    suspend fun exchangeCode(code: String, verifier: String, deviceLabel: String): CruxTokenResponse {
        val response = client.post(url("/auth/native/token")) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("verifier", JsonPrimitive(verifier))
                    put("deviceLabel", JsonPrimitive(deviceLabel))
                }.toString()
            )
        }
        return decode(response.decodeOrThrow())
    }

    // --------------------------------------------------------------- account

    suspend fun account(token: String): CruxAccount =
        decode(client.get(url("/api/session")) { bearer(token) }.decodeOrThrow())

    suspend fun switchProvider(token: String, provider: String): CruxAccount {
        val response = client.post(url("/api/session")) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("provider", JsonPrimitive(provider)) }.toString())
        }
        return decode(response.decodeOrThrow())
    }

    // ----------------------------------------------------------- deployments

    suspend fun deployments(token: String): List<CruxDeployment> =
        decode(client.get(url("/api/deployments")) { bearer(token) }.decodeOrThrow())

    suspend fun connection(token: String, id: String): CruxConnection =
        decode(client.get(url("/api/deployments/$id/connection")) { bearer(token) }.decodeOrThrow())

    suspend fun createDeployment(token: String, request: CruxCreateRequest): CruxDeployment {
        val response = client.post(url("/api/deployments")) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(request.toJson().toString())
        }
        return decode(response.decodeOrThrow())
    }

    suspend fun retryDeployment(token: String, id: String): CruxDeployment =
        decode(client.post(url("/api/deployments/$id")) { bearer(token) }.decodeOrThrow())

    suspend fun deleteDeployment(token: String, id: String) {
        client.delete(url("/api/deployments/$id")) { bearer(token) }.decodeOrThrow()
    }

    // ------------------------------------------------------ templates, extra

    suspend fun templates(token: String): CruxTemplates =
        decode(client.get(url("/api/templates")) { bearer(token) }.decodeOrThrow())

    suspend fun workspaces(token: String): List<CruxWorkspace> =
        decode(client.get(url("/api/railway/workspaces")) { bearer(token) }.decodeOrThrow())

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    companion object {
        const val BASE_URL = "https://crux.casa"
        const val CALLBACK_SCHEME = "crux"
    }
}

/** The create form, in the two shapes the API accepts. */
sealed interface CruxCreateRequest {
    val password: String?
    val templateId: String?

    data class HuggingFace(
        val repoId: String,
        override val password: String? = null,
        override val templateId: String? = null,
    ) : CruxCreateRequest

    data class Railway(
        val name: String,
        val workspaceId: String,
        override val password: String? = null,
        override val templateId: String? = null,
    ) : CruxCreateRequest
}

internal fun CruxCreateRequest.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is CruxCreateRequest.HuggingFace -> put("repoId", JsonPrimitive(repoId))
        is CruxCreateRequest.Railway -> {
            put("name", JsonPrimitive(name))
            put("workspaceId", JsonPrimitive(workspaceId))
        }
    }
    password?.takeIf { it.isNotBlank() }?.let { put("password", JsonPrimitive(it)) }
    templateId?.takeIf { it.isNotBlank() }?.let { put("templateId", JsonPrimitive(it)) }
}

/**
 * PKCE, generated on the device. The verifier never leaves it, which is what stops another
 * app that registers `crux://` from exchanging an intercepted code.
 */
object CruxPkce {
    fun newVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

private fun HttpStatusCode.isSuccess() = value in 200..299
