package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.ImportedArchive
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

data class ImportMonthResult(
    val imported: Int,
    val skipped: Int,
    val processed: Int,
    val affectedPositionIds: Set<UUID>,
    val wasCapTruncated: Boolean,
)

@Component
class MonthImportCoordinator(
    private val chessComClient: ChessComClient,
    private val gameRepository: GameRepository,
    private val importedArchiveRepository: ImportedArchiveRepository,
    private val derivedProcessingCoordinator: DerivedProcessingCoordinator,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun importMonth(
        account: ChessAccount,
        initiatingUser: AppUser?,
        archiveUrl: String,
        yearMonth: String,
        isPastMonth: Boolean,
        request: ImportGamesRequest,
        remainingCap: Int? = null,
    ): ImportMonthResult {
        log.debug("Fetching games from $archiveUrl")
        val rawGames =
            chessComClient.fetchMonthlyGames(archiveUrl)
                ?: return ImportMonthResult(0, 0, 0, emptySet(), false)

        val existingUrls = fetchExistingUrls(account, rawGames)
        val staging = stageGames(account, rawGames, existingUrls, request, remainingCap)

        val savedGamesAndArchive =
            persistGamesAndArchive(
                account = account,
                archiveUrl = archiveUrl,
                yearMonth = yearMonth,
                isPastMonth = isPastMonth,
                wasCapTruncated = staging.wasCapTruncated,
                totalEligibleThisMonth = staging.totalEligibleThisMonth,
                gamesToSave = staging.gamesToSave,
            )

        val affectedPositionIds =
            derivedProcessingCoordinator.processDerivedGames(
                account,
                initiatingUser,
                savedGamesAndArchive.first,
                savedGamesAndArchive.second,
            )

        return ImportMonthResult(
            staging.gamesToSave.size,
            staging.skipped,
            rawGames.size,
            affectedPositionIds,
            staging.wasCapTruncated,
        )
    }

    private fun fetchExistingUrls(
        account: ChessAccount,
        games: List<Map<String, Any>>,
    ): Set<String> {
        val allUrls = games.mapNotNull { it["url"] as? String }
        return transactionTemplate.execute {
            gameRepository.findPlatformGameIdsByChessAccountAndPlatformGameIdIn(account, allUrls).toSet()
        } ?: emptySet()
    }

    private data class StagedMonth(
        val gamesToSave: List<Game>,
        val skipped: Int,
        val totalEligibleThisMonth: Int,
        val wasCapTruncated: Boolean,
    )

    private fun stageGames(
        account: ChessAccount,
        games: List<Map<String, Any>>,
        existingUrls: Set<String>,
        request: ImportGamesRequest,
        remainingCap: Int?,
    ): StagedMonth {
        val username = request.username.ifBlank { account.username }
        val requestedTimeControls = request.timeControls.toSet()
        val requestedPlayerColor = request.playerColor ?: PlayerColor.BOTH

        val seenBatchUrls = mutableSetOf<String>()
        val gamesToSave = mutableListOf<Game>()
        var skipped = 0
        var wasCapTruncated = false
        var totalEligibleThisMonth = 0

        for (game in games) {
            val candidate = parseCandidateGame(account, game, username, requestedTimeControls, requestedPlayerColor) ?: continue

            if (!seenBatchUrls.add(candidate.platformGameId)) {
                skipped++
                continue
            }
            totalEligibleThisMonth++

            if (existingUrls.contains(candidate.platformGameId)) {
                skipped++
                continue
            }
            if (remainingCap != null && gamesToSave.size >= remainingCap) {
                wasCapTruncated = true
                continue
            }
            gamesToSave.add(candidate)
        }

        return StagedMonth(gamesToSave, skipped, totalEligibleThisMonth, wasCapTruncated)
    }

    private fun parseCandidateGame(
        account: ChessAccount,
        game: Map<String, Any>,
        username: String,
        requestedTimeControls: Set<TimeControl>,
        requestedPlayerColor: PlayerColor,
    ): Game? {
        val rules = game["rules"] as? String
        if (rules != null && !rules.equals("chess", ignoreCase = true)) return null

        val timeClass = game["time_class"] as? String
        val domainTimeControl = TimeControl.fromExternal(timeClass)
        if (domainTimeControl == null || !requestedTimeControls.contains(domainTimeControl)) return null

        val white = (game["white"] as? Map<*, *>)?.get("username") as? String ?: return null
        val black = (game["black"] as? Map<*, *>)?.get("username") as? String ?: return null
        if (!matchesPlayerColor(white, black, username, requestedPlayerColor)) return null

        val pgn = game["pgn"] as? String ?: return null
        val url = game["url"] as? String ?: return null
        val playedAtUnix = (game["end_time"] as? Number)?.toLong()
        val whiteResult = (game["white"] as? Map<*, *>)?.get("result") as? String

        return Game(
            chessAccount = account,
            platformGameId = url,
            pgn = pgn,
            timeControl = timeClass,
            playedAt = playedAtUnix?.let { Instant.ofEpochSecond(it) },
            result = whiteResult,
            whiteUsername = white,
            blackUsername = black,
        )
    }

    private fun matchesPlayerColor(
        white: String,
        black: String,
        username: String,
        requestedColor: PlayerColor,
    ): Boolean {
        val isPlayerWhite = white.equals(username, ignoreCase = true)
        val isPlayerBlack = black.equals(username, ignoreCase = true)
        return when (requestedColor) {
            PlayerColor.WHITE -> isPlayerWhite
            PlayerColor.BLACK -> isPlayerBlack
            PlayerColor.BOTH -> isPlayerWhite || isPlayerBlack
        }
    }

    private fun persistGamesAndArchive(
        account: ChessAccount,
        archiveUrl: String,
        yearMonth: String,
        isPastMonth: Boolean,
        wasCapTruncated: Boolean,
        totalEligibleThisMonth: Int,
        gamesToSave: List<Game>,
    ): Pair<List<Game>, ImportedArchive?> =
        transactionTemplate.execute {
            val archive =
                if (isPastMonth && !wasCapTruncated) {
                    importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
                        ?: importedArchiveRepository.save(
                            ImportedArchive(
                                chessAccount = account,
                                archiveUrl = archiveUrl,
                                yearMonth = yearMonth,
                                gameCount = totalEligibleThisMonth,
                            ),
                        )
                } else {
                    null
                }
            gamesToSave.forEach { it.importedArchive = archive }
            val savedGames = gameRepository.saveAll(gamesToSave)
            Pair(savedGames, archive)
        } ?: Pair(emptyList(), null)
}
