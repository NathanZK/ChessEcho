package com.chessecho.humanmove.artifact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class HumanMoveCorpusArtifactCodecTest {
    @Test
    fun `CorpusArtifactInvalidException supports cause chaining and backward compatibility`() {
        val rootCause = IOException("disk read error")
        val withCause = CorpusArtifactInvalidException("Corrupted stream", rootCause)
        assertEquals("Corrupted stream", withCause.message)
        assertSame(rootCause, withCause.cause)

        val withoutCause = CorpusArtifactInvalidException("Simple message")
        assertEquals("Simple message", withoutCause.message)
        assertNull(withoutCause.cause)
    }

    @Test
    fun `readTree decodes canonical json and preserves cause on malformed input`() {
        val validJson = """{"a":1,"b":"hello"}""".toByteArray(Charsets.UTF_8)
        val node = HumanMoveCorpusArtifactCodec.readTree(validJson)
        assertEquals(1, node.get("a").asInt())
        assertEquals("hello", node.get("b").asText())

        val malformedJson = """{"a":1,""".toByteArray(Charsets.UTF_8)
        val ex =
            assertThrows<CorpusArtifactInvalidException> {
                HumanMoveCorpusArtifactCodec.readTree(malformedJson)
            }
        assertTrue(ex.message!!.startsWith("Malformed JSON"))
        assertNotNull(ex.cause)
    }

    @Test
    fun `writeZip and spoolZip roundtrip valid entries in canonical order`(
        @TempDir tempDir: Path,
    ) {
        val gamesFile = tempDir.resolve("games.ndjson")
        val observationsFile = tempDir.resolve("observations.ndjson")
        val manifestFile = tempDir.resolve("manifest.json")

        Files.writeString(gamesFile, "{\"game\":1}\n")
        Files.writeString(observationsFile, "{\"obs\":1}\n")
        Files.writeString(manifestFile, "{\"formatVersion\":1}\n")

        val entries =
            listOf(
                HumanMoveCorpusArtifactCodec.entryFile("games.ndjson", gamesFile),
                HumanMoveCorpusArtifactCodec.entryFile("observations.ndjson", observationsFile),
                HumanMoveCorpusArtifactCodec.entryFile("manifest.json", manifestFile),
            )

        val zipFile = tempDir.resolve("corpus.zip")
        HumanMoveCorpusArtifactCodec.writeZip(entries, zipFile, 1024 * 1024)

        val spoolDir = tempDir.resolve("spool")
        Files.createDirectories(spoolDir)
        val spooled = HumanMoveCorpusArtifactCodec.spoolZip(zipFile, spoolDir, 1024 * 1024, 1024 * 1024)

        assertEquals(3, spooled.size)
        assertEquals("games.ndjson", spooled[0].name)
        assertEquals("observations.ndjson", spooled[1].name)
        assertEquals("manifest.json", spooled[2].name)
        assertEquals(entries[0].sha256, spooled[0].sha256)
        assertEquals(entries[1].sha256, spooled[1].sha256)
        assertEquals(entries[2].sha256, spooled[2].sha256)

        assertTrue(HumanMoveCorpusArtifactCodec.canonicalZipMatches(zipFile, spooled, spoolDir, 1024 * 1024))
    }

    @Test
    fun `copyBounded rejects streams exceeding maximumBytes`(
        @TempDir tempDir: Path,
    ) {
        val out = tempDir.resolve("bounded.dat")
        val input = ByteArrayInputStream(ByteArray(100))

        val ex =
            assertThrows<CorpusArtifactInvalidException> {
                HumanMoveCorpusArtifactCodec.copyBounded(input, out, 50)
            }
        assertTrue(ex.message!!.contains("Archive exceeds the configured maximum"))
    }
}
