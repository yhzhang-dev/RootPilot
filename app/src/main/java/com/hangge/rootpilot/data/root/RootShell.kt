package com.hangge.rootpilot.data.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The only path to `su`. Each invocation is a fixed [RootCommand], capped at 32 KiB of output, and
 * killed after eight seconds so a pending Magisk prompt cannot wedge the UI or the Agent server.
 */
class RootShell(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputBytes: Int = MAX_OUTPUT_BYTES,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    suspend fun run(command: RootCommand): RootResult = withContext(Dispatchers.IO) {
        mutex.withLock { execute(command.commandLine) }
    }

    /**
     * Root detection: `su -c id` and look for `uid=0`. The answer is cached for this process only
     * and can be refreshed explicitly from the Dashboard or Settings.
     */
    suspend fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            cachedRootAvailable?.let { return it }
        }
        val result = run(RootCommand.Id)
        val available = result.success && ROOT_UID.containsMatchIn(result.output)
        cachedRootAvailable = available
        return available
    }

    suspend fun refreshRoot(): Boolean = isRootAvailable(forceRefresh = true)

    private fun execute(commandLine: String): RootResult {
        val process = try {
            ProcessBuilder("su", "-c", commandLine)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            return RootResult(
                success = false,
                exitCode = EXIT_UNAVAILABLE,
                output = "su 不可用：${error.message ?: "not found"}",
            )
        }

        val buffer = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        if (buffer.length < maxOutputBytes) buffer.append(chunk, 0, read)
                    }
                }
            } catch (_: IOException) {
                // Process died; the exit code still tells the story.
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(JOIN_TIMEOUT_MS)
            return RootResult(
                success = false,
                exitCode = EXIT_TIMEOUT,
                output = "命令超时（${timeoutMs}ms）：$commandLine",
            )
        }

        reader.join(JOIN_TIMEOUT_MS)
        val exitCode = process.exitValue()
        val output = buffer.toString().trim().take(maxOutputBytes)
        return RootResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val MAX_OUTPUT_BYTES = 32 * 1024
        const val EXIT_TIMEOUT = -1
        const val EXIT_UNAVAILABLE = -2
        private const val JOIN_TIMEOUT_MS = 1_000L
        private val ROOT_UID = Regex("""uid=0(\s|\()""")
    }
}
