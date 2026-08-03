package com.chessecho.service

import com.chessecho.domain.AsyncJob
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AsyncJobRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant

@Service
class GameImportService(
    private val asyncJobRepository: AsyncJobRepository,
    private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = listOf("QUEUED", "PROCESSING")
    }

    @Transactional
    fun createImportJob(request: ImportGamesRequest): AsyncJob {
        asyncJobRepository.findByUsernameAndStatusIn(request.username, ACTIVE_STATUSES)
            ?.let {
                throw ActiveImportJobException(
                    "An active import job already exists for username '${request.username}' (jobId=${it.id})",
                )
            }

        val job =
            AsyncJob(
                username = request.username,
                platform = request.platform,
                status = "QUEUED",
            )
        return asyncJobRepository.save(job)
    }

    @Async
    @Transactional
    fun executeImportJob(
        job: AsyncJob,
        request: ImportGamesRequest,
    ) {
        log.info("Starting import job ${job.id} for user ${request.username}")
        updateJobStatus(job, "PROCESSING")

        try {
            val archiveUrls = fetchArchiveUrls(request.username, request.fromDate, request.toDate)
            var imported = 0
            var skipped = 0

            for (archiveUrl in archiveUrls) {
                val (monthImported, monthSkipped) = importMonth(request.username, archiveUrl, request)
                imported += monthImported
                skipped += monthSkipped
            }

            job.gamesImported = imported
            job.gamesSkipped = skipped
            updateJobStatus(job, "COMPLETED")
            log.info("Import job ${job.id} completed: $imported imported, $skipped skipped")
        } catch (ex: Exception) {
            log.error("Import job ${job.id} failed", ex)
            job.errorMessage = ex.message
            updateJobStatus(job, "FAILED")
        }
    }

    private fun updateJobStatus(
        job: AsyncJob,
        status: String,
    ) {
        job.status = status
        job.updatedAt = Instant.now()
        asyncJobRepository.save(job)
    }

    private fun importMonth(
        username: String,
        archiveUrl: String,
        request: ImportGamesRequest,
    ): Pair<Int, Int> {
        log.debug("Fetching games from $archiveUrl")

        @Suppress("UNCHECKED_CAST")
        val response =
            restClient.get()
                .uri(archiveUrl)
                .retrieve()
                .body(Map::class.java) as? Map<String, Any> ?: return Pair(0, 0)

        @Suppress("UNCHECKED_CAST")
        val games = response["games"] as? List<Map<String, Any>> ?: return Pair(0, 0)

        var imported = 0
        var skipped = 0

        for (game in games) {
            val timeClass = game["time_class"] as? String ?: continue
            if (!request.timeControls.contains(timeClass)) continue

            val white = (game["white"] as? Map<*, *>)?.get("username") as? String ?: continue
            val black = (game["black"] as? Map<*, *>)?.get("username") as? String ?: continue

            val isPlayerWhite = white.equals(username, ignoreCase = true)
            val isPlayerBlack = black.equals(username, ignoreCase = true)
            val colorMatch =
                when (request.playerColor.lowercase()) {
                    "white" -> isPlayerWhite
                    "black" -> isPlayerBlack
                    "both" -> isPlayerWhite || isPlayerBlack
                    else -> false
                }
            if (!colorMatch) continue

            // For now just count — full PGN parsing and position detection is a separate concern
            imported++
        }

        return Pair(imported, skipped)
    }

    private fun fetchArchiveUrls(
        username: String,
        fromDate: String?,
        toDate: String?,
    ): List<String> {
        val url = "https://api.chess.com/pub/player/${username.lowercase()}/games/archives"

        @Suppress("UNCHECKED_CAST")
        val response =
            restClient.get()
                .uri(url)
                .retrieve()
                .body(Map::class.java) as? Map<String, Any> ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val allArchives = response["archives"] as? List<String> ?: return emptyList()

        return allArchives.filter { archiveUrl ->
            val parts = archiveUrl.split("/")
            if (parts.size >= 2) {
                val year = parts[parts.size - 2]
                val month = parts[parts.size - 1]
                val archiveMonth = "$year-$month"

                val afterFrom = fromDate == null || archiveMonth >= fromDate
                val beforeTo = toDate == null || archiveMonth <= toDate
                afterFrom && beforeTo
            } else {
                false
            }
        }
    }
}

class ActiveImportJobException(message: String) : RuntimeException(message)
