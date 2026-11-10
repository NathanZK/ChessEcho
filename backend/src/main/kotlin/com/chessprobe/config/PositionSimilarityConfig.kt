package com.chessprobe.config

import com.chessprobe.similarity.PositionSimilarityScorer
import com.chessprobe.similarity.PositionTokenExtractor
import com.chessprobe.similarity.RelationalPawnTokenExtractor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PositionSimilarityConfig {
    @Bean
    fun positionTokenExtractor(): PositionTokenExtractor = RelationalPawnTokenExtractor()

    @Bean
    fun positionSimilarityScorer(): PositionSimilarityScorer = PositionSimilarityScorer()
}
