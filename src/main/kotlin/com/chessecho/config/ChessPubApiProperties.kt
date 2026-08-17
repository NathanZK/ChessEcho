package com.chessecho.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chess.pubapi")
data class ChessPubApiProperties(
    var userAgentUsername: String = "",
    var contact: String = "",
    var delayMs: Long = 100L,
    var maxRetries: Int = 3,
    var initialBackoffMs: Long = 1000L,
    var maxBackoffMs: Long = 10000L,
)
