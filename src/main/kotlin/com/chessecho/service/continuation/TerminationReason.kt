package com.chessecho.service.continuation

enum class TerminationReason {
    QUIET_STOP,
    TERMINAL,
    PV_EXHAUSTED,
    PLY_LIMIT,
    NODE_LIMIT,
    TIMEOUT,
    ENGINE_ERROR,
}
