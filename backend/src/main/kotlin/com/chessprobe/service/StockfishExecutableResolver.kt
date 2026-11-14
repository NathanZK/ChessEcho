package com.chessprobe.service

import java.io.File
import java.security.MessageDigest

object StockfishExecutableResolver {
    fun resolveExecutableFile(command: String): File? {
        val file = File(command)
        if (file.isAbsolute || command.contains(File.separator)) {
            return if (file.isFile && file.canExecute()) file.canonicalFile else null
        }
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, command)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.canonicalFile
            }
        }
        return null
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeFallbackDigest(command: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("unresolved:$command".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
