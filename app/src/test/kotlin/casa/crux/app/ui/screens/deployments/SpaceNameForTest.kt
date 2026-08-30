package casa.crux.app.ui.screens.deployments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name a chosen repository gives a space. */
class SpaceNameForTest {

    @Test
    fun `the repository's own short name is used`() {
        assertEquals("crux-android", spaceNameFor("polats/crux-android"))
        assertEquals("opencode-cloud", spaceNameFor("polats/opencode-cloud"))
    }

    @Test
    fun `a repository name is not a space name`() {
        // Dots are legal in a repository and rejected by the deployment API, so lifting the
        // name straight across would fail at create time. This one is in the author's own list.
        assertEquals("crux-casa", spaceNameFor("polats/crux.casa"))
        assertEquals("my-thing", spaceNameFor("owner/my_thing"))
        assertEquals("tacticaarena", spaceNameFor("mindless/TacticaArena"))
        assertTrue(isValidSpaceName(spaceNameFor("polats/crux.casa")!!))
        assertTrue(isValidSpaceName(spaceNameFor("owner/.dotfiles")!!))
    }

    @Test
    fun `a name that is already taken gains a differentiator`() {
        val taken = listOf("crux-android")
        assertEquals("crux-android-2", spaceNameFor("polats/crux-android", taken))
        assertEquals(
            "crux-android-4",
            spaceNameFor("polats/crux-android", taken + listOf("crux-android-2", "crux-android-3")),
        )
    }

    @Test
    fun `matching an existing name ignores case`() {
        assertEquals("crux-android-2", spaceNameFor("polats/Crux-Android", listOf("CRUX-ANDROID")))
    }

    @Test
    fun `a name too long to be legal is cut, and stays legal once numbered`() {
        val long = "owner/" + "a".repeat(80)
        val first = spaceNameFor(long)!!
        assertEquals(63, first.length)
        assertTrue(isValidSpaceName(first))

        val second = spaceNameFor(long, listOf(first))!!
        // Room is made for the suffix rather than the name growing past what the API accepts.
        assertTrue(second.length <= 63)
        assertTrue(isValidSpaceName(second))
        assertTrue(second.endsWith("-2"))
    }

    @Test
    fun `a repository with no usable characters gives nothing`() {
        // Better to fall back to the random suggestion than to offer a name that cannot be used.
        assertNull(spaceNameFor("owner/..."))
        assertNull(spaceNameFor("owner/"))
    }
}
