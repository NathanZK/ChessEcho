package com.chessecho.research

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic UUID generator (UUIDv5) based on SHA-1 hash of canonicalFen + plyNumber.
 * Eliminates UUID random generation non-determinism during candidate ranking and tie-breaking.
 */
object DeterministicIdGenerator {
    fun generateDeterministicId(canonicalFen: String, plyNumber: Int): UUID {
        val input = "$canonicalFen:$plyNumber"
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        // RFC 4122 version 5: set top 4 bits of time_hi_and_version to 0101 (5)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        // RFC 4122 variant: set top 2 bits of clock_seq_hi_and_reserved to 10
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long)
    }
}
