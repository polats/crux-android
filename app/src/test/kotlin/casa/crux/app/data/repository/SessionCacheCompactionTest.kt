package casa.crux.app.data.repository

import casa.crux.app.domain.model.FileDiff
import casa.crux.app.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCacheCompactionTest {
    @Test
    fun `session cache keeps summary counts but drops heavy details`() {
        val session = Session(
            id = "session",
            time = Session.Time(created = 1, updated = 2),
            summary = Session.Summary(
                additions = 3,
                deletions = 2,
                files = 1,
                diffs = listOf(FileDiff(file = "large.kt", before = "before", after = "after")),
            ),
            permission = listOf(Session.PermissionRule(permission = "read")),
        )

        val compacted = compactSessionForCache(session)

        assertEquals(3, compacted.summary?.additions)
        assertEquals(2, compacted.summary?.deletions)
        assertNull(compacted.summary?.diffs)
        assertNull(compacted.permission)
    }
}
