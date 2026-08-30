package casa.crux.app.data.crux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clone command, which runs inside a container whose whole purpose is executing shell
 * commands — so what it does and does not contain matters.
 */
class WorkspaceCheckoutTest {

    @Test
    fun `an authenticated clone never writes the token into the command`() {
        val command = cloneCommand("polats/secret", "/workspace/secret", authenticated = true)
        // The token is read into a shell variable with `read -rs`, which does not echo. Only
        // the variable's name appears here; the credential itself is sent separately.
        assertTrue(command.contains("\$CRUX_TOKEN"))
        assertTrue("the token must be cleared after use", command.contains("unset CRUX_TOKEN"))
    }

    @Test
    fun `the token is stripped from the remote after cloning`() {
        val command = cloneCommand("polats/secret", "/workspace/secret", authenticated = true)
        // Left in place it would sit in .git/config, long-lived and with `repo` scope, inside a
        // container that runs arbitrary commands.
        assertTrue(
            command.contains("remote set-url origin 'https://github.com/polats/secret.git'"),
        )
    }

    @Test
    fun `a public clone carries no credential at all`() {
        val command = cloneCommand("polats/open", "/workspace/open", authenticated = false)
        assertFalse(command.contains("CRUX_TOKEN"))
        assertFalse(command.contains("x-access-token"))
        assertTrue(command.contains("https://github.com/polats/open.git"))
    }

    @Test
    fun `an existing checkout is left alone`() {
        val command = cloneCommand("polats/open", "/workspace/open", authenticated = false)
        // Opening a space twice must not fail the second time, nor discard work done in the
        // first by re-cloning over it.
        assertTrue(command.contains("if [ -d '/workspace/open/.git' ]"))
    }

    @Test
    fun `the outcome is reported with the clone's own exit code`() {
        // Not the exit code of the unset or the set-url that follow it, which would report
        // success for a clone that failed.
        val command = cloneCommand("polats/secret", "/workspace/secret", authenticated = true)
        assertTrue(command.contains("rc=\$?"))
        assertTrue(command.trimEnd().endsWith("echo ${CLONE_MARKER_PREFIX}\$rc"))
        assertTrue(command.indexOf("rc=\$?") < command.indexOf("unset CRUX_TOKEN"))
        assertTrue(command.indexOf("rc=\$?") < command.indexOf("remote set-url"))
    }

    @Test
    fun `the completion marker is recognised, and only with its code`() {
        assertTrue(CLONE_MARKER.containsMatchIn("${CLONE_MARKER_PREFIX}0"))
        assertTrue(CLONE_MARKER.containsMatchIn("noise ${CLONE_MARKER_PREFIX}128 noise"))
        // The echoed command line itself contains the prefix; without a number it is not a result.
        assertFalse(CLONE_MARKER.containsMatchIn("echo ${CLONE_MARKER_PREFIX}\$rc"))
    }
}
