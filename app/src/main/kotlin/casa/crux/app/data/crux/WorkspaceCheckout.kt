package casa.crux.app.data.crux

import android.util.Log
import casa.crux.app.data.api.OpenCodeApi
import casa.crux.app.data.api.ServerConnection
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clones a space's chosen repository into it, the first time the space is opened.
 *
 * Crux itself cannot do this: it has no shell into the container, which is the same reason the
 * server's password has to arrive as an environment variable or a Codespaces secret. So the
 * space records *which* repository it wants and the app carries it out on first connect.
 *
 * It runs through a PTY rather than `POST /session/{id}/shell`. That endpoint routes through
 * opencode's agent and needs an agent and a model — and a space nobody has configured a
 * provider on yet has neither, so setup would fail exactly when the space is new.
 */
@Singleton
class WorkspaceCheckout @Inject constructor(
    private val api: OpenCodeApi,
) {
    sealed interface Result {
        /** Already there, or freshly cloned. [path] is where new sessions should start. */
        data class Ready(val path: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * @param token a GitHub token, needed only for a private repository. A public clone is done
     *   anonymously so no credential is put in front of the shell at all.
     */
    suspend fun ensure(
        conn: ServerConnection,
        repo: String,
        token: String?,
        onProgress: suspend (String) -> Unit = {},
    ): Result {
        val name = repo.substringAfterLast('/').ifBlank { return Result.Failed("Bad repository name") }
        val root = runCatching { api.getServerPaths(conn) }.getOrNull()
            ?.let { it.worktree.ifBlank { it.directory.ifBlank { it.home } } }
            ?.trimEnd('/')
            ?: return Result.Failed("Could not read the server's paths")
        val path = "$root/$name"

        return try {
            withTimeout(CLONE_TIMEOUT_MS) { clone(conn, repo, token, path, onProgress) }
        } catch (e: TimeoutCancellationException) {
            Result.Failed("Timed out cloning $repo")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clone $repo", e)
            Result.Failed(e.message ?: "Could not clone $repo")
        }
    }

    private suspend fun clone(
        conn: ServerConnection,
        repo: String,
        token: String?,
        path: String,
        onProgress: suspend (String) -> Unit,
    ): Result {
        val pty = api.createPty(conn, title = "crux-setup", cwd = path.substringBeforeLast('/'))
        val socket = api.openPtySocket(conn, pty.id)
        var outcome: Int? = null
        try {
            // Read the token into a shell variable rather than writing it into the command:
            // `read -rs` does not echo, so the credential never appears in the terminal's
            // scrollback the way it would in a clone URL typed out in full.
            if (token != null) {
                socket.send("read -rs CRUX_TOKEN\n")
                socket.send("$token\n")
            }
            socket.send(cloneCommand(repo, path, authenticated = token != null) + "\n")

            socket.readLoop { text ->
                text.lineSequence().forEach { line ->
                    CLONE_MARKER.find(line)?.let { outcome = it.groupValues[1].toIntOrNull() }
                    line.trim().takeIf { it.isNotEmpty() && !it.startsWith(CLONE_MARKER_PREFIX) }
                        ?.let { onProgress(it) }
                }
                if (outcome != null) socket.close()
            }
        } finally {
            runCatching { socket.close() }
            runCatching { api.removePty(conn, pty.id) }
        }

        return when (outcome) {
            0 -> Result.Ready(path)
            null -> Result.Failed("The clone did not report a result")
            else -> Result.Failed("git clone exited with $outcome")
        }
    }

    internal companion object {
        const val TAG = "WorkspaceCheckout"
        const val CLONE_TIMEOUT_MS = 180_000L
    }
}

/**
 * Idempotent: an existing checkout is left alone, so opening a space twice does not fail
 * the second time or throw away work done in the first.
 *
 * The token is stripped from the remote immediately after cloning. It is long-lived and has
 * `repo` scope, and `.git/config` inside a container whose whole purpose is running shell
 * commands is not where it should live.
 */
internal fun cloneCommand(repo: String, path: String, authenticated: Boolean): String {
    val origin = "https://github.com/$repo.git"
    val source = if (authenticated) "https://x-access-token:\$CRUX_TOKEN@github.com/$repo.git" else origin
    return buildString {
        append("if [ -d '$path/.git' ]; then rc=0; ")
        append("else git clone --depth 1 '$source' '$path'; rc=\$?; fi; ")
        // Only when there was one: a public clone should mention no credential at all.
        if (authenticated) append("unset CRUX_TOKEN; ")
        append("if [ \$rc -eq 0 ]; then git -C '$path' remote set-url origin '$origin' 2>/dev/null; fi; ")
        append("echo $CLONE_MARKER_PREFIX\$rc")
    }
}

/** How the shell reports the clone's own exit code back through the terminal. */
internal const val CLONE_MARKER_PREFIX = "CRUX_CLONE_DONE_"
internal val CLONE_MARKER = Regex("$CLONE_MARKER_PREFIX(\\d+)")
