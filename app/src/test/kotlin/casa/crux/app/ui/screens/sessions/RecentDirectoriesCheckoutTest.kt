package casa.crux.app.ui.screens.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checkout a space was created from has to be offered for a new session even before any
 * session exists in it — which is exactly its state after being cloned, and the whole reason
 * a repository was chosen.
 */
class RecentDirectoriesCheckoutTest {

    @Test
    fun `the checkout leads even with no sessions in it`() {
        val entries = recentSessionDirectories(
            sessions = emptyList(),
            checkout = "/workspaces/.opencode-state/workspace/opencode-cloud",
        )
        assertEquals(1, entries.size)
        assertEquals("opencode-cloud", entries.first().name)
        assertEquals(0, entries.first().count)
    }

    @Test
    fun `a checkout that already has sessions is not listed twice`() {
        val checkout = "/home/node/workspace/repo"
        val entries = recentSessionDirectories(sessions = emptyList(), checkout = checkout)
        assertEquals(1, entries.count { it.directory.trimEnd('/') == checkout })
    }

    @Test
    fun `without a checkout nothing is invented`() {
        assertTrue(recentSessionDirectories(sessions = emptyList(), checkout = null).isEmpty())
        assertTrue(recentSessionDirectories(sessions = emptyList(), checkout = "  ").isEmpty())
    }
}
