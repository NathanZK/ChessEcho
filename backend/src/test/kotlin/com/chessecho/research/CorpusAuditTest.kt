package com.chessecho.research

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorpusAuditTest {
    private val builder = CorpusBuilder()
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val resourcesDir = File("src/test/resources/research")
    private val rawGamesDir = File("/Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/brain/9ee8df32-f921-4f62-99d5-42752729c645/scratch/raw_games")

    private val corpusAFile = File(resourcesDir, "corpus_a.json")
    private val corpusB1File = File(resourcesDir, "corpus_b1.json")
    private val corpusB2File = File(resourcesDir, "corpus_b2.json")
    private val provenanceAFile = File(resourcesDir, "corpus_a_provenance.json")
    private val auditReportFile = File(resourcesDir, "corpus_audit_report.json")

    private lateinit var corpusA: List<PositionProvenanceRecord>
    private lateinit var corpusB1: List<PositionProvenanceRecord>
    private lateinit var corpusB2: List<PositionProvenanceRecord>

    @BeforeAll
    fun setUp() {
        resourcesDir.mkdirs()

        if (!corpusAFile.exists() || !corpusB1File.exists() || !corpusB2File.exists()) {
            println("Building research corpora from raw game archives...")
            val rawFiles = rawGamesDir.listFiles { f -> f.extension == "json" }?.toList() ?: emptyList()
            assertTrue(rawFiles.isNotEmpty(), "Raw game archives must be present in $rawGamesDir")

            // GM files for Corpus A
            val gmFiles = rawFiles.filter { f ->
                f.name.startsWith("hikaru") || f.name.startsWith("DanielNaroditsky") || f.name.startsWith("MagnusCarlsen")
            }
            corpusA = builder.assembleCorpusA(gmFiles, targetSize = 10_000)
            builder.saveCorpusToJson(corpusAFile, corpusA)

            // Naroditsky files for Corpus B1
            val b1Files = rawFiles.filter { f -> f.name.startsWith("DanielNaroditsky") }
            corpusB1 = builder.assembleCorpusB1(b1Files, targetSize = 3_000)
            builder.saveCorpusToJson(corpusB1File, corpusB1)

            // 1700-1900 Blitz files for Corpus B2
            val b2Files = rawFiles.filter { f -> f.name.startsWith("0med0991") || f.name.startsWith("28juan28") }
            corpusB2 = builder.assembleCorpusB2(b2Files, targetSize = 10_000)
            builder.saveCorpusToJson(corpusB2File, corpusB2)

            // Save provenance audit reports
            val auditA = builder.auditCorpus("Corpus A (GM Scaling)", corpusA)
            val auditB1 = builder.auditCorpus("Corpus B1 (Single Repertoire - Naroditsky)", corpusB1)
            val auditB2 = builder.auditCorpus("Corpus B2 (1700-1900 Blitz Cohort)", corpusB2)

            mapper.writeValue(auditReportFile, mapOf(
                "corpusA" to auditA,
                "corpusB1" to auditB1,
                "corpusB2" to auditB2,
            ))

            mapper.writeValue(provenanceAFile, mapOf(
                "totalPositions" to corpusA.size,
                "ecoBreakdown" to auditA.ecoDistribution,
                "phaseBreakdown" to mapOf(
                    "opening" to auditA.openingCount,
                    "middlegame" to auditA.middlegameCount,
                    "endgame" to auditA.endgameCount,
                ),
                "turnBreakdown" to mapOf(
                    "white" to auditA.whiteCount,
                    "black" to auditA.blackCount,
                ),
                "sampleProvenanceRecords" to corpusA.take(10),
            ))
            println("Corpora assembled and persisted successfully.")
        } else {
            corpusA = builder.loadCorpusFromJson(corpusAFile)
            corpusB1 = builder.loadCorpusFromJson(corpusB1File)
            corpusB2 = builder.loadCorpusFromJson(corpusB2File)
        }
    }

    @Test
    fun `corpus A satisfies all 10,000-position GM scaling criteria`() {
        assertEquals(10_000, corpusA.size, "Corpus A must contain exactly 10,000 positions")

        val distinctFens = corpusA.map { it.canonicalFen }.distinct().size
        assertEquals(10_000, distinctFens, "Corpus A must have zero duplicate canonical FENs")

        val whiteCount = corpusA.count { it.sideToMove == "w" }
        val blackCount = corpusA.count { it.sideToMove == "b" }
        val whitePct = (whiteCount.toDouble() / corpusA.size) * 100.0
        val blackPct = (blackCount.toDouble() / corpusA.size) * 100.0

        assertTrue(whitePct in 49.0..51.0, "White-to-move parity must be 50% +/- 1% (actual: $whitePct%)")
        assertTrue(blackPct in 49.0..51.0, "Black-to-move parity must be 50% +/- 1% (actual: $blackPct%)")

        val openingCount = corpusA.count { it.phase == GamePhase.OPENING }
        val middlegameCount = corpusA.count { it.phase == GamePhase.MIDDLEGAME }
        val endgameCount = corpusA.count { it.phase == GamePhase.ENDGAME }

        val openingPct = (openingCount.toDouble() / corpusA.size) * 100.0
        val middlegamePct = (middlegameCount.toDouble() / corpusA.size) * 100.0
        val endgamePct = (endgameCount.toDouble() / corpusA.size) * 100.0

        assertTrue(openingPct in 20.0..25.5, "Opening phase must be 20-25% (actual: $openingPct%)")
        assertTrue(middlegamePct in 54.0..61.0, "Middlegame phase must be 55-60% (actual: $middlegamePct%)")
        assertTrue(endgamePct in 14.5..21.0, "Endgame phase must be 15-20% (actual: $endgamePct%)")

        // ECO diversity: A, B, C, D, E present
        val ecoCategories = corpusA.map { it.eco.take(1).uppercase() }.toSet()
        assertTrue(ecoCategories.containsAll(listOf("A", "B", "C", "D", "E")), "All 5 ECO categories must be present")

        // No opening family > 15%
        val familyCounts = corpusA.groupBy { it.eco.take(2).uppercase() }
        familyCounts.forEach { (family, positions) ->
            val pct = (positions.size.toDouble() / corpusA.size) * 100.0
            assertTrue(pct <= 15.0, "Opening family $family exceeded 15% of Corpus A ($pct%)")
        }

        // En passant normalization: zero positions with uncapturable ep
        corpusA.forEach { pos ->
            val fields = pos.canonicalFen.split(" ")
            if (fields.size >= 4 && fields[3] != "-") {
                val normalized = builder.normalizeEnPassant(pos.canonicalFen)
                assertTrue(!normalized.endsWith("-"), "FEN ${pos.canonicalFen} had uncapturable ep square")
            }
        }
    }

    @Test
    fun `corpus B1 satisfies single repertoire account criteria`() {
        assertTrue(corpusB1.size in 2500..4000, "Corpus B1 must contain 2,500-4,000 positions (actual: ${corpusB1.size})")
        val distinctFens = corpusB1.map { it.canonicalFen }.distinct().size
        assertEquals(corpusB1.size, distinctFens, "Corpus B1 must have zero duplicate canonical FENs")

        // Verify account identity
        assertTrue(corpusB1.all {
            it.whitePlayer.equals("DanielNaroditsky", ignoreCase = true) ||
                it.blackPlayer.equals("DanielNaroditsky", ignoreCase = true)
        })
    }

    @Test
    fun `corpus B2 satisfies 1700-1900 blitz cohort criteria`() {
        assertEquals(10_000, corpusB2.size, "Corpus B2 must contain exactly 10,000 positions")
        val distinctFens = corpusB2.map { it.canonicalFen }.distinct().size
        assertEquals(10_000, distinctFens, "Corpus B2 must have zero duplicate canonical FENs")

        // Verify players are from cohort
        val cohortPlayers = setOf("0med0991", "28juan28")
        assertTrue(corpusB2.all { pos ->
            cohortPlayers.any { p ->
                pos.whitePlayer.equals(p, ignoreCase = true) || pos.blackPlayer.equals(p, ignoreCase = true)
            }
        })
    }

    @Test
    fun `en passant normalizer converts uncapturable ep square to dash`() {
        // e4 played, but Black has no pawn on d4 or f4 to capture on e3:
        val fenWithGhostEp = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val normalized = builder.normalizeEnPassant(fenWithGhostEp)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -", normalized)

        // Legitimate ep capture possible: Black pawn on d4, White plays e4:
        val fenWithLegalEp = "rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val normalizedLegal = builder.normalizeEnPassant(fenWithLegalEp)
        assertEquals("rnbqkbnr/ppp1pppp/8/8/3pP3/8/PPPP1PPP/RNBQKBNR b KQkq e3", normalizedLegal)
    }
}
