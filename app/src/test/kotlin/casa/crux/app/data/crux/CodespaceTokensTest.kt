package casa.crux.app.data.crux

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub token a Codespaces-backed server needs on every request.
 *
 * The awkward parts are all about *not* losing a token: the saved-server list re-emits on
 * anything as ordinary as a health check, and a wipe there would send every request through a
 * needless refresh.
 */
class CodespaceTokensTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun `a codespaces connection carries a token, an ordinary one does not`() {
        val codespace = json.decodeFromString(
            CruxConnection.serializer(),
            """{"id":"d1","username":"opencode","password":"pw",
                "appUrl":"https://fluffy-space-123-7860.app.github.dev",
                "githubToken":"gho_abc","needsToken":true}"""
        )
        assertTrue(codespace.needsToken)
        assertEquals("gho_abc", codespace.githubToken)

        val space = json.decodeFromString(
            CruxConnection.serializer(),
            """{"id":"d2","username":"opencode","password":"pw","appUrl":"https://x.hf.space"}"""
        )
        assertTrue(!space.needsToken)
        assertNull(space.githubToken)
    }

    @Test
    fun `a host with no deployment behind it is left alone`() = runBlocking {
        val tokens = CodespaceTokens()
        tokens.refresher = { error("must not be asked for a token") }
        assertNull(tokens.tokenFor("my-server.local"))
    }

    @Test
    fun `a known token is served without a refresh`() = runBlocking {
        val tokens = CodespaceTokens()
        var refreshes = 0
        tokens.refresher = { refreshes++; "gho_refreshed" }
        tokens.remember("https://abc-7860.app.github.dev", "d1", "gho_first")

        assertEquals("gho_first", tokens.tokenFor("abc-7860.app.github.dev"))
        assertEquals(0, refreshes)
    }

    @Test
    fun `a host registered without a token fetches one on first use`() = runBlocking {
        val tokens = CodespaceTokens()
        var asked: String? = null
        tokens.refresher = { id -> asked = id; "gho_fetched" }
        tokens.rememberDeployment("https://abc-7860.app.github.dev", "d1")

        assertEquals("gho_fetched", tokens.tokenFor("abc-7860.app.github.dev"))
        assertEquals("d1", asked)
    }

    @Test
    fun `re-registering the same deployment does not discard its token`() = runBlocking {
        val tokens = CodespaceTokens()
        var refreshes = 0
        tokens.refresher = { refreshes++; "gho_refreshed" }
        tokens.remember("https://abc-7860.app.github.dev", "d1", "gho_first")

        // What the saved-server list does on every emission — which is often.
        repeat(5) { tokens.rememberDeployment("https://abc-7860.app.github.dev", "d1") }

        assertEquals("gho_first", tokens.tokenFor("abc-7860.app.github.dev"))
        assertEquals(0, refreshes)
    }

    @Test
    fun `a refresh that fails leaves no token rather than a stale one`() = runBlocking {
        val tokens = CodespaceTokens()
        tokens.remember("https://abc-7860.app.github.dev", "d1", "gho_first")
        tokens.refresher = { throw IllegalStateException("signed out") }

        assertNull(tokens.refresh("abc-7860.app.github.dev"))
        // The host is still known, so a later refresh can succeed without reconnecting.
        tokens.refresher = { "gho_second" }
        assertEquals("gho_second", tokens.tokenFor("abc-7860.app.github.dev"))
    }

    @Test
    fun `forgetting a server stops its host being treated as a codespace`() = runBlocking {
        val tokens = CodespaceTokens()
        tokens.refresher = { "gho_x" }
        tokens.remember("https://abc-7860.app.github.dev", "d1", "gho_first")
        tokens.forget("https://abc-7860.app.github.dev")
        assertNull(tokens.tokenFor("abc-7860.app.github.dev"))
    }
}
