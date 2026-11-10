package com.chessprobe.domain

enum class DiscoveryJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    fun isTerminal(): Boolean = this in TERMINAL_STATUSES

    fun canTransitionTo(target: DiscoveryJobStatus): Boolean =
        when (this) {
            QUEUED -> target in setOf(PROCESSING, CANCELLED)
            PROCESSING -> target in setOf(COMPLETED, FAILED, CANCELLED)
            COMPLETED, FAILED, CANCELLED -> false
        }

    companion object {
        private val TERMINAL_STATUSES = setOf(COMPLETED, FAILED, CANCELLED)
    }
}

enum class DiscoveryStage {
    INITIALIZING,
    INGESTION,
    EVALUATION,
    CLUSTERING,
    PROFILING,
    COMPLETE,
    FAILED,
    CANCELLED,
}
