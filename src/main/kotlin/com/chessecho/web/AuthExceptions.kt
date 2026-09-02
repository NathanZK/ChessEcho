package com.chessecho.web

/** Thrown when an endpoint requires an authenticated principal but none is present. Mapped to 401. */
class UnauthenticatedException(message: String = "Authentication required") : RuntimeException(message)

/** Thrown when the double-submit CSRF token is missing or does not match. Mapped to 403. */
class CsrfException(message: String = "CSRF validation failed") : RuntimeException(message)
