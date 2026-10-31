package com.chessecho.humanmove.artifact

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HumanMoveCorpusArtifactVerifierTest {
    private val defaultLimits =
        HumanMoveCorpusArtifactVerifier.Limits(
            maxArchiveBytes = 10 * 1024 * 1024,
            maxExpandedEntryBytes = 50 * 1024 * 1024,
            maxGames = 1000,
            maxObservations = 50000,
            maxRecordBytes = 1024 * 1024,
        )

    @Test
    fun `verify rejects invalid expectedDigest format`(
        @TempDir tempDir: Path,
    ) {
        val dummyZip = tempDir.resolve("empty.zip")
        Files.write(dummyZip, byteArrayOf())

        val ex =
            assertThrows<CorpusArtifactInvalidException> {
                HumanMoveCorpusArtifactVerifier.verify(dummyZip, "invalid-hex", defaultLimits)
            }
        assertTrue(ex.message!!.contains("64-character lowercase hex"))
    }

    @Test
    fun `verify rejects archive exceeding maxArchiveBytes`(
        @TempDir tempDir: Path,
    ) {
        val dummyZip = tempDir.resolve("large.zip")
        Files.write(dummyZip, ByteArray(100))

        val smallLimits = defaultLimits.copy(maxArchiveBytes = 50)
        val validDigest = "0".repeat(64)

        val ex =
            assertThrows<CorpusArtifactInvalidException> {
                HumanMoveCorpusArtifactVerifier.verify(dummyZip, validDigest, smallLimits)
            }
        assertTrue(ex.message!!.contains("Archive exceeds the configured maximum"))
    }

    @Test
    fun `forEachArchiveRecord throws when requested entry is missing`(
        @TempDir tempDir: Path,
    ) {
        val emptyZip = tempDir.resolve("empty.zip")
        Files.newOutputStream(emptyZip).use { out ->
            java.util.zip
                .ZipOutputStream(out)
                .use { }
        }

        val ex =
            assertThrows<CorpusArtifactInvalidException> {
                HumanMoveCorpusArtifactVerifier.forEachGame(emptyZip, 1024) { }
            }
        assertTrue(ex.message!!.contains("Missing archive entry 'games.ndjson'"))
    }
}
