package casa.crux.app.data.crux

import casa.crux.app.ui.screens.deployments.configuredProviders
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes payloads captured verbatim from crux.casa.
 *
 * These exist because the models were first written from the API docs and `providers` was
 * modelled as a list when the server sends a map — which compiled, passed every other test,
 * and only failed on a real device after a real sign-in. A shape mismatch should fail here.
 */
class CruxPayloadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `signed-out session decodes`() {
        val payload = """
            {"user":null,"identities":[],"activeProvider":null,
             "providers":{"huggingface":true,"railway":true,"github":true}}
        """.trimIndent()
        val account = json.decodeFromString<CruxAccount>(payload)
        assertNull(account.user)
        assertTrue(account.identities.isEmpty())
        assertEquals(mapOf("huggingface" to true, "railway" to true, "github" to true), account.providers)
    }

    @Test
    fun `signed-in session decodes, including the fields spread from accountFor`() {
        val payload = """
            {"user":{"id":"e6f1","username":"polats"},
             "providers":{"huggingface":true,"railway":true,"github":false},
             "userId":"e6f1",
             "identities":[
               {"provider":"huggingface","username":"polats","avatarUrl":"https://x/y.png","linkedAt":"2026-08-29T18:00:00.000Z"},
               {"provider":"github","username":"polats","avatarUrl":null,"linkedAt":"2026-08-29T18:05:00.000Z"}],
             "activeProvider":"huggingface",
             "activeIdentity":{"provider":"huggingface","username":"polats","avatarUrl":null,"linkedAt":"2026-08-29T18:00:00.000Z"},
             "stale":false}
        """.trimIndent()
        val account = json.decodeFromString<CruxAccount>(payload)
        assertEquals("e6f1", account.userId)
        assertEquals("polats", account.user?.username)
        assertEquals(2, account.identities.size)
        assertEquals("huggingface", account.activeProvider)
        assertEquals("polats", account.activeIdentity?.username)
        // GitHub is not configured here, so it must not be offered.
        assertEquals(listOf("huggingface", "railway"), configuredProviders(account))
    }

    @Test
    fun `a stale session carries a loginUrl the app does not model`() {
        val payload = """
            {"user":{"id":"e6f1","username":"polats"},"providers":{"huggingface":true},
             "userId":"e6f1","identities":[],"activeProvider":"huggingface",
             "stale":true,"loginUrl":"/auth/login"}
        """.trimIndent()
        val account = json.decodeFromString<CruxAccount>(payload)
        assertTrue(account.stale)
    }

    @Test
    fun `a deployment list decodes both provider shapes`() {
        val payload = """
            [{"id":"d1","provider":"huggingface","repoId":"polats/my-space",
              "template":{"repo":"polats/opencode-cloud","ref":""},
              "username":"opencode","state":"RUNNING","error":null,
              "appUrl":"https://polats-my-space.hf.space",
              "createdAt":"2026-08-29T18:00:00.000Z","updatedAt":"2026-08-29T18:04:00.000Z"},
             {"id":"d2","provider":"railway","repoId":"my-railway-space",
              "template":{"repo":"polats/opencode-cloud","ref":"main"},
              "username":"opencode","state":"PROVISIONING","error":null,"appUrl":null,
              "createdAt":"2026-08-29T18:02:00.000Z","updatedAt":"2026-08-29T18:02:30.000Z"}]
        """.trimIndent()
        val deployments = json.decodeFromString<List<CruxDeployment>>(payload)
        assertEquals(2, deployments.size)
        assertEquals(CruxDeploymentStatus.RUNNING, deployments[0].status)
        assertEquals("my-space", deployments[0].displayName)
        assertEquals(CruxDeploymentStatus.PROVISIONING, deployments[1].status)
        // Railway repoId has no slash, so the whole value is the name.
        assertEquals("my-railway-space", deployments[1].displayName)
        assertNull(deployments[1].appUrl)
    }

    @Test
    fun `an errored deployment decodes with its message`() {
        val payload = """
            [{"id":"d3","provider":"huggingface","repoId":"polats/broken",
              "template":{"repo":"polats/opencode-cloud","ref":""},"username":"opencode",
              "state":"ERROR","error":"Space build failed","appUrl":null,
              "createdAt":"2026-08-29T18:00:00.000Z","updatedAt":"2026-08-29T18:01:00.000Z"}]
        """.trimIndent()
        val deployments = json.decodeFromString<List<CruxDeployment>>(payload)
        assertEquals(CruxDeploymentStatus.ERROR, deployments[0].status)
        assertEquals("Space build failed", deployments[0].error)
    }

    @Test
    fun `the connection payload carries what a server needs`() {
        val payload = """
            {"id":"d1","username":"opencode","password":"s3cret",
             "appUrl":"https://polats-my-space.hf.space"}
        """.trimIndent()
        val connection = json.decodeFromString<CruxConnection>(payload)
        assertEquals("opencode", connection.username)
        assertEquals("s3cret", connection.password)
        assertEquals("https://polats-my-space.hf.space", connection.appUrl)
    }

    @Test
    fun `templates decode, and the server label wins over the composed one`() {
        val payload = """
            {"default":{"id":"default","name":"opencode-cloud","repo":"polats/opencode-cloud",
                        "ref":"","label":"polats/opencode-cloud","private":false,"isDefault":true},
             "templates":[{"id":"t1","name":"Mine","repo":"me/thing","ref":"main",
                           "label":"Mine (me/thing@main)","private":true,
                           "createdAt":"2026-08-29T00:00:00.000Z"}],
             "github":{"linked":true,"username":"polats","stale":false}}
        """.trimIndent()
        val templates = json.decodeFromString<CruxTemplates>(payload)
        assertEquals("polats/opencode-cloud", templates.default?.repo)
        assertEquals(1, templates.templates.size)
        assertEquals("Mine (me/thing@main)", templates.templates[0].label)
        assertEquals("me/thing@main", templates.templates[0].reference)
        assertTrue(templates.templates[0].isPrivate)
        assertTrue(templates.github?.linked == true)
    }

    @Test
    fun `an unlinked github status decodes without the optional urls`() {
        val templates = json.decodeFromString<CruxTemplates>(
            """{"default":null,"templates":[],"github":{"linked":false,"username":null,"stale":false}}"""
        )
        assertEquals(false, templates.github?.linked)
        assertNull(templates.github?.connectUrl)
    }

    @Test
    fun `railway workspaces decode`() {
        val workspaces = json.decodeFromString<List<CruxWorkspace>>(
            """[{"id":"ws-1","name":"Personal"},{"id":"ws-2","name":null}]"""
        )
        assertEquals("ws-1", workspaces[0].id)
        assertNull(workspaces[1].name)
    }

    @Test
    fun `the native token response decodes`() {
        val issued = json.decodeFromString<CruxTokenResponse>(
            """{"token":"abc.def","user":{"id":"hf-1","username":"polats"},
                "userId":"e6f1","activeProvider":"huggingface"}"""
        )
        assertEquals("abc.def", issued.token)
        assertEquals("polats", issued.user?.username)
        assertEquals("huggingface", issued.activeProvider)
    }

    @Test
    fun `a future deployment state does not crash the list`() {
        val deployments = json.decodeFromString<List<CruxDeployment>>(
            """[{"id":"d9","state":"SOMETHING_NEW"}]"""
        )
        assertEquals(CruxDeploymentStatus.UNKNOWN, deployments[0].status)
    }
}
