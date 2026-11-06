package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectivityClosureExtractorTest {

    private val extractor = ConnectivityClosureExtractor()

    @Test
    fun `pinned piece attacking occupied opposing piece extracts attack token`() {
        // White King on e1, White Knight on e2 (absolutely pinned by Black Rook on e8). Black Pawn on d4.
        val fen = "4r3/8/8/8/3p4/8/4N3/4K3 w - - 0 1"
        val attackTerms = extractor.extractAttackTerms(fen)

        assertTrue(
            attackTerms.containsKey("attack|knight|pawn|d4"),
            "Pinned knight on e2 must attack black pawn on d4 geometrically"
        )
    }

    @Test
    fun `pinned piece defending occupied friendly piece extracts defense token`() {
        // White King on e1, White Knight on e2 (absolutely pinned by Black Rook on e8). White Pawn on f4.
        val fen = "4r3/8/8/8/5P2/8/4N3/4K3 w - - 0 1"
        val defenseTerms = extractor.extractDefenseTerms(fen)

        assertTrue(
            defenseTerms.containsKey("defense|knight|pawn|f4"),
            "Pinned knight on e2 must defend white pawn on f4 geometrically"
        )
    }

    @Test
    fun `attacks directed toward empty squares produce zero closure terms`() {
        // King on a1 and Knight on h8 on an empty board (they do not reach each other)
        val fen = "7N/8/8/8/8/8/8/K7 w - - 0 1"
        val attackTerms = extractor.extractAttackTerms(fen)
        val defenseTerms = extractor.extractDefenseTerms(fen)

        assertTrue(attackTerms.isEmpty(), "Attack terms must be empty when no opposing pieces exist")
        assertTrue(defenseTerms.isEmpty(), "Defense terms must be empty when pieces do not reach each other")
    }

    @Test
    fun `scholars mate attack extraction verifies key attacks`() {
        // 1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# setup
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val attackTerms = extractor.extractAttackTerms(fen)

        // Queen on h5 attacks Pawn on f7 and Pawn on e5
        assertTrue(
            attackTerms.containsKey("attack|queen|pawn|f7"),
            "Queen on h5 attacks Black pawn on f7"
        )
        assertTrue(
            attackTerms.containsKey("attack|queen|pawn|e5"),
            "Queen on h5 attacks Black pawn on e5"
        )
        // Bishop on c4 attacks Pawn on f7
        assertTrue(
            attackTerms.containsKey("attack|bishop|pawn|f7"),
            "Bishop on c4 attacks Black pawn on f7"
        )
    }

    @Test
    fun `source square is omitted from all extracted tokens`() {
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val combinedTerms = extractor.extractCombinedTerms(fen)

        combinedTerms.keys.forEach { term ->
            val parts = term.split("|")
            assertEquals(4, parts.size, "Token '$term' must contain exactly 4 parts: family|source|target|sq")
            // Source square e2 or h5 or c4 must not be part of the token
            assertFalse(
                parts[1].contains("h5") || parts[1].contains("c4"),
                "Source piece must not contain square coordinates: ${parts[1]}"
            )
        }
    }

    @Test
    fun `target square is retained in all extracted tokens`() {
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val combinedTerms = extractor.extractCombinedTerms(fen)

        combinedTerms.keys.forEach { term ->
            val parts = term.split("|")
            assertEquals(4, parts.size, "Token '$term' must have 4 parts")
            val targetSquare = parts[3]
            assertTrue(
                targetSquare.matches(Regex("^[a-h][1-8]$")),
                "Target square in '$term' must be a valid algebraic square (a1..h8)"
            )
        }
    }

    @Test
    fun `color names are omitted from primary tokens`() {
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val combinedTerms = extractor.extractCombinedTerms(fen)

        combinedTerms.keys.forEach { term ->
            assertFalse(term.contains("white"), "Token '$term' must not contain 'white'")
            assertFalse(term.contains("black"), "Token '$term' must not contain 'black'")
        }
    }

    @Test
    fun `combined terms union attack and defense terms`() {
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val attackTerms = extractor.extractAttackTerms(fen)
        val defenseTerms = extractor.extractDefenseTerms(fen)
        val combinedTerms = extractor.extractCombinedTerms(fen)
        val defaultTerms = extractor.extractTerms(fen)

        assertEquals(combinedTerms, defaultTerms, "extractTerms must delegate to extractCombinedTerms")
        assertEquals(attackTerms.size + defenseTerms.size, combinedTerms.size, "Attack and defense keys are mutually disjoint")

        attackTerms.forEach { (k, v) ->
            assertEquals(v, combinedTerms[k])
        }
        defenseTerms.forEach { (k, v) ->
            assertEquals(v, combinedTerms[k])
        }
    }
}
