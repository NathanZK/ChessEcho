package com.chessprobe.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chess.stockfish")
data class StockfishProperties(
    var path: String = "stockfish",
    var requireMock: Boolean = false,
)
