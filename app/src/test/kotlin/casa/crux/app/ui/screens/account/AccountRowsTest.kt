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
    fun `connected accounts sort to the top`() {
        val rows = accountRows(account("github"))
        assertEquals("github", rows.first().provider)
        assertTrue(rows.first().connected)
        // The unconnected keep their stable order behind it.
        assertEquals(listOf("huggingface", "railway"), rows.drop(1).map { it.provider })
    }

    @Test
    fun `every configured provider still gets a row`() {
        assertEquals(ALL.sorted(), accountRows(account("github")).map { it.provider }.sorted())
    }

    @Test
    fun `an unconfigured provider is not offered at all`() {
        val rows = accountRows(account("huggingface", configured = listOf("huggingface", "railway")))
        assertEquals(listOf("huggingface", "railway"), rows.map { it.provider })
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
    fun `the only remaining account offers no disconnect at all`() {
        // Not disabled-with-a-reason: there is nothing useful to offer, so nothing is shown.
        val hf = accountRows(account("huggingface")).first { it.provider == "huggingface" }
        assertFalse(hf.canDisconnect)
        assertNull(hf.blockedReason)
    }

    @Test
    fun `with two linked, either can be disconnected`() {
        val rows = accountRows(account("huggingface", "github"))
        assertTrue(rows.first { it.provider == "huggingface" }.canDisconnect)
        assertNull(rows.first { it.provider == "huggingface" }.blockedReason)
        assertTrue(rows.first { it.provider == "github" }.canDisconnect)
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
    fun `the last account shows nothing even when it owns spaces`() {
        val hf = accountRows(account("huggingface"), spacesByProvider = mapOf("huggingface" to 3))
            .first { it.provider == "huggingface" }
        assertFalse(hf.canDisconnect)
        assertNull(hf.blockedReason)
    }

    @Test
    fun `signed out still offers every configured provider`() {
        val rows = accountRows(CruxAccount(providers = ALL.associateWith { true }))
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.connected })
        assertTrue(rows.none { it.blockedReason != null })
        assertTrue(rows.none { it.canDisconnect })
    }

    @Test
    fun `with no account at all, fall back to all three rather than showing nothing`() {
        val rows = accountRows(null)
        assertEquals(ALL, rows.map { it.provider })
    }
}
