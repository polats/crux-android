package casa.crux.app.ui.screens.deployments

import casa.crux.app.data.crux.CruxAccount
import casa.crux.app.data.crux.cruxAuthCode
import casa.crux.app.data.crux.CruxCreateRequest
import casa.crux.app.data.crux.CruxDeployment
import casa.crux.app.data.crux.CruxDeploymentStatus
import casa.crux.app.data.crux.CruxIdentity
import casa.crux.app.data.crux.CruxIntent
import casa.crux.app.ui.screens.account.providerLabel
import casa.crux.app.data.crux.CruxTemplate
import casa.crux.app.data.crux.CruxWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentsLogicTest {

    private fun account(provider: String?, username: String? = "polats") = CruxAccount(
        activeProvider = provider,
        activeIdentity = provider?.let { CruxIdentity(provider = it, username = username) },
    )

    private fun deployment(id: String, state: String, createdAt: String) =
        CruxDeployment(id = id, state = state, createdAt = createdAt, repoId = "owner/$id")

    @Test
    fun `status parsing tolerates case and unknown values`() {
        assertEquals(CruxDeploymentStatus.RUNNING, CruxDeploymentStatus.from("RUNNING"))
        assertEquals(CruxDeploymentStatus.RUNNING, CruxDeploymentStatus.from("running"))
        assertEquals(CruxDeploymentStatus.UNKNOWN, CruxDeploymentStatus.from("something-new"))
        assertEquals(CruxDeploymentStatus.UNKNOWN, CruxDeploymentStatus.from(null))
    }

    @Test
    fun `only a running deployment can be connected`() {
        assertTrue(CruxDeploymentStatus.RUNNING.isConnectable)
        assertFalse(CruxDeploymentStatus.QUEUED.isConnectable)
        assertFalse(CruxDeploymentStatus.ERROR.isConnectable)
    }

    @Test
    fun `queued and provisioning keep the list polling, settled states do not`() {
        assertTrue(CruxDeploymentStatus.QUEUED.isPending)
        assertTrue(CruxDeploymentStatus.PROVISIONING.isPending)
        assertTrue(CruxDeploymentStatus.DELETING.isPending)
        assertFalse(CruxDeploymentStatus.RUNNING.isPending)
        assertFalse(CruxDeploymentStatus.ERROR.isPending)
    }

    @Test
    fun `in-flight deployments sort above settled ones, newest first within each`() {
        val ordered = orderDeployments(
            listOf(
                deployment("old-running", "RUNNING", "2026-01-01"),
                deployment("new-running", "RUNNING", "2026-03-01"),
                deployment("provisioning", "PROVISIONING", "2026-02-01"),
            )
        )
        assertEquals(listOf("provisioning", "new-running", "old-running"), ordered.map { it.id })
    }

    @Test
    fun `a space name allows letters numbers and hyphens only`() {
        assertTrue(isValidSpaceName("my-opencode"))
        assertTrue(isValidSpaceName("Space1"))
        assertFalse(isValidSpaceName(""))
        assertFalse(isValidSpaceName("-leading"))
        assertFalse(isValidSpaceName("has space"))
        assertFalse(isValidSpaceName("under_score"))
        assertFalse(isValidSpaceName("a".repeat(64)))
    }

    @Test
    fun `github alone is not a deploy target`() {
        assertEquals("huggingface", createTargetFor(account("huggingface")))
        assertEquals("railway", createTargetFor(account("railway")))
        assertNull(createTargetFor(account("github")))
        assertNull(createTargetFor(null))
    }

    @Test
    fun `a hugging face repo id is scoped to the signed-in owner`() {
        assertEquals("polats/my-space", huggingFaceRepoId(account("huggingface"), "my-space"))
        assertEquals("polats/my-space", huggingFaceRepoId(account("huggingface"), "  my-space  "))
        assertNull(huggingFaceRepoId(account("huggingface"), "bad name"))
        assertNull(huggingFaceRepoId(account("huggingface", username = null), "my-space"))
        assertNull(huggingFaceRepoId(null, "my-space"))
    }

    @Test
    fun `the create form builds the shape each provider expects`() {
        val hf = buildRequest(
            provider = "huggingface",
            account = account("huggingface"),
            name = "my-space",
            workspace = null,
            template = CruxTemplate(id = "t1", repo = "polats/opencode-cloud"),
            password = "hunter2",
        )
        assertTrue(hf is CruxCreateRequest.HuggingFace)
        hf as CruxCreateRequest.HuggingFace
        assertEquals("polats/my-space", hf.repoId)
        assertEquals("hunter2", hf.password)
        assertEquals("t1", hf.templateId)

        val railway = buildRequest(
            provider = "railway",
            account = account("railway"),
            name = "my-space",
            workspace = CruxWorkspace(id = "ws-1", name = "Personal"),
            template = null,
            password = "",
        )
        assertTrue(railway is CruxCreateRequest.Railway)
        railway as CruxCreateRequest.Railway
        assertEquals("my-space", railway.name)
        assertEquals("ws-1", railway.workspaceId)
        // An empty password means "generate one", so it must not be sent as an empty string.
        assertNull(railway.password)
    }

    @Test
    fun `the create form refuses input the API would reject`() {
        assertNull(
            buildRequest("railway", account("railway"), "my-space", workspace = null, template = null, password = "")
        )
        assertNull(
            buildRequest("huggingface", account("huggingface"), "bad name", null, null, "")
        )
        assertNull(
            buildRequest("github", account("github"), "my-space", null, null, "")
        )
    }

    @Test
    fun `a template reference round-trips the form the API accepts`() {
        assertEquals("owner/name", CruxTemplate(repo = "owner/name").reference)
        assertEquals("owner/name@main", CruxTemplate(repo = "owner/name", ref = "main").reference)
        assertEquals("owner/name", CruxTemplate(repo = "owner/name").label)
        assertEquals("Mine", CruxTemplate(repo = "owner/name", name = "Mine").label)
    }

    @Test
    fun `a deployment is named after its space, not its full repo path`() {
        assertEquals("my-space", CruxDeployment(id = "d1", repoId = "polats/my-space").displayName)
        // Railway deployments carry a synthetic repoId, so fall back to something stable.
        assertEquals("d1", CruxDeployment(id = "d1", repoId = null).displayName)
    }

    @Test
    fun `only a crux auth callback yields a code`() {
        assertEquals("abc", cruxAuthCode("crux", "auth", "abc"))
        assertEquals("abc", cruxAuthCode("CRUX", "AUTH", "abc"))
        // Another app's scheme, or another host of ours, must never be read as a sign-in.
        assertNull(cruxAuthCode("https", "auth", "abc"))
        assertNull(cruxAuthCode("crux", "deployment", "abc"))
        assertNull(cruxAuthCode(null, null, "abc"))
        // The provider error redirect carries no code.
        assertNull(cruxAuthCode("crux", "auth", null))
        assertNull(cruxAuthCode("crux", "auth", ""))
    }

    private fun accountWith(vararg providers: String, configured: List<String> = providers.toList()) =
        CruxAccount(
            identities = providers.map { CruxIdentity(provider = it, username = "polats") },
            activeProvider = providers.firstOrNull { it != "github" },
            activeIdentity = providers.firstOrNull { it != "github" }
                ?.let { CruxIdentity(provider = it, username = "polats") },
            providers = configured.associateWith { true },
        )

    @Test
    fun `only unlinked configured providers can be connected`() {
        // GitHub already linked, so offering to "connect GitHub" again is meaningless.
        assertEquals(
            listOf("railway"),
            linkableProviders(accountWith("huggingface", "github", configured = listOf("huggingface", "railway", "github")))
        )
        assertTrue(linkableProviders(accountWith("huggingface", "railway", "github")).isEmpty())
        assertTrue(linkableProviders(null).isEmpty())
    }

    @Test
    fun `github is never a deploy target`() {
        assertEquals(listOf("huggingface"), deployableIdentities(accountWith("huggingface", "github")).map { it.provider })
        assertTrue(deployableIdentities(accountWith("github")).isEmpty())
    }

    @Test
    fun `the deploy selector appears only when there is a choice`() {
        assertFalse(showsDeployTarget(accountWith("huggingface")))
        // GitHub does not count towards having a choice.
        assertFalse(showsDeployTarget(accountWith("huggingface", "github")))
        assertTrue(showsDeployTarget(accountWith("huggingface", "railway")))
        assertFalse(showsDeployTarget(null))
    }

    @Test
    fun `an account-changing login is explained rather than silent`() {
        assertTrue(outcomeNotice("switch")!!.contains("different Crux account"))
        assertTrue(outcomeNotice("absorb")!!.contains("Nothing was lost"))
        assertTrue(outcomeNotice("link")!!.isNotBlank())
        // A plain sign-in or signup needs no explanation.
        assertNull(outcomeNotice("signin"))
        assertNull(outcomeNotice("signup"))
        assertNull(outcomeNotice(null))
    }

    @Test
    fun `each intent sends the wire value the server expects`() {
        assertEquals("signin", CruxIntent.SIGN_IN.wire)
        assertEquals("link", CruxIntent.LINK.wire)
        assertEquals("switch", CruxIntent.SWITCH.wire)
    }

    @Test
    fun `provider labels are human readable`() {
        assertEquals("Hugging Face", providerLabel("huggingface"))
        assertEquals("Railway", providerLabel("railway"))
        assertEquals("GitHub", providerLabel("github"))
        assertEquals("unknown", providerLabel("unknown"))
    }

    @Test
    fun `every status has a label`() {
        CruxDeploymentStatus.entries.forEach { status ->
            assertTrue(status.name, statusLabel(status).isNotBlank())
        }
    }
}
