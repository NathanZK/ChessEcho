package com.chessecho.config

import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.PositionTokenExtractor
import com.chessecho.similarity.RelationalPawnTokenExtractor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PositionSimilarityConfig {
    @Bean
    fun positionTokenExtractor(): PositionTokenExtractor = RelationalPawnTokenExtractor()

    @Bean
    fun positionSimilarityScorer(): PositionSimilarityScorer = PositionSimilarityScorer()
}
