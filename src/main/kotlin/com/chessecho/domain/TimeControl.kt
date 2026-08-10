package com.chessecho.domain

enum class TimeControl {
    RAPID,
    BLITZ,
    BULLET,
    CLASSICAL,
    ;

    companion object {
        fun fromExternal(value: String?): TimeControl? {
            if (value.isNullOrBlank()) return null
            val normalized = value.trim().uppercase()
            return when (normalized) {
                "RAPID" -> RAPID
                "BLITZ" -> BLITZ
                "BULLET" -> BULLET
                "CLASSICAL", "STANDARD" -> CLASSICAL
                "DAILY", "CORRESPONDENCE" -> null
                else -> entries.find { it.name == normalized }
            }
        }
    }
}
