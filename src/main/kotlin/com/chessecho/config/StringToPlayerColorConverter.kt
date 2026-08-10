package com.chessecho.config

import com.chessecho.domain.PlayerColor
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class StringToPlayerColorConverter : Converter<String, PlayerColor> {
    override fun convert(source: String): PlayerColor {
        val trimmed = source.trim()
        return try {
            PlayerColor.valueOf(trimmed.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid playerColor '$source'. Must be WHITE, BLACK, or BOTH.")
        }
    }
}
