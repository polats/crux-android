package casa.crux.app.ui.screens.account

import casa.crux.app.R
import casa.crux.app.data.crux.CruxAccount
import casa.crux.app.data.crux.CruxIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRowsTest {

    private fun account(vararg linked: String, configured: List<String> = ALL) = CruxAccount(
        identities = linked.map { CruxIdentity(provider = it, username = "polats") },
        providers = configured.associateWith { true },
    )

    private companion object {
        val ALL = listOf("huggingface", "railway", "github")
    }

    @Test
    fun `the order is fixed, whatever is connected`() {
        // GitHub first because it is the way in, and nothing moves as accounts come and go:
        // a list that rearranges under you is one you have to re-read to use.
        val expected = listOf("github", "huggingface", "railway")
        assertEquals(expected, accountRows(account("github")).map { it.provider })
        assertEquals(expected, accountRows(account("railway", "github")).map { it.provider })
        assertEquals(expected, accountRows(account("huggingface", "railway", "github")).map { it.provider })
        // Connecting one must not move the others.
        assertEquals(expected, accountRows(account("github")).map { it.provider })
    }

    @Test
    fun `every configured provider still gets a row`() {
        assertEquals(ALL.sorted(), accountRows(account("github")).map { it.provider }.sorted())
    }

    @Test
    fun `an unconfigured provider is not offered at all`() {
        val rows = accountRows(account("github", "huggingface", configured = listOf("github", "huggingface")))
        assertEquals(listOf("github", "huggingface"), rows.map { it.provider })
    }

    @Test
    fun `rows report which accounts are connected, and as whom`() {
        val rows = accountRows(account("huggingface", "github")).associateBy { it.provider }
        assertTrue(rows.getValue("huggingface").connected)
        assertEquals("polats", rows.getValue("huggingface").username)
        assertFalse(rows.getValue("railway").connected)
        assertNull(rows.getValue("railway").username)
    }

    @Test
    fun `an unconnected provider is never blocked, it is connectable`() {
        val railway = accountRows(account("huggingface", "github")).first { it.provider == "railway" }
        assertNull(railway.blockedReason)
        assertFalse(railway.canDisconnect)
    }

    @Test
    fun `github never offers a disconnect, because it is the way in`() {
        // Not disabled-with-a-reason: there is nothing useful to offer, so nothing is shown.
        // Removing it would leave the account with no way to sign in; sign-out covers that.
        val onlyGithub = accountRows(account("github")).first { it.provider == "github" }
        assertFalse(onlyGithub.canDisconnect)
        assertNull(onlyGithub.blockedReason)

        val alsoGithub = accountRows(account("github", "huggingface")).first { it.provider == "github" }
        assertFalse(alsoGithub.canDisconnect)
        assertNull(alsoGithub.blockedReason)
    }

    @Test
    fun `a connected side provider can be disconnected`() {
        val rows = accountRows(account("huggingface", "github"))
        assertTrue(rows.first { it.provider == "huggingface" }.canDisconnect)
        assertNull(rows.first { it.provider == "huggingface" }.blockedReason)
    }

    @Test
    fun `an account still holding spaces cannot be disconnected`() {
        // Unlinking deletes the credential Crux needs to manage or delete those spaces,
        // so they would keep running and keep costing money with no way to reach them.
        val rows = accountRows(
            account("huggingface", "github"),
            spacesByProvider = mapOf("huggingface" to 2),
        )
        assertEquals(
            R.string.deployments_account_blocked_spaces,
            rows.first { it.provider == "huggingface" }.blockedReason,
        )
        assertNull(rows.first { it.provider == "github" }.blockedReason)
    }

    @Test
    fun `a zero space count does not block`() {
        val rows = accountRows(
            account("huggingface", "github"),
            spacesByProvider = mapOf("huggingface" to 0),
        )
        assertNull(rows.first { it.provider == "huggingface" }.blockedReason)
    }

    @Test
    fun `github owning spaces still shows nothing, since it cannot be disconnected anyway`() {
        val gh = accountRows(account("github"), spacesByProvider = mapOf("github" to 3))
            .first { it.provider == "github" }
        assertFalse(gh.canDisconnect)
        assertNull(gh.blockedReason)
    }

    @Test
    fun `signed out offers github alone`() {
        // The other two can be connected to an account but cannot create one, so offering them
        // here would be offering a sign-in the server refuses.
        val rows = accountRows(CruxAccount(providers = ALL.associateWith { true }))
        assertEquals(listOf("github"), rows.map { it.provider })
        assertTrue(rows.none { it.connected })
        assertTrue(rows.none { it.canDisconnect })
    }

    @Test
    fun `with no account at all, github is still the way in`() {
        assertEquals(listOf("github"), accountRows(null).map { it.provider })
    }

    @Test
    fun `signing in reveals the other two as connectable`() {
        val rows = accountRows(account("github"))
        assertEquals(ALL.sorted(), rows.map { it.provider }.sorted())
        assertTrue(rows.first { it.provider == "huggingface" }.let { !it.connected && !it.canDisconnect })
    }
}
