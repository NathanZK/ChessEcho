package com.chessecho.similarity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FenSimilarityFixtureEvaluationTest {
    private val similarity = FenPositionSimilarity()
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `fixed fixture is legal deterministic and produces rankings and stability evidence`() {
        val fixtureSpec =
            javaClass.getResourceAsStream("/fen-similarity-v1-fixtures.json")!!.use { input ->
                objectMapper.readValue<FixtureSpec>(input)
            }
        val documents = sortedMapOf<String, String>()
        val seeds = mutableListOf<SeedPosition>()
        val fixtureRecords = mutableListOf<Map<String, Any>>()
        val stateRecords = mutableListOf<Map<String, Any>>()
        val counterRecords = mutableListOf<Map<String, Any>>()

        fixtureSpec.fixtures.forEach { fixture ->
            val boardA = replay(fixtureSpec.startFen, fixture.sequenceA)
            val boardB = replay(fixtureSpec.startFen, fixture.sequenceB)
            val fenA = boardA.fen
            val fenB = boardB.fen
            val keyA = canonical(fenA)
            val keyB = canonical(fenB)
            assertEquals(keyA, keyB, "Fixture ${fixture.id} move orders must transpose")
            assertEquals(
                similarity.extractTerms(fenA, includeReachability = true),
                similarity.extractTerms(fenB, includeReachability = true),
                "Fixture ${fixture.id} transpositions must have identical document features",
            )
            assertEquals(
                descriptors(boardA, fenA),
                descriptors(boardB, fenB),
                "Fixture ${fixture.id} transpositions must have identical independent descriptors",
            )

            val successorBoard = boardA.clone()
            val successorMove = successorBoard.legalMoves().map { it.toString() }.sorted().first()
            assertTrue(successorBoard.doMove(successorMove), "Fixture ${fixture.id} successor must be legal")
            val successorFen = successorBoard.fen
            val seedFen = fenA
            documents.putIfAbsent(keyA, seedFen)
            documents.putIfAbsent(canonical(successorFen), successorFen)
            seeds.add(SeedPosition(fixture.id, seedFen, successorMove))

            fixtureRecords.add(
                mapOf(
                    "id" to fixture.id,
                    "sequenceA" to fixture.sequenceA,
                    "sequenceB" to fixture.sequenceB,
                    "fenA" to fenA,
                    "fenB" to fenB,
                    "canonicalKeyA" to keyA,
                    "canonicalKeyB" to keyB,
                    "transpositionFeaturesEqual" to true,
                    "transpositionDescriptorsEqual" to true,
                    "successorMove" to successorMove,
                    "successorFen" to successorFen,
                    "successorKey" to canonical(successorFen),
                ),
            )
        }

        assertEquals(8, documents.size, "The fixture pool must have eight distinct positions")
        val documentFens = documents.values.toList()
        documents.forEach { (key, fen) ->
            val board = parse(fen)
            val documentTerms = similarity.extractTerms(fen, includeReachability = true)
            val queryTerms = similarity.extractTerms(fen, includeReachability = false)
            val counters = counterVariants(fen)

            counters.forEach { (variantName, variantFen) ->
                assertEquals(key, canonical(variantFen), "$variantName must preserve $key")
                assertEquals(
                    documentTerms,
                    similarity.extractTerms(variantFen, includeReachability = true),
                    "$variantName document features must be invariant",
                )
                assertEquals(
                    queryTerms,
                    similarity.extractTerms(variantFen, includeReachability = false),
                    "$variantName query features must be invariant",
                )
                val otherQuery = seeds.first { canonical(it.fen) != key }.fen
                assertEquals(
                    similarity.rank(otherQuery, listOf(fen)),
                    similarity.rank(otherQuery, listOf(fen, variantFen)),
                    "$variantName candidate score must be invariant",
                )
                assertEquals(
                    similarity.rank(fen, documentFens),
                    similarity.rank(variantFen, documentFens),
                    "$variantName query ranking must be invariant",
                )
                counterRecords.add(
                    mapOf(
                        "positionKey" to key,
                        "variant" to variantName,
                        "fen" to variantFen,
                        "keyUnchanged" to true,
                        "documentTermsUnchanged" to true,
                        "queryTermsUnchanged" to true,
                        "candidateScoreUnchanged" to true,
                        "queryRankingUnchanged" to true,
                    ),
                )
            }

            stateRecords.add(
                mapOf(
                    "canonicalKey" to key,
                    "fen" to fen,
                    "descriptors" to descriptors(board, fen),
                    "documentTerms" to documentTerms,
                    "queryTerms" to queryTerms,
                    "documentTermCount" to documentTerms.size,
                    "documentTermDigest" to digestTerms(documentTerms),
                ),
            )
        }

        val fullRankings =
            seeds.map { seed ->
                val ranking = similarity.rank(seed.fen, documentFens)
                assertEquals(7, ranking.size, "Query ${seed.fixtureId} must rank all seven non-self documents")
                assertEquals(
                    ranking,
                    similarity.rank(seed.fen, documentFens.reversed()),
                    "Query ${seed.fixtureId} ranking must not depend on pool input order",
                )
                mapOf(
                    "queryFixture" to seed.fixtureId,
                    "queryFen" to seed.fen,
                    "queryKey" to canonical(seed.fen),
                    "poolStatistics" to poolStatistics(seed.fen, documentFens),
                    "ranking" to
                        ranking.mapIndexed { index, result ->
                            mapOf(
                                "rank" to index + 1,
                                "canonicalFen" to result.canonicalFen,
                                "score" to result.score.toString(),
                            )
                        },
                )
            }

        val stability = mutableListOf<Map<String, Any>>()
        seeds.forEach { seed ->
            val fullRanking = similarity.rank(seed.fen, documentFens)
            listOf("A", "B", "C", "D").forEach { omittedFixture ->
                val omittedKeys =
                    fixtureRecords
                        .filter { it["id"] == omittedFixture }
                        .flatMap { record -> listOf(record.getValue("canonicalKeyA"), record.getValue("successorKey")) }
                        .toSet()
                val perturbedPool = documentFens.filterNot { canonical(it) in omittedKeys }
                val commonKeys = perturbedPool.map(::canonical).toSet()
                val baselineTop = fullRanking.filter { it.canonicalFen in commonKeys }.take(3).map { it.canonicalFen }.toSet()
                val perturbedRanking = similarity.rank(seed.fen, perturbedPool)
                val perturbedTop = perturbedRanking.take(3).map { it.canonicalFen }.toSet()
                val unionSize = (baselineTop + perturbedTop).size
                val jaccard = if (unionSize == 0) 1.0 else (baselineTop intersect perturbedTop).size.toDouble() / unionSize

                assertEquals(3, baselineTop.size)
                assertEquals(3, perturbedTop.size)
                stability.add(
                    mapOf(
                        "queryFixture" to seed.fixtureId,
                        "omittedFixture" to omittedFixture,
                        "candidateCount" to perturbedPool.size,
                        "fullPoolCommonTop3" to baselineTop.sorted(),
                        "perturbedTop3" to perturbedTop.sorted(),
                        "jaccard" to jaccard.toString(),
                        "poolStatistics" to poolStatistics(seed.fen, perturbedPool),
                    ),
                )
            }
        }
        assertEquals(16, stability.size)
        val meanJaccard = stability.map { it.getValue("jaccard").toString().toDouble() }.average()

        val report =
            mapOf(
                "fixtures" to fixtureRecords,
                "documentPool" to documents.map { (key, fen) -> mapOf("canonicalKey" to key, "fen" to fen) },
                "states" to stateRecords,
                "counterVariants" to counterRecords,
                "rankings" to fullRankings,
                "stability" to stability,
                "meanJaccard" to meanJaccard.toString(),
                "projectStabilityGate" to (meanJaccard >= 0.60),
            )

        System.getenv("FEN_SIMILARITY_REPORT_PATH")?.let { path ->
            Files.writeString(Path.of(path), objectMapper.writeValueAsString(report))
        }
    }

    private fun replay(
        startFen: String,
        sequence: String,
    ): Board {
        val board = Board()
        board.loadFromFen(startFen)
        sequence.split(' ').forEach { uci ->
            assertTrue(board.doMove(uci), "Fixture move $uci must be legal")
        }
        return board
    }

    private fun parse(fen: String): Board =
        Board().also { board ->
            board.loadFromFen(fen)
        }

    private fun canonical(fen: String): String = fen.split(' ').take(4).joinToString(" ")

    private fun counterVariants(fen: String): List<Pair<String, String>> {
        val fields = fen.split(' ')
        val halfmove = fields.toMutableList().also { it[4] = (it[4].toInt() + 1).toString() }
        val fullmove = fields.toMutableList().also { it[5] = (it[5].toInt() + 5).toString() }
        return listOf("halfmove+1" to halfmove.joinToString(" "), "fullmove+5" to fullmove.joinToString(" "))
    }

    private fun descriptors(
        board: Board,
        fen: String,
    ): Map<String, Any> {
        val counts =
            Side.values().flatMap { side ->
                PieceType.values().filter { it != PieceType.NONE }.map { type ->
                    val name = "${colorName(side)}_${type.value().lowercase()}"
                    name to board.getPieceLocation(Piece.make(side, type)).size
                }
            }.toMap()
        val attackedSquares =
            Side.values().associate { side ->
                colorName(side) to
                    Square.values().filter { square ->
                        square != Square.NONE && board.squareAttackedBy(square, side) != 0L
                    }
                        .map { it.value().lowercase() }
                        .sorted()
            }

        return mapOf(
            "pieceTerms" to similarity.extractTerms(fen, includeReachability = false).keys.filter { it.startsWith("piece|") },
            "pieceCounts" to counts,
            "sideToMove" to board.sideToMove.value(),
            "castlingRights" to fen.split(' ')[2].filter { it != '-' }.toCharArray().sorted().joinToString(""),
            "enPassantTarget" to fen.split(' ')[3],
            "legalUciMoves" to board.legalMoves().map { it.toString() }.sorted(),
            "attackedSquares" to attackedSquares,
        )
    }

    private fun poolStatistics(
        queryFen: String,
        candidateFens: List<String>,
    ): Map<String, Any> {
        val queryKey = canonical(queryFen)
        val documents =
            candidateFens.filterNot { canonical(it) == queryKey }
                .associate { fen -> canonical(fen) to similarity.extractTerms(fen, includeReachability = true) }
        val lengths = documents.mapValues { (_, terms) -> terms.values.sum() }
        val frequencies =
            documents.values.flatMap { it.filterValues { frequency -> frequency > 0.0 }.keys }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        return mapOf(
            "N" to documents.size,
            "averageDocumentLength" to lengths.values.average().toString(),
            "documentFrequencyDigest" to digestStringMap(frequencies),
        )
    }

    private fun digestTerms(terms: Map<String, Double>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(terms.toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun digestStringMap(values: Map<String, Int>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(values.toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun colorName(side: Side): String = if (side == Side.WHITE) "white" else "black"

    private data class FixtureSpec(
        val startFen: String,
        val fixtures: List<FixtureDefinition>,
    )

    private data class FixtureDefinition(
        val id: String,
        val sequenceA: String,
        val sequenceB: String,
    )

    private data class SeedPosition(
        val fixtureId: String,
        val fen: String,
        val successorMove: String,
    )
}
