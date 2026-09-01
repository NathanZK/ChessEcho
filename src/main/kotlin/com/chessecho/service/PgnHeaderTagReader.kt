package com.chessecho.service

import org.springframework.stereotype.Component

enum class PgnHeaderStatus {
    OK,
    ABSENT,
    MALFORMED,
    LIMIT_EXCEEDED,
}

data class PgnHeaderTags(
    val white: String? = null,
    val black: String? = null,
    val result: String? = null,
    val variant: String? = null,
    val status: PgnHeaderStatus,
)

@Component
class PgnHeaderTagReader {
    fun read(pgn: String): PgnHeaderTags {
        var index = if (pgn.startsWith('\uFEFF')) 1 else 0
        var lineCount = 0
        var tagCount = 0
        var headerStarted = false
        val retainedTags = mutableMapOf<String, String>()

        while (true) {
            if (index >= pgn.length) {
                return when {
                    !headerStarted -> PgnHeaderTags(status = PgnHeaderStatus.ABSENT)
                    tagCount >= MAX_TAG_PAIRS -> limitExceeded()
                    else -> tags(retainedTags, PgnHeaderStatus.OK)
                }
            }
            if (lineCount >= MAX_LOGICAL_LINES || index >= MAX_CHARACTERS) {
                return limitExceeded()
            }

            lineCount++
            val lineStart = index
            var rawLineLength = 0
            while (index < pgn.length && pgn[index] != '\n') {
                if (index >= MAX_CHARACTERS) {
                    return limitExceeded()
                }
                val character = pgn[index]
                index++
                rawLineLength++
                if (
                    rawLineLength > MAX_LINE_LENGTH + 1 ||
                    (rawLineLength == MAX_LINE_LENGTH + 1 && character != '\r')
                ) {
                    return limitExceeded()
                }
            }

            val hasLineTerminator = index < pgn.length && pgn[index] == '\n'
            val lineEnd =
                if (hasLineTerminator && index > lineStart && pgn[index - 1] == '\r') {
                    index - 1
                } else {
                    index
                }
            if (lineEnd - lineStart > MAX_LINE_LENGTH) {
                return limitExceeded()
            }

            if (hasLineTerminator) {
                if (index >= MAX_CHARACTERS) {
                    return limitExceeded()
                }
                index++
            }

            val line = pgn.substring(lineStart, lineEnd)
            if (line.isEmpty()) {
                if (headerStarted) {
                    return tags(retainedTags, PgnHeaderStatus.OK)
                }
                continue
            }

            if (!headerStarted && !line.startsWith('[')) {
                return PgnHeaderTags(status = PgnHeaderStatus.ABSENT)
            }
            headerStarted = true

            if (tagCount >= MAX_TAG_PAIRS) {
                return limitExceeded()
            }
            val tag = parseTag(line) ?: return tags(retainedTags, PgnHeaderStatus.MALFORMED)
            tagCount++

            if (tag.name in RETAINED_TAGS) {
                if (retainedTags.putIfAbsent(tag.name, tag.value) != null) {
                    return tags(retainedTags, PgnHeaderStatus.MALFORMED)
                }
            }
        }
    }

    private fun parseTag(line: String): PgnTag? {
        if (line.length < MIN_TAG_LENGTH || line.first() != '[' || line.last() != ']') {
            return null
        }

        var index = 1
        val nameStart = index
        while (index < line.length && isTagNameCharacter(line[index])) {
            index++
        }
        if (index == nameStart || index >= line.length || line[index] != ' ') {
            return null
        }
        val name = line.substring(nameStart, index)
        while (index < line.length && line[index] == ' ') {
            index++
        }
        if (index >= line.length || line[index] != '"') {
            return null
        }
        index++

        val value = StringBuilder()
        while (index < line.length) {
            when (val character = line[index++]) {
                '"' -> {
                    return if (index == line.lastIndex) {
                        PgnTag(name, value.toString())
                    } else {
                        null
                    }
                }

                '\\' -> {
                    if (index >= line.length) {
                        return null
                    }
                    val escaped = line[index++]
                    if (escaped != '"' && escaped != '\\') {
                        return null
                    }
                    value.append(escaped)
                }

                else -> value.append(character)
            }
        }
        return null
    }

    private fun tags(
        retainedTags: Map<String, String>,
        status: PgnHeaderStatus,
    ): PgnHeaderTags =
        PgnHeaderTags(
            white = retainedTags["White"],
            black = retainedTags["Black"],
            result = retainedTags["Result"],
            variant = retainedTags["Variant"],
            status = status,
        )

    private fun limitExceeded(): PgnHeaderTags = PgnHeaderTags(status = PgnHeaderStatus.LIMIT_EXCEEDED)

    private fun isTagNameCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '_'

    private data class PgnTag(
        val name: String,
        val value: String,
    )

    companion object {
        private const val MAX_CHARACTERS = 16_384
        private const val MAX_LOGICAL_LINES = 64
        private const val MAX_TAG_PAIRS = 32
        private const val MAX_LINE_LENGTH = 1_024
        private const val MIN_TAG_LENGTH = 6
        private val RETAINED_TAGS = setOf("White", "Black", "Result", "Variant")
    }
}
