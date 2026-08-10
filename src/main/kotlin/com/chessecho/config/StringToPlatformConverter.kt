package com.chessecho.config

import com.chessecho.domain.Platform
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class StringToPlatformConverter : Converter<String, Platform> {
    override fun convert(source: String): Platform {
        val trimmed = source.trim().uppercase()
        val normalized =
            when (trimmed) {
                "CHESSDOTCOM", "CHESS.COM" -> "CHESS_COM"
                else -> trimmed
            }
        return try {
            Platform.valueOf(normalized)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid platform '$source'. Supported platforms: CHESS_COM.")
        }
    }
}
