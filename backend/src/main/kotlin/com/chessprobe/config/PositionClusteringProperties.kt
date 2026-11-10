package com.chessprobe.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "chessprobe.clustering")
data class PositionClusteringProperties(
    val alpha: Double = 0.60,
    val topK: Int = 30,
    val candidateLimit: Int = 200,
    val floorThreshold: Double = 0.40,
    val top1Threshold: Double = 0.65,
    val rbResolution: Double = 1.25,
    val centroidThreshold: Double = 0.38,
) : InitializingBean {
    override fun afterPropertiesSet() {
        validate()
    }

    fun validate() {
        require(alpha.isFinite() && alpha in 0.0..1.0) { "alpha must be finite and in [0.0, 1.0]" }
        require(topK in 25..40) { "topK must be in [25, 40]" }
        require(candidateLimit in 50..500) { "candidateLimit must be in [50, 500]" }
        require(floorThreshold.isFinite() && floorThreshold in 0.0..1.0) { "floorThreshold must be in [0.0, 1.0]" }
        require(top1Threshold.isFinite() && top1Threshold in 0.0..1.0) { "top1Threshold must be in [0.0, 1.0]" }
        require(rbResolution.isFinite() && rbResolution > 0.0) { "rbResolution must be positive and finite" }
        require(centroidThreshold.isFinite() && centroidThreshold in 0.0..1.0) { "centroidThreshold must be in [0.0, 1.0]" }
    }
}
