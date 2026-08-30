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

        return try {
            withTimeout(CLONE_TIMEOUT_MS) { clone(conn, repo, token, name, onProgress) }
        } catch (e: TimeoutCancellationException) {
            Result.Failed("Timed out cloning $repo")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clone $repo", e)
            Result.Failed(e.message ?: "Could not clone $repo")
        }
    }

    /**
     * @param name the directory to clone into, relative to wherever the terminal starts.
     *
     * Relative on purpose. This used to ask the server for a path and build an absolute one,
     * and `ServerPaths.worktree` came back as "/" — which, once the trailing slash was
     * stripped, made every clone target the filesystem root and fail with "could not create
     * work tree dir '/repo': Permission denied". The terminal already opens in the directory
     * opencode runs in, which is the place we wanted all along, so it is not worth deriving.
     */
    private suspend fun clone(
        conn: ServerConnection,
        repo: String,
        token: String?,
        name: String,
        onProgress: suspend (String) -> Unit,
    ): Result {
        val pty = api.createPty(conn, title = "crux-setup")
        Log.i(TAG, "Cloning $repo through pty ${pty.id}")
        val socket = api.openPtySocket(conn, pty.id)
        var outcome: Int? = null
        var resolved: String? = null
        // git's own words. Without them a failure is just an exit code, and 128 covers
        // everything from a missing repository to a directory already in the way.
        val transcript = ArrayDeque<String>()
        try {
            // Read the token into a shell variable rather than writing it into the command:
            // `read -rs` does not echo, so the credential never appears in the terminal's
            // scrollback the way it would in a clone URL typed out in full.
            if (token != null) {
                socket.send("read -rs CRUX_TOKEN\n")
                socket.send("$token\n")
            }
            socket.send(cloneCommand(repo, name, authenticated = token != null) + "\n")

            socket.readLoop { text ->
                text.lineSequence().forEach { line ->
                    CLONE_MARKER.find(line)?.let { outcome = it.groupValues[1].toIntOrNull() }
                    CLONE_PATH_MARKER.find(line)
                        ?.let { it.groupValues[1].trim() }
                        ?.takeIf { it.startsWith("/") }
                        ?.let { resolved = it }
                    line.trim().takeIf {
                        it.isNotEmpty() &&
                            !it.startsWith(CLONE_MARKER_PREFIX) &&
                            !it.startsWith(CLONE_PATH_PREFIX)
                    }
                        ?.let {
                            onProgress(it)
                            transcript.addLast(it)
                            if (transcript.size > TRANSCRIPT_LINES) transcript.removeFirst()
                        }
                }
                if (outcome != null) socket.close()
            }
        } finally {
            runCatching { socket.close() }
            runCatching { api.removePty(conn, pty.id) }
        }

        // The last line git printed says more than the code: "already exists and is not an
        // empty directory" and "repository not found" are both exit 128.
        val reason = transcript.lastOrNull { it.contains("fatal", ignoreCase = true) }
            ?: transcript.lastOrNull()
        return when (outcome) {
            // The absolute path comes back from the shell rather than being assumed here.
            0 -> resolved?.let { Result.Ready(it) }
                ?: Result.Failed("The clone finished but did not say where")
            null -> Result.Failed("The clone did not report a result. ${reason.orEmpty()}".trim())
            else -> Result.Failed(reason ?: "git clone exited with $outcome")
        }
    }

    internal companion object {
        const val TAG = "WorkspaceCheckout"
        const val CLONE_TIMEOUT_MS = 180_000L
        const val TRANSCRIPT_LINES = 12
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
        // A clone that died partway leaves a directory behind, and git refuses to clone into
        // one that already exists — so every retry failed with 128 where the first attempt had
        // failed with something else. Only ever removed when it holds no repository and
        // nothing else, so work already in the space is never destroyed.
        append("else rmdir '$path' 2>/dev/null; ")
        append("git clone --depth 1 '$source' '$path'; rc=\$?; fi; ")
        // Only when there was one: a public clone should mention no credential at all.
        if (authenticated) append("unset CRUX_TOKEN; ")
        append("if [ \$rc -eq 0 ]; then git -C '$path' remote set-url origin '$origin' 2>/dev/null; ")
        // Where it actually landed, said by the shell rather than assumed by the caller.
        append("echo $CLONE_PATH_PREFIX\$(cd '$path' && pwd); fi; ")
        append("echo $CLONE_MARKER_PREFIX\$rc")
    }
}

/** How the shell reports the clone's own exit code back through the terminal. */
internal const val CLONE_MARKER_PREFIX = "CRUX_CLONE_DONE_"
internal val CLONE_MARKER = Regex("$CLONE_MARKER_PREFIX(\\d+)")

/** How the shell reports the absolute path it cloned into. */
internal const val CLONE_PATH_PREFIX = "CRUX_CLONE_PATH_"
internal val CLONE_PATH_MARKER = Regex("$CLONE_PATH_PREFIX(/\\S*)")
