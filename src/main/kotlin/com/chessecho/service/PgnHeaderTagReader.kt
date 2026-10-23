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
    val eco: String? = null,
    val ecoUrl: String? = null,
    val timeControl: String? = null,
    val status: PgnHeaderStatus,
)

@Component
class PgnHeaderTagReader {
    fun read(pgn: String): PgnHeaderTags {
        var index = if (pgn.startsWith('\uFEFF')) 1 else 0
        var lineCount = 0
        val state = ReaderState()

        while (true) {
            if (index >= pgn.length) {
                return finishAtEnd(state)
            }
            if (lineCount >= MAX_LOGICAL_LINES || index >= MAX_CHARACTERS) {
                return limitExceeded()
            }

            lineCount++
            val scan = scanNextLine(pgn, index) ?: return limitExceeded()
            index = scan.nextIndex

            val outcome = processHeaderLine(scan.line, state)
            if (outcome != null) {
                return outcome
            }
        }
    }

    private fun finishAtEnd(state: ReaderState): PgnHeaderTags =
        when {
            !state.headerStarted -> PgnHeaderTags(status = PgnHeaderStatus.ABSENT)
            state.tagCount >= MAX_TAG_PAIRS -> limitExceeded()
            else -> tags(state.retainedTags, PgnHeaderStatus.OK)
        }

    private fun processHeaderLine(
        line: String,
        state: ReaderState,
    ): PgnHeaderTags? {
        if (line.isEmpty()) {
            return if (state.headerStarted) tags(state.retainedTags, PgnHeaderStatus.OK) else null
        }
        if (!state.headerStarted) {
            if (!line.startsWith('[')) {
                return PgnHeaderTags(status = PgnHeaderStatus.ABSENT)
            }
            state.headerStarted = true
        }
        if (state.tagCount >= MAX_TAG_PAIRS) {
            return limitExceeded()
        }
        if (!recordTag(line, state.retainedTags)) {
            return tags(state.retainedTags, PgnHeaderStatus.MALFORMED)
        }
        state.tagCount++
        return null
    }

    private fun recordTag(
        line: String,
        retainedTags: MutableMap<String, String>,
    ): Boolean {
        val tag = parseTag(line) ?: return false
        if (tag.name in RETAINED_TAGS) {
            if (retainedTags.putIfAbsent(tag.name, tag.value) != null) {
                return false
            }
        }
        return true
    }

    private fun scanNextLine(
        pgn: String,
        lineStart: Int,
    ): ScannedLine? {
        val lineBreakIndex = findLineBreak(pgn, lineStart) ?: return null
        val hasTerminator = lineBreakIndex < pgn.length && pgn[lineBreakIndex] == '\n'
        val lineEnd =
            if (hasTerminator && lineBreakIndex > lineStart && pgn[lineBreakIndex - 1] == '\r') {
                lineBreakIndex - 1
            } else {
                lineBreakIndex
            }
        if (lineEnd - lineStart > MAX_LINE_LENGTH) {
            return null
        }

        val nextIndex =
            if (hasTerminator) {
                if (lineBreakIndex >= MAX_CHARACTERS) {
                    return null
                }
                lineBreakIndex + 1
            } else {
                lineBreakIndex
            }

        return ScannedLine(pgn.substring(lineStart, lineEnd), nextIndex)
    }

    private fun findLineBreak(
        pgn: String,
        lineStart: Int,
    ): Int? {
        var index = lineStart
        var rawLineLength = 0
        while (index < pgn.length && pgn[index] != '\n') {
            if (index >= MAX_CHARACTERS) {
                return null
            }
            val character = pgn[index]
            index++
            rawLineLength++
            if (rawLineLength > MAX_LINE_LENGTH + 1 ||
                (rawLineLength == MAX_LINE_LENGTH + 1 && character != '\r')
            ) {
                return null
            }
        }
        return index
    }

    private fun parseTag(line: String): PgnTag? {
        if (line.length < MIN_TAG_LENGTH || line.first() != '[' || line.last() != ']') {
            return null
        }
        val (name, valueStartIndex) = parseTagNameAndValueStart(line) ?: return null
        val value = parseTagValue(line, valueStartIndex) ?: return null
        return PgnTag(name, value)
    }

    private fun parseTagNameAndValueStart(line: String): Pair<String, Int>? {
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
        return Pair(name, index + 1)
    }

    private fun parseTagValue(
        line: String,
        startIndex: Int,
    ): String? {
        var index = startIndex
        val value = StringBuilder()
        while (index < line.length) {
            when (val character = line[index++]) {
                '"' -> {
                    return if (index == line.lastIndex) {
                        value.toString()
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
            eco = retainedTags["ECO"],
            ecoUrl = retainedTags["ECOUrl"],
            timeControl = retainedTags["TimeControl"],
            status = status,
        )

    private fun limitExceeded(): PgnHeaderTags = PgnHeaderTags(status = PgnHeaderStatus.LIMIT_EXCEEDED)

    private fun isTagNameCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '_'

    private class ReaderState(
        var headerStarted: Boolean = false,
        var tagCount: Int = 0,
        val retainedTags: MutableMap<String, String> = mutableMapOf(),
    )

    private data class ScannedLine(
        val line: String,
        val nextIndex: Int,
    )

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
        private val RETAINED_TAGS = setOf("White", "Black", "Result", "Variant", "ECO", "ECOUrl", "TimeControl")
    }
}
