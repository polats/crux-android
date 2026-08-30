package casa.crux.app.data.crux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal output, made fit for a status line. */
class PlainTextTest {

    private val esc = '\u001B'

    @Test
    fun `colour codes are removed`() {
        assertEquals("Cloning into 'repo'...", plainText("$esc[32mCloning into 'repo'...$esc[0m"))
    }

    @Test
    fun `a progress redraw leaves only its text`() {
        // git rewrites this line in place with cursor moves and carriage returns. Shown raw,
        // that was the garbling on the card's status line.
        val raw = "$esc[KReceiving objects:  47% (5/12)\r$esc[K"
        assertEquals("Receiving objects:  47% (5/12)", plainText(raw))
    }

    @Test
    fun `a line of nothing but control codes disappears`() {
        assertTrue(plainText("$esc[2K").isEmpty())
        assertTrue(plainText("\u0007").isEmpty())
    }

    @Test
    fun `ordinary text is left alone`() {
        assertEquals("remote: Enumerating objects: 42", plainText("remote: Enumerating objects: 42"))
    }
}
