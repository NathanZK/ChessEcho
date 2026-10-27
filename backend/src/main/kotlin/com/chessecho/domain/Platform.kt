package com.chessecho.domain

import com.fasterxml.jackson.annotation.JsonCreator

enum class Platform {
    CHESS_COM,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): Platform =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported platform: $value")
    }
}
