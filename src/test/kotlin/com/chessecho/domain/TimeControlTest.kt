package com.chessecho.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimeControlTest {
    @Test
    fun `fromExternal correctly maps supported external time_class strings`() {
        assertEquals(TimeControl.RAPID, TimeControl.fromExternal("rapid"))
        assertEquals(TimeControl.BLITZ, TimeControl.fromExternal("blitz"))
        assertEquals(TimeControl.BULLET, TimeControl.fromExternal("bullet"))
        assertEquals(TimeControl.CLASSICAL, TimeControl.fromExternal("classical"))
        assertEquals(TimeControl.CLASSICAL, TimeControl.fromExternal("standard"))
    }

    @Test
    fun `fromExternal returns null for unsupported time_class strings including daily and correspondence`() {
        assertNull(TimeControl.fromExternal("daily"))
        assertNull(TimeControl.fromExternal("correspondence"))
        assertNull(TimeControl.fromExternal("tactics"))
        assertNull(TimeControl.fromExternal("puzzle"))
        assertNull(TimeControl.fromExternal("unknown"))
        assertNull(TimeControl.fromExternal(""))
        assertNull(TimeControl.fromExternal(null))
    }
}
