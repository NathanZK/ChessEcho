package com.chessecho.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionCookiePropertiesTest {
    @Test
    fun `default cookie secure attribute is true for production transport safety`() {
        val properties = SessionCookieProperties()
        assertTrue(properties.cookie.secure, "chessecho.auth.cookie.secure must default to true")
    }

    @Test
    fun `default csrf cookie name is XSRF-TOKEN`() {
        val properties = SessionCookieProperties()
        assertEquals("XSRF-TOKEN", properties.csrf.cookieName)
    }
}
