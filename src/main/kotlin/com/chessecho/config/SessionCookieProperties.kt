package com.chessecho.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the identity/session cookie, CSRF double-submit tokens,
 * session lifetimes, and the development-only principal gate (prefix
 * `chessecho.auth`). All values have safe defaults so the foundation works
 * without external configuration; production overrides `cookie.secure` and the
 * timeouts per deployment (#79 D2).
 */
@ConfigurationProperties(prefix = "chessecho.auth")
data class SessionCookieProperties(
    val cookie: Cookie = Cookie(),
    val csrf: Csrf = Csrf(),
    val session: Session = Session(),
    val devMode: DevMode = DevMode(),
) {
    data class Cookie(
        val name: String = "CHESSECHO_SESSION",
        val secure: Boolean = false,
        val sameSite: String = "Lax",
        val path: String = "/",
    )

    data class Csrf(
        val cookieName: String = "XSRF-TOKEN",
        val headerName: String = "X-XSRF-TOKEN",
    )

    data class Session(
        val idleTimeout: Duration = Duration.ofMinutes(30),
        val absoluteTimeout: Duration = Duration.ofDays(30),
    )

    data class DevMode(
        val enabled: Boolean = false,
    )
}
