package com.chessecho.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PositionClusteringPropertiesTest {
    @Test
    fun `default properties are valid with approved default values`() {
        val props = PositionClusteringProperties()
        props.validate()

        assertEquals(0.60, props.alpha)
        assertEquals(30, props.topK)
        assertEquals(200, props.candidateLimit)
        assertEquals(0.40, props.floorThreshold)
        assertEquals(0.65, props.top1Threshold)
        assertEquals(1.25, props.rbResolution)
        assertEquals(0.38, props.centroidThreshold)
    }

    @Test
    fun `boundary values are accepted across all properties`() {
        PositionClusteringProperties(alpha = 0.0).validate()
        PositionClusteringProperties(alpha = 1.0).validate()
        PositionClusteringProperties(topK = 25).validate()
        PositionClusteringProperties(topK = 40).validate()
        PositionClusteringProperties(candidateLimit = 50).validate()
        PositionClusteringProperties(candidateLimit = 500).validate()
        PositionClusteringProperties(floorThreshold = 0.0).validate()
        PositionClusteringProperties(floorThreshold = 1.0).validate()
        PositionClusteringProperties(top1Threshold = 0.0).validate()
        PositionClusteringProperties(top1Threshold = 1.0).validate()
        PositionClusteringProperties(centroidThreshold = 0.0).validate()
        PositionClusteringProperties(centroidThreshold = 1.0).validate()
    }

    @Test
    fun `invalid alpha is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(alpha = -0.01).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(alpha = 1.01).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(alpha = Double.NaN).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(alpha = Double.POSITIVE_INFINITY).validate()
        }
    }

    @Test
    fun `invalid topK is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(topK = 24).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(topK = 41).validate()
        }
    }

    @Test
    fun `invalid candidateLimit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(candidateLimit = 49).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(candidateLimit = 501).validate()
        }
    }

    @Test
    fun `invalid thresholds and resolution are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(floorThreshold = -0.1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(floorThreshold = 1.1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(top1Threshold = -0.1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(top1Threshold = 1.1).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(rbResolution = 0.0).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(rbResolution = -1.0).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(centroidThreshold = -0.01).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionClusteringProperties(centroidThreshold = 1.01).validate()
        }
    }
}
