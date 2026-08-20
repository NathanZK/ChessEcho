package com.chessecho.domain

enum class RatingBand(val value: String) {
    BAND_400_600("400-600"),
    BAND_600_800("600-800"),
    BAND_800_1000("800-1000"),
    BAND_1000_1200("1000-1200"),
    BAND_1200_1400("1200-1400"),
    BAND_1400_1600("1400-1600"),
    BAND_1600_1800("1600-1800"),
    BAND_1800_2000("1800-2000"),
    BAND_2000_2200("2000-2200"),
    BAND_2200_PLUS("2200+"),
    ;

    companion object {
        private val BY_VALUE = entries.associateBy { it.value }

        fun fromValue(value: String?): RatingBand? {
            return BY_VALUE[value]
        }

        fun isValid(value: String?): Boolean {
            return value != null && BY_VALUE.containsKey(value)
        }

        fun getAdjacentBands(band: RatingBand): List<RatingBand> {
            val index = entries.indexOf(band)
            val adjacent = mutableListOf<RatingBand>()

            if (index > 0) {
                adjacent.add(entries[index - 1])
            }
            if (index < entries.size - 1) {
                adjacent.add(entries[index + 1])
            }

            return adjacent
        }
    }
}
