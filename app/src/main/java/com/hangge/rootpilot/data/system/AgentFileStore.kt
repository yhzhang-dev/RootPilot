package com.hangge.rootpilot.data.system

import android.content.Context
import java.io.File

data class AgentFileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

/** Sandboxed file exchange for desktop Agents. Paths can never escape files/agent-exchange. */
class AgentFileStore(context: Context) {
    private val root = File(context.filesDir, "agent-exchange").apply { mkdirs() }.canonicalFile

    fun rootPath(): String = root.absolutePath

    fun list(relativePath: String): List<AgentFileEntry> {
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "不是目录：$relativePath" }
        return directory.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                AgentFileEntry(
                    name = file.name,
                    path = file.relativeTo(root).invariantSeparatorsPath,
                    directory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    modifiedAt = file.lastModified(),
                )
            }
    }

    fun read(relativePath: String): ByteArray {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        require(file.length() <= MAX_FILE_BYTES) { "文件超过 ${MAX_FILE_BYTES / 1024 / 1024} MiB" }
        return file.readBytes()
    }

    fun write(relativePath: String, bytes: ByteArray): AgentFileEntry {
        require(bytes.size <= MAX_FILE_BYTES) { "文件超过 ${MAX_FILE_BYTES / 1024 / 1024} MiB" }
        val file = resolve(relativePath)
        require(file != root) { "必须指定文件名" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return AgentFileEntry(
            name = file.name,
            path = file.relativeTo(root).invariantSeparatorsPath,
            directory = false,
            size = file.length(),
            modifiedAt = file.lastModified(),
        )
    }

    private fun resolve(relativePath: String): File {
        val clean = relativePath.trim().replace('\\', '/').trimStart('/')
        val candidate = File(root, clean).canonicalFile
        require(candidate == root || candidate.path.startsWith(root.path + File.separator)) {
            "路径超出 Agent 交换目录"
        }
        return candidate
    }

    companion object {
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
    }
}
