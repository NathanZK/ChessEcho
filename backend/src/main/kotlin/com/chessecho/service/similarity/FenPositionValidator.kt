package com.chessecho.service.similarity

import com.github.bhlangonijr.chesslib.Board
import org.springframework.stereotype.Component

@Component
class FenPositionValidator {
    fun validateOrthodoxFen(fen: String): String {
        val trimmed = fen.trim()
        val fields = trimmed.split("\\s+".toRegex())
        if (fields.size < 4) {
            throw IllegalArgumentException("Invalid FEN position: $fen (expected at least 4 fields)")
        }

        val board = Board()
        try {
            board.loadFromFen(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid FEN position: $fen")
        }

        val activeColor = fields[1].lowercase()
        if (activeColor != "w" && activeColor != "b") {
            throw IllegalArgumentException("Invalid FEN position: $fen (invalid active color: ${fields[1]})")
        }

        val ranks = fields[0].split('/')
        if (ranks.size != 8) {
            throw IllegalArgumentException("Invalid FEN position: $fen (expected 8 ranks, got ${ranks.size})")
        }

        return trimmed
    }
}
