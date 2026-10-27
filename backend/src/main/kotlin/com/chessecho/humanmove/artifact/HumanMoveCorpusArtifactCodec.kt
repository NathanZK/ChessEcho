package com.chessecho.humanmove.artifact

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Deterministic version-1 canonical encode/decode primitives shared by export and verification. */
internal object HumanMoveCorpusArtifactCodec {
    val mapper =
        jacksonObjectMapper().apply {
            enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    val names = listOf("games.ndjson", "observations.ndjson", "manifest.json")

    fun writeJson(
        value: Any,
        path: Path,
        maxBytes: Long,
    ) {
        var written = 0L
        Files.newOutputStream(path).buffered().use { fileOutput ->
            val bounded =
                object : FilterOutputStream(fileOutput) {
                    override fun write(value: Int) {
                        enforceLimit(1)
                        out.write(value)
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        enforceLimit(length)
                        out.write(bytes, offset, length)
                    }

                    private fun enforceLimit(count: Int) {
                        if (count.toLong() > maxBytes - written) {
                            throw CorpusArtifactInvalidException("JSON record exceeds the configured maximum of $maxBytes bytes")
                        }
                        written += count
                    }
                }
            mapper.writeValue(bounded, value)
        }
    }

    data class EntryFile(
        val name: String,
        val path: Path,
        val size: Long,
        val sha256: String,
        val crc32: Long,
    )

    class EntryWriter(
        val name: String,
        val path: Path,
        private val maxBytes: Long,
        private val maxRecordBytes: Int,
    ) : AutoCloseable {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val crc = CRC32()
        private val output = BufferedOutputStream(Files.newOutputStream(path))
        var size: Long = 0
            private set
        var count: Int = 0
            private set

        fun writeRecord(bytes: ByteArray) {
            if (bytes.size > maxRecordBytes) {
                throw CorpusArtifactInvalidException("A $name record exceeds the configured maximum record size")
            }
            val recordSize = bytes.size.toLong() + 1
            if (size + recordSize > maxBytes) {
                throw CorpusArtifactInvalidException("Archive entry '$name' exceeds the configured expansion bound")
            }
            output.write(bytes)
            output.write('\n'.code)
            digest.update(bytes)
            digest.update('\n'.code.toByte())
            crc.update(bytes)
            crc.update('\n'.code)
            size += recordSize
            count++
        }

        override fun close() = output.close()

        fun entryFile(): EntryFile =
            EntryFile(
                name = name,
                path = path,
                size = size,
                sha256 = digest.digest().toHex(),
                crc32 = crc.value,
            )
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun digestFiles(parts: List<EntryFile>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        parts.forEach { part ->
            hash.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(part.size).array())
            updateDigestFromFile(hash, part.path, buffer)
        }
        return hash.digest().toHex()
    }

    private fun updateDigestFromFile(
        hash: MessageDigest,
        path: Path,
        buffer: ByteArray,
    ) {
        Files.newInputStream(path).buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
    }

    fun entryFile(
        name: String,
        path: Path,
    ): EntryFile {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                crc.update(buffer, 0, read)
            }
        }
        return EntryFile(name, path, Files.size(path), digest.digest().toHex(), crc.value)
    }

    fun writeZip(
        entries: List<EntryFile>,
        outputPath: Path,
        maxArchiveBytes: Long,
    ) {
        Files.newOutputStream(outputPath).use { fileOutput ->
            val boundedOutput = createBoundedArchiveOutput(fileOutput, maxArchiveBytes)
            ZipOutputStream(BufferedOutputStream(boundedOutput)).use { zip ->
                entries.forEach { writeEntry(zip, it) }
            }
        }
    }

    private fun createBoundedArchiveOutput(
        fileOutput: OutputStream,
        maxArchiveBytes: Long,
    ): OutputStream =
        object : OutputStream() {
            private var written = 0L

            override fun write(value: Int) {
                checkLimit(1)
                fileOutput.write(value)
            }

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                checkLimit(length)
                fileOutput.write(bytes, offset, length)
            }

            private fun checkLimit(count: Int) {
                if (written + count > maxArchiveBytes) {
                    throw CorpusArtifactInvalidException(
                        "Archive exceeds the configured maximum of $maxArchiveBytes bytes",
                    )
                }
                written += count
            }
        }

    private fun writeEntry(
        zip: ZipOutputStream,
        part: EntryFile,
    ) {
        val entry =
            ZipEntry(part.name).apply {
                method = ZipEntry.STORED
                time = 0
                size = part.size
                compressedSize = part.size
                this.crc = part.crc32
            }
        zip.putNextEntry(entry)
        Files.newInputStream(part.path).buffered().use { input -> input.copyTo(zip, DEFAULT_BUFFER_SIZE) }
        zip.closeEntry()
    }

    fun spoolZip(
        archive: Path,
        tempDirectory: Path,
        maxArchiveBytes: Long,
        maxExpandedEntryBytes: Long,
    ): List<EntryFile> {
        if (Files.size(archive) > maxArchiveBytes) {
            throw CorpusArtifactInvalidException("Archive exceeds the configured maximum of $maxArchiveBytes bytes")
        }
        val result = mutableListOf<EntryFile>()
        var expandedTotal = 0L
        try {
            ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
                while (true) {
                    val entry = readNextZipEntry(zip) ?: break
                    validateEntryMetadata(entry, result.size)
                    val remainingBytes = maxExpandedEntryBytes - expandedTotal
                    val entryFile = spoolSingleEntry(zip, entry, tempDirectory, remainingBytes, maxExpandedEntryBytes)
                    result += entryFile
                    expandedTotal += entryFile.size
                    zip.closeEntry()
                }
            }
            if (result.map { it.name } != names) {
                throw CorpusArtifactInvalidException("Archive must contain exactly $names in order")
            }
            return result
        } catch (e: java.io.EOFException) {
            throw CorpusArtifactInvalidException("Truncated archive: ${e.message}", e)
        } catch (e: ZipException) {
            throw CorpusArtifactInvalidException("Malformed archive: ${e.message}", e)
        } catch (e: Exception) {
            result.forEach { Files.deleteIfExists(it.path) }
            throw e
        }
    }

    private fun readNextZipEntry(zip: ZipInputStream): ZipEntry? =
        try {
            zip.nextEntry
        } catch (e: ZipException) {
            throw CorpusArtifactInvalidException("Malformed archive: ${e.message}", e)
        }

    private fun validateEntryMetadata(
        entry: ZipEntry,
        currentIndex: Int,
    ) {
        if (currentIndex >= names.size || entry.name != names[currentIndex]) {
            throw CorpusArtifactInvalidException("Unexpected or out-of-order archive entry '${entry.name}'")
        }
        if (entry.method != ZipEntry.STORED || entry.size < 0 || entry.compressedSize != entry.size) {
            throw CorpusArtifactInvalidException("Archive entry '${entry.name}' must be STORED with a declared size")
        }
    }

    private fun spoolSingleEntry(
        zip: ZipInputStream,
        entry: ZipEntry,
        tempDirectory: Path,
        remainingBytes: Long,
        maxExpandedEntryBytes: Long,
    ): EntryFile {
        val path = Files.createTempFile(tempDirectory, "corpus-entry-", ".tmp")
        try {
            val (size, sha256Hex, crcValue) =
                copyAndDigestZipEntry(zip, entry.name, path, remainingBytes, maxExpandedEntryBytes)
            if (size != entry.size || crcValue != entry.crc) {
                throw CorpusArtifactInvalidException("Archive entry '${entry.name}' has invalid size or CRC")
            }
            return EntryFile(entry.name, path, size, sha256Hex, crcValue)
        } catch (e: Exception) {
            Files.deleteIfExists(path)
            throw e
        }
    }

    private data class EntryStreamMetrics(
        val size: Long,
        val sha256: String,
        val crc32: Long,
    )

    private fun copyAndDigestZipEntry(
        zip: ZipInputStream,
        entryName: String,
        path: Path,
        remainingBytes: Long,
        maxExpandedEntryBytes: Long,
    ): EntryStreamMetrics {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        Files.newOutputStream(path).buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = readZipBuffer(zip, entryName, buffer)
                if (read < 0) break
                if (read.toLong() > remainingBytes - size) {
                    throw CorpusArtifactInvalidException(
                        "Expanded archive entries exceed the configured maximum of $maxExpandedEntryBytes bytes",
                    )
                }
                size += read
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                crc.update(buffer, 0, read)
            }
        }
        return EntryStreamMetrics(size, digest.digest().toHex(), crc.value)
    }

    private fun readZipBuffer(
        zip: ZipInputStream,
        entryName: String,
        buffer: ByteArray,
    ): Int =
        try {
            zip.read(buffer)
        } catch (e: ZipException) {
            throw CorpusArtifactInvalidException("Malformed archive entry '$entryName': ${e.message}", e)
        } catch (e: java.io.EOFException) {
            throw CorpusArtifactInvalidException("Truncated archive entry '$entryName'", e)
        }

    fun canonicalZipMatches(
        archive: Path,
        entries: List<EntryFile>,
        tempDirectory: Path,
        maxArchiveBytes: Long,
    ): Boolean {
        val reconstructed = Files.createTempFile(tempDirectory, "corpus-canonical-", ".zip")
        return try {
            writeZip(entries, reconstructed, maxArchiveBytes)
            Files.mismatch(archive, reconstructed) == -1L
        } finally {
            Files.deleteIfExists(reconstructed)
        }
    }

    fun copyBounded(
        input: InputStream,
        output: Path,
        maximumBytes: Long,
    ) {
        var total = 0L
        Files.newOutputStream(output).buffered().use { sink ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumBytes) {
                    throw CorpusArtifactInvalidException("Archive exceeds the configured maximum of $maximumBytes bytes")
                }
                sink.write(buffer, 0, read)
            }
        }
    }

    fun readTree(bytes: ByteArray): JsonNode =
        try {
            val node = mapper.readTree(bytes) ?: throw CorpusArtifactInvalidException("Empty JSON record")
            if (!mapper.writeValueAsBytes(node).contentEquals(bytes)) {
                throw CorpusArtifactInvalidException("JSON record is not canonical")
            }
            node
        } catch (e: CorpusArtifactInvalidException) {
            throw e
        } catch (e: Exception) {
            throw CorpusArtifactInvalidException("Malformed JSON: ${e.message}", e)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
