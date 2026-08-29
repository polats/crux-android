package casa.crux.app.ui.screens.server

import casa.crux.app.data.api.McpStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class McpServerItemsTest {
    @Test
    fun `mcp servers are mapped and sorted by name`() {
        val items = mcpItems(
            mapOf(
                "Zeta" to McpStatus(status = "failed", error = "offline"),
                "alpha" to McpStatus(status = "connected"),
            )
        )

        assertEquals(listOf("alpha", "Zeta"), items.map(McpServerItem::name))
        assertEquals("offline", items.last().error)
    }
}
